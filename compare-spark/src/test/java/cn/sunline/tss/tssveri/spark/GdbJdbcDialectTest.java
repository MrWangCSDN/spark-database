package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.jdbc.JdbcDialects;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class GdbJdbcDialectTest {
    @Test
    void recognizesDefaultAndCustomGdbPrefixesAndUsesMysqlCursorSetup() throws Exception {
        GdbJdbcDialect.register("jdbc:mysql:,jdbc:gdb:");
        var gdb = JdbcDialects.get("jdbc:gdb://gdb/core");
        var mysql = new GdbJdbcDialect();
        assertTrue(mysql.canHandle("jdbc:mysql://gdb/core"));
        assertTrue(gdb instanceof GdbJdbcDialect);
        assertFalse(mysql.canHandle("jdbc:postgresql://db/core"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:gdb_cursor")) {
            scala.collection.immutable.Map<String,String> options = new scala.collection.immutable.HashMap<>();
            options = options.$plus(new scala.Tuple2<>("fetchsize", "1000"));
            assertTrue(connection.getAutoCommit());
            mysql.beforeFetch(connection, options);
            assertFalse(connection.getAutoCommit());
        }
    }
}
