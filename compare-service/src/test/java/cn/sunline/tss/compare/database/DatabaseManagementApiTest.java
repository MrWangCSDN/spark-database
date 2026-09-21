package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import cn.sunline.tss.compare.api.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
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
    DatabaseConnectionTester tester;
    AtomicReference<DatabaseConnectionSpec> recorded;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        var repository = new DatabaseManagementRepository(source, 30);
        var service = new DatabaseManagementService(repository);
        recorded = new AtomicReference<>();
        tester = new DatabaseConnectionTester(spec -> {
            recorded.set(spec);
            if ("missing.Driver".equals(spec.driver())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DATABASE_DRIVER_NOT_FOUND", "数据库驱动不可用，请检查驱动类和部署包");
            }
            if ("failing.Driver".equals(spec.driver())) {
                throw new SQLException("password=secret jdbc:private-host");
            }
            if ("slow.Driver".equals(spec.driver())) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
            }
            return new DatabaseConnectionProbe.Metadata("GaussDB", "V500");
        }, Duration.ofMillis(30), 2);
        var connectionTestService = new DatabaseConnectionTestService(repository, tester);
        mvc = MockMvcBuilders.standaloneSetup(new DatabaseController(service, connectionTestService))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach void tearDown() {
        tester.close();
    }

    @Test void createsEncryptedPasswordAndNeverReturnsPasswordMaterial() throws Exception {
        String request = """
            {"databaseId":7,"databaseName":"GaussDB-A","url":"jdbc:gaussdb://db/core?currentSchema=tssreplay","user":"compare","driver":"com.huawei.Driver","encryption":"1","newPassword":"plain-password"}
            """;
        String response = mvc.perform(post("/api/databases").contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordConfigured").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("plain-password").doesNotContain("generater");
        var row = jdbc.queryForMap("select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7");
        assertThat(row.get("DATABASE_PASSWORD")).isNotEqualTo("plain-password");
        assertThat(PasswordEncryption.decrypt(row.get("DATABASE_PASSWORD").toString(), row.get("DATABASE_GENERATERKEY").toString())).isEqualTo("plain-password");
    }

    @Test void rejectsCreatingCompatibleConnectionWithoutCurrentSchema() throws Exception {
        mvc.perform(post("/api/databases").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"GaussDB-A","url":"jdbc:postgresql://db/core","user":"compare","driver":"org.postgresql.Driver","encryption":"1","newPassword":"secret"}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CURRENT_SCHEMA_REQUIRED"));
        assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_DATABASE_CONFIG_NEW", Integer.class)).isZero();
    }

    @Test void rejectsDraftCompatibleConnectionBeforeCallingProbe() throws Exception {
        mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
            {"databaseId":9,"databaseName":"draft","url":"jdbc:postgresql://db/core?ssl=true","user":"reader","driver":"org.postgresql.Driver","encryption":"1","newPassword":"secret"}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CURRENT_SCHEMA_REQUIRED"));
        assertThat(recorded.get()).isNull();
    }

    @Test void rejectsTestingSavedLegacyCompatibleConnectionBeforeCallingProbe() throws Exception {
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG_NEW values(7,'legacy','jdbc:postgresql://db/core','reader','secret','org.postgresql.Driver','0',null)");

        mvc.perform(post("/api/databases/7/test"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CURRENT_SCHEMA_REQUIRED"));
        assertThat(recorded.get()).isNull();
    }

    @Test void blankPasswordUpdateKeepsExistingCipherAndKey() throws Exception {
        mvc.perform(post("/api/databases").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"old","url":"jdbc:old","user":"u","driver":"D","encryption":"1","newPassword":"secret"}
            """)).andExpect(status().isCreated());
        var before = jdbc.queryForMap("select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7");

        mvc.perform(put("/api/databases/7").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"renamed","url":"jdbc:new","user":"u2","driver":"D2","encryption":"1","newPassword":""}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.databaseName").value("renamed"));

        var after = jdbc.queryForMap("select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7");
        assertThat(after).isEqualTo(before);
    }

    @Test void rejectsUpdateWhenWaitingTaskReferencesConnection() throws Exception {
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION) values(7,'db','jdbc:x','u','p','D','0')");
        jdbc.update("insert into TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) values(22,'c','2')");
        jdbc.update("insert into TSS_BCOMP_TABLE_SQL_NEW values(22,7,7,'O','N')");
        jdbc.update("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(22,'20260920','1','EOD','1')");

        mvc.perform(put("/api/databases/7").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"db","url":"jdbc:y","user":"u","driver":"D","encryption":"0","newPassword":""}
            """)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATABASE_IN_USE"));
    }

    @Test void listsWithoutPasswordOrKeyProperties() throws Exception {
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG_NEW values(7,'db','jdbc:x','u','cipher','D','1','key')");
        String response = mvc.perform(get("/api/databases"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("cipher").doesNotContain("key").doesNotContain("password\"");
    }

    @Test void testsDraftWithoutPersistingIt() throws Exception {
        mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
            {"databaseId":9,"databaseName":"draft","url":"jdbc:test","user":"reader","driver":"test.Driver","encryption":"1","newPassword":"draft-secret"}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.databaseProduct").value("GaussDB"))
            .andExpect(jsonPath("$.databaseVersion").value("V500"));

        assertThat(jdbc.queryForObject(
            "select count(*) from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=9",
            Integer.class
        )).isZero();
        assertThat(recorded.get().password()).isEqualTo("draft-secret");
    }

    @Test void savedTestDecryptsPasswordWithoutReturningOrChangingIt() throws Exception {
        createEncryptedConnection();
        var before = jdbc.queryForMap(
            "select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7"
        );

        String response = mvc.perform(post("/api/databases/7/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.databaseProduct").value("GaussDB"))
            .andReturn().getResponse().getContentAsString();

        var after = jdbc.queryForMap(
            "select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7"
        );
        assertThat(recorded.get().password()).isEqualTo("secret");
        assertThat(after).isEqualTo(before);
        assertThat(response).doesNotContain("secret", before.get("DATABASE_PASSWORD").toString());
    }

    @Test void temporarySavedPasswordIsRequestOnly() throws Exception {
        createEncryptedConnection();
        var before = jdbc.queryForMap(
            "select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7"
        );

        mvc.perform(post("/api/databases/7/test").contentType(APPLICATION_JSON)
                .content("{\"newPassword\":\"temporary\"}"))
            .andExpect(status().isOk());

        assertThat(recorded.get().password()).isEqualTo("temporary");
        assertThat(jdbc.queryForMap(
            "select DATABASE_PASSWORD,DATABASE_GENERATERKEY from TSS_BCOMP_DATABASE_CONFIG_NEW where DATABASE_ID=7"
        )).isEqualTo(before);
    }

    @Test void rejectsIncompleteDraftWithoutCallingTheProbe() throws Exception {
        mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
            {"databaseId":9,"databaseName":"draft","url":"","user":"reader","driver":"test.Driver","encryption":"1","newPassword":"draft-secret"}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DATABASE"));
        assertThat(recorded.get()).isNull();
    }

    @Test void rejectsMissingSavedConnection() throws Exception {
        mvc.perform(post("/api/databases/999/test").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DATABASE_NOT_FOUND"));
    }

    @Test void returnsStableDriverFailure() throws Exception {
        mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
            {"databaseId":9,"databaseName":"draft","url":"jdbc:test","user":"reader","driver":"missing.Driver","encryption":"1","newPassword":"draft-secret"}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DATABASE_DRIVER_NOT_FOUND"));
    }

    @Test void returnsStableTimeout() throws Exception {
        mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
            {"databaseId":9,"databaseName":"draft","url":"jdbc:test","user":"reader","driver":"slow.Driver","encryption":"1","newPassword":"draft-secret"}
            """))
            .andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.code").value("DATABASE_CONNECTION_TIMEOUT"));
    }

    @Test void redactsRawConnectionFailure() throws Exception {
        String response = mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
            {"databaseId":9,"databaseName":"draft","url":"jdbc:private-host","user":"reader","driver":"failing.Driver","encryption":"1","newPassword":"secret"}
            """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DATABASE_CONNECTION_FAILED"))
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("secret", "private-host", "jdbc:");
    }

    private void createEncryptedConnection() throws Exception {
        mvc.perform(post("/api/databases").contentType(APPLICATION_JSON).content("""
            {"databaseId":7,"databaseName":"saved","url":"jdbc:test","user":"reader","driver":"test.Driver","encryption":"1","newPassword":"secret"}
            """))
            .andExpect(status().isCreated());
    }
}
