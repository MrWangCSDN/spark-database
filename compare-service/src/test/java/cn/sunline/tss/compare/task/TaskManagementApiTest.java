package cn.sunline.tss.compare.task;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskManagementApiTest {
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        var repository = new TaskManagementRepository(source, 30);
        var service = new TaskManagementService(repository, null);
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(service), new DashboardController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        jdbc.update("insert into TSS_BCOMP_CONF(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE,BCOMP_DATE_MODE,BCOMP_TIME_NODE,BCOMP_STATE) values(22,'客户余额核对','2','2','EOD','1')");
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION) values(1,'old','jdbc:old','u','p','D','0'),(2,'new','jdbc:new','u','p','D','0')");
        jdbc.update("insert into TSS_BCOMP_TABLE_SQL values(22,1,2,'OLD_SQL','NEW_SQL')");
        jdbc.update("insert into TSS_BCOMP_FIELD(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_PARTITION_FLAG,FIELD_IGNORE_FLAG) values(22,'ID','1',1,1,'1','1','0')");
    }

    @Test
    void createsWaitingTaskUsingOriginalColumns() throws Exception {
        String request = """
            {"bcompIndex":22,"taskBusinessDate":"20260920","verifyBusinessDate":"20260919","taskBatch":"001","timeNode":"EOD"}
            """;
        mvc.perform(post("/api/tasks").contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.configurationName").value("客户余额核对"))
            .andExpect(jsonPath("$.taskState").value("1"));

        var row = jdbc.queryForMap("select * from TSS_BCOMP_TASK");
        assertThat(row.get("TASK_STATE")).isEqualTo("1");
        assertThat(row.get("TASK_RESULT")).isNull();
        assertThat(row.get("CREATE_DATETIME").toString()).hasSize(14);
    }

    @Test
    void rejectsDuplicateThreePartIdentityEvenWithAnotherTimeNode() throws Exception {
        jdbc.update("insert into TSS_BCOMP_TASK(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(22,'20260920','001','AM','1')");
        String request = """
            {"bcompIndex":22,"taskBusinessDate":"20260920","verifyBusinessDate":"20260919","taskBatch":"001","timeNode":"EOD"}
            """;
        mvc.perform(post("/api/tasks").contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TASK_IDENTITY_CONFLICT"));
    }

    @Test
    void rejectsConfigurationWithoutBusinessKey() throws Exception {
        jdbc.update("update TSS_BCOMP_FIELD set FIELD_INDEX_FLAG='0'");
        String request = """
            {"bcompIndex":22,"taskBusinessDate":"20260920","verifyBusinessDate":"20260919","taskBatch":"001","timeNode":"EOD"}
            """;
        mvc.perform(post("/api/tasks").contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONFIGURATION_INCOMPLETE"));
    }

    @Test
    void listsAndLooksUpExactTaskAndPagesDifferences() throws Exception {
        jdbc.update("insert into TSS_BCOMP_TASK(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,CREATE_DATETIME,TASK_STATE,TASK_RESULT,FILE1_ACOUNT,FILE2_ACOUNT) values(22,'20260920','001','EOD','20260920160000','3','1',3,3)");
        jdbc.update("insert into TSS_BCOMP_DTL values(22,'20260920','001','[A]','A|+|old','A|+|new','4','VAL')");
        jdbc.update("insert into TSS_BCOMP_DTL values(22,'20260920','001','[B]','', 'B|+|new','2','')");

        mvc.perform(get("/api/tasks").param("size", "20").param("sort", "createdAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].taskResult").value("1"));
        mvc.perform(get("/api/tasks/22/20260920/001/EOD"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.file1Count").value(3));
        mvc.perform(get("/api/tasks/22/20260920/001/EOD/differences").param("lineResult", "4"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].businessKey").value("[A]"));
    }

    @Test
    void rejectsOversizedPageAndSortInjection() throws Exception {
        mvc.perform(get("/api/tasks").param("size", "101"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/tasks").param("sort", "TASK_STATE;drop table TSS_BCOMP_TASK"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    @Test
    void dashboardUsesBoundedRecentTaskQuery() throws Exception {
        jdbc.update("insert into TSS_BCOMP_TASK(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,CREATE_DATETIME,TASK_STATE) values(22,'20260920','001','EOD','20260920160000','2')");
        mvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runningCount").value(1))
            .andExpect(jsonPath("$.recentTasks.length()").value(1));
    }
}
