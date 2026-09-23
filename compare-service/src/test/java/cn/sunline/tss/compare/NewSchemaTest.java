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
        String wideSql = "x".repeat(12000);
        jdbc.update("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW (BCOMP_INDEX,ORIG_SQL) VALUES (1,?)", wideSql);
        assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW", String.class)).isEqualTo(wideSql);
        jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW (BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE) VALUES (1,'20260921','1','A')");
        assertThat(jdbc.queryForObject("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW (BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE) VALUES (1,'20260921','1','B')"))
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
