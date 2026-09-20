package cn.sunline.tss.compare.database;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatabaseManagementApiTest {
    JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        var service = new DatabaseManagementService(new DatabaseManagementRepository(source, 30));
        mvc = MockMvcBuilders.standaloneSetup(new DatabaseController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void createsEncryptedPasswordAndNeverReturnsPasswordMaterial() throws Exception {
        String request = """
            {"databaseId":7,"databaseName":"GaussDB-A","url":"jdbc:gaussdb://db/core","user":"compare","driver":"com.huawei.Driver","encryption":"1","newPassword":"plain-password"}
            """;
        String response = mvc.perform(post("/api/databases").contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordConfigured").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("plain-password").doesNotContain("generater");
        var row = jdbc.queryForMap("select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG where DATABASE_ID=7");
        assertThat(row.get("DATABASE_PASSWORD")).isNotEqualTo("plain-password");
        assertThat(PasswordEncryption.decrypt(row.get("DATABASE_PASSWORD").toString(), row.get("DATABASE_GENERATERKEY").toString())).isEqualTo("plain-password");
    }

    @Test void blankPasswordUpdateKeepsExistingCipherAndKey() throws Exception {
        mvc.perform(post("/api/databases").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"old","url":"jdbc:old","user":"u","driver":"D","encryption":"1","newPassword":"secret"}
            """)).andExpect(status().isCreated());
        var before = jdbc.queryForMap("select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG where DATABASE_ID=7");

        mvc.perform(put("/api/databases/7").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"renamed","url":"jdbc:new","user":"u2","driver":"D2","encryption":"1","newPassword":""}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.databaseName").value("renamed"));

        var after = jdbc.queryForMap("select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG where DATABASE_ID=7");
        assertThat(after).isEqualTo(before);
    }

    @Test void rejectsUpdateWhenWaitingTaskReferencesConnection() throws Exception {
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION) values(7,'db','jdbc:x','u','p','D','0')");
        jdbc.update("insert into TSS_BCOMP_CONF(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) values(22,'c','2')");
        jdbc.update("insert into TSS_BCOMP_TABLE_SQL values(22,7,7,'O','N')");
        jdbc.update("insert into TSS_BCOMP_TASK(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(22,'20260920','1','EOD','1')");

        mvc.perform(put("/api/databases/7").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"db","url":"jdbc:y","user":"u","driver":"D","encryption":"0","newPassword":""}
            """)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATABASE_IN_USE"));
    }

    @Test void listsWithoutPasswordOrKeyProperties() throws Exception {
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG values(7,'db','jdbc:x','u','cipher','D','1','key')");
        String response = mvc.perform(get("/api/databases"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("cipher").doesNotContain("key").doesNotContain("password\"");
    }
}
