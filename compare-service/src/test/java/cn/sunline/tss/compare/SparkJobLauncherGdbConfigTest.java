package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class SparkJobLauncherGdbConfigTest {
    @Test
    void propagatesConfiguredGdbPrefixesAndKeepsSafeDefault() {
        assertEquals("jdbc:mysql:,jdbc:gdb:,jdbc:gbase:", SparkJobLauncher.gdbJdbcUrlPrefixes(new MockEnvironment()));
        assertEquals("jdbc:vendor-gdb:", SparkJobLauncher.gdbJdbcUrlPrefixes(
            new MockEnvironment().withProperty("spark.gdb.jdbc-url-prefixes", "jdbc:vendor-gdb:")));
    }
}
