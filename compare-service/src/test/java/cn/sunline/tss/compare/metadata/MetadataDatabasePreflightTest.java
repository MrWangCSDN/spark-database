package cn.sunline.tss.compare.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetadataDatabasePreflightTest {
    @Test
    void acceptsGaussDbAndH2TestProductsForGaussDbMode() {
        assertDoesNotThrow(() -> MetadataDatabasePreflight.requireCompatible(
            MetadataDbType.GAUSSDB, "GaussDB", "jdbc:gaussdb://db/schema", "org.opengauss.Driver"));
        assertDoesNotThrow(() -> MetadataDatabasePreflight.requireCompatible(
            MetadataDbType.GAUSSDB, "H2", "jdbc:h2:mem:test", "org.h2.Driver"));
    }

    @Test
    void acceptsMysqlCompatibleGdbModeWithoutCurrentSchema() {
        assertDoesNotThrow(() -> MetadataDatabasePreflight.requireCompatible(
            MetadataDbType.GDB, "GDB", "jdbc:mysql://db/schema", "com.mysql.jdbc.Driver"));
        assertDoesNotThrow(() -> MetadataDatabasePreflight.requireCompatible(
            MetadataDbType.GDB, "MySQL", "jdbc:gdb://db/schema", "vendor.gdb.Driver"));
    }

    @Test
    void rejectsExplicitTypeMismatchWithoutLeakingConnectionMaterial() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            MetadataDatabasePreflight.requireCompatible(
                MetadataDbType.GDB, "GaussDB", "jdbc:secret-host/db?password=secret", "org.opengauss.Driver"));
        assertFalse(error.getMessage().contains("secret-host"));
        assertFalse(error.getMessage().contains("secret"));
    }
}
