package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NewSchemaTest {
    @Test void productionDdlCreatesEmptyTablesSupportsWideSqlAndUniqueTaskIdentity() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("../sql/create_bcomp_new.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        for (String name : new String[]{"CONF", "TASK", "DATABASE_CONFIG", "TABLE_SQL", "FIELD", "DTL", "ENUM", "DIFFFIELD", "BATCH", "BATCH_ITEM", "RESULT", "FIELD_DIFF"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_" + name + "_NEW", Long.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_IGNORE_VALUE_NEW", Long.class)).isZero();
        jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,REVISER_NAME,REVISER_USERNAME) VALUES(1,?,?)", "名".repeat(100), "u".repeat(100));
        assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1", String.class)).isEqualTo("名".repeat(100));
        assertThat(jdbc.queryForObject("SELECT REVISER_USERNAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1", String.class)).isEqualTo("u".repeat(100));
        String wideSql = "x".repeat(12000);
        jdbc.update("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW (BCOMP_INDEX,ORIG_SQL) VALUES (1,?)", wideSql);
        assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW", String.class)).isEqualTo(wideSql);
        jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW (BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE) VALUES (1,'20260921','1','A')");
        assertThat(jdbc.queryForObject("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW (BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE) VALUES (1,'20260921','1','B')"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT) VALUES(1,'20260927','run',?,'[\"a\"]',3,1)", "a".repeat(64));
        assertThat(jdbc.queryForObject("SELECT COLLAPSED_PATTERN_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE TSS_BCOMP_FIELD_DIFF_NEW SET COLLAPSED_PATTERN_COUNT=0"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void reviserMigrationsPreserveExistingConfigurationAndAllowLegacyRows() {
        for (String dialect : new String[]{"new", "gdb"}) {
            String mode = dialect.equals("new") ? "PostgreSQL" : "MySQL";
            var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1", "sa", "");
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE TSS_BCOMP_CONF_NEW(BCOMP_INDEX BIGINT PRIMARY KEY,BCOMP_NAME VARCHAR(100))");
            jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME) VALUES(1,'existing')");
            new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_reviser_" + dialect + ".sql")).execute(ds);
            assertThat(jdbc.queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1", String.class)).isEqualTo("existing");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW WHERE REVISER_NAME IS NULL AND REVISER_USERNAME IS NULL", Integer.class)).isEqualTo(1);
            jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET REVISER_NAME=?,REVISER_USERNAME=? WHERE BCOMP_INDEX=1", "名".repeat(100), "u".repeat(100));
            jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME) VALUES(2,'legacy')");
            assertThat(jdbc.queryForObject("SELECT REVISER_USERNAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1", String.class)).isEqualTo("u".repeat(100));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=2 AND REVISER_NAME IS NULL AND REVISER_USERNAME IS NULL", Integer.class)).isEqualTo(1);
        }
    }

    @Test void patternOverflowMigrationDefaultsExistingRowsAndRejectsNonPositiveCounts() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("../sql/create_bcomp_new.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("ALTER TABLE TSS_BCOMP_FIELD_DIFF_NEW DROP COLUMN COLLAPSED_PATTERN_COUNT");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT) VALUES(1,'20260927','run',?,'[\"a\"]',3,1)", "a".repeat(64));
        new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_pattern_overflow_new.sql")).execute(ds);
        assertThat(jdbc.queryForObject("SELECT COLLAPSED_PATTERN_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE TSS_BCOMP_FIELD_DIFF_NEW SET COLLAPSED_PATTERN_COUNT=0"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void incrementalFieldPatternMigrationPreservesDailyResultsAndLegacySamples() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        String oldSchema = java.nio.file.Files.readString(java.nio.file.Path.of("../sql/create_bcomp_new.sql"))
            .split("-- 批次历史", 2)[0] + "COMMIT;";
        new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(oldSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_batch_tables_new.sql")).execute(ds);
        new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_daily_results_new.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW (BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT) VALUES (1,'20260922','old','20260922','1','A',1,1,0,0,0,1,1000,0,0,1,'20260922120000')");
        jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW (BCOMP_INDEX,TASK_DATE,TASK_BATCH,COMP_INDEX_STR,LINE_RESULT,RESULT_DATE,SAMPLE_NO,DETAIL_JSON) VALUES (1,'20260922','1','key','4','20260922',1,'{}')");
        new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_field_patterns_new.sql")).execute(ds);
        assertThat(jdbc.queryForObject("SELECT DIFFERENT_COUNT FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW WHERE PATTERN_ID IS NULL", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_DIFF_NEW", Long.class)).isZero();
    }

    @Test void incrementalBatchMigrationPreservesExistingTasks() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        String oldSchema = java.nio.file.Files.readString(java.nio.file.Path.of("../sql/create_bcomp_new.sql"))
            .split("-- 批次历史", 2)[0] + "COMMIT;";
        new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(oldSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW (BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH) VALUES (1,'20260922','old')");
        new ResourceDatabasePopulator(new FileSystemResource("../sql/add_bcomp_batch_tables_new.sql")).execute(ds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_BATCH_NEW", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_BATCH_ITEM_NEW", Integer.class)).isZero();
    }
}
