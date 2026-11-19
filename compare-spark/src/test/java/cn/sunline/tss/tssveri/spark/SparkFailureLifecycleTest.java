package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.SparkSession;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SparkFailureLifecycleTest {
    private static final String SECRET="synthetic-password-and-business-value";
    private static final ThreadLocal<Throwable> READ_FAILURE=new ThreadLocal<>();
    private static final ThreadLocal<Throwable> WRITE_FAILURE=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> WRITE_CONTEXT_ACTIVE=new ThreadLocal<>();
    private final Map<String,String> previousProperties=new HashMap<>();
    private final Driver driver=new FaultDriver();
    private String url;

    @BeforeEach void setup() throws Exception {
        Map.of("spark.master","local[2]","spark.ui.enabled","false",
            "spark.driver.host","127.0.0.1","spark.driver.bindAddress","127.0.0.1",
            "spark.default.parallelism","2","spark.sql.shuffle.partitions","2")
            .forEach((key,value)->{
                previousProperties.put(key,System.getProperty(key));
                System.setProperty(key,value);
            });
        DriverManager.registerDriver(driver);
        url="jdbc:h2:mem:failure_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            try(var schema=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {
                org.h2.tools.RunScript.execute(connection,schema);
            }
            statement.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_SAMPLE_LIMIT) VALUES(1,'failure lifecycle',0)");
            statement.execute("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'origin'),(2,'destination')");
            statement.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID) VALUES(1,1,2)");
            statement.execute("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_IGNORE_FLAG) VALUES(1,'ID','1',1,1,'1','0'),(1,'VAL','1',2,2,'0','0')");
            statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261119','failure-batch','D','2')");
            statement.execute("CREATE TABLE FAILURE_OLD(ID VARCHAR(20),VAL VARCHAR(20))");
            statement.execute("CREATE TABLE FAILURE_NEW(ID VARCHAR(20),VAL VARCHAR(20))");
            statement.execute("INSERT INTO FAILURE_OLD VALUES('1','same')");
            statement.execute("INSERT INTO FAILURE_NEW VALUES('1','same')");
        }
    }

    @AfterEach void cleanup() throws Exception {
        if(SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
        SparkSession.clearActiveSession();
        SparkSession.clearDefaultSession();
        READ_FAILURE.remove();
        WRITE_FAILURE.remove();
        WRITE_CONTEXT_ACTIVE.remove();
        DriverManager.deregisterDriver(driver);
        previousProperties.forEach((key,value)->{
            if(value==null) System.clearProperty(key); else System.setProperty(key,value);
        });
    }

    @Test void storesNestedDiskFailureWithoutOriginalMessages() throws Exception {
        var primary=new IllegalStateException("SELECT "+SECRET,
            new FileSystemException("/synthetic/"+SECRET,null,"No space left on device"));
        READ_FAILURE.set(primary);

        assertSame(primary,assertThrows(IllegalStateException.class,()->SparkHandlerTable.main(arguments())));

        String reason=failedReason();
        assertTrue(reason.contains("IllegalStateException"),reason);
        assertTrue(reason.contains("LOCAL_DISK_FULL"),reason);
        assertFalse(reason.contains(SECRET),reason);
        assertFalse(reason.contains("SELECT"),reason);
        assertTrue(reason.length()<=500,reason);
    }

    @Test void persistsFailureBeforeClosingTheSparkContext() throws Exception {
        var primary=new IllegalStateException(SECRET,new java.io.IOException("No space left on device"));
        READ_FAILURE.set(primary);

        assertSame(primary,assertThrows(IllegalStateException.class,()->SparkHandlerTable.main(arguments())));

        assertEquals(Boolean.TRUE,WRITE_CONTEXT_ACTIVE.get(),"The task reason must be persisted before Spark stops");
        assertTrue(failedReason().contains("LOCAL_DISK_FULL"));
    }

    @Test void marksDriverOutOfMemoryErrorFailedAndRethrowsTheSameError() throws Exception {
        var primary=new OutOfMemoryError(SECRET);
        READ_FAILURE.set(primary);

        assertSame(primary,assertThrows(OutOfMemoryError.class,()->SparkHandlerTable.main(arguments())));

        String reason=failedReason();
        assertTrue(reason.contains("OUT_OF_MEMORY"),reason);
        assertFalse(reason.contains(SECRET),reason);
    }

    @Test void storesFixedArrayRangeCategoryWithoutRowContents() throws Exception {
        var primary=new ArrayIndexOutOfBoundsException("Index 35 for length 27 "+SECRET);
        READ_FAILURE.set(primary);

        assertSame(primary,assertThrows(ArrayIndexOutOfBoundsException.class,()->SparkHandlerTable.main(arguments())));

        String reason=failedReason();
        assertTrue(reason.contains("ARRAY_INDEX_OUT_OF_RANGE"),reason);
        assertFalse(reason.contains(SECRET),reason);
    }

    @Test void unknownMessagesDoNotInferMemoryOrDiskFailureFromTerminationWords() throws Exception {
        var primary=new SQLException("TERM 137 143 heartbeater OutOfMemoryError ENOSPC "+SECRET);
        READ_FAILURE.set(primary);

        assertSame(primary,assertThrows(SQLException.class,()->SparkHandlerTable.main(arguments())));

        assertEquals("SQLException",failedReason());
    }

    @Test void closesSessionWhenMetadataDriverInitializationFails() throws Exception {
        SparkSession session=SparkSession.builder().appName("initialization-failure").getOrCreate();
        String[] args=arguments();
        args[4]="synthetic.missing.Driver";

        assertThrows(ClassNotFoundException.class,()->SparkHandlerTable.main(args));

        assertTrue(session.sparkContext().isStopped());
        assertTrue(failedReason().contains("ClassNotFoundException"));
    }

    @Test void closeFailureIsAttachedToPrimaryFailureAndLabeledAsAdditional() throws Exception {
        var primary=new IllegalArgumentException(SECRET);
        var closeFailure=new IllegalStateException("cleanup "+SECRET,
            new FileSystemException("/synthetic/cleanup",null,"No space left on device"));
        READ_FAILURE.set(primary);
        installFailingClose(closeFailure);
        var events=new CopyOnWriteArrayList<LogEvent>();
        var appender=new AbstractAppender("failure-diagnostics-test",null,null,true,Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        var logger=(org.apache.logging.log4j.core.Logger)LogManager.getLogger(SparkHandlerTable.class);
        Level previousLevel=logger.getLevel();
        logger.setLevel(Level.ERROR);
        appender.start();
        logger.addAppender(appender);

        try {
            assertSame(primary,assertThrows(IllegalArgumentException.class,()->SparkHandlerTable.main(arguments())));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }

        assertArrayEquals(new Throwable[]{closeFailure},primary.getSuppressed());
        String reason=failedReason();
        assertEquals("IllegalArgumentException",reason);
        var event=events.stream().filter(item->item.getMessage().getFormattedMessage().contains("库表核对失败"))
            .findFirst().orElseThrow();
        String diagnostic=event.getMessage().getFormattedMessage();
        assertTrue(diagnostic.contains("附加异常"),diagnostic);
        assertTrue(diagnostic.indexOf("LOCAL_DISK_FULL")>diagnostic.indexOf("附加异常"),diagnostic);
        assertFalse(diagnostic.contains(SECRET),diagnostic);
        assertNull(event.getThrown());
    }

    @Test void failureWriteErrorIsSuppressedOnTheOriginalFailure() throws Exception {
        var primary=new SQLException("query "+SECRET);
        var writeFailure=new AssertionError("write "+SECRET);
        READ_FAILURE.set(primary);
        WRITE_FAILURE.set(writeFailure);

        assertSame(primary,assertThrows(SQLException.class,()->SparkHandlerTable.main(arguments())));

        assertArrayEquals(new Throwable[]{writeFailure},primary.getSuppressed());
        assertEquals("2",taskValue("TASK_STATE"));
    }

    @Test void fatalFailurePreservesAnAlreadyCancelledTask() throws Exception {
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            statement.execute("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='5',FTASK_REASON='已取消'");
        }
        var primary=new AssertionError(SECRET);
        READ_FAILURE.set(primary);

        assertSame(primary,assertThrows(AssertionError.class,()->SparkHandlerTable.main(arguments())));

        assertEquals("5",taskValue("TASK_STATE"));
        assertEquals("已取消",taskValue("FTASK_REASON"));
    }

    @Test void failureWriteOfTheSameExceptionPreservesTheOriginalFailure() throws Exception {
        var primary=new SQLException(SECRET);
        READ_FAILURE.set(primary);
        WRITE_FAILURE.set(primary);

        assertSame(primary,assertThrows(SQLException.class,()->SparkHandlerTable.main(arguments())));

        assertEquals(0,primary.getSuppressed().length);
        assertEquals("2",taskValue("TASK_STATE"));
    }

    @Test void closeFailureAfterPublicationPreservesTheSuccessfulResult() throws Exception {
        var closeFailure=new IllegalStateException("close "+SECRET);
        installFailingClose(closeFailure);
        String[] args=arguments();
        args[1]=url;
        args[4]="org.h2.Driver";

        assertSame(closeFailure,assertThrows(IllegalStateException.class,()->SparkHandlerTable.main(args)));

        assertEquals("3",taskValue("TASK_STATE"),taskValue("FTASK_REASON"));
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=1")) {
            assertTrue(result.next());
            assertEquals(1,result.getLong(1));
            assertFalse(result.next());
        }
    }

    private String failedReason() throws Exception {
        assertEquals("4",taskValue("TASK_STATE"));
        String reason=taskValue("FTASK_REASON");
        assertNotNull(reason);
        return reason;
    }

    private String taskValue(String column) throws Exception {
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();
            var result=statement.executeQuery("SELECT "+column+" FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=1")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private String[] arguments() {
        String metadataUrl=url.replace("jdbc:h2:","jdbc:failure-test:");
        return new String[]{"failure-lifecycle",metadataUrl,"sa","",FaultDriver.class.getName(),"1000","1","20261119","failure-batch","D",
            url,"sa","","org.h2.Driver","(SELECT ID,VAL FROM FAILURE_OLD) old_src",
            url,"sa","","org.h2.Driver","(SELECT ID,VAL FROM FAILURE_NEW) new_src","20261119"};
    }

    private static void installFailingClose(RuntimeException failure) {
        SparkSession base=SparkSession.builder().appName("failing-close").getOrCreate();
        SparkSession session=new SparkSession(base.sparkContext()) {
            @Override public void close() {
                super.close();
                throw failure;
            }
        };
        SparkSession.setActiveSession(session);
        SparkSession.setDefaultSession(session);
    }

    public static final class FaultDriver implements Driver {
        @Override public Connection connect(String url,java.util.Properties properties) throws SQLException {
            if(!acceptsURL(url)) return null;
            Connection delegate=DriverManager.getConnection(url.replace("jdbc:failure-test:","jdbc:h2:"),"sa","");
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")) {
                    String sql=args[0].toString();
                    if(sql.startsWith("SELECT BCOMP_SAMPLE_LIMIT")&&READ_FAILURE.get()!=null) throw READ_FAILURE.get();
                    if(sql.startsWith("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4'")) {
                        WRITE_CONTEXT_ACTIVE.set(SparkSession.getDefaultSession().isDefined()
                            &&!SparkSession.getDefaultSession().get().sparkContext().isStopped());
                        if(WRITE_FAILURE.get()!=null) throw WRITE_FAILURE.get();
                    }
                }
                try { return method.invoke(delegate,args); }
                catch(InvocationTargetException error) { throw error.getCause(); }
            });
        }
        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:failure-test:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url,java.util.Properties properties) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
