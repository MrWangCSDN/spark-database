package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigurationIdentityImportTest {
    JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setUp() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID) VALUES(1),(2)");
        mvc=MockMvcBuilders.standaloneSetup(new ConfigurationController(new ConfigurationService(new ConfigurationRepository(source,30),()->{})))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void importsSeventeenColumnsAndExposesPhysicalSourceWithoutChangingTheCode() throws Exception {
        upload(script(true,new Item(1,"a","配置甲","a=1"),new Item(2,"a_1","配置乙","b=2")),200);
        mvc.perform(get("/api/configurations/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.basic.name").value("a"))
            .andExpect(jsonPath("$.basic.sourceTableName").value("a"))
            .andExpect(jsonPath("$.basic.configurationName").value("配置甲"))
            .andExpect(jsonPath("$.basic.configurationScope").value("a=1"));
        mvc.perform(get("/api/configurations?keyword=配置乙")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("a_1"));
    }

    @Test void bindsRemappedBigintIndexesAsNumbersForPostgresInsteadOfVarcharParameters() {
        var commands=new java.util.ArrayList<AxonConfigScriptParser.Command>();
        new AxonConfigScriptParser().parse(new java.io.StringReader(script(true,new Item(1,"a","甲","a=1"))),commands::add,(index,code)->7L);
        var inserts=commands.stream().filter(command->command.kind()==AxonConfigScriptParser.Kind.INSERT).toList();
        assertThat(inserts).hasSize(3);
        for(var command:inserts) assertThat(command.parameters().get(0)).isEqualTo(7L);
    }

    @Test void reordersAllThreeTablesByStableCodeAndRestoresLegacyCapsByCode() throws Exception {
        upload(script(true,new Item(1,"a","甲","a=1"),new Item(2,"a_1","乙","b=2")),200);
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=128 WHERE BCOMP_NAME='a'");
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=32 WHERE BCOMP_NAME='a_1'");
        upload(script(false,new Item(1,"a_1","乙","b=2"),new Item(2,"a","甲","a=1")),200);
        assertThat(index("a")).isEqualTo(1);
        assertThat(index("a_1")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=1",Integer.class)).isEqualTo(128);
        assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=2",Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT ORIG_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=1",String.class)).contains("a=1");
        assertThat(jdbc.queryForObject("SELECT FIELD_MEMO FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=2",String.class)).isEqualTo("a_1字段");
        assertThat(jdbc.queryForObject("SELECT CONFIGURATION_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=2",String.class)).isEqualTo("乙");
    }

    @Test void deletedCodesAndOrphanHistoryNumbersAreNeverAssignedToANewCode() throws Exception {
        upload(script(true,new Item(1,"a","甲","a=1")),200);
        jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,TASK_STATE) VALUES(40,'20261126','orphan','3')");
        upload(script(true,new Item(1,"a_1","乙","b=2")),200);
        assertThat(index("a_1")).isEqualTo(41);
        upload(script(true,new Item(1,"a","甲","a=1"),new Item(40,"a_2","丙","c=3")),200);
        assertThat(index("a")).isEqualTo(1);
        assertThat(index("a_2")).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=40",Integer.class)).isEqualTo(1);
    }

    @Test void bootstrapRetainsAnExistingCodeIndexAndItsLocalCap() throws Exception {
        jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE,BCOMP_MAX_PARTITION_NUM) VALUES(7,'a','2',128)");
        upload(script(false,new Item(1,"a","甲","a=1")),200);
        assertThat(index("a")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(128);
        assertThat(jdbc.queryForObject("SELECT BCOMP_INDEX FROM TSS_BCOMP_TABLE_SQL_NEW",Long.class)).isEqualTo(7);
    }

    @Test void bootstrapRejectsConflictingHistoricalNamesWithoutReplacingAnything() throws Exception {
        jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) VALUES(7,'a','2')");
        jdbc.update("INSERT INTO TSS_BCOMP_BATCH_NEW(BATCH_ID,REQUEST_ID,REQUEST_HASH,TASK_BUSI_DATE,CREATED_AT,TOTAL_COUNT) VALUES('old','req','hash','20261126','20261126120000',1)");
        jdbc.update("INSERT INTO TSS_BCOMP_BATCH_ITEM_NEW(BATCH_ID,BCOMP_INDEX,CONFIGURATION_NAME,TIME_NODE,CONFIGURATION_HASH) VALUES('old',7,'b','D','hash')");
        mvc.perform(multipart("/api/configurations/import").file(file(script(true,new Item(1,"c","丙","c=3")))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_IDENTITY_AMBIGUOUS"));
        assertThat(index("a")).isEqualTo(7);
    }

    @Test void duplicateNormalizedCodesAndUnknownReferencesRollBackTheWholeImport() throws Exception {
        upload(script(true,new Item(1,"a","甲","a=1")),200);
        upload(script(true,new Item(1,"a","甲","a=1"),new Item(2," A ","乙","b=2")),400);
        upload(script(true,new Item(2,"b","乙","b=2")).replace("(2,'ID'","(99,'ID'"),400);
        assertThat(index("a")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Integer.class)).isEqualTo(1);
    }

    @Test void rejectsMissingMetadataAndUnrecognizedSeventeenColumnLayouts() throws Exception {
        String script=script(true,new Item(1,"a","甲","a=1"));
        upload(script.replace("'a','甲','a=1'","'','甲','a=1'"),400);
        upload(script.replace("source_table_name,configuration_name,configuration_scope","configuration_name,source_table_name,configuration_scope"),400);
    }

    @Test void editingDisplayMetadataKeepsTheCodeAndCopiesPreserveThePhysicalSource() throws Exception {
        upload(script(true,new Item(1,"a","甲","a=1")),200);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(mvc.perform(get("/api/configurations/1")).andReturn().getResponse().getContentAsString());
        var basic=(com.fasterxml.jackson.databind.node.ObjectNode)body.get("basic");
        basic.put("configurationName","更名甲");basic.put("configurationScope","a=3");
        mvc.perform(put("/api/configurations/1").contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configuration.basic.configurationName").value("更名甲"));
        basic.put("name","a_2");
        mvc.perform(put("/api/configurations/1").contentType("application/json").content(body.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_CODE_IMMUTABLE"));
        mvc.perform(post("/api/configurations/1/copy").contentType("application/json").content("{\"newIndex\":5,\"newName\":\"a_2\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.sourceTableName").value("a"))
            .andExpect(jsonPath("$.configuration.basic.configurationName").value("更名甲"));
    }

    @Test void manualCopyCannotReuseAnIndexReservedByADeletedCode() throws Exception {
        upload(script(true,new Item(1,"a","甲","a=1")),200);
        upload(script(true,new Item(1,"b","乙","b=2")),200);
        mvc.perform(post("/api/configurations/2/copy").contentType("application/json").content("{\"newIndex\":1,\"newName\":\"c\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_ID_CONFLICT"));
        assertThat(index("b")).isEqualTo(2);
    }

    @Test void changingPhysicalSourceThroughPutIsRejectedAndOmittedLegacySourceIsPreserved() throws Exception {
        upload(script(true,new Item(1,"a_1","乙","b=2")),200);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(mvc.perform(get("/api/configurations/1")).andReturn().getResponse().getContentAsString());
        var basic=(com.fasterxml.jackson.databind.node.ObjectNode)body.get("basic");basic.put("sourceTableName","b");
        mvc.perform(put("/api/configurations/1").contentType("application/json").content(body.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_SOURCE_IMMUTABLE"));
        basic.remove("sourceTableName");basic.put("configurationName","更名乙");
        mvc.perform(put("/api/configurations/1").contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configuration.basic.sourceTableName").value("a"))
            .andExpect(jsonPath("$.configuration.basic.configurationName").value("更名乙"));
    }

    private long index(String code) { return jdbc.queryForObject("SELECT BCOMP_INDEX FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_NAME=?",Long.class,code); }
    private void upload(String sql,int expected) throws Exception { mvc.perform(multipart("/api/configurations/import").file(file(sql))).andExpect(status().is(expected)); }
    private MockMultipartFile file(String sql) { return new MockMultipartFile("file","multi.sql","application/sql",sql.getBytes(StandardCharsets.UTF_8)); }
    record Item(long id,String code,String display,String scope) {}
    static String script(boolean metadata,Item... items) {
        String columns="bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit,reviser_name,reviser_username";
        if(metadata) columns+=",bcomp_max_partition_num,source_table_name,configuration_name,configuration_scope";
        String conf=Arrays.stream(items).map(item->"("+item.id()+",'dept',"+literal(item.code())+",'memo','2','1','D','1',1,'HASH',100,NULL,NULL"
            +(metadata?",64,'a',"+literal(item.display())+","+literal(item.scope()):"")+")").collect(Collectors.joining(",\n"));
        String fields=Arrays.stream(items).map(item->"("+item.id()+",'ID','1',"+literal(item.code()+"字段")+",1,1,'1','0')").collect(Collectors.joining(",\n"));
        String sqls=Arrays.stream(items).map(item->"("+item.id()+","+literal("(select ID from a where "+item.scope()+") orig")+",1,"+literal("(select ID from a where "+item.scope()+") dest")+",2)").collect(Collectors.joining(",\n"));
        return "START TRANSACTION;\nTRUNCATE TABLE tss_bcomp_field_new;\nTRUNCATE TABLE tss_bcomp_table_sql_new;\nTRUNCATE TABLE tss_bcomp_conf_new;\n"
            +"INSERT INTO tss_bcomp_conf_new("+columns+") VALUES "+conf+";\n"
            +"INSERT INTO tss_bcomp_field_new(bcomp_index,field_name,field_type,field_memo,field_old_index,field_new_index,field_index_flag,field_enum_flag) VALUES "+fields+";\n"
            +"INSERT INTO tss_bcomp_table_sql_new(bcomp_index,orig_sql,orig_database_id,dest_sql,dest_database_id) VALUES "+sqls+";\nCOMMIT;\n";
    }
    private static String literal(String value) { return "'"+value.replace("'","''")+"'"; }
}
