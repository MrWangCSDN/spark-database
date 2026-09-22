package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.jdbc.JdbcDialect;
import org.apache.spark.sql.jdbc.JdbcDialects;
import org.apache.spark.sql.jdbc.PostgresDialect$;
import java.sql.Connection;
import java.util.Locale;

/** Spark's built-in PostgreSQL cursor setup does not recognize jdbc:opengauss. */
public final class OpenGaussJdbcDialect extends JdbcDialect {
    private static final OpenGaussJdbcDialect INSTANCE = new OpenGaussJdbcDialect();

    public static void register() { JdbcDialects.registerDialect(INSTANCE); }

    @Override public boolean canHandle(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:opengauss:");
    }

    @Override public void beforeFetch(Connection connection,
            scala.collection.immutable.Map<String, String> properties) {
        PostgresDialect$.MODULE$.beforeFetch(connection, properties);
    }
}
