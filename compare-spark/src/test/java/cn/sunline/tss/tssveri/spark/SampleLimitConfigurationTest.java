package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SampleLimitConfigurationTest {
    @Test void incrementalMigrationPreservesExistingConfigurationFallback() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:sample_limit_migration"); Statement s=c.createStatement()) {
            s.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX bigint primary key)");
            s.execute("insert into TSS_BCOMP_CONF_NEW values(42)");
            try(var script=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("../sql/add_bcomp_sample_limit_new.sql"))) {
                org.h2.tools.RunScript.execute(c,script);
            }
            assertEquals(71,SparkHandlerTable.readSampleLimit(c,42,"71"));
            try(var rows=s.executeQuery("select BCOMP_SAMPLE_LIMIT from TSS_BCOMP_CONF_NEW where BCOMP_INDEX=42")) {
                assertTrue(rows.next()); assertNull(rows.getObject(1));
            }
        }
    }

    @Test void resolvesByIndexAndValidatesOnlyTheChosenValue() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:sample_limits"); Statement s=c.createStatement()) {
            s.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX bigint,BCOMP_SAMPLE_LIMIT decimal(30,2))");
            s.execute("insert into TSS_BCOMP_CONF_NEW values(1,null),(2,0),(3,37),(4,10000)");
            assertEquals(1000,SparkHandlerTable.readSampleLimit(c,1,"1000"));
            assertEquals(19,SparkHandlerTable.readSampleLimit(c,1,"19"));
            assertEquals(0,SparkHandlerTable.readSampleLimit(c,2,"1000"));
            assertEquals(37,SparkHandlerTable.readSampleLimit(c,3,"invalid-unused-fallback"));
            assertEquals(10000,SparkHandlerTable.readSampleLimit(c,4,"1000"));
            s.execute("update TSS_BCOMP_CONF_NEW set BCOMP_SAMPLE_LIMIT=-1 where BCOMP_INDEX=3");
            assertEquals(-1,SparkHandlerTable.readSampleLimit(c,3,"1000"));
            assertThrows(IllegalArgumentException.class,()->SparkHandlerTable.readSampleLimit(c,1,"-1"));
            for(String invalid:List.of("-2","10001","1.5","999999999999999999")) {
                s.execute("update TSS_BCOMP_CONF_NEW set BCOMP_SAMPLE_LIMIT="+invalid+" where BCOMP_INDEX=3");
                assertThrows(IllegalArgumentException.class,()->SparkHandlerTable.readSampleLimit(c,3,"1000"));
                assertThrows(IllegalArgumentException.class,()->SparkHandlerTable.readSampleLimit(c,1,invalid));
            }
            assertThrows(IllegalArgumentException.class,()->SparkHandlerTable.readSampleLimit(c,99,"1000"));
            s.execute("alter table TSS_BCOMP_CONF_NEW drop column BCOMP_SAMPLE_LIMIT");
            assertThrows(SQLException.class,()->SparkHandlerTable.readSampleLimit(c,1,"1000"));
        }
    }
}
