package cn.sunline.tss.tssveri.spark;

import org.apache.spark.TaskContext;
import org.apache.spark.sql.SparkSession;

import java.io.EOFException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Top-level driver keeps Spark 3.4/3.5 binary and canonical class names identical. */
public final class MetadataLifecycleDriver implements Driver {
    @Override public Connection connect(String url,Properties properties) throws SQLException {
        if(!acceptsURL(url))return null;
        boolean metadata=url.startsWith("jdbc:phase-test:metadata:");
        String actual=url.substring((metadata?"jdbc:phase-test:metadata:":"jdbc:phase-test:business:").length());
        Scenario scenario=SCENARIOS.get(actual);
        if(scenario==null)throw new SQLException("Missing test scenario");
        if(metadata&&scenario.sourceCounts.get()>=2&&scenario.distributedReads.get()==0&&scenario.strategyOpenFailure!=null)throw scenario.strategyOpenFailure;
        Connection delegate=DriverManager.getConnection(actual,properties);
        var tracked=new TrackedConnection();tracked.openedAfterComparison=scenario.distributedReads.get()>0;
        if(metadata)scenario.connections.add(tracked);
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("close")) {
                try {
                    Object result=invoke(method,delegate,args);
                    if(metadata&&"STRATEGY".equals(tracked.role)&&scenario.strategyCloseFailure!=null)throw scenario.strategyCloseFailure;
                    return result;
                } finally {tracked.closed=true;}
            }
            if(metadata&&method.getName().equals("commit")&&"PUBLISH".equals(tracked.role)&&scenario.commitFailure!=null) {
                delegate.commit();throw scenario.commitFailure;
            }
            Object result=invoke(method,delegate,args);
            if(result instanceof Statement statement&&(method.getName().equals("prepareStatement")||method.getName().equals("createStatement"))) {
                String prepared=args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:null;
                Class<?> type=statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{type},(statementProxy,operation,parameters)->{
                    String sql=prepared!=null?prepared:parameters!=null&&parameters.length>0&&parameters[0] instanceof String?(String)parameters[0]:"";
                    String normalized=sql.toUpperCase(Locale.ROOT);
                    if(operation.getName().startsWith("execute")) {
                        if(metadata&&normalized.startsWith("SELECT BCOMP_MAX_PARTITION_NUM"))tracked.role="STARTUP";
                        if(metadata&&normalized.startsWith("UPDATE TSS_BCOMP_TASK_NEW SET BCOMP_READ_MODE")) {
                            if(!"STARTUP".equals(tracked.role))tracked.role="STRATEGY";
                            if(scenario.strategyFailure!=null)throw scenario.strategyFailure;
                        }
                        if(metadata&&normalized.startsWith("SELECT TASK_STATE")) {
                            scenario.publications.incrementAndGet();
                            if("STARTUP".equals(tracked.role))throw new SQLException(SECRET,"08006",new EOFException(SECRET));
                            tracked.role="PUBLISH";
                            if(scenario.stateBeforePublication!=null)try(var c=DriverManager.getConnection(actual,"sa","");var s=c.createStatement()) {
                                s.executeUpdate("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='"+scenario.stateBeforePublication+"'");
                            }
                        }
                        if(metadata&&normalized.startsWith("INSERT INTO TSS_BCOMP_RESULT_NEW")&&scenario.publicationFailure!=null)throw scenario.publicationFailure;
                        if(metadata&&normalized.startsWith("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4'"))scenario.failureWrittenBeforeSparkClose=SparkSession.getDefaultSession().isDefined()&&!SparkSession.getDefaultSession().get().sparkContext().isStopped();
                        if(!metadata&&normalized.startsWith("SELECT COUNT(*)")) {
                            scenario.sourceCounts.incrementAndGet();scenario.checkClosed("COUNT","STARTUP");
                        }
                        if(!metadata&&TaskContext.get()!=null) {
                            scenario.distributedReads.incrementAndGet();scenario.checkClosed("COMPARE","STARTUP","STRATEGY");
                        }
                    }
                    return invoke(operation,statement,parameters);
                });
            }
            return result;
        });
    }
    private static Object invoke(java.lang.reflect.Method method,Object target,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    @Override public boolean acceptsURL(String url){return url.startsWith("jdbc:phase-test:metadata:")||url.startsWith("jdbc:phase-test:business:");}
    @Override public DriverPropertyInfo[] getPropertyInfo(String url,Properties properties){return new DriverPropertyInfo[0];}
    @Override public int getMajorVersion(){return 1;}
    @Override public int getMinorVersion(){return 0;}
    @Override public boolean jdbcCompliant(){return false;}
    @Override public Logger getParentLogger(){return Logger.getGlobal();}
    static final String SECRET="synthetic-password-jdbc:secret-SELECT-business-value";
    static final Map<String,Scenario> SCENARIOS=new ConcurrentHashMap<>();
    static final class TrackedConnection {
        volatile String role="OTHER";volatile boolean closed,openedAfterComparison;
    }
    static final class Scenario {
        final List<TrackedConnection> connections=new CopyOnWriteArrayList<>();
        final List<String> violations=new CopyOnWriteArrayList<>();
        final AtomicInteger sourceCounts=new AtomicInteger(),distributedReads=new AtomicInteger(),publications=new AtomicInteger();
        volatile SQLException publicationFailure,commitFailure,strategyFailure,strategyOpenFailure,strategyCloseFailure;
        volatile String stateBeforePublication;
        volatile Boolean failureWrittenBeforeSparkClose;
        void checkClosed(String stage,String... roles) {
            for(var connection:connections) if(Arrays.asList(roles).contains(connection.role)&&!connection.closed) violations.add(stage+":"+connection.role);
        }
    }

}
