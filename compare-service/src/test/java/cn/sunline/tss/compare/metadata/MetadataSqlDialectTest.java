package cn.sunline.tss.compare.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataSqlDialectTest {
    @Test
    void gaussDbUsesExistingOffsetFetchSyntaxAndParameterOrder() {
        MetadataSqlDialect dialect = MetadataSqlDialect.forType(MetadataDbType.GAUSSDB);

        MetadataSqlDialect.Page page = dialect.page("SELECT * FROM T ORDER BY ID", 20, 10);
        MetadataSqlDialect.Page limited = dialect.limit("SELECT * FROM T ORDER BY ID", 5001);

        assertEquals("SELECT * FROM T ORDER BY ID OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", page.sql());
        assertEquals(List.of(20L, 10), page.parameters());
        assertEquals("SELECT * FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY", limited.sql());
        assertEquals(List.of(5001), limited.parameters());
    }

    @Test
    void gdbUsesMysqlLimitOffsetSyntaxAndBindsLimitBeforeOffset() {
        MetadataSqlDialect dialect = MetadataSqlDialect.forType(MetadataDbType.GDB);

        MetadataSqlDialect.Page page = dialect.page("SELECT * FROM T ORDER BY ID", 20, 10);
        MetadataSqlDialect.Page limited = dialect.limit("SELECT * FROM T ORDER BY ID", 5001);

        assertEquals("SELECT * FROM T ORDER BY ID LIMIT ? OFFSET ?", page.sql());
        assertEquals(List.of(10, 20L), page.parameters());
        assertEquals("SELECT * FROM T ORDER BY ID LIMIT ?", limited.sql());
        assertEquals(List.of(5001), limited.parameters());
    }
}
