package cn.sunline.tss.tssveri.spark;

import org.apache.spark.sql.SparkSession;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Both conditions overlap on ID=2; each configuration must count and publish that row independently. */
class MultiConfigurationComparisonTest {
    @Test void comparesOnePhysicalTableTwiceAndKeepsBothDailyPublicationsIndependent() throws Exception {
        String url="jdbc:h2:mem:multi_configuration_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var connection=DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()) {
            try(var reader=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {RunScript.execute(connection,reader);}
            sql.execute("CREATE SCHEMA ENV_OLD");sql.execute("CREATE SCHEMA ENV_NEW");
            for(String schema:List.of("ENV_OLD","ENV_NEW")) {
                sql.execute("CREATE TABLE "+schema+".A(ID VARCHAR(10),AMOUNT VARCHAR(20),FLAG_A INT,FLAG_B INT)");
                sql.execute("INSERT INTO "+schema+".A VALUES('1','100',1,0),('2','200',1,2),('3','300',0,2),('4','400',0,0)");
            }
            sql.execute("UPDATE ENV_NEW.A SET AMOUNT='201' WHERE ID='2'");
            sql.execute("UPDATE ENV_NEW.A SET AMOUNT='301' WHERE ID='3'");
            sql.execute("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'旧环境'),(2,'新环境')");
            sql.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_MODULE,BCOMP_SAMPLE_LIMIT,SOURCE_TABLE_NAME,CONFIGURATION_NAME,CONFIGURATION_SCOPE) VALUES(1,'a','dept',100,'a','配置甲','FLAG_A=1'),(2,'a_1','dept',-1,'a','配置乙','FLAG_B=2')");
            for(int index=1;index<=2;index++) {
                sql.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID) VALUES("+index+",1,2)");
                sql.execute("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_IGNORE_FLAG) VALUES("+index+",'ID','1',1,1,'1','0'),("+index+",'AMOUNT','1',2,2,'0','0')");
                sql.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES("+index+",'20261126','first','D','2')");
                run(url,index,"first",index==1?"FLAG_A=1":"FLAG_B=2");
            }
            try(var rows=sql.executeQuery("SELECT CONFIGURATION_NAME,SOURCE_TABLE_NAME,CONFIGURATION_DISPLAY_NAME,CONFIGURATION_SCOPE,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,DIFFERENT_COUNT FROM TSS_BCOMP_RESULT_NEW ORDER BY BCOMP_INDEX")) {
                assertTrue(rows.next());assertEquals("a",rows.getString(1));assertEquals("a",rows.getString(2));
                assertEquals("配置甲",rows.getString(3));assertEquals("FLAG_A=1",rows.getString(4));
                assertEquals(2,rows.getLong(5));assertEquals(2,rows.getLong(6));assertEquals(1,rows.getLong(7));assertEquals(1,rows.getLong(8));
                assertTrue(rows.next());assertEquals("a_1",rows.getString(1));assertEquals("a",rows.getString(2));
                assertEquals("配置乙",rows.getString(3));assertEquals("FLAG_B=2",rows.getString(4));
                assertEquals(2,rows.getLong(5));assertEquals(2,rows.getLong(6));assertEquals(0,rows.getLong(7));assertEquals(2,rows.getLong(8));
                assertFalse(rows.next());
            }
            try(var rows=sql.executeQuery("SELECT BCOMP_INDEX,FIELD_NAME,DIFFERENT_COUNT FROM TSS_BCOMP_FIELD_COUNT_NEW ORDER BY BCOMP_INDEX")) {
                assertTrue(rows.next());assertEquals(1,rows.getLong(1));assertEquals("AMOUNT",rows.getString(2));assertEquals(1,rows.getLong(3));
                assertTrue(rows.next());assertEquals(2,rows.getLong(1));assertEquals("AMOUNT",rows.getString(2));assertEquals(2,rows.getLong(3));assertFalse(rows.next());
            }
            sql.execute("UPDATE ENV_OLD.A SET AMOUNT='201' WHERE ID='2'");
            sql.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261126','retry','D','2')");
            run(url,1,"retry","FLAG_A=1");
            try(var rows=sql.executeQuery("SELECT BCOMP_INDEX,TASK_BATCH,MATCHED_COUNT,DIFFERENT_COUNT FROM TSS_BCOMP_RESULT_NEW ORDER BY BCOMP_INDEX")) {
                assertTrue(rows.next());assertEquals("retry",rows.getString(2));assertEquals(2,rows.getLong(3));assertEquals(0,rows.getLong(4));
                assertTrue(rows.next());assertEquals("first",rows.getString(2));assertEquals(0,rows.getLong(3));assertEquals(2,rows.getLong(4));assertFalse(rows.next());
            }
        }
    }

    private static void run(String url,int index,String batch,String condition) throws Exception {
        var previous=new HashMap<String,String>();
        Map.of("spark.master","local[2]","spark.ui.enabled","false","spark.driver.host","127.0.0.1",
            "spark.driver.bindAddress","127.0.0.1","spark.default.parallelism","2","spark.sql.shuffle.partitions","2")
            .forEach((key,value)->{previous.put(key,System.getProperty(key));System.setProperty(key,value);});
        String origin="(SELECT ID,AMOUNT FROM ENV_OLD.A WHERE "+condition+") orig";
        String destination="(SELECT ID,AMOUNT FROM ENV_NEW.A WHERE "+condition+") dest";
        try {
            SparkHandlerTable.main(new String[]{"multi-"+index,url,"sa","","org.h2.Driver","100",Integer.toString(index),"20261126",batch,"D",
                url,"sa","","org.h2.Driver",origin,url,"sa","","org.h2.Driver",destination,"20261126"});
        } finally {
            if(SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
            SparkSession.clearActiveSession();SparkSession.clearDefaultSession();
            previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
        }
    }
}
