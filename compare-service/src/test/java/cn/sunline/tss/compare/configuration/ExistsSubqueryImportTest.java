package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the same real Axon-generated NEW17 fixture as ExistsSubqueryComparisonTest, without a module dependency. */
class ExistsSubqueryImportTest {
    @Test void importsGeneratedCorrelatedExistsQueriesWithoutChangingTheirEscapedLiteralsOrWrappers() throws Exception {
        Path path = Path.of(System.getProperty("bd.exists.fixture", "../sql/test-fixtures/axon-exists-new17.sql"));
        assertThat(path).as("actual Axon NEW17; override with -Dbd.exists.fixture=/absolute/path.sql").isRegularFile();
        String script = Files.readString(path);
        var source = new DriverManagerDataSource("jdbc:h2:mem:exists_import_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID) VALUES(1),(2)");
        var mvc = MockMvcBuilders.standaloneSetup(new ConfigurationController(
            new ConfigurationService(new ConfigurationRepository(source, 30), () -> {})))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(multipart("/api/configurations/import").file(new MockMultipartFile(
                "file", "axon-exists-new17.sql", "application/sql", Files.readAllBytes(path))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(true))
            .andExpect(jsonPath("$.configurationCount").value(3))
            .andExpect(jsonPath("$.fieldCount").value(12))
            .andExpect(jsonPath("$.tableSqlCount").value(3));

        var codes = new ArrayList<String>();
        var limits = new ArrayList<Integer>();
        var negative = new ArrayList<Boolean>();
        jdbc.query("SELECT c.BCOMP_INDEX,c.BCOMP_NAME,c.BCOMP_SAMPLE_LIMIT,c.SOURCE_TABLE_NAME,c.CONFIGURATION_SCOPE,s.ORIG_SQL,s.DEST_SQL "
            + "FROM TSS_BCOMP_CONF_NEW c JOIN TSS_BCOMP_TABLE_SQL_NEW s ON c.BCOMP_INDEX=s.BCOMP_INDEX ORDER BY c.BCOMP_INDEX",
            (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                long index = row.getLong(1);
                codes.add(row.getString(2));
                limits.add(row.getInt(3));
                assertThat(row.getString(4)).isEqualTo("kgnb_chrg_item");
                assertThat(row.getString(5).toUpperCase(Locale.ROOT)).contains("EXISTS");
                negative.add(row.getString(5).toUpperCase(Locale.ROOT).contains("NOT EXISTS"));
                for (int column : List.of(6, 7)) {
                    String sql = row.getString(column);
                    // Re-escaping the saved value must reproduce an exact literal in the original generated script.
                    assertThat(script).contains("'" + sql.replace("'", "''") + "'");
                    assertThat(sql).as("SQL must belong to configuration %s", row.getString(2)).contains(row.getString(5));
                    assertThat(sql.toLowerCase(Locale.ROOT)).contains("result_q", "kgna_gntl_acct", "kci.gntltr_id", "gntltr_bsn_stcd <> 's'");
                    assertThat(sql.toLowerCase(Locale.ROOT).trim()).endsWith(column == 6 ? "orig" : "dest");
                }
                assertThat(jdbc.queryForList("SELECT FIELD_NAME FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=? ORDER BY FIELD_OLD_INDEX", String.class, index))
                    .containsExactly("tenant_id", "item_id", "gntltr_id", "amount");
                assertThat(jdbc.queryForList("SELECT FIELD_NAME FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=? AND FIELD_INDEX_FLAG='1' ORDER BY FIELD_OLD_INDEX", String.class, index))
                    .containsExactly("tenant_id", "item_id");
            });
        assertThat(codes).containsExactly("kgnb_chrg_item", "kgnb_chrg_item_1", "kgnb_chrg_item_2");
        assertThat(limits).containsExactly(100, -1, -1);
        assertThat(negative).containsExactly(false, true, false);
    }
}
