package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MaxPartitionConfigurationTest {
    @Test void readsTableCapAndDefaultsOnlyNullOnTheExistingConnection() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:cap_" + UUID.randomUUID());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TSS_BCOMP_CONF_NEW(BCOMP_INDEX BIGINT,BCOMP_MAX_PARTITION_NUM DECIMAL(30,2))");
            statement.execute("INSERT INTO TSS_BCOMP_CONF_NEW VALUES(1,NULL),(2,1),(3,128),(4,256),(5,100)");
            assertEquals(64, SparkHandlerTable.readMaxPartitionNum(connection, 1));
            assertEquals(1, SparkHandlerTable.readMaxPartitionNum(connection, 2));
            assertEquals(128, SparkHandlerTable.readMaxPartitionNum(connection, 3));
            assertEquals(256, SparkHandlerTable.readMaxPartitionNum(connection, 4));
            assertEquals(100, SparkHandlerTable.readMaxPartitionNum(connection, 5));
            for (String invalid : new String[]{"0", "-1", "257", "64.5", "999999999999999999999"}) {
                statement.execute("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=" + invalid + " WHERE BCOMP_INDEX=3");
                assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.readMaxPartitionNum(connection, 3));
            }
            assertFalse(connection.isClosed());
            assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.readMaxPartitionNum(connection, 99));
            statement.execute("ALTER TABLE TSS_BCOMP_CONF_NEW DROP COLUMN BCOMP_MAX_PARTITION_NUM");
            assertThrows(SQLException.class, () -> SparkHandlerTable.readMaxPartitionNum(connection, 1));
        }
    }
}
