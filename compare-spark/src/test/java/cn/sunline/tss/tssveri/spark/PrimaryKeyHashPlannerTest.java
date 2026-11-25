package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class PrimaryKeyHashPlannerTest {
    @Test void gaussHashReadsEveryNullableKeyWhenEmptyStringsAreNull() throws Exception {
        assertCompatibilityCoverage("Oracle", "GaussDB");
    }

    @Test void gaussHashStillReadsEveryKeyWithPostgresStringSemantics() throws Exception {
        assertCompatibilityCoverage("PostgreSQL", "GaussDB");
    }

    @Test void h2HashReadsEveryNullableKeyWhenEmptyStringsAreNull() throws Exception {
        assertCompatibilityCoverage("Oracle", null);
    }

    @Test void rejectsNullNegativeAndOutOfRangeProbeBuckets() throws Exception {
        for(String bucket : List.of("CAST(NULL AS INTEGER)", "-1", "16")) {
            try(Connection c=DriverManager.getConnection("jdbc:h2:mem:bad_bucket_"+UUID.randomUUID())) {
                Connection faulty=connectionView(c,null,bucket);
                assertThrows(SQLException.class,()->PrimaryKeyHashPlanner.predicates(
                    faulty,"(select 1 as ID) src",List.of(1),16),"invalid probe bucket: "+bucket);
            }
        }
    }

    @Test void emptyTableStillHasAValidPartitionPlan() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:empty_hash_"+UUID.randomUUID()+";MODE=Oracle");
            Statement s=c.createStatement()) {
            s.execute("create table DATA(ROW_ID int, K1 bigint, K2 varchar(100))");
            String[] predicates=PrimaryKeyHashPlanner.predicates(c,"DATA",List.of(2,3),16);
            assertEquals(16,predicates.length);
            for(String predicate:predicates) try(ResultSet rows=s.executeQuery("select ROW_ID from DATA where "+predicate)) {
                assertFalse(rows.next());
            }
        }
    }

    private static void assertCompatibilityCoverage(String mode,String product) throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:compat_hash_"+UUID.randomUUID()+";MODE="+mode);
            Statement s=c.createStatement()) {
            // The database executes the real predicates. Only the external PG hash function is supplied here;
            // like hashtext, it propagates SQL NULL and returns a signed integer.
            s.execute("CREATE ALIAS HASHTEXT FOR 'cn.sunline.tss.tssveri.spark.PrimaryKeyHashPlannerTest.strictHashtext'");
            s.execute("create table DATA(ROW_ID int, K1 bigint, K2 varchar(100))");
            s.execute("create unique index DATA_KEY on DATA(K1,K2)");
            // Keep a normal row first: a successful one-row probe must not hide later NULL-key omissions.
            s.execute("insert into DATA values (1,100,'normal'),(2,null,'normal'),(3,100,null),"
                +"(4,null,null),(5,-7,''),(6,-9223372036854775808,' '),"
                +"(7,200,'polygenelubricants'),(8,200,'Aa'),(9,201,'BB'),(10,101,null),(11,102,null)");
            try(ResultSet r=s.executeQuery("select case when '' is null then 1 else 0 end")) {
                assertTrue(r.next());assertEquals("Oracle".equals(mode)?1:0,r.getInt(1));
            }
            Connection database=product==null?c:connectionView(c,product,null);
            String[] predicates=PrimaryKeyHashPlanner.predicates(database,"DATA",List.of(2,3),16);
            assertEquals(16,predicates.length);
            Map<Integer,Integer> seen=new TreeMap<>();
            for(String predicate:predicates) try(ResultSet rows=s.executeQuery("select ROW_ID from DATA where "+predicate)) {
                while(rows.next()) seen.merge(rows.getInt(1),1,Integer::sum);
            }
            assertEquals(Map.ofEntries(Map.entry(1,1),Map.entry(2,1),Map.entry(3,1),Map.entry(4,1),
                Map.entry(5,1),Map.entry(6,1),Map.entry(7,1),Map.entry(8,1),Map.entry(9,1),
                Map.entry(10,1),Map.entry(11,1)),seen,"every source row must appear in exactly one bucket");
        }
    }

    public static Integer strictHashtext(String value) { return value==null?null:value.hashCode(); }

    /** Keep JDBC execution real; adapt product metadata or inject an invalid one-row probe result. */
    private static Connection connectionView(Connection connection,String product,String probeBucket) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(connection,method,args);
            if(method.getName().equals("getMetaData")&&product!=null) {
                return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},
                    (meta,m,a)->m.getName().equals("getDatabaseProductName")?product:invoke(result,m,a));
            }
            if(method.getName().equals("createStatement")&&probeBucket!=null) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(stmt,m,a)->{
                    if(m.getName().equals("executeQuery")&&((String)a[0]).startsWith("SELECT MOD("))
                        return ((Statement)result).executeQuery("SELECT "+probeBucket);
                    return invoke(result,m,a);
                });
            }
            return result;
        });
    }

    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch(InvocationTargetException error) { throw error.getCause(); }
    }

    @Test void predicatesReadEveryNullNegativeAndCompositeStringKeyExactlyOnce() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:hash_"+UUID.randomUUID());
             Statement s = c.createStatement()) {
            s.execute("create table DATA(ROW_ID int, K1 bigint, K2 varchar(100))");
            s.execute("insert into DATA values (1,null,null),(2,-9223372036854775808,'中文'),"
                    + "(3,-1,''),(4,0,'a|b'),(5,1,'a'),(6,1,'b'),(7,null,'x'),(8,9,null)");
            String source = "(select ROW_ID,K1 as \"key one\",K2 as \"key\"\"two\" from DATA) src";
            String[] predicates = PrimaryKeyHashPlanner.predicates(c, source, List.of(2,3), 7);
            assertEquals(7, predicates.length);
            Map<Integer,Integer> seen = new TreeMap<>();
            Set<Integer> secondKeyBuckets = new HashSet<>();
            for (int i=0; i<predicates.length; i++) {
                try (ResultSet rs = s.executeQuery("select ROW_ID from "+source+" where "+predicates[i])) {
                    while (rs.next()) {
                        int id = rs.getInt(1);
                        seen.merge(id,1,Integer::sum);
                        if (id==5 || id==6) secondKeyBuckets.add(i);
                    }
                }
            }
            assertEquals(Map.of(1,1,2,1,3,1,4,1,5,1,6,1,7,1,8,1),seen);
            assertEquals(2,secondKeyBuckets.size(),"second composite key must affect bucketing");
        }
    }
    @Test void rejectsInvalidCountsAndMissingOrInvalidKeyPositions() throws Exception {
        try (Connection c=DriverManager.getConnection("jdbc:h2:mem:validation")) {
            for (int count : new int[]{0,-1,257})
                assertThrows(IllegalArgumentException.class, () -> PrimaryKeyHashPlanner.predicates(c,"(select 1 as ID) src",List.of(1),count));
            assertThrows(IllegalArgumentException.class, () -> PrimaryKeyHashPlanner.predicates(c,"(select 1 as ID) src",List.of(),1));
            assertThrows(IllegalArgumentException.class, () -> PrimaryKeyHashPlanner.predicates(c,"(select 1 as ID) src",List.of(2),2));
            assertThrows(IllegalArgumentException.class, () -> PrimaryKeyHashPlanner.predicates(c,"(select 1 as ID) src",List.of(0),2));
        }
    }
    @Test void productMetadataControlsDialectAndAmbiguousOceanBaseFails() {
        assertEquals(PrimaryKeyHashPlanner.Dialect.POSTGRES,PrimaryKeyHashPlanner.dialect("PostgreSQL","jdbc:postgresql:x",null));
        assertEquals(PrimaryKeyHashPlanner.Dialect.POSTGRES,PrimaryKeyHashPlanner.dialect("GaussDB","jdbc:gaussdb:x",null));
        assertEquals(PrimaryKeyHashPlanner.Dialect.POSTGRES,PrimaryKeyHashPlanner.dialect("openGauss","jdbc:opengauss:x",null));
        assertEquals(PrimaryKeyHashPlanner.Dialect.ORACLE,PrimaryKeyHashPlanner.dialect("Oracle","jdbc:oracle:x",null));
        assertEquals(PrimaryKeyHashPlanner.Dialect.MYSQL,PrimaryKeyHashPlanner.dialect("MySQL","jdbc:mysql:x",null));
        assertEquals(PrimaryKeyHashPlanner.Dialect.ORACLE,PrimaryKeyHashPlanner.dialect("OceanBase","jdbc:oceanbase:x","ORACLE"));
        assertEquals(PrimaryKeyHashPlanner.Dialect.MYSQL,PrimaryKeyHashPlanner.dialect("OceanBase","jdbc:oceanbase:x","MYSQL"));
        assertThrows(IllegalArgumentException.class, () -> PrimaryKeyHashPlanner.dialect("Unknown","jdbc:postgresql:x",null));
        assertThrows(IllegalArgumentException.class, () -> PrimaryKeyHashPlanner.dialect("OceanBase","jdbc:oceanbase:x",null));
    }

    @Test void gdbAndMysqlUseCrc32HashDialect() {
        assertEquals(PrimaryKeyHashPlanner.Dialect.MYSQL, PrimaryKeyHashPlanner.dialect("GDB", "jdbc:gdb:x", null));
        assertEquals(PrimaryKeyHashPlanner.Dialect.MYSQL, PrimaryKeyHashPlanner.dialect("MySQL", "jdbc:mysql:x", null));
        String expression = PrimaryKeyHashPlanner.Dialect.MYSQL.hash("`ID`");
        assertTrue(expression.contains("CRC32"));
        assertTrue(expression.contains("COALESCE"));
        assertTrue(expression.contains("CAST(`ID` AS CHAR)"));
    }
    @Test void singlePartitionNeedsNoHashFunctionOrDialect() throws Exception {
        try (Connection c=DriverManager.getConnection("jdbc:h2:mem:single")) {
            assertNull(PrimaryKeyHashPlanner.predicates(c,"(select 1 as ID) src",List.of(1),1));
        }
    }
}
