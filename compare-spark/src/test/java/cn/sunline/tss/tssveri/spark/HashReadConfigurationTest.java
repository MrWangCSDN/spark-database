package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Logger;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HashReadConfigurationTest {
    @Test void invalidHashCountsNeverFallBackToAdaptiveLegacyDefaults() throws Exception {
        String url="jdbc:h2:mem:conf_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try (Connection c=DriverManager.getConnection(url,"sa",""); Statement s=c.createStatement()) {
            s.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX int, BCOMP_PARTITION_NUM decimal(30,2), BCOMP_SHARD_STRATEGY varchar(10))");
            s.execute("insert into TSS_BCOMP_CONF_NEW values(1,1,'HASH')");
            String[] args = args(url);
            for (String invalid : List.of("0","-1","257","1.5","999999999999")) {
                s.execute("update TSS_BCOMP_CONF_NEW set BCOMP_PARTITION_NUM="+invalid);
                assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.readShardConf(args), invalid);
            }
            s.execute("update TSS_BCOMP_CONF_NEW set BCOMP_PARTITION_NUM=1");
            assertEquals(1,SparkHandlerTable.readShardConf(args).partitionNum);
            s.execute("alter table TSS_BCOMP_CONF_NEW drop column BCOMP_PARTITION_NUM");
            assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.readShardConf(args));
        }
    }
    @Test void missingStrategyAndLegacyStrategyBothUseHashOnly() throws Exception {
        String url="jdbc:h2:mem:legacy_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try (Connection c=DriverManager.getConnection(url,"sa",""); Statement s=c.createStatement()) {
            s.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX int, BCOMP_PARTITION_NUM int)");
            s.execute("insert into TSS_BCOMP_CONF_NEW values(1,null)");
            var legacy=SparkHandlerTable.readShardConf(args(url));
            assertEquals(1,legacy.partitionNum);
            s.execute("alter table TSS_BCOMP_CONF_NEW add BCOMP_SHARD_STRATEGY varchar(10) default 'NTILE'");
            s.execute("update TSS_BCOMP_CONF_NEW set BCOMP_PARTITION_NUM=12");
            var ntile=SparkHandlerTable.readShardConf(args(url));
            assertEquals(12,ntile.partitionNum);
        }
    }
    @Test void obsoleteStrategyColumnDoesNotControlReads() throws Exception {
        String url="jdbc:h2:mem:strategy_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try (Connection c=DriverManager.getConnection(url,"sa",""); Statement statement=c.createStatement()) {
            statement.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX int, BCOMP_PARTITION_NUM int, BCOMP_SHARD_STRATEGY varchar(10))");
            statement.execute("insert into TSS_BCOMP_CONF_NEW values(1,4,'HSAH')");
            assertEquals(4,SparkHandlerTable.readShardConf(args(url)).partitionNum);
        }
    }
    @Test void strategyPermissionFailureCannotMasqueradeAsMissingLegacyColumn() throws Exception {
        String url="jdbc:h2:mem:permission_"+UUID.randomUUID();
        try (Connection c=DriverManager.getConnection(url,"sa",""); Statement statement=c.createStatement()) {
            statement.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX int, BCOMP_PARTITION_NUM int, BCOMP_SHARD_STRATEGY varchar(10))");
            statement.execute("insert into TSS_BCOMP_CONF_NEW values(1,4,'HASH')");
            statement.execute("create user RESTRICTED password 'test'");
            String[] restricted=args(url); restricted[2]="RESTRICTED"; restricted[3]="test";
            assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.readShardConf(restricted));
        }
    }
    @Test void predicateConnectionsCarryTimeoutsAndMysqlStreamingCursorProperties() throws Exception {
        var builder=SparkHandlerTable.class.getDeclaredMethod("buildConnProps",String.class,String.class,String.class);
        builder.setAccessible(true);
        for (String driver : List.of("com.mysql.cj.jdbc.Driver","com.mysql.jdbc.Driver","com.oceanbase.jdbc.Driver")) {
            Properties props=(Properties) builder.invoke(null,"user","password",driver);
            assertEquals("true",props.getProperty("useCursorFetch"),driver);
            assertEquals("1000",props.getProperty("fetchsize"),driver);
            assertEquals("60000",props.getProperty("connectTimeout"),driver);
            assertEquals("300000",props.getProperty("socketTimeout"),driver);
        }
        Properties postgres=(Properties) builder.invoke(null,"user","password","org.postgresql.Driver");
        assertNull(postgres.getProperty("useCursorFetch"));
        assertEquals("60000",postgres.getProperty("connectTimeout"));
        assertEquals("300000",postgres.getProperty("socketTimeout"));
    }
    @Test void hashPlanUsesEverySideSpecificKeyWithoutAPartitionFlagColumn() throws Exception {
        String url="jdbc:h2:mem:plan_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        String[] args=args(url);
        args[14]="(select 7 as \"old id\",'tenant' as \"old tenant\") old_src";
        args[19]="(select 'tenant' as \"new tenant\",7 as \"new id\") new_src";
        var plan=SparkHandlerTable.resolveReadPlan(args,List.of(key("1","1","2"),key("1","2","1")),new SparkHandlerTable.ShardConf(4));
        assertTrue(plan.oldSide.usePredicates); assertTrue(plan.newSide.usePredicates);
        assertEquals(4,plan.oldSide.predicates.length); assertEquals(4,plan.newSide.predicates.length);
        assertEquals("1000",plan.oldSide.connProps.getProperty("fetchsize"));
        assertEquals("60000",plan.oldSide.connProps.getProperty("connectTimeout"));
        assertEquals("300000",plan.oldSide.connProps.getProperty("socketTimeout"));
        assertTrue(plan.oldSide.predicates[0].contains("\"old id\""));
        assertTrue(plan.oldSide.predicates[0].contains("\"old tenant\""));
        assertTrue(plan.newSide.predicates[0].contains("\"new id\""));
        assertTrue(plan.newSide.predicates[0].contains("\"new tenant\""));
        var one=SparkHandlerTable.resolveReadPlan(args,List.of(key("1","1","2")),new SparkHandlerTable.ShardConf(1));
        assertFalse(one.oldSide.usePredicates); assertFalse(one.oldSide.options.containsKey("numPartitions"));
    }
    @Test void missingAndFractionalKeysFailBeforeAnySourceConnection() {
        String[] args=args("jdbc:missing");
        for (List<Row> fields : List.of(List.of(key("0","1","1")),List.of(key("1","1.5","1")),List.of(key("1","1","0")))) {
            assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.resolveReadPlan(args,fields,new SparkHandlerTable.ShardConf(1)));
        }
    }
    @Test void unavailableVendorHashFunctionEscapesLegacySerialFallback() {
        String[] args=args("jdbc:hash-probe:test");
        args[13]=UnavailableHashDriver.class.getName();
        args[18]=UnavailableHashDriver.class.getName();
        args[14]="(select 1 as ID) src";
        args[19]="(select 1 as ID) src";
        SQLException error=assertThrows(SQLException.class, () -> SparkHandlerTable.resolveReadPlan(args,
                List.of(key("1","1","1")),new SparkHandlerTable.ShardConf(4)));
        assertTrue(error.getMessage().toUpperCase(Locale.ROOT).contains("HASHTEXT"));
    }
    /** Real H2 SQL execution with only vendor metadata overridden, so the advertised function really fails. */
    public static class UnavailableHashDriver implements Driver {
        static {
            try { DriverManager.registerDriver(new UnavailableHashDriver()); }
            catch (SQLException e) { throw new ExceptionInInitializerError(e); }
        }
        public Connection connect(String url, Properties properties) throws SQLException {
            if (!acceptsURL(url)) return null;
            Connection actual=DriverManager.getConnection("jdbc:h2:mem:probe_"+UUID.randomUUID());
            DatabaseMetaData metadata=actual.getMetaData();
            DatabaseMetaData advertised=(DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy,method,arguments) -> {
                        if (method.getName().equals("getDatabaseProductName")) return "PostgreSQL";
                        try { return method.invoke(metadata,arguments); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                    (proxy,method,arguments) -> {
                        if (method.getName().equals("getMetaData")) return advertised;
                        try { return method.invoke(actual,arguments); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        }
        public boolean acceptsURL(String url) { return url.startsWith("jdbc:hash-probe:"); }
        public DriverPropertyInfo[] getPropertyInfo(String url,Properties props) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
    private static Row key(String flag,String oldIndex,String newIndex) {
        StructType schema=new StructType().add("FIELD_INDEX_FLAG",DataTypes.StringType)
                .add("FIELD_OLD_INDEX",DataTypes.createDecimalType(10,2)).add("FIELD_NEW_INDEX",DataTypes.createDecimalType(10,2));
        return new GenericRowWithSchema(new Object[]{flag,new BigDecimal(oldIndex),new BigDecimal(newIndex)},schema);
    }
    private static String[] args(String url) {
        String[] args=new String[21]; Arrays.fill(args,"");
        args[1]=url; args[2]="sa"; args[4]="org.h2.Driver"; args[6]="1";
        args[10]=url; args[11]="sa"; args[13]="org.h2.Driver";
        args[15]=url; args[16]="sa"; args[18]="org.h2.Driver";
        return args;
    }
}
