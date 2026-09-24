package cn.sunline.tss.compare;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskWorkerTest {
    JdbcTemplate jdbc; LegacyRepository repository; ConfigStore configs; MockEnvironment env; TaskWorker worker;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); repository = new LegacyRepository(ds, 30); configs = new ConfigStore(repository);
        jdbc.update("insert into TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) values(2,'table','2')");
        jdbc.update("insert into TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION,DATABASE_GENERATERKEY) values(1,'jdbc:source','sa','','D','0',null)");
        jdbc.update("insert into TSS_BCOMP_TABLE_SQL_NEW values(2,1,1,'T','T')");
        jdbc.update("insert into TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME) values(2,'ID')");
        env = new MockEnvironment().withProperty("tssveritask.executor.thread.enable", "false")
            .withProperty("spark.datasource.url", "jdbc:meta").withProperty("spark.datasource.username", "sa")
            .withProperty("spark.datasource.password", "").withProperty("spark.datasource.driver-class-name", "D")
            .withProperty("compare.refresh-timeout-seconds", "1");
    }
    void addTask(String batch) {
        jdbc.update("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(2,'20260920',?,'A','1')", batch);
    }
    void start(JobLauncher launcher) {
        worker = new TaskWorker(repository, configs, new JobArguments(env), launcher, env); worker.start();
    }
    @AfterEach void cleanup() { if(worker != null) worker.stop(); }
    @Test void failedSubmissionIsRecordedWithoutKillingWorker() {
        var logger=(Logger)LoggerFactory.getLogger(TaskWorker.class);var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            addTask("1"); start(args -> { throw new IllegalStateException("stage=submit password=sensitive-detail"); }); worker.runOne();
            String reason=jdbc.queryForObject("select FTASK_REASON from TSS_BCOMP_TASK_NEW",String.class);
            String logged=events.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("4");
            assertThat(reason).contains("IllegalStateException","stage=submit").doesNotContain("sensitive-detail");
            assertThat(logged).contains("IllegalStateException","TaskWorkerTest").doesNotContain("sensitive-detail");
            assertThat(worker.status()).isEqualTo("2");
        } finally { logger.detachAppender(events); }
    }
    @Test void finishedProcessWithoutPersistedResultMustNotBecomeSuccess() {
        var logger=(Logger)LoggerFactory.getLogger(TaskWorker.class);var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            addTask("1"); start(args -> {}); worker.runOne();
            assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("4");
            assertThat(jdbc.queryForObject("select FTASK_REASON from TSS_BCOMP_TASK_NEW",String.class))
                .contains("TASK_STATE=2","FILE1_ACOUNT=缺失","FILE2_ACOUNT=缺失");
            assertThat(events.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anySatisfy(message -> assertThat(message).contains("Spark作业结束但结果不完整","TASK_STATE=2"));
        } finally { logger.detachAppender(events); }
    }
    @Test void successfulJobKeepsLegacyDifferenceResult() {
        addTask("1"); start(args -> jdbc.update("update TSS_BCOMP_TASK_NEW set TASK_STATE='3',TASK_RESULT='1',FILE1_ACOUNT=3,FILE2_ACOUNT=3"));
        worker.runOne();
        assertThat(jdbc.queryForObject("select TASK_RESULT from TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("3");
    }
    @Test void incompleteCompletedRowMustBeMarkedFailed() {
        addTask("1"); start(args -> jdbc.update("update TSS_BCOMP_TASK_NEW set TASK_STATE='3',TASK_RESULT='2'"));
        worker.runOne();
        assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW", String.class)).isEqualTo("4");
    }
    @Test void failedRefreshKeepsPreviousSnapshotAndPollingAlive() {
        start(args -> {});
        jdbc.execute("drop table TSS_BCOMP_DATABASE_CONFIG_NEW");
        assertThat(worker.refresh().code()).isEqualTo(500);
        assertThat(configs.snapshot().names().get(2L)).isEqualTo("table");
        assertThat(worker.isRunning()).isTrue();
    }
    @Test void refreshWaitsForJobAndWorkerRemainsUsable() throws Exception {
        addTask("1"); var started = new CountDownLatch(1); var finish = new CountDownLatch(1);
        start(args -> { started.countDown(); finish.await(); jdbc.update("update TSS_BCOMP_TASK_NEW set TASK_STATE='3',TASK_RESULT='2',FILE1_ACOUNT=1,FILE2_ACOUNT=1 where TASK_BATCH=?",args[8]); });
        var pool=Executors.newFixedThreadPool(2);
        try {
            var job=pool.submit(worker::runOne); assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            jdbc.update("update TSS_BCOMP_CONF_NEW set BCOMP_NAME='refreshed'");
            var refresh=pool.submit(worker::refresh);
            assertThatThrownBy(() -> refresh.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            finish.countDown(); job.get(3,TimeUnit.SECONDS);
            assertThat(refresh.get(3,TimeUnit.SECONDS).code()).isEqualTo(200);
            assertThat(configs.snapshot().names().get(2L)).isEqualTo("refreshed");
            addTask("2"); worker.runOne();
            assertThat(jdbc.queryForList("select TASK_STATE from TSS_BCOMP_TASK_NEW",String.class)).containsOnly("3");
        } finally { finish.countDown(); pool.shutdownNow(); }
    }
    @Test void originalHttpContractsRemainAvailable() throws Exception {
        start(args -> {});
        var mvc=MockMvcBuilders.standaloneSetup(new StatusController(worker)).build();
        mvc.perform(get("/tss/runStatus")).andExpect(status().isOk()).andExpect(content().string("2"));
        mvc.perform(post("/api/cache/refresh")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.msg").value("刷新成功"));
        worker.stop(); mvc.perform(get("/tss/runStatus")).andExpect(content().string("0"));
    }
    @Test void refreshTimeoutKeepsOldSnapshotAndAllowsNextRefresh() throws Exception {
        addTask("1"); var started=new CountDownLatch(1); var finish=new CountDownLatch(1);
        start(args -> { started.countDown(); finish.await(); });
        var pool=Executors.newSingleThreadExecutor();
        try {
            var job=pool.submit(worker::runOne); assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            jdbc.update("update TSS_BCOMP_CONF_NEW set BCOMP_NAME='changed'");
            assertThat(worker.refresh()).isEqualTo(new TaskWorker.RefreshResult(500,"等待任务超时"));
            assertThat(configs.snapshot().names().get(2L)).isEqualTo("table");
            finish.countDown(); job.get(3,TimeUnit.SECONDS);
            assertThat(worker.refresh().code()).isEqualTo(200);
            assertThat(configs.snapshot().names().get(2L)).isEqualTo("changed");
        } finally { finish.countDown(); pool.shutdownNow(); }
    }
    @Test void stopInterruptsOverdueJobAndDoesNotClaimAnotherTask() throws Exception {
        addTask("1"); addTask("2"); var started=new CountDownLatch(1);
        env.withProperty("tssveritask.executor.thread.enable","true")
            .withProperty("tssveritask.executor.thread.awaitTerminationSeconds","0");
        start(args -> { started.countDown(); new CountDownLatch(1).await(); });
        assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
        worker.stop();
        assertThat(worker.status()).isEqualTo("0");
        assertThat(jdbc.queryForList("select TASK_STATE from TSS_BCOMP_TASK_NEW",String.class)).containsExactlyInAnyOrder("4","1");
    }
}
