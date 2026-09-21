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
        for (String name : new String[]{"CONF", "TASK", "DATABASE_CONFIG", "TABLE_SQL", "FIELD", "DTL", "ENUM", "DIFFFIELD"}) {
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
}
