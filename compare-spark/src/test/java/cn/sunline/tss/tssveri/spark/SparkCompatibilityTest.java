package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerStageSubmitted;
import java.util.concurrent.CopyOnWriteArrayList;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SparkCompatibilityTest {
    @Test void rejectsIncompleteContractBeforeStartingSpark() {
        assertThrows(IllegalArgumentException.class, () -> SparkHandlerTable.main(new String[]{"job"}));
    }
    @Test void comparesLegacyTablesAndWritesOriginalDetailCodes() throws Exception {
        compareTables("RANGE", 2);
    }
    @Test void legacyNtileStillComparesWithPredicateReads() throws Exception {
        compareTables("NTILE", 2);
    }
    @Test void hashReadsWithoutPartitionFlagsAndUsesSideSpecificCompositeAliases() throws Exception {
        compareTables("HASH", 4);
    }
    @Test void hashOneUsesSingleRead() throws Exception {
        compareTables("HASH", 1);
    }
    private void compareTables(String strategy, int partitions) throws Exception {
        String url="jdbc:h2:mem:spark_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            s.execute("create table TSS_BCOMP_CONF_NEW(BCOMP_INDEX int, BCOMP_PARTITION_NUM int, BCOMP_SHARD_STRATEGY varchar(10))");
            s.execute("insert into TSS_BCOMP_CONF_NEW values(1,"+partitions+",'"+strategy+"')");
            s.execute("create table TSS_BCOMP_FIELD_NEW(BCOMP_INDEX int, FIELD_NAME varchar(30), FIELD_TYPE varchar(1), FIELD_OLD_INDEX bigint, FIELD_NEW_INDEX bigint, FIELD_INDEX_FLAG varchar(1), FIELD_PARTITION_FLAG varchar(1), FIELD_ENUM_INDEX bigint, FIELD_IGNORE_VALUE varchar(30), FIELD_IGNORE_FLAG varchar(1))");
            s.execute("insert into TSS_BCOMP_FIELD_NEW values(1,'ID','1',1,1,'1','1',null,null,'0'),(1,'VAL','1',2,2,'0','0',null,null,'0')");
            s.execute("create table TSS_BCOMP_TASK_NEW(BCOMP_INDEX int, TASK_BUSI_DATE varchar(8), TASK_BATCH varchar(10), BCOMP_TIME_NODE varchar(10), TASK_STATE varchar(1), TASK_RESULT varchar(1), FTASK_REASON varchar(2000), FILE1_ACOUNT bigint,FILE2_ACOUNT bigint,FILE1_SCOUNT bigint,FILE2_SCOUNT bigint,FILE1_FCOUNT bigint,FILE2_FCOUNT bigint,END_DATETIME varchar(14),BCOMP_READ_MODE varchar(1000))");
            s.execute("insert into TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) values(1,'20260920','1','A','2')");
            s.execute("create table TSS_BCOMP_DTL_NEW(BCOMP_INDEX int,TASK_DATE varchar(8),TASK_BATCH varchar(10),COMP_INDEX_STR varchar(1000),LINE_CONTENT1 varchar(2000),LINE_CONTENT2 varchar(2000),LINE_RESULT varchar(1),FDTL_REASON varchar(2000))");
            s.execute("create table TSS_BCOMP_DIFFFIELD_NEW(BCOMP_INDEX int)");
            s.execute("create table OLD_DATA(ID int,VAL varchar(100))");
            s.execute("create table NEW_DATA(ID int,VAL varchar(100))");
            s.execute("insert into OLD_DATA values(1,'same'),(2,'old'),(3,'missing-new'),(5,'a'),(5,'b')");
            s.execute("insert into NEW_DATA values(1,'same'),(2,'new'),(4,'missing-old'),(5,'a'),(5,'c')");
            if ("HASH".equals(strategy)) {
                s.execute("update TSS_BCOMP_FIELD_NEW set FIELD_PARTITION_FLAG='0'");
                s.execute("alter table OLD_DATA add TENANT varchar(30) default 'tenant|中文'");
                s.execute("alter table NEW_DATA add TENANT varchar(30) default 'tenant|中文'");
                s.execute("insert into TSS_BCOMP_FIELD_NEW values(1,'TENANT','1',3,1,'1','0',null,null,'0')");
                s.execute("update TSS_BCOMP_FIELD_NEW set FIELD_NEW_INDEX=2 where FIELD_NAME='ID'");
                s.execute("update TSS_BCOMP_FIELD_NEW set FIELD_NEW_INDEX=3 where FIELD_NAME='VAL'");
            }
            // 由原main创建SparkSession，保证原Kryo/内存配置也参与验证。
            var properties=Map.of("spark.master","local[2]", "spark.ui.enabled","false",
                "spark.driver.host","127.0.0.1", "spark.driver.bindAddress","127.0.0.1",
                "spark.default.parallelism","2", "spark.sql.shuffle.partitions","2");
            var previous=new HashMap<String,String>();
            properties.forEach((key,value) -> { previous.put(key,System.getProperty(key)); System.setProperty(key,value); });
            List<Integer> jdbcStagePartitions=new CopyOnWriteArrayList<>();
            previous.put("spark.extraListeners",System.getProperty("spark.extraListeners"));
            System.setProperty("spark.extraListeners",JdbcPartitionListener.class.getName());
            JdbcPartitionListener.partitions=jdbcStagePartitions;
            try {
                SparkHandlerTable.main(new String[]{"compat",url,"sa","","org.h2.Driver","2","1","20260920","1","A",url,"sa","","org.h2.Driver",("HASH".equals(strategy) ? "(SELECT ID AS \"old id\",VAL,TENANT FROM OLD_DATA) old_src" : "OLD_DATA"),url,"sa","","org.h2.Driver",("HASH".equals(strategy) ? "(SELECT TENANT AS \"new tenant\",ID AS \"new id\",VAL FROM NEW_DATA) new_src" : "NEW_DATA"),"20260919"});
            } finally {
                if(SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
                SparkSession.clearActiveSession(); SparkSession.clearDefaultSession();
                previous.forEach((key,value) -> { if(value==null) System.clearProperty(key); else System.setProperty(key,value); });
            }
            try(var r=s.executeQuery("select * from TSS_BCOMP_TASK_NEW")) {
                assertTrue(r.next()); assertEquals("3",r.getString("TASK_STATE")); assertEquals("1",r.getString("TASK_RESULT"));
                assertEquals(5,r.getLong("FILE1_ACOUNT")); assertEquals(5,r.getLong("FILE2_ACOUNT"));
                if ("NTILE".equals(strategy)) assertTrue(r.getString("BCOMP_READ_MODE").contains("NTILE"));
                if ("HASH".equals(strategy)) {
                    assertTrue(r.getString("BCOMP_READ_MODE").contains("HASH"));
                    assertTrue(r.getString("BCOMP_READ_MODE").contains(partitions+"分区"));
                }
            }
            if ("HASH".equals(strategy)) {
                assertEquals(List.of(partitions,partitions),jdbcStagePartitions);
            }
            var codes=new HashMap<String,Integer>(); boolean separator=false, duplicateHash=false;
            try(var r=s.executeQuery("select * from TSS_BCOMP_DTL_NEW")) { while(r.next()) {
                codes.merge(r.getString("LINE_RESULT"),1,Integer::sum);
                separator |= Objects.toString(r.getString("LINE_CONTENT1"),"").contains("|+|");
                duplicateHash |= r.getString("COMP_INDEX_STR").startsWith("HASH".equals(strategy) ? "[5, tenant|中文]#" : "[5]#");
            }}
            assertEquals(Map.of("4",1,"2",2,"3",2),codes);
            assertTrue(separator); assertTrue(duplicateHash);
        }
    }
    public static class JdbcPartitionListener extends SparkListener {
        static volatile List<Integer> partitions=new CopyOnWriteArrayList<>();
        @Override public void onStageSubmitted(SparkListenerStageSubmitted event) {
            // The two source stages feed cogroup's shuffle; configuration-table scans are ResultStages.
            if (event.stageInfo().name().startsWith("mapToPair")) partitions.add(event.stageInfo().numTasks());
        }
    }
}
