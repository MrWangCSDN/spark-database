package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AutoHashPartitionPlannerTest {
    @Test void roundsUpAtMillionRowBoundariesAndCapsAt64() {
        assertEquals(1, AutoHashPartitionPlanner.partitions(0));
        assertEquals(1, AutoHashPartitionPlanner.partitions(1_000_000));
        assertEquals(2, AutoHashPartitionPlanner.partitions(1_000_001));
        assertEquals(4, AutoHashPartitionPlanner.partitions(3_000_000));
        assertEquals(8, AutoHashPartitionPlanner.partitions(5_000_000));
        assertEquals(16, AutoHashPartitionPlanner.partitions(10_000_000));
        assertEquals(32, AutoHashPartitionPlanner.partitions(30_000_000));
        assertEquals(64, AutoHashPartitionPlanner.partitions(50_000_000));
        assertEquals(64, AutoHashPartitionPlanner.partitions(300_000_000));
        assertThrows(IllegalArgumentException.class, () -> AutoHashPartitionPlanner.partitions(-1));
    }

    @Test void capsAutomaticPartitionsPerTableWithoutInflatingSmallTables() {
        assertEquals(4, AutoHashPartitionPlanner.partitions(3_000_000, 64));
        assertEquals(32, AutoHashPartitionPlanner.partitions(50_000_000, 32));
        assertEquals(128, AutoHashPartitionPlanner.partitions(200_000_000, 128));
        assertEquals(100, AutoHashPartitionPlanner.partitions(200_000_000, 100));
        assertEquals(100, AutoHashPartitionPlanner.partitions(65_000_000, 100));
        assertEquals(256, AutoHashPartitionPlanner.partitions(Long.MAX_VALUE, 256));
        assertEquals(1, AutoHashPartitionPlanner.partitions(0, 256));
        assertEquals(1, AutoHashPartitionPlanner.partitions(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> AutoHashPartitionPlanner.partitions(-1, 256));
        for (int invalid : new int[]{0, -1, 257, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> AutoHashPartitionPlanner.partitions(1, invalid));
        }
    }

    @Test void rejectsInvalidCapBeforeAccessingSourceConnections() {
        assertThrows(IllegalArgumentException.class, () -> AutoHashPartitionPlanner.plan(new String[21], 257));
    }

    @Test void countsBothFilteredQueriesAndUsesLargerSide() throws Exception {
        String url = "jdbc:h2:mem:auto_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE samples(id INT)");
            statement.execute("INSERT INTO samples SELECT x FROM SYSTEM_RANGE(1, 1000001)");
            String[] args = new String[21];
            args[SparkHandlerTable.OLD_URL_INDEX] = url;
            args[SparkHandlerTable.NEW_URL_INDEX] = url;
            args[SparkHandlerTable.OLD_USER_INDEX] = args[SparkHandlerTable.NEW_USER_INDEX] = "sa";
            args[SparkHandlerTable.OLD_PASSWORD_INDEX] = args[SparkHandlerTable.NEW_PASSWORD_INDEX] = "";
            args[SparkHandlerTable.OLD_DRIVER_INDEX] = args[SparkHandlerTable.NEW_DRIVER_INDEX] = "org.h2.Driver";
            args[SparkHandlerTable.OLD_TABLE_INDEX] = "(SELECT id FROM samples WHERE id <= 1000000) old_src";
            args[SparkHandlerTable.NEW_TABLE_INDEX] = "(SELECT id FROM samples) new_src";
            assertEquals(2, AutoHashPartitionPlanner.plan(args));
            assertEquals(1, AutoHashPartitionPlanner.plan(args, 1));
            assertEquals(2, AutoHashPartitionPlanner.plan(args, 256));
            args[SparkHandlerTable.NEW_TABLE_INDEX] = "(SELECT id FROM missing_table) new_src";
            assertThrows(Exception.class, () -> AutoHashPartitionPlanner.plan(args));
        }
    }
}
