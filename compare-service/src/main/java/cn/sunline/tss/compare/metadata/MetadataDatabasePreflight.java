package cn.sunline.tss.compare.metadata;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

public final class MetadataDatabasePreflight {
    private final MetadataDbType expected;
    public MetadataDatabasePreflight(MetadataDbType expected) {
        this.expected = expected;
    }

    public void validate(DataSource source) {
        try (Connection connection = source.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            requireCompatible(expected, metadata.getDatabaseProductName(), metadata.getURL(), metadata.getDriverName());
        } catch (SQLException error) {
            throw new IllegalStateException("控制面数据库连接预检失败，请检查 URL、用户名、驱动和数据库状态", error);
        }
    }

    public static void requireCompatible(MetadataDbType expected, String product, String url, String driver) {
        String productValue = lower(product);
        String urlValue = lower(url);
        String driverValue = lower(driver);
        boolean compatible = switch (expected) {
            case GDB -> containsAny(productValue, "gdb", "gbase", "mysql")
                || startsWithAny(urlValue, "jdbc:mysql:", "jdbc:gdb:", "jdbc:gbase:")
                || containsAny(driverValue, "mysql", "gdb", "gbase");
            case GAUSSDB -> containsAny(productValue, "gauss", "postgres", "opengauss", "h2")
                || startsWithAny(urlValue, "jdbc:postgresql:", "jdbc:gaussdb:", "jdbc:opengauss:", "jdbc:h2:")
                || containsAny(driverValue, "postgres", "gauss", "opengauss", "h2");
        };
        if (!compatible) {
            throw new IllegalStateException("控制面数据库类型与实际 JDBC 数据库不匹配，请检查 bd.metadata-db-type 配置");
        }
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static boolean startsWithAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.startsWith(candidate)) return true;
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
