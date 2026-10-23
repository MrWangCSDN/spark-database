package cn.sunline.tss.compare.configuration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class GdbScriptTest {
    @Test
    void gdbFullSchemaUsesMysqlCompatibleSyntaxAndKeepsAllTablesAndIndexes() throws Exception {
        String sql = Files.readString(Path.of("sql/create_bcomp_gdb.sql"));
        assertFalse(sql.contains("search_path"));
        assertFalse(sql.contains("FETCH FIRST"));
        assertFalse(sql.matches("(?s).*\\bTEXT\\b.*"));
        assertFalse(sql.contains("LONGLONGTEXT"));
        for (String table : new String[]{"TSS_BCOMP_CONF_NEW", "TSS_BCOMP_TASK_NEW", "TSS_BCOMP_DATABASE_CONFIG_NEW",
            "TSS_BCOMP_TABLE_SQL_NEW", "TSS_BCOMP_FIELD_NEW", "TSS_BCOMP_DTL_NEW", "TSS_BCOMP_RESULT_NEW",
            "TSS_BCOMP_FIELD_DIFF_NEW", "TSS_BCOMP_BATCH_NEW", "TSS_BCOMP_BATCH_ITEM_NEW"}) {
            assertTrue(sql.contains("CREATE TABLE " + table), table);
        }
        assertTrue(sql.contains("CREATE UNIQUE INDEX idx_bcomp_dtl_daily_sample"));
        assertTrue(sql.contains("START TRANSACTION"));
    }

    @Test
    void gdbAxonExampleUsesFixedImportContractAndCompositeBusinessKeys() throws Exception {
        var parsed = new AxonConfigScriptParser().parse(
            new StringReader(Files.readString(Path.of("docs/examples/axon-config-gdb.sql"))), command -> {});
        assertEquals(1, parsed.configurationCount());
        assertEquals(3, parsed.fieldCount());
        assertEquals(1, parsed.tableSqlCount());
        assertTrue(parsed.hasHashConfigurations());
    }

    @Test
    void gdbRuntimeCleanupUsesOneTruncateStatementPerTable() throws Exception {
        String sql = Files.readString(Path.of("sql/clear_bcomp_runtime_data_gdb.sql"));
        assertFalse(sql.matches("(?is).*TRUNCATE\\s+TABLE[^;]*,[^;]*;.*"));
        assertTrue(sql.contains("TRUNCATE TABLE TSS_BCOMP_BATCH_ITEM_NEW;"));
        assertTrue(sql.contains("TRUNCATE TABLE TSS_BCOMP_RESULT_NEW;"));
    }
}
