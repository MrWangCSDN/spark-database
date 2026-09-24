package cn.sunline.tss.compare;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class LegacyRepositoryTest {
    LegacyRepository repository;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(ds);
        repository = new LegacyRepository(ds, 30);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) values(1,'file','1'),(2,'table','2')");
    }
    void task(long index, String time) {
        jdbc.update("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,VERIFY_BUSINESS_DATE,TASK_STATE) values(?,'20260920','1',?,'20260919','1')", index, time);
    }
    @Test void claimsOnlyDatabaseTasksWithoutChangingConfiguration() {
        task(1, "A"); task(2, "A");
        var claimed = repository.claim();
        assertThat(claimed.index()).isEqualTo(2);
        assertThat(claimed.verifyDate()).isEqualTo("20260919");
        assertThat(repository.claim()).isNull();
        assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW where BCOMP_INDEX=1", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForList("select BCOMP_TYPE from TSS_BCOMP_CONF_NEW order by BCOMP_INDEX", String.class)).containsExactly("1", "2");
    }
    @Test void concurrentClaimersNeverBothOwnOneTask() throws Exception {
        task(2, "A");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<Task> claim = () -> { gate.await(); return repository.claim(); };
            var a = pool.submit(claim); var b = pool.submit(claim); gate.countDown();
            int owners = (a.get(5, TimeUnit.SECONDS) == null ? 0 : 1) + (b.get(5, TimeUnit.SECONDS) == null ? 0 : 1);
            assertThat(owners).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void refusesAmbiguousLegacyJobIdentityWithoutTouchingRows() {
        task(2, "A"); task(2, "B");
        assertThat(repository.claim()).isNull();
        assertThat(jdbc.queryForList("select TASK_STATE from TSS_BCOMP_TASK_NEW", String.class)).containsOnly("1");
    }
    @Test void sameComparisonDateIsSerializedAcrossDifferentTaskDatesAndBatches() {
        task(2,"A");
        jdbc.update("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,VERIFY_BUSINESS_DATE,TASK_STATE) values(2,'20260921','second','A','20260919','1')");
        var first=repository.claim();assertThat(first).isNotNull();assertThat(repository.claim()).isNull();
        repository.fail(first,"test");assertThat(repository.claim()).isNotNull();
    }
    @Test void failureOnlyChangesOwnedRunningTask() {
        task(1, "A"); task(2, "A");
        var claimed = repository.claim(); repository.fail(claimed, "提交失败");
        assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW where BCOMP_INDEX=2", String.class)).isEqualTo("4");
        assertThat(jdbc.queryForObject("select TASK_RESULT from TSS_BCOMP_TASK_NEW where BCOMP_INDEX=2", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select TASK_STATE from TSS_BCOMP_TASK_NEW where BCOMP_INDEX=1", String.class)).isEqualTo("1");
    }
    @Test void completionAcceptsStateThreeWithZeroCounts() {
        task(2,"A");var claimed=repository.claim();
        jdbc.update("update TSS_BCOMP_TASK_NEW set TASK_STATE='3',TASK_RESULT='2',FILE1_ACOUNT=0,FILE2_ACOUNT=0 where BCOMP_INDEX=2");
        var completion=repository.completion(claimed);
        assertThat(completion.complete()).isTrue();
        assertThat(completion.failureReason()).isNull();
    }
    @Test void completionExplainsMissingTaskRow() {
        var completion=repository.completion(new Task(99,"20260920","missing","A",null));
        assertThat(completion.complete()).isFalse();
        assertThat(completion.failureReason()).isEqualTo("Spark结束后找不到对应任务记录");
    }
    @Test void completionExplainsStateAndMissingCounts() {
        task(2,"A");var claimed=repository.claim();
        jdbc.update("update TSS_BCOMP_TASK_NEW set TASK_STATE='3',TASK_RESULT='2',FILE1_ACOUNT=8 where BCOMP_INDEX=2");
        var completion=repository.completion(claimed);
        assertThat(completion.complete()).isFalse();
        assertThat(completion.failureReason()).contains("TASK_STATE=3","FILE1_ACOUNT=8","FILE2_ACOUNT=缺失");
    }
    @Test void completionPreservesFailureReasonWrittenBySpark() {
        task(2,"A");var claimed=repository.claim();
        jdbc.update("update TSS_BCOMP_TASK_NEW set TASK_STATE='4',TASK_RESULT='3',FTASK_REASON='Spark结果写库失败' where BCOMP_INDEX=2");
        var completion=repository.completion(claimed);
        assertThat(completion.complete()).isFalse();
        assertThat(completion.failureReason()).isEqualTo("Spark结果写库失败");
    }
}
