package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class DailyResultExportSnapshotTest {
    DriverManagerDataSource source;JdbcTemplate jdbc;DailyResultRepository repository;
    @BeforeEach void setup() {
        source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        new ResourceDatabasePopulator(new FileSystemResource("../sql/create_bcomp_new.sql")).execute(source);
        jdbc=new JdbcTemplate(source);repository=new DailyResultRepository(source,new ObjectMapper());
    }
    @Test void capturesAllTablesWithoutPaginationOrSampleDependence() {
        insert(1,260,1);count(1,"A",260);count(1,"B",180);
        insert(2,260,0);pattern(2,"a","[\"A\",\"A\"]",80);pattern(2,"ab","[\"A\",\"B\"]",180);
        var snapshot=repository.exportSnapshot("20260928");
        assertThat(repository.list("20260928",0,1).results().items()).hasSize(1);
        assertThat(snapshot.results()).hasSize(2).allSatisfy(row->assertThat(row.fieldCounts()).isEqualTo(Map.of("A",260L,"B",180L)));
        assertThatThrownBy(()->snapshot.results().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->snapshot.results().get(0).fieldCounts().put("X",1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(repository.expire("20261001000000")).isEqualTo(2);
        assertThat(repository.exportSnapshot("20260928").results()).extracting(DailyResultExportSnapshot.TableResult::fieldCounts)
            .containsExactly(Map.of("A",260L,"B",180L),Map.of("A",260L,"B",180L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_COUNT_NEW",Long.class)).isEqualTo(2);
    }
    @Test void gdbDialectKeepsFilteredPagingExportAndRetentionCompatible() {
        var mysqlSource=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("legacy-schema.sql")).execute(mysqlSource);
        var mysqlJdbc=new JdbcTemplate(mysqlSource);
        var mysqlRepository=new DailyResultRepository(mysqlSource,new ObjectMapper(),
            cn.sunline.tss.compare.metadata.MetadataSqlDialect.forType(cn.sunline.tss.compare.metadata.MetadataDbType.GDB));
        insert(mysqlJdbc,1,2,1,"run-1");
        mysqlJdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET REVISER_NAME='负责人',REVISER_USERNAME='test-user',FIELD_COUNTS_SIZE=1 WHERE BCOMP_INDEX=1");
        mysqlJdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(1,'20260928','run-1','A',2)");
        var page=mysqlRepository.list("20260928",0,1,"存款","表1","USER");
        assertThat(page.results().total()).isEqualTo(1);
        assertThat(page.results().items()).hasSize(1);
        assertThat(page.connections()).hasSize(1);
        assertThat(mysqlRepository.list("20260928",1,1,"dept","表1","负责人").results().items()).isEmpty();
        assertThat(mysqlRepository.exportSnapshot("20260928").results().get(0).fieldCounts()).isEqualTo(Map.of("A",2L));
        assertThat(mysqlRepository.expire("20261001000000")).isEqualTo(1);
        assertThat(mysqlRepository.exportSnapshot("20260928").results().get(0).fieldCounts()).isEqualTo(Map.of("A",2L));
    }
    @Test void rejectsMalformedPatternJsonWithoutReturningZero() {
        insert(1,1,0);pattern(1,"bad","not-json",1);
        assertThatThrownBy(()->repository.exportSnapshot("20260928")).isInstanceOfSatisfying(ApiException.class,
            error->assertThat(error.code()).isEqualTo("RESULT_EXPORT_FAILED"));
    }
    @Test void rejectsStaleNativeRowsAndKeepsAccurateZeroDistinctFromMissingCounts() {
        insert(1,1,1);count(1,"A",1);
        jdbc.update("UPDATE TSS_BCOMP_FIELD_COUNT_NEW SET RUN_ID='stale'");
        assertThatThrownBy(()->repository.exportSnapshot("20260928")).isInstanceOfSatisfying(ApiException.class,
            error->assertThat(error.code()).isEqualTo("RESULT_EXPORT_FAILED"));
        jdbc.update("DELETE FROM TSS_BCOMP_FIELD_COUNT_NEW");
        assertThatThrownBy(()->repository.exportSnapshot("20260928")).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET DIFFERENT_COUNT=0,ORIGIN_COUNT=0,DESTINATION_COUNT=0,FIELD_COUNTS_SIZE=0");
        assertThat(repository.exportSnapshot("20260928").results().get(0).fieldCounts()).isEmpty();
    }
    @Test void reportsUnavailableTablesWithBoundedMessage() {
        for(int i=1;i<=11;i++) insert(i,1,0);
        assertThatThrownBy(()->repository.exportSnapshot("20260928")).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.code()).isEqualTo("FIELD_COUNTS_UNAVAILABLE");
            assertThat(error.getMessage()).contains("20260928","11","表1（配置1）","表10（配置10）","其余1张","重新核对").doesNotContain("表11（配置11）");
        });
    }
    @Test void releasesTransactionBeforeSnapshotIsConsumed() throws Exception {
        insert(1,1,1);count(1,"A",1);
        var snapshot=repository.exportSnapshot("20260928");
        try(var c=source.getConnection();var statement=c.createStatement()) {
            c.setAutoCommit(false);statement.setQueryTimeout(2);
            try(var rs=statement.executeQuery("SELECT RUN_ID FROM TSS_BCOMP_RESULT_NEW FOR UPDATE")){assertThat(rs.next()).isTrue();}
            c.rollback();
        }
        assertThat(snapshot.results().get(0).fieldCounts()).containsEntry("A",1L);
    }
    @Test void neverCombinesSummaryAndCountsFromDifferentRuns() throws Exception {
        insert(1,1,1);count(1,"A",1);
        var captured=new CountDownLatch(1);var release=new CountDownLatch(1);var requested=new CountDownLatch(1);
        var once=new AtomicBoolean();
        var gated=new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var delegate=source.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                    if(m.getName().equals("prepareStatement")&&args[0].toString().contains("FROM TSS_BCOMP_FIELD_COUNT_NEW")&&once.compareAndSet(false,true)) {
                        captured.countDown();
                        if(!release.await(10,TimeUnit.SECONDS)) throw new SQLException("test reader barrier timed out");
                    }
                    try{return m.invoke(delegate,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
            @Override public Connection getConnection(String u,String p) throws SQLException{return getConnection();}
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var reader=pool.submit(()->new DailyResultRepository(gated,new ObjectMapper()).exportSnapshot("20260928"));
            assertThat(captured.await(5,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->{
                try(var c=source.getConnection();var ps=c.prepareStatement("SELECT RUN_ID FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=1 FOR UPDATE")) {
                    c.setAutoCommit(false);requested.countDown();ps.executeQuery().close();
                    var transactionJdbc=new JdbcTemplate(new SingleConnectionDataSource(c,true));
                    transactionJdbc.update("DELETE FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE BCOMP_INDEX=1");
                    transactionJdbc.update("DELETE FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=1");
                    insert(transactionJdbc,1,5,1,"new-run");
                    transactionJdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(1,'20260928','new-run','A',5)");
                    transactionJdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET FIELD_COUNTS_SIZE=1 WHERE BCOMP_INDEX=1");
                    c.commit();return true;
                }
            });
            assertThat(requested.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->writer.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var old=reader.get(5,TimeUnit.SECONDS).results().get(0);
            assertThat(old.summary().runId()).isEqualTo("run-1");assertThat(old.fieldCounts()).containsEntry("A",1L);
            assertThat(writer.get(5,TimeUnit.SECONDS)).isTrue();
            var current=repository.exportSnapshot("20260928").results().get(0);
            assertThat(current.summary().runId()).isEqualTo("new-run");assertThat(current.fieldCounts()).containsEntry("A",5L);
        } finally {release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
    }

    @Test void sampleExpirySkipsACandidateRemovedBeforeItsLockedLookup() throws Exception {
        insert(1,1,1);
        var selected=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        var gated=new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate=source.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                    if(m.getName().equals("prepareStatement")&&args[0].toString().contains("WHERE BCOMP_INDEX=? AND RESULT_DATE=? FOR UPDATE")&&once.compareAndSet(false,true)) {
                        selected.countDown();
                        if(!release.await(10,TimeUnit.SECONDS)) throw new SQLException("expiry test barrier timed out");
                    }
                    try{return m.invoke(delegate,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
            @Override public Connection getConnection(String u,String p) throws SQLException {return getConnection();}
        };
        var pool=Executors.newSingleThreadExecutor();
        try {
            var expiry=pool.submit(()->new DailyResultRepository(gated,new ObjectMapper()).expire("20261001000000"));
            assertThat(selected.await(5,TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=1");
            release.countDown();
            assertThatCode(()->expiry.get(5,TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThat(expiry.get(5,TimeUnit.SECONDS)).isZero();
        } finally {release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
    }
    private void insert(long index,long different,int version) {insert(jdbc,index,different,version,"run-"+index);}
    private static void insert(JdbcTemplate jdbc,long index,long different,int version,String run) {
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,CONFIGURATION_NAME,CONFIGURATION_MODULE,ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT,FIELD_COUNTS_VERSION) VALUES(?,'20260928',?,'20260928','batch','D',?,'dept',1,'来源',2,'目标',?,?,0,0,0,?,0,0,0,0,'20260928120000',?)",
            index,run,"表"+index,different,different,different,version);
    }
    private void count(long index,String field,long count) {
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(?,'20260928',?,?,?)",index,"run-"+index,field,count);
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET FIELD_COUNTS_SIZE=FIELD_COUNTS_SIZE+1 WHERE BCOMP_INDEX=?",index);
    }
    private void pattern(long index,String id,String fields,long count) {
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT) VALUES(?,'20260928',?,?,?,?,0)",index,"run-"+index,id,fields,count);
    }
}
