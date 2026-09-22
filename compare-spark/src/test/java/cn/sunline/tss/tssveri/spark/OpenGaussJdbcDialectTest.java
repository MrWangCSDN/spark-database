package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.jdbc.JdbcDialects;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class OpenGaussJdbcDialectTest {
    @Test void positiveFetchSizeUsesTransactionForOpenGaussCursor() throws Exception {
        OpenGaussJdbcDialect.register();
        var dialect = JdbcDialects.get("jdbc:opengauss://example/db");
        assertTrue(dialect instanceof OpenGaussJdbcDialect);
        assertFalse(dialect.canHandle("jdbc:mysql://example/db"));
        try (var conn = DriverManager.getConnection("jdbc:h2:mem:gauss_cursor")) {
            scala.collection.immutable.Map<String,String> options = new scala.collection.immutable.HashMap<>();
            options = options.$plus(new scala.Tuple2<>("fetchsize", "1000"));
            assertTrue(conn.getAutoCommit());
            dialect.beforeFetch(conn, options);
            assertFalse(conn.getAutoCommit());
        }
    }
}
