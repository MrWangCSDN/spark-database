package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import cn.sunline.tss.compare.metadata.MetadataDbType;
import cn.sunline.tss.compare.metadata.MetadataSqlDialect;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigurationImportRetentionApiTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-26T02:00:00Z"),ZoneId.of("Asia/Shanghai"));

    @Test void importCleansBothDialectsAtTheFixedPublishCutoffAndPreservesHistory() throws Exception {
        for(MetadataDbType type:MetadataDbType.values()) {
            var f=new Fixture(type);
            f.result(22,"20240101","20261119095959",true);
            f.result(23,"20200101","20261126100000",false); // old business day, freshly rerun
            f.result(24,"20240101","20261119100000",false); // exact cutoff remains
            f.result(25,"20240101","20261119100001",false);
            f.legacy(30,"old","3","20261119095959");
            f.legacy(31,"recent","3","20261126100000");
            f.legacy(32,"orphan",null,null);
            f.legacy(33,"invalid","3","20260230010203");
            f.legacy(34,"missing-end","3",null);
            f.legacy(35,"cancelled","5","20261119095959");
            long tasks=f.count("TSS_BCOMP_TASK_NEW");

            f.importScript(f.source,f.script(),()->{}).andExpect(status().isOk())
                .andExpect(jsonPath("$.cleanup.retentionDays").value(7))
                .andExpect(jsonPath("$.cleanup.cutoff").value("20261119100000"))
                .andExpect(jsonPath("$.cleanup.deletedResults").value(1))
                .andExpect(jsonPath("$.cleanup.deletedDetails").value(3))
                .andExpect(jsonPath("$.cleanup.deletedLegacyDetails").value(2))
                .andExpect(jsonPath("$.cleanup.deletedFieldPatterns").value(1))
                .andExpect(jsonPath("$.cleanup.deletedFieldCounts").value(1));
            assertThat(f.count("TSS_BCOMP_RESULT_NEW")).isEqualTo(3);
            assertThat(f.count("TSS_BCOMP_FIELD_DIFF_NEW")).isEqualTo(3);
            assertThat(f.count("TSS_BCOMP_FIELD_COUNT_NEW")).isEqualTo(3);
            assertThat(f.count("TSS_BCOMP_DTL_NEW")).isEqualTo(7);
            assertThat(f.count("TSS_BCOMP_TASK_NEW")).isEqualTo(tasks);
            f.assertControlHistoryPreserved();

            f.importScript(f.source,f.script(),()->{}).andExpect(status().isOk())
                .andExpect(jsonPath("$.cleanup.deletedResults").value(0))
                .andExpect(jsonPath("$.cleanup.deletedDetails").value(0));
        }
    }

    @Test void invalidScriptsReferencesAndBusinessKeysLeaveResultsAndConfigurationUntouched() throws Exception {
        for(String scenario:new String[]{"forbidden","missing-database","missing-key"}) {
            var f=new Fixture(MetadataDbType.GAUSSDB);f.result(22,"20240101","20261118000000",false);
            String script=f.script();
            if(scenario.equals("forbidden")) script=script.replace("COMMIT;","DELETE FROM TSS_BCOMP_TASK_NEW; COMMIT;");
            if(scenario.equals("missing-database")) f.jdbc.update("DELETE FROM TSS_BCOMP_DATABASE_CONFIG_NEW WHERE DATABASE_ID=2");
            if(scenario.equals("missing-key")) script=script.replace(",'1','0')",",'0','0')");
            var reloads=new AtomicInteger();
            f.importScript(f.source,script,reloads::incrementAndGet).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
            f.assertOriginalResultAndConfiguration();
            assertThat(reloads).hasValue(0);
        }
    }

    @Test void activeTasksBlockImportAndHistoryDeletion() throws Exception {
        var f=new Fixture(MetadataDbType.GAUSSDB);f.result(22,"20240101","20261118000000",false);
        f.jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,TASK_STATE) VALUES(88,'20261126','waiting','1')");
        var reloads=new AtomicInteger();
        f.importScript(f.source,f.script(),reloads::incrementAndGet).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFIG_IMPORT_TASKS_ACTIVE"));
        f.assertOriginalResultAndConfiguration();assertThat(reloads).hasValue(0);
    }

    @Test void lateCleanupFailureRollsBackDeletedResultsAndImportedConfiguration() throws Exception {
        var f=new Fixture(MetadataDbType.GAUSSDB);f.result(22,"20240101","20261118000000",false);
        f.legacy(30,"old","3","20261118000000");
        var afterDeletes=new AtomicBoolean();var reloads=new AtomicInteger();
        DataSource faulty=new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate=f.source.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                    if(m.getName().equals("prepareStatement")) {
                        String query=args[0].toString().toUpperCase(Locale.ROOT);
                        if(query.startsWith("DELETE FROM TSS_BCOMP_DTL_NEW")&&query.matches("(?s).*RESULT_DATE\\s+IS\\s+NULL.*")) {
                            try(var statement=delegate.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM TSS_BCOMP_RESULT_NEW")) {
                                rows.next();afterDeletes.set(rows.getLong(1)==0);
                            }
                            throw new SQLException("injected legacy cleanup failure");
                        }
                    }
                    try{return m.invoke(delegate,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
            @Override public Connection getConnection(String u,String p) throws SQLException {return getConnection();}
        };
        f.importScript(faulty,f.script(),reloads::incrementAndGet).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CONFIG_SCRIPT_EXECUTION_FAILED"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("均未提交")));
        assertThat(afterDeletes).isTrue();
        f.assertOriginalResultAndConfiguration();
        assertThat(f.count("TSS_BCOMP_DTL_NEW")).isEqualTo(2);
        assertThat(reloads).hasValue(0);
    }

    @Test void cacheFailureReportsAlreadyCommittedCleanup() throws Exception {
        var f=new Fixture(MetadataDbType.GAUSSDB);f.result(22,"20240101","20261118000000",false);
        f.importScript(f.source,f.script(),()->{throw new IllegalStateException("cache");})
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(false))
            .andExpect(jsonPath("$.cleanup.deletedResults").value(1));
        assertThat(f.count("TSS_BCOMP_RESULT_NEW")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Long.class)).isEqualTo(1);
    }

    private static final class Fixture {
        final DriverManagerDataSource source;
        final JdbcTemplate jdbc;
        final MetadataSqlDialect dialect;
        Fixture(MetadataDbType type) {
            String mode=type==MetadataDbType.GDB?"MySQL":"PostgreSQL";
            source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE="+mode+";DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
            jdbc=new JdbcTemplate(source);dialect=MetadataSqlDialect.forType(type);
            jdbc.update("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'old'),(2,'new')");
            jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) VALUES(22,'original','2')");
            jdbc.update("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID,ORIG_SQL,DEST_SQL) VALUES(22,1,2,'original old','original new')");
            jdbc.update("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_INDEX_FLAG) VALUES(22,'original field','1')");
            jdbc.update("INSERT INTO TSS_BCOMP_BATCH_NEW VALUES('keep-batch','request','hash','20240101',NULL,NULL,'20240101000000',1)");
            jdbc.update("INSERT INTO TSS_BCOMP_BATCH_ITEM_NEW VALUES('keep-batch',22,'original','dept','3',NULL,'hash')");
            jdbc.update("INSERT INTO TSS_BCOMP_ENUM_NEW VALUES(1,'old','new')");
            jdbc.update("INSERT INTO TSS_IGNORE_VALUE_NEW VALUES(1,'keep')");
        }
        String script() throws Exception {
            try(var input=new ClassPathResource("axon-primary-key-hash-config.sql").getInputStream()) {
                return new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }
        }
        ResultActions importScript(DataSource connectionSource,String script,CacheReloader reloader) throws Exception {
            var repository=new ConfigurationRepository(connectionSource,30,dialect,CLOCK);
            MockMvc mvc=MockMvcBuilders.standaloneSetup(new ConfigurationController(new ConfigurationService(repository,reloader)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
            return mvc.perform(multipart("/api/configurations/import").file(new MockMultipartFile("file","axon.sql","application/sql",script.getBytes(StandardCharsets.UTF_8))));
        }
        void result(long index,String date,String published,boolean expired) {
            jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT,DETAILS_EXPIRED) VALUES(?,?,?,'20240101','old','3',1,1,0,0,0,1,100,0,0,1,?,?)",index,date,"run-"+index,published,expired?"1":"0");
            jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,RESULT_DATE,LINE_RESULT,SAMPLE_NO,DETAIL_JSON) VALUES(?,'20240101','old',?,'4',1,'{}')",index,date);
            jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT) VALUES(?,?,?,'p','[\"F\"]',1,1)",index,date,"run-"+index);
            jdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(?,?,?,'F',1)",index,date,"run-"+index);
        }
        void legacy(long index,String batch,String state,String end) {
            jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,LINE_RESULT) VALUES(?,'20200101',?,'3')",index,batch);
            if(state!=null) jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,TASK_STATE,END_DATETIME) VALUES(?,'20200101',?,?,?)",index,batch,state,end);
        }
        long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
        void assertControlHistoryPreserved() {
            assertThat(count("TSS_BCOMP_BATCH_NEW")).isEqualTo(1);
            assertThat(count("TSS_BCOMP_BATCH_ITEM_NEW")).isEqualTo(1);
            assertThat(count("TSS_BCOMP_DATABASE_CONFIG_NEW")).isEqualTo(2);
            assertThat(count("TSS_BCOMP_ENUM_NEW")).isEqualTo(1);
            assertThat(count("TSS_IGNORE_VALUE_NEW")).isEqualTo(1);
        }
        void assertOriginalResultAndConfiguration() {
            assertThat(jdbc.queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=22",String.class)).isEqualTo("original");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Long.class)).isZero();
            assertThat(count("TSS_BCOMP_RESULT_NEW")).isEqualTo(1);
            assertThat(count("TSS_BCOMP_FIELD_DIFF_NEW")).isEqualTo(1);
            assertThat(count("TSS_BCOMP_FIELD_COUNT_NEW")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW WHERE RESULT_DATE IS NOT NULL",Long.class)).isEqualTo(1);
        }
    }
}
