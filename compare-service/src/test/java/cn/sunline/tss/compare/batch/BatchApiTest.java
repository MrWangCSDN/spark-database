package cn.sunline.tss.compare.batch;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import cn.sunline.tss.compare.configuration.CacheReloader;
import cn.sunline.tss.compare.configuration.ConfigurationController;
import cn.sunline.tss.compare.configuration.ConfigurationRepository;
import cn.sunline.tss.compare.configuration.ConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BatchApiTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean cacheFails = new AtomicBoolean();
    private final AtomicReference<String> cancelledBatch = new AtomicReference<>();
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("../sql/create_bcomp_new.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        CacheReloader cache = () -> { if (cacheFails.get()) throw new IllegalStateException("secret-jdbc-message"); };
        cn.sunline.tss.compare.JobLauncher launcher = new cn.sunline.tss.compare.JobLauncher() {
            @Override public void submit(String[] args) { }
            @Override public void cancelBatch(String batchId) { cancelledBatch.set(batchId); }
        };
        var service = new BatchService(new BatchRepository(source, 30), cache, new MockEnvironment(), launcher);
        var configurationService = new ConfigurationService(new ConfigurationRepository(source, 30), cache);
        mvc = MockMvcBuilders.standaloneSetup(new BatchController(service), new ConfigurationController(configurationService))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        jdbc.update("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION) VALUES(1,'源库','jdbc:opengauss://source/db?currentSchema=business','u','secret','org.opengauss.Driver','0'),(2,'目标库','jdbc:opengauss://dest/db?currentSchema=business','u','secret','org.opengauss.Driver','0')");
    }

    @Test
    void allCreatesTwoHundredTasksBeyondUiPageSizeAndReplaysWithoutDuplicates() throws Exception {
        configurations(200);
        var request = request("ALL");
        var preview = preview(request);
        assertThat(preview.path("totalCount").asInt()).isEqualTo(200);
        assertThat(preview.path("readyCount").asInt()).isEqualTo(200);
        var submission = submission(request, preview);
        var batch = create(submission);
        assertThat(batch.path("waitingCount").asInt()).isEqualTo(200);
        assertThat(create(submission).path("batchId").asText()).isEqualTo(batch.path("batchId").asText());
        assertThat(count("TSS_BCOMP_TASK_NEW")).isEqualTo(200);
        assertThat(count("TSS_BCOMP_BATCH_NEW")).isEqualTo(1);
        mvc.perform(get("/api/batches/" + batch.path("batchId").asText() + "/tasks").param("page", "1").param("size", "100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(200)).andExpect(jsonPath("$.items.length()").value(100));
        submission.put("taskBusinessDate", "20260923");
        mvc.perform(post("/api/batches").contentType(APPLICATION_JSON).content(submission.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_REQUEST_CONFLICT"));
    }

    @Test
    void invalidItemBlocksEntireBatchUntilExplicitlyExcluded() throws Exception {
        configurations(2);
        jdbc.update("UPDATE TSS_BCOMP_FIELD_NEW SET FIELD_INDEX_FLAG='0' WHERE BCOMP_INDEX=2");
        var request = request("ALL");
        var preview = preview(request);
        assertThat(preview.path("blockedCount").asInt()).isEqualTo(1);
        assertThat(preview.path("items").get(1).path("issueCode").asText()).isEqualTo("BUSINESS_KEY_REQUIRED");
        mvc.perform(post("/api/batches").contentType(APPLICATION_JSON).content(submission(request, preview).toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_NOT_READY"));
        assertThat(count("TSS_BCOMP_TASK_NEW")).isZero();
        assertThat(count("TSS_BCOMP_BATCH_NEW")).isZero();
        request.putArray("excludedIds").add(2);
        create(submission(request, preview(request)));
        assertThat(count("TSS_BCOMP_TASK_NEW")).isEqualTo(1);
    }

    @Test
    void sqlConnectionAndSelectedSetChangesInvalidatePreview() throws Exception {
        configurations(1);
        var request = request("ALL");
        var original = submission(request, preview(request));
        jdbc.update("UPDATE TSS_BCOMP_TABLE_SQL_NEW SET ORIG_SQL='SELECT id FROM changed' WHERE BCOMP_INDEX=1");
        stale(original);
        var next = submission(request, preview(request));
        jdbc.update("UPDATE TSS_BCOMP_DATABASE_CONFIG_NEW SET DATABASE_PASSWORD='changed-secret' WHERE DATABASE_ID=1");
        stale(next);
        next = submission(request, preview(request));
        jdbc.update("UPDATE TSS_BCOMP_FIELD_NEW SET FIELD_TYPE='2' WHERE BCOMP_INDEX=1");
        stale(next);
        next = submission(request, preview(request));
        configuration(2);
        stale(next);
        assertThat(count("TSS_BCOMP_TASK_NEW")).isZero();
    }

    @Test
    void activeTaskAndCacheFailureCannotCreateAnotherBatch() throws Exception {
        configurations(1);
        var request = request("ALL");
        var submission = submission(request, preview(request));
        cacheFails.set(true);
        mvc.perform(post("/api/batches").contentType(APPLICATION_JSON).content(submission.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_CACHE_REFRESH_FAILED"));
        assertThat(count("TSS_BCOMP_BATCH_NEW")).isZero();
        cacheFails.set(false);
        create(submission);
        assertThat(preview(request).path("items").get(0).path("issueCode").asText()).isEqualTo("TASK_ALREADY_ACTIVE");
    }

    @Test
    void insertionFailureRollsBackBatchItemsAndAlreadyInsertedTasks() throws Exception {
        configurations(2);
        jdbc.execute("ALTER TABLE TSS_BCOMP_TASK_NEW ADD CONSTRAINT test_reject_second CHECK(BCOMP_INDEX<>2)");
        var request = request("ALL");
        mvc.perform(post("/api/batches").contentType(APPLICATION_JSON).content(submission(request, preview(request)).toString()))
            .andExpect(status().isInternalServerError());
        assertThat(count("TSS_BCOMP_BATCH_NEW")).isZero();
        assertThat(count("TSS_BCOMP_BATCH_ITEM_NEW")).isZero();
        assertThat(count("TSS_BCOMP_TASK_NEW")).isZero();
    }

    @Test
    void failedRerunPreservesDatesAndTimeNodeUsesCurrentConfigurationAndKeepsHistory() throws Exception {
        configurations(3);
        var request = request("ALL").put("verifyBusinessDate", "20260921").put("timeNode", "AM");
        var source = create(submission(request, preview(request))).path("batchId").asText();
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4' WHERE BCOMP_INDEX IN(1,3)");
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='3',TASK_RESULT='1' WHERE BCOMP_INDEX=2");
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_NAME='更新后的配置',BCOMP_TIME_NODE='PM' WHERE BCOMP_INDEX=1");
        jdbc.update("DELETE FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=3");
        var retry = mapper.createObjectNode().put("scope", "FAILED").put("sourceBatchId", source);
        var preview = preview(retry);
        assertThat(preview.path("totalCount").asInt()).isEqualTo(2);
        assertThat(preview.path("items").get(0).path("configurationChanged").asBoolean()).isTrue();
        assertThat(preview.path("items").get(1).path("issueCode").asText()).isEqualTo("CONFIGURATION_NOT_FOUND");
        retry.putArray("excludedIds").add(3);
        var created = create(submission(retry, preview(retry)));
        var row = jdbc.queryForMap("SELECT * FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH=?", created.path("batchId").asText());
        assertThat(row.get("BCOMP_INDEX")).isEqualTo(1L);
        assertThat(row.get("TASK_BUSI_DATE")).isEqualTo("20260922");
        assertThat(row.get("VERIFY_BUSINESS_DATE")).isEqualTo("20260921");
        assertThat(row.get("BCOMP_TIME_NODE")).isEqualTo("AM");
        mvc.perform(get("/api/batches/" + source + "/tasks").param("status", "FAILED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].configurationName").value("配置1"))
            .andExpect(jsonPath("$.items[1].configurationName").value("配置3"));
        retry.put("taskBusinessDate", "20260923");
        mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(retry.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH_REQUEST"));
    }

    @Test
    void summarySeparatesDifferenceFailureAndUnknownIncludingMissingTasks() throws Exception {
        configurations(6);
        var request = request("ALL");
        var id = create(submission(request, preview(request))).path("batchId").asText();
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='2' WHERE BCOMP_INDEX=2");
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='3',TASK_RESULT='2' WHERE BCOMP_INDEX=3");
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='3',TASK_RESULT='1' WHERE BCOMP_INDEX=4");
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4' WHERE BCOMP_INDEX=5");
        jdbc.update("DELETE FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=6");
        mvc.perform(get("/api/batches/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(6)).andExpect(jsonPath("$.waitingCount").value(1))
            .andExpect(jsonPath("$.runningCount").value(1)).andExpect(jsonPath("$.consistentCount").value(1))
            .andExpect(jsonPath("$.differenceCount").value(1)).andExpect(jsonPath("$.failedCount").value(1))
            .andExpect(jsonPath("$.otherCount").value(1)).andExpect(jsonPath("$.endedCount").value(3));
        mvc.perform(get("/api/batches/" + id + "/tasks").param("status", "OTHER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].bcompIndex").value(6));
    }

    @Test
    void rejectsInvalidCalendarDateAndEmptyOrOversizedSelection() throws Exception {
        var request = request("ALL").put("taskBusinessDate", "20260230");
        mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(request.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH_REQUEST"));
        mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(request("SELECTED").toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_BATCH"));
        request = request("SELECTED");
        var ids = request.putArray("configurationIds");
        for (int i=1;i<=5001;i++) ids.add(i);
        mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(request.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BATCH_TOO_LARGE"));
    }

    @Test
    void selectedScopeHonorsExplicitIdsAndChecksSchemasAndFieldPositions() throws Exception {
        configurations(3);
        var request = request("SELECTED");
        request.putArray("configurationIds").add(3).add(1);
        jdbc.update("UPDATE TSS_BCOMP_DATABASE_CONFIG_NEW SET DATABASE_URL='jdbc:opengauss://source/db' WHERE DATABASE_ID=1");
        var invalidSchema = preview(request);
        assertThat(invalidSchema.path("totalCount").asInt()).isEqualTo(2);
        assertThat(invalidSchema.path("items").get(0).path("issueCode").asText()).isEqualTo("CURRENT_SCHEMA_REQUIRED");
        jdbc.update("UPDATE TSS_BCOMP_DATABASE_CONFIG_NEW SET DATABASE_URL='jdbc:opengauss://source/db?currentSchema=business' WHERE DATABASE_ID=1");
        jdbc.update("UPDATE TSS_BCOMP_FIELD_NEW SET FIELD_OLD_INDEX=0 WHERE BCOMP_INDEX=3");
        assertThat(preview(request).path("items").get(1).path("issueCode").asText()).isEqualTo("INVALID_FIELD");
        request.putArray("excludedIds").add(3);
        create(submission(request, preview(request)));
        assertThat(jdbc.queryForList("SELECT BCOMP_INDEX FROM TSS_BCOMP_TASK_NEW", Long.class)).containsExactly(1L);
    }

    @Test
    void disappearingFailedSelectionRequiresFreshPreview() throws Exception {
        configurations(1);
        var request = request("ALL");
        var source = create(submission(request, preview(request))).path("batchId").asText();
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4'");
        var retry = mapper.createObjectNode().put("scope", "FAILED").put("sourceBatchId", source);
        var submission = submission(retry, preview(retry));
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='3',TASK_RESULT='1'");
        stale(submission);
        assertThat(count("TSS_BCOMP_BATCH_NEW")).isEqualTo(1);
        mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(retry.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_BATCH"));
    }

    @Test
    void runtimeAndListExposeQueueSettingsAndStableBatchTotals() throws Exception {
        configurations(1);
        var request = request("ALL");
        create(submission(request, preview(request)));
        mvc.perform(get("/api/batches/runtime")).andExpect(status().isOk())
            .andExpect(jsonPath("$.tasksEnabled").value(true)).andExpect(jsonPath("$.concurrency").value(1))
            .andExpect(jsonPath("$.maxBatchSize").value(5000));
        mvc.perform(get("/api/batches")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].totalCount").value(1));
        mvc.perform(get("/api/batches").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/batches/missing")).andExpect(status().isNotFound());
    }

    @Test
    void stoppingBatchCancelsOnlyActiveItemsAndIsIdempotent() throws Exception {
        configurations(3);
        var request = request("ALL");
        String batchId = create(submission(request, preview(request))).path("batchId").asText();
        var otherRequest = request("ALL").put("taskBusinessDate", "20260923");
        String otherBatchId = create(submission(otherRequest, preview(otherRequest))).path("batchId").asText();
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='2' WHERE BCOMP_INDEX=2 AND TASK_BATCH=?", batchId);
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='3',TASK_RESULT='2' WHERE BCOMP_INDEX=3 AND TASK_BATCH=?", batchId);

        for (int attempt=0; attempt<2; attempt++) {
            mvc.perform(post("/api/batches/" + batchId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledCount").value(2))
                .andExpect(jsonPath("$.consistentCount").value(1))
                .andExpect(jsonPath("$.endedCount").value(3));
        }
        assertThat(jdbc.queryForList("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH=? ORDER BY BCOMP_INDEX", String.class, batchId))
            .containsExactly("5", "5", "3");
        assertThat(jdbc.queryForList("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH=? ORDER BY BCOMP_INDEX", String.class, otherBatchId))
            .containsExactly("1", "1", "1");
        assertThat(cancelledBatch.get()).isEqualTo(batchId);
        mvc.perform(get("/api/batches/" + batchId + "/tasks").param("status", "CANCELLED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/batches/" + batchId + "/tasks").param("status", "FAILED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/batches/missing/cancel"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }

    @Test
    void importedAxonHashConfigurationWithoutDateModeCanStartAllTasks() throws Exception {
        try (var input = new ClassPathResource("axon-primary-key-hash-config.sql").getInputStream()) {
            mvc.perform(multipart("/api/configurations/import")
                    .file(new MockMultipartFile("file", "axon.sql", "text/plain", input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cacheRefreshed").value(true));
        }
        var request = request("ALL");
        var preview = preview(request);
        assertThat(preview.path("readyCount").asInt()).isEqualTo(1);
        assertThat(preview.path("items").get(0).path("timeNode").asText()).isEqualTo("3");
        create(submission(request, preview));
        var row = jdbc.queryForMap("SELECT * FROM TSS_BCOMP_TASK_NEW");
        assertThat(row.get("VERIFY_BUSINESS_DATE")).isEqualTo("20260922");
        assertThat(row.get("TASK_STATE")).isEqualTo("1");
    }

    @Test
    void malformedRequestsReturnActionableClientErrors() throws Exception {
        mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON)
                .content("{\"scope\":\"SELECTED\",\"configurationIds\":[\"invalid\"],\"taskBusinessDate\":\"20260922\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH_REQUEST"));
        mvc.perform(get("/api/batches").param("page", "invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH_REQUEST"));
    }

    @Test
    void identifierListsRequireJsonIntegersWithoutTruncationOrCoercion() throws Exception {
        configurations(2);
        for (String field : new String[]{"configurationIds", "excludedIds"}) {
            for (String value : new String[]{"1.5", "1.0", "1e0", "\"1\"", "9223372036854775808", "null", "true"}) {
                String scope = "configurationIds".equals(field) ? "SELECTED" : "ALL";
                String request = "{\"scope\":\"" + scope + "\",\"taskBusinessDate\":\"20260922\",\"" + field + "\":[" + value + "]}";
                mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BATCH_REQUEST"));
            }
        }
        assertThat(count("TSS_BCOMP_TASK_NEW")).isZero();
    }

    private void stale(ObjectNode request) throws Exception {
        mvc.perform(post("/api/batches").contentType(APPLICATION_JSON).content(request.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_PREVIEW_STALE"));
    }
    private ObjectNode request(String scope) { return mapper.createObjectNode().put("scope", scope).put("taskBusinessDate", "20260922"); }
    private ObjectNode submission(ObjectNode request, JsonNode preview) {
        return request.deepCopy().put("requestId", UUID.randomUUID().toString()).put("previewFingerprint", preview.path("fingerprint").asText());
    }
    private JsonNode preview(ObjectNode request) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/batches/preview").contentType(APPLICATION_JSON).content(request.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode create(ObjectNode request) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/batches").contentType(APPLICATION_JSON).content(request.toString()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void configurations(int count) { for (int i=1;i<=count;i++) configuration(i); }
    private void configuration(int id) {
        jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE,BCOMP_DATE_MODE,BCOMP_TIME_NODE,BCOMP_STATE,BCOMP_PARTITION_NUM) VALUES(?,?,'2','2','EOD','1',4)", id, "配置"+id);
        jdbc.update("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW VALUES(?,1,2,'SELECT id FROM source','SELECT id FROM dest')", id);
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_PARTITION_FLAG,FIELD_IGNORE_FLAG) VALUES(?,'id','1',1,1,'1','0','0')", id);
    }
}
