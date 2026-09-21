package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NewRuleTablesTest {
    @Test void readsEnumAndIgnoreRulesFromOnlyNewTables() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:h2:mem:rules_new", "sa", "")) {
            try (var s = conn.createStatement()) {
                s.execute("CREATE TABLE TSS_BCOMP_ENUM_NEW(CONV_ENUM_INDEX BIGINT,ORIG_ENUM_VALUE VARCHAR(200),DEST_ENUM_VALUE VARCHAR(200))");
                s.execute("INSERT INTO TSS_BCOMP_ENUM_NEW VALUES(1,'A','B'),(1,'C','D')");
                s.execute("CREATE TABLE TSS_IGNORE_VALUE_NEW(IGNORE_ID BIGINT,IGNORE_VALUE VARCHAR(200))");
                s.execute("INSERT INTO TSS_IGNORE_VALUE_NEW VALUES(2,'NA'),(2,'NONE')");
            }
            var enums = SparkHandlerTable.class.getDeclaredMethod("loadEnumMap", Connection.class, String.class);
            enums.setAccessible(true);
            assertEquals(Map.of(1, Map.of("A", "B", "C", "D")), enums.invoke(null, conn, "1"));
            var ignores = SparkHandlerTable.class.getDeclaredMethod("loadIgnoreMap", Connection.class, String.class);
            ignores.setAccessible(true);
            assertEquals(Map.of(2, Set.of("NA", "NONE")), ignores.invoke(null, conn, "2"));
        }
    }
}
