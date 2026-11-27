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
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
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

    MockMvc mvcByName(){
        return MockMvcBuilders.standaloneSetup(new ConfigurationController(new ConfigurationService(repository,()->{},52_428_800L,true)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void createsReadsListsAndCopiesTableHashPartitionLimits() throws Exception {
        var mvc=mvc(()->{});
        for(int limit:new int[]{1,64,100,128,256}) {
            long index=1000+limit;
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(index,"limit"+limit),""+limit)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(limit));
            mvc.perform(get("/api/configurations/"+index)).andExpect(status().isOk())
                .andExpect(jsonPath("$.basic.maxPartitionNum").value(limit));
            assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=?",Integer.class,index)).isEqualTo(limit);
        }
        mvc.perform(get("/api/configurations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].maxPartitionNum").value(1));
        mvc.perform(post("/api/configurations/1256/copy").contentType(APPLICATION_JSON).content("{\"newIndex\":2000,\"newName\":\"copy\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(256));
    }

    @Test void defaultsMissingAndNullLimitsAndReadsLegacyNullAs64() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"missing")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(64));
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(23,"null"),"null")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(64));
        jdbc.execute("ALTER TABLE TSS_BCOMP_CONF_NEW ALTER COLUMN BCOMP_MAX_PARTITION_NUM DROP NOT NULL");
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=NULL WHERE BCOMP_INDEX=22");
        mvc.perform(get("/api/configurations/22")).andExpect(status().isOk()).andExpect(jsonPath("$.basic.maxPartitionNum").value(64));
        mvc.perform(get("/api/configurations")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].maxPartitionNum").value(64));
    }

    @Test void omittedOrNullUpdatePreservesLimitAndExplicit64ResetsIt() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(22,"before"),"128")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(128));
        for(String body:new String[]{valid(22,"before"),withLimit(valid(22,"before"),"null")}) {
            mvc.perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(128));
        }
        mvc.perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(withLimit(valid(22,"before"),"64")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(64));
    }

    @Test void savesTablePartitionLimitWhenLegacyDateModeIsUnspecified() throws Exception {
        var mvc=mvc(()->{});
        String[] modes={null,""," "};
        for(int offset=0;offset<modes.length;offset++) {
            long index=226+offset;
            String mode=modes[offset];
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(index,"legacy"+index)))
                .andExpect(status().isCreated());
            jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_DATE_MODE=? WHERE BCOMP_INDEX=?",mode,index);
            // Save the detail response exactly as the editor does, changing only the cap.
            String response=mvc.perform(get("/api/configurations/"+index)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            var body=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            ((com.fasterxml.jackson.databind.node.ObjectNode)body.get("basic")).put("maxPartitionNum",256);
            mvc.perform(put("/api/configurations/"+index).contentType(APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(256));
            assertThat(jdbc.queryForObject("SELECT BCOMP_DATE_MODE FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=?",String.class,index)).isEqualTo(mode);
            assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=?",Integer.class,index)).isEqualTo(256);
        }
    }

    @Test void rejectsUnsupportedNonBlankDateModeWithoutChangingThePartitionLimit() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(226,"original")))
            .andExpect(status().isCreated());
        for(String mode:new String[]{"0","3","invalid"}) {
            String body=withLimit(valid(226,"invalid"),"256").replace("\"dateMode\":\"2\"","\"dateMode\":\""+mode+"\"");
            mvc.perform(put("/api/configurations/226").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIGURATION"));
        }
        assertThat(jdbc.queryForObject("SELECT BCOMP_DATE_MODE FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=226",String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=226",Integer.class)).isEqualTo(64);
    }

    @Test void rejectsInvalidLimitJsonWithoutSavingOrRefreshing() throws Exception {
        var reloads=new AtomicInteger();var mvc=mvc(reloads::incrementAndGet);
        for(String token:new String[]{"0","257","-1","64.0","64.5","1e2","\"64\"","true","false","[]","{}","2147483648","-2147483649","999999999999999999999999"}) {
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(22,"invalid"),token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MAX_PARTITION_NUM"));
        }
        assertThat(reloads).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isZero();
    }

    @Test void malformedConfigurationJsonKeepsTheStructuredApiError() throws Exception {
        var mvc=mvc(()->{});
        for(String body:new String[]{"{","{\"basic\":{\"bcompIndex\":\"oops\"}}"}) {
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIGURATION"))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.traceId").isNotEmpty());
        }
    }

    @Test void invalidLimitUpdatePreservesTheConfiguration() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(22,"before"),"128")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(128));
        mvc.perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(withLimit(valid(22,"after"),"8.5")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MAX_PARTITION_NUM"));
        mvc.perform(get("/api/configurations/22")).andExpect(status().isOk())
            .andExpect(jsonPath("$.basic.name").value("before")).andExpect(jsonPath("$.basic.maxPartitionNum").value(128));
    }

    @Test void waitingAndRunningTasksBlockPartitionLimitChanges() throws Exception {
        var mvc=mvc(()->{});
        for(int index:new int[]{22,23}) {
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(index,"active"+index),"128")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(128));
            jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(?,'20261123','active','EOD',?)",index,index==22?"1":"2");
            mvc.perform(put("/api/configurations/"+index).contentType(APPLICATION_JSON).content(withLimit(valid(index,"changed"),"32")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_IN_USE"));
            assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=?",Integer.class,index)).isEqualTo(128);
        }
    }

    @Test void allLegacyAxonFormatsPreserveMatchingTableLimitsAndDefaultNewIds() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(1,"HASH_TABLE"),"128")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(128));
        for(String script:new String[]{axonScript("HASH_TABLE"),hashScript("16","'HASH'"),sampleScript("100"),reviserScript("NULL","NULL")}) {
            mvc.perform(multipart("/api/configurations/import").file(scriptFile(script))).andExpect(status().isOk());
            mvc.perform(get("/api/configurations/1")).andExpect(status().isOk()).andExpect(jsonPath("$.basic.maxPartitionNum").value(128));
        }
        mvc.perform(multipart("/api/configurations/import").file(scriptFile(axonScript("new").replace("(1,","(2,"))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/configurations/2")).andExpect(status().isOk()).andExpect(jsonPath("$.basic.maxPartitionNum").value(64));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Integer.class)).isZero();
    }

    @Test void replacingImportDoesNotInheritLimitsFromNonTableConfigurations() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(1,"file"),"256")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(256));
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_TYPE='1' WHERE BCOMP_INDEX=1");
        mvc.perform(multipart("/api/configurations/import").file(scriptFile(axonScript("table")))).andExpect(status().isOk());
        mvc.perform(get("/api/configurations/2")).andExpect(status().isOk()).andExpect(jsonPath("$.basic.maxPartitionNum").value(64));
    }

    @Test void importsExplicitMaximumAndAllowsAnExcelReviserNameWithoutUsername() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(withLimit(valid(1,"HASH_TABLE"),"128")))
            .andExpect(status().isCreated());
        for(int limit:new int[]{1,64,100,256}) {
            jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=128 WHERE BCOMP_INDEX=1");
            mvc.perform(multipart("/api/configurations/import").file(scriptFile(maxPartitionScript(""+limit))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationCount").value(1));
            mvc.perform(get("/api/configurations/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.basic.maxPartitionNum").value(limit));
            assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("Excel负责人");
            assertThat(jdbc.queryForObject("SELECT REVISER_USERNAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isNull();
        }
    }

    @Test void importsActualAxonFullDifferenceExportThroughBothMetadataDialects() throws Exception {
        byte[] fixture;
        try(var input=new ClassPathResource("axon-full-differences.sql").getInputStream()) { fixture=input.readAllBytes(); }
        for(var type:cn.sunline.tss.compare.metadata.MetadataDbType.values()) {
            boolean mysql=type==cn.sunline.tss.compare.metadata.MetadataDbType.GDB;
            var db=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE="+(mysql?"MySQL":"PostgreSQL"),"sa","");
            String ddl=java.nio.file.Files.readString(java.nio.file.Path.of("../sql/create_bcomp_"+(mysql?"gdb":"new")+".sql"));
            try(var c=db.getConnection()) { org.h2.tools.RunScript.execute(c,new java.io.StringReader(ddl.replace("START TRANSACTION;","BEGIN;"))); }
            var sql=new JdbcTemplate(db);
            sql.update("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'old'),(2,'new')");
            var repo=new ConfigurationRepository(db,30,cn.sunline.tss.compare.metadata.MetadataSqlDialect.forType(type));
            var service=new ConfigurationService(repo,()->{});
            var imported=service.importScript(new MockMultipartFile("file","actual-axon.sql","application/sql",fixture));
            assertThat(imported.configurationCount()).isEqualTo(1);
            assertThat(imported.fieldCount()).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT BCOMP_SAMPLE_LIMIT FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Integer.class)).isEqualTo(-1);
            assertThat(sql.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Integer.class)).isEqualTo(64);
        }
    }

    @Test void importsNamedProfileContractFixtureWithCompositePrimaryKeys() throws Exception {
        byte[] script;
        try(var input=new ClassPathResource("axon-named-max-partition-config.sql").getInputStream()) {
            script=input.readAllBytes();
        }
        var file=new MockMultipartFile("file","axon-named-max-partition-config.sql","application/sql",script);
        mvc(()->{}).perform(multipart("/api/configurations/import").file(file))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configurationCount").value(1))
            .andExpect(jsonPath("$.fieldCount").value(3)).andExpect(jsonPath("$.tableSqlCount").value(1));
        assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Integer.class)).isEqualTo(256);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=1 AND FIELD_INDEX_FLAG='1'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=1",String.class))
            .isEqualTo("(select TENANT_ID,ACCOUNT_ID,BALANCE from account_detail) orig");
    }

    @Test void preservesImportedMetadataTextAsBoundValuesForPlainAndHexLiterals() throws Exception {
        String memo="账户\\n明细\nO'Brien";
        String owner="开发\\A'乙";
        String fieldMemo="字段\\\\路径\r\n'引号'";
        var mvc=mvc(()->{});
        for(boolean hex:new boolean[]{false,true}) {
            java.util.function.Function<String,String> literal=hex?this::utf8SqlExpression:
                value->"'"+value.replace("'","''")+"'";
            String script=maxPartitionScript("100")
                .replace("'配置''说明'",literal.apply(memo))
                .replace("'Excel负责人'",literal.apply(owner))
                .replace("'主键'",literal.apply(fieldMemo));
            mvc.perform(multipart("/api/configurations/import").file(scriptFile(script)))
                .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT BCOMP_MEMO FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(memo);
            assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(owner);
            assertThat(jdbc.queryForObject("SELECT FIELD_MEMO FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=1 AND FIELD_NAME='ID'",String.class)).isEqualTo(fieldMemo);
            var commands=new java.util.ArrayList<AxonConfigScriptParser.Command>();
            new AxonConfigScriptParser().parse(new java.io.StringReader(script),commands::add);
            var conf=commands.stream().filter(command->command.value().startsWith("INSERT INTO tss_bcomp_conf_new")).findFirst().orElseThrow();
            var fields=commands.stream().filter(command->command.value().startsWith("INSERT INTO tss_bcomp_field_new")).findFirst().orElseThrow();
            // Binding rather than raw SQL literals preserves backslashes on MySQL and avoids
            // executing the MySQL-only CONVERT expression on a GaussDB or H2 metadata database.
            assertThat(conf.parameters()).contains(memo,owner);
            assertThat(fields.parameters()).contains(fieldMemo);
            assertThat(conf.value()).doesNotContain("CONVERT(").doesNotContain("账户\\n");
            assertThat(fields.value()).doesNotContain("CONVERT(").doesNotContain("字段\\\\");
        }
    }

    @Test void importsTheActualNamedAxonSafeTextExportContract() throws Exception {
        var mvc=mvc(()->{});
        for(int id:new int[]{1,2}) {
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON)
                    .content(withLimit(valid(id,id==1?"acct_master":"loan_detail"),id==1?"128":"32")))
                .andExpect(status().isCreated());
        }
        byte[] script;
        try(var input=new ClassPathResource("axon-named-safe-text-config.sql").getInputStream()) {
            script=input.readAllBytes();
        }
        mvc.perform(multipart("/api/configurations/import").file(new MockMultipartFile("file","actual-axon.sql","application/sql",script)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configurationCount").value(2))
            .andExpect(jsonPath("$.fieldCount").value(4)).andExpect(jsonPath("$.tableSqlCount").value(2));
        assertThat(jdbc.queryForList("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW ORDER BY BCOMP_INDEX",Integer.class)).containsExactly(100,256);
        assertThat(jdbc.queryForObject("SELECT BCOMP_MEMO FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("账户\\明细'注比对");
        assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("A\\nB");
        assertThat(jdbc.queryForObject("SELECT BCOMP_MEMO FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=2",String.class)).isEqualTo("贷款\n明细比对");
        assertThat(jdbc.queryForObject("SELECT FIELD_MEMO FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=1 AND FIELD_NAME='id'",String.class)).isEqualTo("编号\\代码'注");
        assertThat(jdbc.queryForObject("SELECT FIELD_MEMO FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=2 AND FIELD_NAME='balance'",String.class)).isEqualTo("金额\n现值");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW WHERE FIELD_INDEX_FLAG='1'",Integer.class)).isEqualTo(2);
    }

    @Test void validatesDecodedHashStrategyButKeepsNumericColumnsExpressionFree() throws Exception {
        var mvc=mvc(()->{});
        mvc.perform(multipart("/api/configurations/import").file(scriptFile(maxPartitionScript("100")
                .replace("'HASH'",utf8SqlExpression("HASH")))))
            .andExpect(status().isOk());
        for(String strategy:new String[]{"RANGE"," HASH","HASH "}) {
            mvc.perform(multipart("/api/configurations/import").file(scriptFile(maxPartitionScript("100")
                    .replace("'HASH'",utf8SqlExpression(strategy)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        }
        mvc.perform(multipart("/api/configurations/import").file(scriptFile(maxPartitionScript(utf8SqlExpression("64")))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Integer.class)).isEqualTo(100);
    }

    @Test void mixedConfLayoutsRestoreOnlyRowsWithoutAnExplicitMaximum() throws Exception {
        var mvc=mvc(()->{});
        for(int index:new int[]{1,2}) {
            mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON)
                    .content(withLimit(valid(index,index==1?"explicit-existing":"legacy-existing"),index==1?"128":"32")))
                .andExpect(status().isCreated());
        }
        String script="""
            START TRANSACTION;
            TRUNCATE TABLE TSS_BCOMP_FIELD_NEW;
            TRUNCATE TABLE TSS_BCOMP_TABLE_SQL_NEW;
            TRUNCATE TABLE TSS_BCOMP_CONF_NEW;
            INSERT INTO TSS_BCOMP_CONF_NEW
              (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username,bcomp_max_partition_num)
            VALUES (1,'dept','explicit-existing','memo','2','1','3','1',1,'HASH',100,'Excel负责人',NULL,100),
                   (4,'dept','explicit-new','memo','2','1','3','1',1,'HASH',100,NULL,NULL,256);
            INSERT INTO TSS_BCOMP_CONF_NEW
              (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username)
            VALUES (2,'dept','legacy-existing','memo','2','1','3','1',1,'HASH',100,NULL,NULL);
            INSERT INTO TSS_BCOMP_CONF_NEW
              (bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state)
            VALUES (3,'dept','legacy-new','memo','2','1','3','1');
            INSERT INTO TSS_BCOMP_FIELD_NEW
              (bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag)
            VALUES (1,'ID','1','key',1,1,'1','0'),(2,'ID','1','key',1,1,'1','0'),
                   (3,'ID','1','key',1,1,'1','0'),(4,'ID','1','key',1,1,'1','0');
            INSERT INTO TSS_BCOMP_TABLE_SQL_NEW
              (bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id)
            VALUES (1,'(select ID from T) orig',1,'(select ID from T) dest',2),
                   (2,'(select ID from T) orig',1,'(select ID from T) dest',2),
                   (3,'(select ID from T) orig',1,'(select ID from T) dest',2),
                   (4,'(select ID from T) orig',1,'(select ID from T) dest',2);
            COMMIT;
            """;
        mvc.perform(multipart("/api/configurations/import").file(scriptFile(script)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configurationCount").value(4));
        assertThat(jdbc.queryForList("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW ORDER BY BCOMP_INDEX",Integer.class))
            .containsExactly(100,32,64,256);
    }

    @Test void invalidExplicitMaximumAndExtraSqlRollBackTheWholeReplacement() throws Exception {
        mvc(()->{}).perform(post("/api/configurations").contentType(APPLICATION_JSON)
                .content(withLimit(valid(22,"保留配置"),"128")))
            .andExpect(status().isCreated());
        var invalidScripts=new java.util.ArrayList<String>();
        for(String limit:new String[]{"0","-1","257","1.5","NULL","'64'","999999999999999999999","CURRENT_USER"}) {
            invalidScripts.add(maxPartitionScript(limit));
        }
        invalidScripts.add(maxPartitionScript("100").replace("bcomp_max_partition_num)","bcomp_max_partition_num,bcomp_max_partition_num)"));
        invalidScripts.add(maxPartitionScript("100").replace("bcomp_max_partition_num)","unexpected_maximum)"));
        invalidScripts.add(maxPartitionScript("100").replace("COMMIT;","UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=256;\nCOMMIT;"));
        AtomicInteger reloads=new AtomicInteger();
        var mvc=mvc(reloads::incrementAndGet);
        for(String script:invalidScripts) {
            mvc.perform(multipart("/api/configurations/import").file(scriptFile(script)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
            assertThat(jdbc.queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=22",String.class)).isEqualTo("保留配置");
            assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=22",Integer.class)).isEqualTo(128);
            assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=22",String.class)).isEqualTo("OLD_SQL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
        }
        assertThat(reloads).hasValue(0);
    }

    @Test void failedReplacingImportRollsBackRowsAndTheirPartitionLimits() throws Exception {
        var mvc=mvc(()->{});
        for(int index:new int[]{1,22}) mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON)
                .content(withLimit(valid(index,"original"+index),index==1?"128":"32")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.maxPartitionNum").value(index==1?128:32));
        var reloads=new AtomicInteger();
        mvc(reloads::incrementAndGet).perform(multipart("/api/configurations/import")
                .file(scriptFile(axonScript("replacement").replace(" orig',1,"," orig',999,"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        assertThat(reloads).hasValue(0);
        mvc.perform(get("/api/configurations/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.basic.name").value("original1")).andExpect(jsonPath("$.basic.maxPartitionNum").value(128));
        mvc.perform(get("/api/configurations/22")).andExpect(status().isOk())
            .andExpect(jsonPath("$.basic.maxPartitionNum").value(32));
        assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("OLD_SQL");
    }

    String withLimit(String body,String token) {
        return body.replace("\"partitionNum\":16", "\"partitionNum\":16,\"maxPartitionNum\":"+token);
    }

    @Test void nameModeReportsOptionsAndAcceptsLegacyPositions() throws Exception {
        var mvc=mvcByName();
        mvc.perform(get("/api/configuration-options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.fieldMatchByName").value(true));
        String fields=valid(22,"名称映射").replace("\"oldIndex\":1", "\"oldIndex\":37")
            .replace("]}",",{\"name\":\"AMOUNT\",\"type\":\"2\",\"oldIndex\":37,\"newIndex\":0,\"indexFlag\":\"0\"}]}");
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(fields))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT FIELD_OLD_INDEX FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=22 AND FIELD_NAME='ID'",Long.class)).isEqualTo(37);
        jdbc.update("UPDATE TSS_BCOMP_FIELD_NEW SET FIELD_OLD_INDEX=NULL,FIELD_NEW_INDEX=NULL WHERE BCOMP_INDEX=22");
        mvc.perform(get("/api/configurations/22")).andExpect(status().isOk());
        mvc(() -> {}).perform(get("/api/configuration-options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.fieldMatchByName").value(false));
    }

    @Test void nameModeRejectsCaseInsensitiveDuplicateFieldNames() throws Exception {
        String fields=valid(22,"重名").replace("]}",",{\"name\":\" id \",\"type\":\"1\",\"oldIndex\":2,\"newIndex\":2,\"indexFlag\":\"0\"}]}");
        mvcByName().perform(post("/api/configurations").contentType(APPLICATION_JSON).content(fields))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_FIELD_NAME"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isZero();
    }

    @Test void positionModeStillRequiresPositivePositions() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON)
                .content(valid(22,"位置映射").replace("\"oldIndex\":1","\"oldIndex\":0")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD"));
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

    String reviserScript(String name, String username) {
        return sampleScript("100")
            .replace("bcomp_sample_limit)", "bcomp_sample_limit,reviser_name,reviser_username)")
            .replace("16,'HASH',100);", "16,'HASH',100,"+name+","+username+");");
    }

    String maxPartitionScript(String maximum) {
        return reviserScript("'Excel负责人'","NULL")
            .replace("reviser_username)","reviser_username,bcomp_max_partition_num)")
            .replace("100,'Excel负责人',NULL);","100,'Excel负责人',NULL,"+maximum+");");
    }

    String scriptWithSqlExpressions(String origin, String destination) {
        return reviserScript("'张三'", "'c-zhangsan'")
            .replace("'(select ID,UPDATED_AT from T where NOTE=''a;b'') orig'", origin)
            .replace("'(select ID,UPDATED_AT from T where NOTE=''a;b'') dest'", destination);
    }

    String utf8SqlExpression(String sql) {
        return "CONVERT(0x"+HexFormat.of().formatHex(sql.getBytes(StandardCharsets.UTF_8))+" USING utf8mb4)";
    }

    @Test void importsAxonUtf8HexSqlWithoutChangingItsContents() throws Exception {
        String originSql="(select ID,UPDATED_AT from T where NOTE='中文😀\\路径\\\\连续;--/*''引号*/?'\n and TAG='源' and MARK='\\n\\t\\r\\0') orig";
        String destinationSql="(select ID,UPDATED_AT from U where NOTE='目标\\\\目录\\文件;/*--''内容*/?'\n and TAG='目标😀') dest";
        AtomicInteger reloads=new AtomicInteger();
        var mvc=mvc(reloads::incrementAndGet);

        mvc.perform(multipart("/api/configurations/import").file(scriptFile(
                scriptWithSqlExpressions(utf8SqlExpression(originSql),utf8SqlExpression(destinationSql)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(true))
            .andExpect(jsonPath("$.configurationCount").value(1))
            .andExpect(jsonPath("$.fieldCount").value(2))
            .andExpect(jsonPath("$.tableSqlCount").value(1));

        assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(originSql);
        assertThat(jdbc.queryForObject("SELECT DEST_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(destinationSql);
        mvc.perform(get("/api/configurations/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.tableSql.origSql").value(originSql))
            .andExpect(jsonPath("$.tableSql.destSql").value(destinationSql));
        assertThat(reloads).hasValue(1);
    }

    @Test void importsMixedLiteralAndHexSqlRowsInTheirOriginalParameterOrder() throws Exception {
        String[] originSql={"(select ID,UPDATED_AT from T where NOTE='普通?\\路径😀') orig","(select ID from U where NOTE='编码?\\\\路径') orig"};
        String[] destinationSql={"(select ID,UPDATED_AT from V where NOTE='编码目标?\\路径') dest","(select ID from W where NOTE='普通目标?\\\\路径😀') dest"};
        String firstOrigin="'"+originSql[0].replace("'","''")+"'";
        String firstDestination=utf8SqlExpression(destinationSql[0]);
        String secondOrigin=utf8SqlExpression(originSql[1]);
        String secondDestination="'"+destinationSql[1].replace("'","''")+"'";
        String script=axonScript("MIXED")
            .replace("(1,'dept','MIXED','配置''说明','2','1','3','1');",
                "(1,'dept','MIXED','配置''说明','2','1','3','1'),(2,'dept','SECOND','第二配置','2','1','3','1');")
            .replace("(1,'ID','1','主键', '1','1','1','0'),",
                "(1,'ID','1','主键', '1','1','1','0'),(2,'ID','1','第二主键','1','1','1','0'),")
            .replace("'(select ID,UPDATED_AT from T where NOTE=''a;b'') orig'",firstOrigin)
            .replace("'(select ID,UPDATED_AT from T where NOTE=''a;b'') dest'",firstDestination)
            .replace(firstDestination+",2);",firstDestination+",2),\n(2,"+secondOrigin+",1,"+secondDestination+",2);");
        AtomicInteger reloads=new AtomicInteger();
        var mvc=mvc(reloads::incrementAndGet);
        mvc.perform(multipart("/api/configurations/import").file(scriptFile(script)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationCount").value(2))
            .andExpect(jsonPath("$.tableSqlCount").value(2));
        for(int i=0;i<2;i++) {
            assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=?",String.class,i+1)).isEqualTo(originSql[i]);
            assertThat(jdbc.queryForObject("SELECT DEST_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=?",String.class,i+1)).isEqualTo(destinationSql[i]);
            mvc.perform(get("/api/configurations/"+(i+1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tableSql.origSql").value(originSql[i]))
                .andExpect(jsonPath("$.tableSql.destSql").value(destinationSql[i]));
        }
        assertThat(reloads).hasValue(1);
    }

    @Test void rejectsMalformedTextHexAndExpressionsInNumericColumnsWithoutReplacingConfiguration() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"保留配置")))
            .andExpect(status().isCreated());
        String validExpression=utf8SqlExpression("(select ID,UPDATED_AT from T where NOTE='中文\\路径') orig");
        String[] invalidExpressions={
            "CONVERT(0x0 USING utf8mb4)",
            "CONVERT(0x USING utf8mb4)",
            "CONVERT(0xGG USING utf8mb4)",
            "CONVERT(0xC328 USING utf8mb4)",
            "CONVERT(0xC080 USING utf8mb4)",
            "CONVERT(0x410042 USING utf8mb4)",
            "CONVERT(0x41 USING utf8)",
            "CONVERT('SQL' USING utf8mb4)",
            "CONVERT(0x41 USING utf8mb4) + 1",
            "CURRENT_TIMESTAMP",
            "CONCAT('a','b')"
        };
        var invalidScripts=new java.util.ArrayList<String>();
        for(String expression:invalidExpressions) {
            invalidScripts.add(scriptWithSqlExpressions(expression,validExpression));
            invalidScripts.add(scriptWithSqlExpressions(validExpression,expression));
            invalidScripts.add(maxPartitionScript("100").replace("'配置''说明'",expression));
            invalidScripts.add(maxPartitionScript("100").replace("'Excel负责人'",expression));
            invalidScripts.add(maxPartitionScript("100").replace("'主键'",expression));
        }
        String validScript=scriptWithSqlExpressions(validExpression,validExpression);
        invalidScripts.add(validScript.replace("16,'HASH',100",validExpression+",'HASH',100"));
        invalidScripts.add(validScript.replace("16,'HASH',100","16,'HASH',"+validExpression));
        invalidScripts.add(validScript.replace(validExpression+",1,",validExpression+","+validExpression+","));
        invalidScripts.add(validScript.replace("(1,'dept'","("+validExpression+",'dept'"));
        invalidScripts.add(validScript.replace("'主键', '1','1'","'主键', "+validExpression+",'1'"));
        AtomicInteger reloads=new AtomicInteger();
        var mvc=mvc(reloads::incrementAndGet);
        for(String script:invalidScripts) {
            mvc.perform(multipart("/api/configurations/import").file(scriptFile(script)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
            assertThat(jdbc.queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=22",String.class)).isEqualTo("保留配置");
            assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=22",String.class)).isEqualTo("OLD_SQL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=22",Integer.class)).isEqualTo(1);
        }
        assertThat(reloads).hasValue(0);
    }

    @Test void importsReviserMetadataAndPreservesItWhenEditingConfiguration() throws Exception {
        mvc(() -> {}).perform(multipart("/api/configurations/import")
                .file(scriptFile(reviserScript("'张三 O''Brien'", "'c-zhangsan'"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationCount").value(1));
        assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("张三 O'Brien");
        assertThat(jdbc.queryForObject("SELECT REVISER_USERNAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("c-zhangsan");
        mvc(() -> {}).perform(put("/api/configurations/1").contentType(APPLICATION_JSON).content(valid(1,"HASH_TABLE")))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("张三 O'Brien");
        assertThat(jdbc.queryForObject("SELECT REVISER_USERNAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("c-zhangsan");
    }

    @Test void importsNullableRevisersAndLegacyConfigurationLayouts() throws Exception {
        String[] scripts = {
            reviserScript("NULL", "null"),
            reviserScript("'NULL'", "NULL"),
            reviserScript("NULL", "'c-zhangsan'"),
            axonScript("HASH_TABLE"), hashScript("16", "'HASH'"), sampleScript("100")
        };
        String[] names = {null, "NULL", null, null, null, null};
        String[] usernames = {null, null, "c-zhangsan", null, null, null};
        for (int i=0; i<scripts.length; i++) {
            mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(scripts[i])))
                .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT REVISER_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(names[i]);
            assertThat(jdbc.queryForObject("SELECT REVISER_USERNAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(usernames[i]);
        }
    }

    @Test void invalidReviserScriptsRollBackAndKeepNumericAndHashValidation() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"保留配置")))
            .andExpect(status().isCreated());
        String validScript = reviserScript("NULL", "NULL");
        String[] invalidScripts = {
            reviserScript("CURRENT_USER", "NULL"),
            reviserScript("NULL", "COALESCE(NULL,'user')"),
            reviserScript("NULL", "123"),
            validScript.replace("16,'HASH',100,", "16,'HASH',NULL,"),
            validScript.replace("'配置''说明'", "NULL"),
            validScript.replace("16,'HASH',100,", "16,'RANGE',100,"),
            validScript.replace("16,'HASH',100,", "257,'HASH',100,")
        };
        AtomicInteger reloads = new AtomicInteger();
        for (String script : invalidScripts) {
            mvc(reloads::incrementAndGet).perform(multipart("/api/configurations/import").file(scriptFile(script)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG_SCRIPT"));
        }
        assertThat(reloads).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=22",String.class)).isEqualTo("保留配置");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
    }

    @Test void reviserScriptOnUnmigratedSchemaRollsBackExistingConfiguration() throws Exception {
        jdbc.execute("ALTER TABLE TSS_BCOMP_CONF_NEW DROP COLUMN REVISER_NAME");
        jdbc.execute("ALTER TABLE TSS_BCOMP_CONF_NEW DROP COLUMN REVISER_USERNAME");
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(sampleScript("100"))))
            .andExpect(status().isOk());
        mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(reviserScript("'张三'", "'c-zhangsan'"))))
            .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo("HASH_TABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
    }

    @Test void importsSampleLimitsAndKeepsLegacyFallbackNullable() throws Exception {
        for (int limit : new int[]{-1,0,37,1000,10000}) {
            mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(sampleScript(""+limit))))
                .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select BCOMP_SAMPLE_LIMIT from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",Integer.class)).isEqualTo(limit);
        }
        for (String legacy : new String[]{axonScript("HASH_TABLE"),hashScript("16","'HASH'")}) {
            mvc(() -> {}).perform(multipart("/api/configurations/import").file(scriptFile(legacy))).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select BCOMP_SAMPLE_LIMIT from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1",Integer.class)).isNull();
        }
    }

    @Test void invalidSampleLimitsRollBackReplacementAndDoNotRefresh() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"保留配置")))
            .andExpect(status().isCreated());
        AtomicInteger reloads=new AtomicInteger();
        for (String limit : new String[]{"-2","10001","1.5","NULL","'1000'","1e3","999999999999999999999"}) {
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
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON)
            .content(valid(32,"automatic").replace("\"partitionNum\":16", "\"partitionNum\":257")))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT BCOMP_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=32",Integer.class)).isEqualTo(1);
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
        assertThat(jdbc.queryForObject("select BCOMP_MODULE from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=1", String.class)).isEqualTo("dept");
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

    @Test void importDeletesExpiredResultsAndReturnsCleanupWhileKeepingTaskHistory() throws Exception {
        String oldTime=java.time.LocalDateTime.now().minusDays(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE,END_DATETIME) VALUES(22,'20240101','old','3','3',?)",oldTime);
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT) VALUES(22,'20240101','old-run','20240101','old','3',1,1,0,0,0,1,100,0,0,1,?)",oldTime);
        jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,RESULT_DATE,LINE_RESULT,SAMPLE_NO,DETAIL_JSON) VALUES(22,'20240101','old','20240101','4',1,'{}')");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT) VALUES(22,'20240101','old-run','pattern','[\"F\"]',1,1)");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(22,'20240101','old-run','F',1)");

        mvc(()->{}).perform(multipart("/api/configurations/import").file(scriptFile(axonScript("NEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cleanup.retentionDays").value(7))
            .andExpect(jsonPath("$.cleanup.deletedResults").value(1))
            .andExpect(jsonPath("$.cleanup.deletedDetails").value(1))
            .andExpect(jsonPath("$.cleanup.deletedFieldPatterns").value(1))
            .andExpect(jsonPath("$.cleanup.deletedFieldCounts").value(1));
        for(String table:java.util.List.of("TSS_BCOMP_RESULT_NEW","TSS_BCOMP_DTL_NEW","TSS_BCOMP_FIELD_DIFF_NEW","TSS_BCOMP_FIELD_COUNT_NEW"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW",Long.class)).isEqualTo(1);
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
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_SAMPLE_LIMIT=-1 WHERE BCOMP_INDEX=22");
        mvc.perform(post("/api/configurations/22/copy").contentType(APPLICATION_JSON).content("{\"newIndex\":23,\"newName\":\"副本\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.bcompIndex").value(23));
        assertThat(jdbc.queryForObject("SELECT BCOMP_SAMPLE_LIMIT FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=23",Integer.class)).isEqualTo(-1);
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_TASK_NEW where BCOMP_INDEX=23",Integer.class)).isZero();
        mvc.perform(get("/api/configuration-options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.states[0]").value("1"));
    }
}
