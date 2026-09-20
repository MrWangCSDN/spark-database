package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

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
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION) values(1,'old','jdbc:o','u','p','D','0'),(2,'new','jdbc:n','u','p','D','0')");
    }

    MockMvc mvc(CacheReloader reloader){
        return MockMvcBuilders.standaloneSetup(new ConfigurationController(new ConfigurationService(repository,reloader)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    String valid(long index,String name){return """
        {"basic":{"bcompIndex":%d,"name":"%s","module":"ACCOUNT","memo":"日终","range":"ALL","timeNode":"EOD","state":"1","partitionNum":16,"dateMode":"2"},
         "tableSql":{"origDatabaseId":1,"destDatabaseId":2,"origSql":"OLD_SQL","destSql":"NEW_SQL"},
         "fields":[{"name":"ID","type":"1","memo":"主键","oldIndex":1,"newIndex":1,"indexFlag":"1","enumFlag":"0","enumIndex":null,"partitionFlag":"1","ignoreValue":null,"ignoreFlag":"0"}]}
        """.formatted(index,name);}

    @Test void createsAggregateAndRefreshesCache() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"客户余额")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(true));
        assertThat(jdbc.queryForObject("select BCOMP_TYPE from TSS_BCOMP_CONF where BCOMP_INDEX=22",String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_FIELD where BCOMP_INDEX=22",Integer.class)).isEqualTo(1);
    }

    @Test void invalidSecondFieldDoesNotMutateExistingAggregate() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"before"))).andExpect(status().isCreated());
        String invalid=valid(22,"after").replace("]}",",{\"name\":\"DUP\",\"type\":\"1\",\"oldIndex\":1,\"newIndex\":2,\"indexFlag\":\"0\",\"enumFlag\":\"0\",\"partitionFlag\":\"0\",\"ignoreFlag\":\"0\"}]}");
        mvc(() -> {}).perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(invalid))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_FIELD_POSITION"));
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF where BCOMP_INDEX=22",String.class)).isEqualTo("before");
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_FIELD where BCOMP_INDEX=22",Integer.class)).isEqualTo(1);
    }

    @Test void runningTaskBlocksConfigurationUpdate() throws Exception {
        mvc(() -> {}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"before"))).andExpect(status().isCreated());
        jdbc.update("insert into TSS_BCOMP_TASK(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(22,'20260920','1','EOD','2')");
        mvc(() -> {}).perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(valid(22,"after")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_IN_USE"));
    }

    @Test void cacheFailureReportsSavedWarning() throws Exception {
        mvc(() -> {throw new IllegalStateException("cache");}).perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"saved")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.cacheRefreshed").value(false))
            .andExpect(jsonPath("$.warning").value("配置已保存，但缓存刷新失败"));
        assertThat(jdbc.queryForObject("select BCOMP_NAME from TSS_BCOMP_CONF where BCOMP_INDEX=22",String.class)).isEqualTo("saved");
    }

    @Test void copiesConfigurationWithoutTasksAndReturnsOptions() throws Exception {
        var mvc=mvc(() -> {});
        mvc.perform(post("/api/configurations").contentType(APPLICATION_JSON).content(valid(22,"source"))).andExpect(status().isCreated());
        mvc.perform(post("/api/configurations/22/copy").contentType(APPLICATION_JSON).content("{\"newIndex\":23,\"newName\":\"副本\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.configuration.basic.bcompIndex").value(23));
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_TASK where BCOMP_INDEX=23",Integer.class)).isZero();
        mvc.perform(get("/api/configuration-options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.states[0]").value("1"));
    }
}
