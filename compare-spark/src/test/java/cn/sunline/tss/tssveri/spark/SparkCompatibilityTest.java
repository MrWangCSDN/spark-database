package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import org.apache.spark.sql.SparkSession;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SparkCompatibilityTest {
    @Test void rejectsIncompleteContractBeforeStartingSpark() {
        assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.main(new String[]{"job"}));
    }
    @Test void comparesLegacyTablesAndWritesOriginalDetailCodes() throws Exception {
        String url="jdbc:h2:mem:spark_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            s.execute("create table TSS_BCOMP_CONF(BCOMP_INDEX int, BCOMP_PARTITION_NUM int, BCOMP_SHARD_STRATEGY varchar(10))");
            s.execute("insert into TSS_BCOMP_CONF values(1,2,'RANGE')");
            s.execute("create table TSS_BCOMP_FIELD(BCOMP_INDEX int, FIELD_NAME varchar(30), FIELD_TYPE varchar(1), FIELD_OLD_INDEX decimal(10), FIELD_NEW_INDEX decimal(10), FIELD_INDEX_FLAG varchar(1), FIELD_PARTITION_FLAG varchar(1), FIELD_ENUM_INDEX decimal(10), FIELD_IGNORE_VALUE varchar(30), FIELD_IGNORE_FLAG varchar(1))");
            s.execute("insert into TSS_BCOMP_FIELD values(1,'ID','1',1,1,'1','1',null,null,'0'),(1,'VAL','1',2,2,'0','0',null,null,'0')");
            s.execute("create table TSS_BCOMP_TASK(BCOMP_INDEX int, TASK_BUSI_DATE varchar(8), TASK_BATCH varchar(10), BCOMP_TIME_NODE varchar(10), TASK_STATE varchar(1), TASK_RESULT varchar(1), FTASK_REASON varchar(2000), FILE1_ACOUNT bigint,FILE2_ACOUNT bigint,FILE1_SCOUNT bigint,FILE2_SCOUNT bigint,FILE1_FCOUNT bigint,FILE2_FCOUNT bigint,END_DATETIME varchar(14),BCOMP_READ_MODE varchar(1000))");
            s.execute("insert into TSS_BCOMP_TASK(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(1,'20260920','1','A','2')");
            s.execute("create table TSS_BCOMP_DTL(BCOMP_INDEX int,TASK_DATE varchar(8),TASK_BATCH varchar(10),COMP_INDEX_STR varchar(1000),LINE_CONTENT1 varchar(2000),LINE_CONTENT2 varchar(2000),LINE_RESULT varchar(1),FDTL_REASON varchar(2000))");
            s.execute("create table TSS_BCOMP_DIFFFIELD(BCOMP_INDEX int)");
            s.execute("create table OLD_DATA(ID int,VAL varchar(100))");
            s.execute("create table NEW_DATA(ID int,VAL varchar(100))");
            s.execute("insert into OLD_DATA values(1,'same'),(2,'old'),(3,'missing-new'),(5,'a'),(5,'b')");
            s.execute("insert into NEW_DATA values(1,'same'),(2,'new'),(4,'missing-old'),(5,'a'),(5,'c')");
            // 由原main创建SparkSession，保证原Kryo/内存配置也参与验证。
            var properties=Map.of("spark.master","local[2]", "spark.ui.enabled","false",
                "spark.driver.host","127.0.0.1", "spark.driver.bindAddress","127.0.0.1",
                "spark.default.parallelism","2", "spark.sql.shuffle.partitions","2");
            var previous=new HashMap<String,String>();
            properties.forEach((key,value) -> { previous.put(key,System.getProperty(key)); System.setProperty(key,value); });
            try {
                SparkHandlerTable.main(new String[]{"compat",url,"sa","","org.h2.Driver","2","1","20260920","1","A",url,"sa","","org.h2.Driver","OLD_DATA",url,"sa","","org.h2.Driver","NEW_DATA","20260919"});
            } finally {
                if(SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
                SparkSession.clearActiveSession(); SparkSession.clearDefaultSession();
                previous.forEach((key,value) -> { if(value==null) System.clearProperty(key); else System.setProperty(key,value); });
            }
            try(var r=s.executeQuery("select * from TSS_BCOMP_TASK")) {
                assertTrue(r.next()); assertEquals("3",r.getString("TASK_STATE")); assertEquals("1",r.getString("TASK_RESULT"));
                assertEquals(5,r.getLong("FILE1_ACOUNT")); assertEquals(5,r.getLong("FILE2_ACOUNT"));
            }
            var codes=new HashMap<String,Integer>(); boolean separator=false, duplicateHash=false;
            try(var r=s.executeQuery("select * from TSS_BCOMP_DTL")) { while(r.next()) {
                codes.merge(r.getString("LINE_RESULT"),1,Integer::sum);
                separator |= Objects.toString(r.getString("LINE_CONTENT1"),"").contains("|+|");
                duplicateHash |= r.getString("COMP_INDEX_STR").startsWith("[5]#");
            }}
            assertEquals(Map.of("4",1,"2",2,"3",2),codes);
            assertTrue(separator); assertTrue(duplicateHash);
        }
    }
}
