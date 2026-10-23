package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimaryKeyHashPlannerTest {
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
