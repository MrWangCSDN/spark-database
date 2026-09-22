package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigurationApiTest {
    JdbcTemplate jdbc;
    ConfigurationRepository repository;

    @BeforeEach void setUp() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source); repository=new ConfigurationRepository(source,30);
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION) values(1,'old','jdbc:o','u','p','D','0'),(2,'new','jdbc:n','u','p','D','0')");
    }

    MockMvc mvc(CacheReloader reloader){
        return MockMvcBuilders.standaloneSetup(new ConfigurationController(new ConfigurationService(repository,reloader)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    MockMvc mvc(CacheReloader reloader,long maxImportBytes){
        return MockMvcBuilders.standaloneSetup(new ConfigurationController(new ConfigurationService(repository,reloader,maxImportBytes)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    String valid(long index,String name){return """
        {"basic":{"bcompIndex":%d,"name":"%s","module":"ACCOUNT","memo":"日终","range":"ALL","timeNode":"EOD","state":"1","partitionNum":16,"dateMode":"2"},
         "tableSql":{"origDatabaseId":1,"destDatabaseId":2,"origSql":"OLD_SQL","destSql":"NEW_SQL"},
         "fields":[{"name":"ID","type":"1","memo":"主键","oldIndex":1,"newIndex":1,"indexFlag":"1","enumFlag":"0","enumIndex":null,"partitionFlag":"1","ignoreValue":null,"ignoreFlag":"0"}]}
        """.formatted(index,name);}

    String axonScript(String name) { return """
        START TRANSACTION;

        TRUNCATE TABLE tss_bcomp_field_new;
        TRUNCATE TABLE tss_bcomp_table_sql_new;
        TRUNCATE TABLE tss_bcomp_conf_new;

        INSERT INTO tss_bcomp_conf_new
          (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state)
        VALUES
          (1,'dept','%s','配置''说明','2','1','3','1');

        INSERT INTO tss_bcomp_field_new
          (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
        VALUES
          (1,'ID','1','主键', '1','1','1','0'),
          (1,'UPDATED_AT','1','时间', '2','2','0','0');

        INSERT INTO tss_bcomp_table_sql_new
          (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
        VALUES
          (1,'(select ID,UPDATED_AT from T where NOTE=''a;b'') orig',1,'(select ID,UPDATED_AT from T where NOTE=''a;b'') dest',2);

        COMMIT;
        -- Version: V1
        -- Tables: 1
        -- Fields: 2
        """.formatted(name); }

    MockMultipartFile scriptFile(String script) {
        return new MockMultipartFile("file","axon.sql","application/sql",script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    String hashScript(String count, String strategy) {
        return axonScript("HASH_TABLE")
            .replace("bcomp_time_node,bcomp_state)", "bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy)")
            .replace("'配置''说明','2','1','3','1');", "'配置''说明','2','1','3','1',"+count+","+strategy+");");
    }

    String sampleScript(String limit) {
        return hashScript("16", "'HASH'")
            .replace("bcomp_shard_strategy)", "bcomp_shard_strategy,bcomp_sample_limit)")
            .replace("16,'HASH');", "16,'HASH',"+limit+");");
    }

    @Test void importsSampleLimitsAndKeepsLegacyFallbackNullable() throws Exception {
        for (int limit : new int[]{0,37,1000,10000}) {
            mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(sampleScript(""+limit))))
                .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select BCOMP_SAMPLE_LIMIT from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",Integer.class)).isEqualTo(limit);
        }
        for (String legacy : new String[]{axonScript("OLD"),hashScript("16","'HASH'")}) {
            mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(legacy))).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select BCOMP_SAMPLE_LIMIT from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",Integer.class)).isNull();
        }
    }

    @Test void invalidSampleLimitsRollBackReplacementAndDoNotRefresh() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"保留配置")))
            .andExpect(status().isCreated());
        AtomicInteger reloads=new AtomicInteger();
        for (String limit : new String[]{"-1","10001","1.5","NULL","'1000'","1e3","999999999999999999999"}) {
            mvc(reloads::incrementAndGet).perform(multipart("/api/configurations/import").file(scriptFile(sampleScript(limit))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        }
        assertThat(reloads).hasValue(0);
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",String.class)).isEqualTo("保留配置");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
    }

    @Test void hashOnlyDefaultsCountAndDiscardsPartitionFlags() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON)
            .content(valid(31,"hash").replace("\"partitionNum\":16", "\"partitionNum\":null")))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT BCOMP_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=31",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT FIELD_PARTITION_FLAG FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=31",String.class)).isEqualTo("0");
        for(int count : new int[]{0,257}) {
            mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON)
                .content(valid(32,"bad").replace("\"partitionNum\":16", "\"partitionNum\":"+count)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARTITION_COUNT"));
        }
    }

    @Test void rejectsOldTableScriptWithoutTouchingOldTables() throws Exception {
        jdbc.execute("CREATE TABLE TSS_BCOMP_CONF (BCOMP_INDEX BIGINT)");
        jdbc.update("INSERT INTO TSS_BCOMP_CONF VALUES (99)");
        mvc(() -> {}).perform(multipart("/api/configurations/import")
            .file(scriptFile(axonScript("OLD").replace("_new", ""))))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT BCOMP_INDEX FROM TSS_BCOMP_CONF", Long.class)).isEqualTo(99L);
    }

    @Test void importsHashPartitionCountWithoutPartitionField() throws Exception {
        for (int count : new int[]{1,16,256}) {
            mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(hashScript(""+count,"'HASH'"))))
                .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select BCOMP_PARTITION_NUM from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",Integer.class)).isEqualTo(count);
            assertThat(jdbc.queryForObject("select BCOMP_SHARD_STRATEGY from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",String.class)).isEqualTo("HASH");
            assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_FIELD_NEW where FIELD_PARTITION_FLAG='1'",Integer.class)).isZero();
        }
    }

    @Test void importsTheActualAxonPrimaryKeyHashExportContract() throws Exception {
        byte[] script = new ClassPathResource("axon-primary-key-hash-config.sql")
            .getInputStream().readAllBytes();
        var file = new MockMultipartFile("file", "axon-primary-key-hash-config.sql", "application/sql", script);
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationCount").value(1))
            .andExpect(jsonPath("$.fieldCount").value(3));
        assertThat(jdbc.queryForObject("select BCOMP_PARTITION_NUM from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("select BCOMP_SAMPLE_LIMIT from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1", Integer.class)).isEqualTo(37);
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_FIELD_NEW where BCOMP_INDEX=1 and FIELD_INDEX_FLAG='1'", Integer.class)).isEqualTo(2);
    }

    @Test void invalidHashParametersRollBackReplacementAndDoNotRefresh() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"保留配置")))
            .andExpect(status().isCreated());
        AtomicInteger reloads=new AtomicInteger();
        for (String count : new String[]{"0","-1","257","1.5","NULL","'16'","999999999999999999999"}) {
            mvc(reloads::incrementAndGet).perform(multipart("/api/configurations/import").file(scriptFile(hashScript(count,"'HASH'"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        }
        mvc(reloads::incrementAndGet).perform(multipart("/api/configurations/import").file(scriptFile(hashScript("16","'RANGE'"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        assertThat(reloads).hasValue(0);
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",String.class)).isEqualTo("保留配置");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
    }

    @Test void hashScriptWithNoBusinessKeyIsRejectedAndRolledBack() throws Exception {
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(
            hashScript("16","'HASH'").replace("'主键', '1','1','1','0'", "'主键', '1','1','0','0'"))))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW",Integer.class)).isZero();
    }

    @Test void legacyScriptStillImportsWhenOptionalStrategyColumnDoesNotExist() throws Exception {
        jdbc.execute("ALTER TABLE TSS_BCOMP_CONF_NEW DROP COLUMN BCOMP_SHARD_STRATEGY");
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(axonScript("LEGACY"))))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",String.class)).isEqualTo("LEGACY");
    }

    @Test void newScriptOnAnUnmigratedDatabaseDoesNotEraseExistingConfiguration() throws Exception {
        jdbc.execute("ALTER TABLE TSS_BCOMP_CONF_NEW DROP COLUMN BCOMP_SHARD_STRATEGY");
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(axonScript("LEGACY"))))
            .andExpect(status().isOk());
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(hashScript("16","'HASH'"))))
            .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",String.class)).isEqualTo("LEGACY");
    }

    @Test void importsAxonScriptAsOneTransactionAndRefreshesCache() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"旧配置")))
            .andExpect(status().isCreated());
        AtomicInteger reloads=new AtomicInteger();

        mvc(reloads::incrementAndGet).perform(multipart("/api/configurations/import").file(scriptFile(axonScript("ACCOUNT_LOG"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(true))
            .andExpect(jsonPath("$.configurationCount").value(1))
            .andExpect(jsonPath("$.fieldCount").value(2))
            .andExpect(jsonPath("$.tableSqlCount").value(1));

        assertThat(reloads).hasValue(1);
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",String.class)).isEqualTo("ACCOUNT_LOG");
        assertThat(jdbc.queryForObject("select ORIG_SQL from TSS_BCOMP_TABLE_SQL_NEW where BCOMP_INDEX=1",String.class)).contains("a;b");
    }

    @Test void rejectsForbiddenSqlAndRollsBackWholeImport() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"保留配置")))
            .andExpect(status().isCreated());
        String invalid=axonScript("被拒绝").replace("COMMIT;", "DELETE FROM TSS_BCOMP_TASK_NEW;\nCOMMIT;");

        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(invalid)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));

        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",String.class)).isEqualTo("保留配置");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",Integer.class)).isZero();
    }

    @Test void rejectsValueExpressionsAndMismatchedTableCounts() throws Exception {
        String expression=axonScript("EXPR").replace("'配置''说明'","CURRENT_TIMESTAMP");
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(expression)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));

        String mismatch=axonScript("MISMATCH").replace(
            "(1,'dept','MISMATCH','配置''说明','2','1','3','1');",
            "(1,'dept','MISMATCH','配置''说明','2','1','3','1'),\n  (2,'dept','SECOND','第二个','2','1','3','1');");
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(mismatch)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW",Integer.class)).isZero();
    }

    @Test void activeTaskBlocksWholeScriptImport() throws Exception {
        jdbc.update("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(22,'20260921','1','3','1')");

        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(axonScript("BLOCKED"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFIG_IMPORT_TASKS_ACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_CONF_NEW",Integer.class)).isZero();
    }

    @Test void importReportsCommittedDataWhenCacheRefreshFails() throws Exception {
        mvc(() -> { throw new IllegalStateException("cache"); })
            .perform(multipart("/api/configurations/import").file(scriptFile(axonScript("SAVED"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(false))
            .andExpect(jsonPath("$.warning").value("配置已导入，但缓存刷新失败"));
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",String.class)).isEqualTo("SAVED");
    }

    @Test void importRequiresANonEmptyFileAndHonorsSizeLimit() throws Exception {
        mvc(() -> {}).perform(multipart("/api/configurations/import"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIG_SCRIPT_REQUIRED"));
        mvc(() -> {},8).perform(multipart("/api/configurations/import").file(scriptFile(axonScript("TOO_BIG"))))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("CONFIG_SCRIPT_TOO_LARGE"));
    }

    @Test void createsAggregateAndRefreshesCache() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"客户余额")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(true));
        assertThat(jdbc.queryForObject("select BCOMP_TYPE from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_FIELD_NEW where BCOMP_INDEX=22",Integer.class)).isEqualTo(1);
    }

    @Test void invalidSecondFieldDoesNotMutateExistingAggregate() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"before"))).andExpect(status().isCreated());
        String invalid=valid(22,"after").replace("]}",",{\"name\":\"DUP\",\"type\":\"1\",\"oldIndex\":1,\"newIndex\":2,\"indexFlag\":\"0\",\"enumFlag\":\"0\",\"partitionFlag\":\"0\",\"ignoreFlag\":\"0\"}]}");
        mvc(() -> {}).perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(invalid))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_FIELD_POSITION"));
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",String.class)).isEqualTo("before");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_FIELD_NEW where BCOMP_INDEX=22",Integer.class)).isEqualTo(1);
    }

    @Test void runningTaskBlocksConfigurationUpdate() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"before"))).andExpect(status().isCreated());
        jdbc.update("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(22,'20260920','1','EOD','2')");
        mvc(() -> {}).perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(valid(22,"after")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_IN_USE"));
    }

    @Test void cacheFailureReportsSavedWarning() throws Exception {
        mvc(() -> {throw new IllegalStateException("cache");}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"saved")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(false))
            .andExpect(jsonPath("$.warning").value("配置已保存，但缓存刷新失败"));
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=22",String.class)).isEqualTo("saved");
    }

    @Test void copiesConfigurationWithoutTasksAndReturnsOptions() throws Exception {
        var mvc=mvc(() -> {});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"source"))).andExpect(status().isCreated());
        mvc.perform(post("/api/configurations/22/copy").contentType(APPLICATION_JSON).content("{\"newIndex\":23,\"newName\":\"副本\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.bcompIndex").value(23));
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_TASK_NEW where BCOMP_INDEX=23",Integer.class)).isZero();
        mvc.perform(get("/api/configuration-options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.states[0]").value("1"));
    }
}
