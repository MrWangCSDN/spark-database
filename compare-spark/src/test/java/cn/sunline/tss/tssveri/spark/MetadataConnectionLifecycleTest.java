package cn.sunline.tss.tssveri.spark;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static cn.sunline.tss.tssveri.spark.MetadataLifecycleDriver.*;
import cn.sunline.tss.tssveri.spark.MetadataLifecycleDriver.Scenario;

/** Real local Spark/H2 work; only JDBC transport/lifecycle faults are injected. */
class MetadataConnectionLifecycleTest {
    private final Map<String,String> previousProperties=new HashMap<>();
    private final MetadataLifecycleDriver driver=new MetadataLifecycleDriver();
    private final Scenario scenario=new Scenario();
    private final List<LogEvent> events=new CopyOnWriteArrayList<>();
    private final AbstractAppender appender=new AbstractAppender("metadata-phases",null,null,true,Property.EMPTY_ARRAY) {
        @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
    };
    private org.apache.logging.log4j.core.Logger logger;
    private Level previousLevel;
    private String url;

    @BeforeEach void setup() throws Exception {
        Map.of("spark.master","local[2]","spark.ui.enabled","false",
            "spark.driver.host","127.0.0.1","spark.driver.bindAddress","127.0.0.1",
            "spark.default.parallelism","2","spark.sql.shuffle.partitions","2")
            .forEach((key,value)->{previousProperties.put(key,System.getProperty(key));System.setProperty(key,value);});
        DriverManager.registerDriver(driver);
        org.apache.spark.sql.execution.datasources.jdbc.DriverRegistry.register(MetadataLifecycleDriver.class.getName());
        url="jdbc:h2:mem:metadata_phases_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        SCENARIOS.put(url,scenario);
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            try(var schema=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {
                org.h2.tools.RunScript.execute(connection,schema);
            }
            statement.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_SAMPLE_LIMIT) VALUES(1,'connection lifecycle',0)");
            statement.execute("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'origin'),(2,'destination')");
            statement.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID) VALUES(1,1,2)");
            statement.execute("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_IGNORE_FLAG) VALUES(1,'ID','1',1,1,'1','0'),(1,'VAL','1',2,2,'0','0')");
            statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261123','phase-batch','D','2')");
            statement.execute("CREATE TABLE PHASE_OLD(ID VARCHAR(20),VAL VARCHAR(20))");
            statement.execute("CREATE TABLE PHASE_NEW(ID VARCHAR(20),VAL VARCHAR(20))");
            statement.execute("INSERT INTO PHASE_OLD VALUES('1','same')");
            statement.execute("INSERT INTO PHASE_NEW VALUES('1','same')");
        }
        logger=(org.apache.logging.log4j.core.Logger)LogManager.getLogger(SparkHandlerTable.class);
        previousLevel=logger.getLevel();logger.setLevel(Level.INFO);appender.start();logger.addAppender(appender);
    }

    @AfterEach void cleanup() throws Exception {
        if(SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
        SparkSession.clearActiveSession();SparkSession.clearDefaultSession();
        logger.removeAppender(appender);appender.stop();logger.setLevel(previousLevel);
        SCENARIOS.remove(url);DriverManager.deregisterDriver(driver);
        previousProperties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
    }

    @Test void publishesOnFreshConnectionAfterClosingMetadataScopesBeforeLongWork() throws Exception {
        // The startup connection deterministically gets EOF if publication reuses it.
        SparkHandlerTable.main(arguments());

        assertEquals("3",value("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW"));
        assertEquals("1",value("SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW"));
        assertEquals(1,scenario.publications.get());
        assertTrue(scenario.sourceCounts.get()>=2,"Both source COUNTs must run");
        assertTrue(scenario.distributedReads.get()>0,"Real Spark JDBC tasks must read the sources");
        assertTrue(scenario.violations.isEmpty(),scenario.violations.toString());
        assertEquals(1,scenario.connections.stream().filter(c->"STARTUP".equals(c.role)).count());
        assertEquals(1,scenario.connections.stream().filter(c->"STRATEGY".equals(c.role)).count());
        assertEquals(1,scenario.connections.stream().filter(c->"PUBLISH".equals(c.role)&&c.openedAfterComparison).count(),
            "The publication connection must be created after distributed reads");
        assertTrue(scenario.connections.stream().allMatch(c->c.closed),"Every metadata connection must close");
        assertTrue(events.stream().anyMatch(e->e.getMessage().getFormattedMessage().contains("phase=COMPLETE")));
    }

    @Test void doesNotReplayFailedPublicationAndRollsBackToPreviousResult() throws Exception {
        publishPreviousResult();
        var failure=new SQLException(SECRET,"08006",new EOFException(SECRET));
        scenario.publicationFailure=failure;

        assertSame(failure,assertThrows(SQLException.class,()->SparkHandlerTable.main(arguments())));

        assertEquals(1,scenario.publications.get(),"Publication must not be replayed");
        assertEquals("99",value("SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW"));
        assertEquals("4",value("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW"));
        assertEquals("SQLException [SQLSTATE=08006] -> EOFException",value("SELECT FTASK_REASON FROM TSS_BCOMP_TASK_NEW"));
        assertEquals(Boolean.TRUE,scenario.failureWrittenBeforeSparkClose);
        var event=events.stream().filter(e->e.getMessage().getFormattedMessage().contains("库表核对失败")).findFirst().orElseThrow();
        assertTrue(event.getMessage().getFormattedMessage().contains("phase=PUBLISH"));
        assertFalse(event.getMessage().getFormattedMessage().contains(SECRET));assertNull(event.getThrown());
        assertTrue(scenario.connections.stream().allMatch(c->c.closed));
    }

    @Test void uncertainCommitIsNotReplayedAndCannotOverwriteCommittedSuccess() throws Exception {
        publishPreviousResult();
        var failure=new SQLException(SECRET,"08007");scenario.commitFailure=failure;

        assertSame(failure,assertThrows(SQLException.class,()->SparkHandlerTable.main(arguments())));

        assertEquals(1,scenario.publications.get());
        assertEquals("3",value("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW"));
        assertNull(value("SELECT FTASK_REASON FROM TSS_BCOMP_TASK_NEW"));
        assertEquals("1",value("SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW"));
    }

    @Test void publicationFailureDoesNotOverwriteAlreadySuccessfulOrCancelledTasks() throws Exception {
        publishPreviousResult();
        for(String state:List.of("3","5")) {
            execute("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='2',FTASK_REASON=NULL");
            scenario.stateBeforePublication=state;

            assertThrows(SQLException.class,()->SparkHandlerTable.main(arguments()));

            assertEquals(state,value("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW"));
            assertNull(value("SELECT FTASK_REASON FROM TSS_BCOMP_TASK_NEW"));
            assertEquals("99",value("SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW"));
        }
        assertEquals(2,scenario.publications.get(),"One attempt for each independent job");
    }

    @Test void readModeFailureRemainsBestEffortWithSafePhaseDiagnostic() throws Exception {
        scenario.strategyFailure=new SQLException(SECRET,"08006");

        assertBestEffortStrategyFailure();
    }

    @Test void readModeConnectionOpenFailureDoesNotPreventFreshPublication() throws Exception {
        scenario.strategyOpenFailure=new SQLException(SECRET,"08006");

        assertBestEffortStrategyFailure();
    }

    @Test void readModeConnectionCloseFailureDoesNotPreventFreshPublication() throws Exception {
        scenario.strategyCloseFailure=new SQLException(SECRET,"08006");

        assertBestEffortStrategyFailure();
    }

    private void assertBestEffortStrategyFailure() throws Exception {

        SparkHandlerTable.main(arguments());

        assertEquals("3",value("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW"));
        assertEquals("1",value("SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW"));
        var warning=events.stream().filter(e->e.getLevel()==Level.WARN&&e.getMessage().getFormattedMessage().contains("BCOMP_READ_MODE失败")).findFirst().orElseThrow();
        assertTrue(warning.getMessage().getFormattedMessage().contains("phase=WRITE_READ_MODE"));
        assertTrue(warning.getMessage().getFormattedMessage().contains("SQLSTATE=08006"));
        assertFalse(warning.getMessage().getFormattedMessage().contains(SECRET));assertNull(warning.getThrown());
        assertTrue(scenario.violations.isEmpty(),scenario.violations.toString());
    }

    private void publishPreviousResult() throws Exception {
        try(var connection=DriverManager.getConnection(url,"sa","")) {
            var result=new ResultAggregate(0);result.matched=99;
            DailyResultPublisher.publish(connection,arguments(),DailyResultPublisher.context(connection,1),result);
        }
        execute("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='2'");
    }
    private void execute(String sql) throws Exception {
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){statement.execute(sql);}
    }
    private String value(String sql) throws Exception {
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();var result=statement.executeQuery(sql)) {
            assertTrue(result.next());return result.getString(1);
        }
    }
    private String[] arguments() {
        String metadata="jdbc:phase-test:metadata:"+url,business="jdbc:phase-test:business:"+url;
        return new String[]{"metadata-phases",metadata,"sa","",MetadataLifecycleDriver.class.getName(),"1000","1","20261123","phase-batch","D",
            business,"sa","",MetadataLifecycleDriver.class.getName(),"(SELECT ID,VAL FROM PHASE_OLD) old_src",
            business,"sa","",MetadataLifecycleDriver.class.getName(),"(SELECT ID,VAL FROM PHASE_NEW) new_src","20261123"};
    }

}
