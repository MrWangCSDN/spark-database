package cn.sunline.tss.compare.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetadataDbTypeTest {
    @Test
    void defaultsToGaussDbWhenUnset() {
        assertEquals(MetadataDbType.GAUSSDB, MetadataDbType.parse(null));
        assertEquals(MetadataDbType.GAUSSDB, MetadataDbType.parse(" "));
    }

    @Test
    void parsesConfiguredTypesCaseInsensitively() {
        assertEquals(MetadataDbType.GAUSSDB, MetadataDbType.parse("gaussdb"));
        assertEquals(MetadataDbType.GDB, MetadataDbType.parse(" GDB "));
    }

    @Test
    void rejectsUnknownMetadataDatabaseType() {
        assertThrows(IllegalArgumentException.class, () -> MetadataDbType.parse("oracle"));
    }
}
