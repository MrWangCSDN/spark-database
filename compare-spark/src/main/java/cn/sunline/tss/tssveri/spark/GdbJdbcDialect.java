package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.jdbc.JdbcDialect;
import org.apache.spark.sql.jdbc.JdbcDialects;
import org.apache.spark.sql.jdbc.MySQLDialect$;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.MetadataBuilder;
import org.apache.spark.sql.jdbc.JdbcType;

import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** MySQL-compatible JDBC behavior for GDB and vendor URL prefixes. */
public final class GdbJdbcDialect extends JdbcDialect {
    private static final GdbJdbcDialect INSTANCE = new GdbJdbcDialect();
    private static final String DEFAULT_PREFIXES = "jdbc:mysql:,jdbc:gdb:,jdbc:gbase:";
    private static volatile Set<String> prefixes = parse(DEFAULT_PREFIXES);

    public static void register(String configuredPrefixes) {
        prefixes = parse(configuredPrefixes);
        JdbcDialects.registerDialect(INSTANCE);
    }

    public static void register() {
        register(DEFAULT_PREFIXES);
    }

    @Override public boolean canHandle(String url) {
        if (url == null) return false;
        String value = url.trim().toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    @Override public void beforeFetch(Connection connection,
            scala.collection.immutable.Map<String, String> properties) {
        try {
            if (connection.getAutoCommit()) connection.setAutoCommit(false);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("GDB JDBC 游标事务初始化失败", error);
        }
        MySQLDialect$.MODULE$.beforeFetch(connection, properties);
    }

    @Override public scala.Option<DataType> getCatalystType(int sqlType, String typeName, int size, MetadataBuilder md) {
        return MySQLDialect$.MODULE$.getCatalystType(sqlType, typeName, size, md);
    }

    @Override public scala.Option<JdbcType> getJDBCType(DataType dataType) {
        return MySQLDialect$.MODULE$.getJDBCType(dataType);
    }

    @Override public String quoteIdentifier(String colName) {
        return MySQLDialect$.MODULE$.quoteIdentifier(colName);
    }

    @Override public String getTableExistsQuery(String table) {
        return MySQLDialect$.MODULE$.getTableExistsQuery(table);
    }

    @Override public String getSchemaQuery(String table) {
        return MySQLDialect$.MODULE$.getSchemaQuery(table);
    }

    @Override public String getLimitClause(Integer limit) {
        return MySQLDialect$.MODULE$.getLimitClause(limit);
    }

    @Override public String getOffsetClause(Integer offset) {
        return MySQLDialect$.MODULE$.getOffsetClause(offset);
    }

    // Spark 3.5 adds these capability methods; keep source compatibility with 3.4.
    public boolean supportsLimit() {
        return true;
    }

    public boolean supportsOffset() {
        return false;
    }

    private static Set<String> parse(String raw) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String source = raw == null || raw.isBlank() ? DEFAULT_PREFIXES : raw;
        Arrays.stream(source.split(","))
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isEmpty())
            .forEach(value -> values.add(value.endsWith(":") ? value : value + ":"));
        if (values.isEmpty()) values.addAll(parse(DEFAULT_PREFIXES));
        return Set.copyOf(values);
    }
}
