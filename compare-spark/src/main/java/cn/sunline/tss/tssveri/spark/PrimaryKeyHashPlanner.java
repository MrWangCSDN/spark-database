package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.Row;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Database-side hash predicates. Hash collisions affect distribution only; cogroup still joins full business keys. */
final class PrimaryKeyHashPlanner {
    private PrimaryKeyHashPlanner() { }

    static int partitionCount(Object value) {
        try {
            int count = new BigDecimal(String.valueOf(value)).intValueExact();
            if (count >= 1 && count <= 256) return count;
        } catch (NumberFormatException | ArithmeticException ignored) { }
        throw new IllegalArgumentException("HASH分区数必须是1..256的整数: " + value);
    }

    static List<Integer> keyPositions(List<Row> fields, String sideIndex) {
        List<Integer> positions = new ArrayList<>();
        for (Row field : fields) {
            if (!"1".equals(field.getAs("FIELD_INDEX_FLAG"))) continue;
            Object raw = field.getAs(sideIndex);
            try {
                int position = new BigDecimal(String.valueOf(raw)).intValueExact();
                if (position < 1) throw new ArithmeticException();
                positions.add(position);
            } catch (NumberFormatException | ArithmeticException e) {
                throw new IllegalArgumentException("HASH主键位置无效: " + sideIndex + "=" + raw, e);
            }
        }
        if (positions.isEmpty()) throw new IllegalArgumentException("HASH读取必须配置业务主键(FIELD_INDEX_FLAG='1')");
        return positions;
    }

    /** null means a deliberate single JDBC read; it never means failed partition planning. */
    static String[] predicates(Connection connection, String source, List<Integer> keyPositions, int count) throws SQLException {
        partitionCount(count);
        if (keyPositions.isEmpty()) throw new IllegalArgumentException("HASH读取必须配置业务主键");
        List<String> keys = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + source + " WHERE 1=0")) {
            ResultSetMetaData metadata = rs.getMetaData();
            for (int position : keyPositions) {
                if (position < 1 || position > metadata.getColumnCount())
                    throw new IllegalArgumentException("HASH主键位置超出查询列范围: " + position);
                String label = metadata.getColumnLabel(position);
                for (int other=1; other<=metadata.getColumnCount(); other++) {
                    if (other != position && label.equals(metadata.getColumnLabel(other)))
                        throw new IllegalArgumentException("HASH主键查询别名不唯一: " + label);
                }
                keys.add(label);
            }
        }
        if (count == 1) return null;
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        String driver = metadata.getDriverName();
        String mode = null;
        if (product != null && product.toLowerCase(Locale.ROOT).contains("oceanbase")) {
            mode = oceanBaseMode(connection);
        }
        Dialect dialect = dialect(product, metadata.getURL(), mode, driver);
        String quote = metadata.getIdentifierQuoteString();
        if (!"\"".equals(quote) && !"`".equals(quote))
            throw new SQLException("HASH不支持JDBC标识符引用方式: " + quote);
        List<String> hashes = new ArrayList<>();
        for (String key : keys) hashes.add(dialect.hash(quote + key.replace(quote, quote + quote) + quote));
        // Avoid ABS(MIN_VALUE) overflow. Each term is widened before addition and NULL maps to a hash value.
        String bucket = "MOD(MOD((" + String.join(" + ", hashes) + "), " + count + ") + " + count + ", " + count + ")";
        // Execute the expression on at most one source row: unavailable functions/types fail before reporting HASH mode.
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(1);
            try (ResultSet rs = statement.executeQuery("SELECT " + bucket + " FROM " + source)) {
                if (rs.next()) {
                    int bucketId=rs.getInt(1);
                    if (rs.wasNull() || bucketId<0 || bucketId>=count)
                        throw new SQLException("HASH探测返回NULL或越界分区号，拒绝不完整的分区读取");
                }
            }
        }
        String[] predicates = new String[count];
        for (int bucketId=0; bucketId<count; bucketId++) predicates[bucketId] = bucket + " = " + bucketId;
        return predicates;
    }

    private static String oceanBaseMode(Connection connection) throws SQLException {
        SQLException first;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT @@ob_compatibility_mode")) {
            if (rs.next()) return rs.getString(1);
            first = new SQLException("OceanBase compatibility mode is empty");
        } catch (SQLException e) { first = e; }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT VALUE FROM V$OB_PARAMETERS WHERE NAME='ob_compatibility_mode'")) {
            if (rs.next()) return rs.getString(1);
            throw new SQLException("OceanBase compatibility mode is empty");
        } catch (SQLException e) {
            e.addSuppressed(first);
            throw new SQLException("无法确定OceanBase兼容模式，不能生成HASH谓词", e);
        }
    }

    static Dialect dialect(String product, String url, String oceanBaseMode) {
        return dialect(product, url, oceanBaseMode, null);
    }

    static Dialect dialect(String product, String url, String oceanBaseMode, String driver) {
        String name = Objects.toString(product, "").toLowerCase(Locale.ROOT);
        String jdbcUrl = Objects.toString(url, "").toLowerCase(Locale.ROOT);
        if (name.contains("oceanbase")) {
            if ("ORACLE".equalsIgnoreCase(oceanBaseMode)) return Dialect.ORACLE;
            if ("MYSQL".equalsIgnoreCase(oceanBaseMode)) return Dialect.MYSQL;
            throw new IllegalArgumentException("无法确定OceanBase兼容模式");
        }
        if (name.contains("oracle")) return Dialect.ORACLE;
        if (isMysqlFamily(name,jdbcUrl,driver)) return Dialect.MYSQL;
        if (name.contains("postgresql") || name.contains("opengauss")) return Dialect.POSTGRES;
        if (name.contains("gaussdb")) {
            if (jdbcUrl.startsWith("jdbc:mysql:")) return Dialect.MYSQL;
            return Dialect.POSTGRES;
        }
        if ("h2".equals(name)) return Dialect.H2;
        throw new IllegalArgumentException("HASH不支持数据库产品: " + product);
    }

    static boolean isMysqlFamily(String product,String url,String driver) {
        String name=Objects.toString(product,"").toLowerCase(Locale.ROOT);
        String jdbcUrl=Objects.toString(url,"").toLowerCase(Locale.ROOT);
        String driverName=Objects.toString(driver,"").toLowerCase(Locale.ROOT);
        return name.contains("mysql") || name.contains("gdb") || name.contains("gbase")
                || jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:gdb:") || jdbcUrl.startsWith("jdbc:gbase:")
                || driverName.contains("mysql") || driverName.contains("gdb") || driverName.contains("gbase");
    }

    enum Dialect {
        // GaussDB Oracle-compatible modes treat '' as NULL; guard every numeric hash, not just its text input.
        POSTGRES { String hash(String key) { return "COALESCE(CAST(hashtext(COALESCE(CAST("+key+" AS TEXT), '')) AS BIGINT), 0)"; } },
        ORACLE { String hash(String key) { return "COALESCE(ORA_HASH("+key+"), 0)"; } },
        MYSQL { String hash(String key) { return "CAST(CRC32(COALESCE(CAST("+key+" AS CHAR), '')) AS DECIMAL(38,0))"; } },
        H2 { String hash(String key) { return "COALESCE(CAST(ORA_HASH(COALESCE(CAST("+key+" AS VARCHAR), '')) AS BIGINT), 0)"; } };
        abstract String hash(String key);
    }
}
