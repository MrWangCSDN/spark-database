package cn.sunline.tss.tssveri.spark;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyFieldStatisticsTest {
    @Test void countsAllFieldsAcrossCollapsedPatternsAndWeightedRowsWithoutSamples() {
        var result=new ResultAggregate(0);
        result.difference("4","ordinary",List.of("A","B","A"),3,()->{throw new AssertionError("No samples");});
        var other=new ResultAggregate(0);
        other.difference("4","other",List.of("B"),()->{throw new AssertionError("No samples");});
        other.difference("3","nullable",List.of(),5,()->{throw new AssertionError("No samples");});
        result.merge(other);
        result.validateFieldCounts();
        assertEquals(Map.of("A",3L,"B",4L),result.fieldCounts());
        assertEquals(4,result.different);
        assertEquals(5,result.onlyOrigin);
    }

    @Test void publishesAndReplacesExactFieldCountsAndReviserSnapshotAtomically() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL","sa","");var s=c.createStatement()) {
            try(var reader=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {RunScript.execute(c,reader);}
            s.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,REVISER_NAME,REVISER_USERNAME) VALUES(1,'table','姓名','account')");
            s.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX) VALUES(1)");
            String[] args=new String[21];args[6]="1";args[7]="20261118";args[8]="one";args[9]="A";args[20]="20261118";
            insertTask(s,"one");
            var result=new ResultAggregate(0);
            result.difference("4","one",List.of("A","B"),()->{throw new AssertionError();});
            DailyResultPublisher.publish(c,args,DailyResultPublisher.context(c,1),result);
            String run;
            try(var r=s.executeQuery("SELECT RUN_ID,REVISER_NAME,REVISER_USERNAME,FIELD_COUNTS_VERSION,FIELD_COUNTS_SIZE FROM TSS_BCOMP_RESULT_NEW")) {
                assertTrue(r.next());run=r.getString(1);assertEquals("姓名",r.getString(2));assertEquals("account",r.getString(3));assertEquals(1,r.getInt(4));assertEquals(2,r.getInt(5));
            }
            try(var r=s.executeQuery("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE RUN_ID='"+run+"' AND DIFFERENT_COUNT=1")) {r.next();assertEquals(2,r.getInt(1));}
            args[8]="two";insertTask(s,"two");
            s.execute("ALTER TABLE TSS_BCOMP_DTL_NEW ADD CONSTRAINT fail_detail CHECK(COMP_INDEX_STR<>'fail')");
            var failed=new ResultAggregate(1);
            failed.difference("4","fail",List.of("C"),()->SampleRow.of("fail","4",List.of("C"),new String[]{"a"},new String[]{"b"},List.of("C")));
            assertThrows(SQLException.class,()->DailyResultPublisher.publish(c,args,DailyResultPublisher.context(c,1),failed));
            try(var r=s.executeQuery("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE RUN_ID='"+run+"'")) {r.next();assertEquals(2,r.getInt(1));}
            var matched=new ResultAggregate(0);matched.matched=2;
            DailyResultPublisher.publish(c,args,DailyResultPublisher.context(c,1),matched);
            try(var r=s.executeQuery("SELECT FIELD_COUNTS_VERSION,FIELD_COUNTS_SIZE FROM TSS_BCOMP_RESULT_NEW")) {r.next();assertEquals(1,r.getInt(1));assertEquals(0,r.getInt(2));}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_COUNT_NEW")) {r.next();assertEquals(0,r.getInt(1));}
        }
    }

    @Test void upgradesBothMetadataDialectsWithoutRepeatingExistingConfigurationReviserMigration() throws Exception {
        for(String dialect:List.of("new","gdb")) {
            String mode=dialect.equals("new")?"PostgreSQL":"MySQL";
            try(var c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE="+mode,"sa","");var statement=c.createStatement()) {
                String old=Files.readString(Path.of("../sql/create_bcomp_"+dialect+".sql")).split("-- 批次历史",2)[0];
                // The GDB schema has the same batch boundary but its comment can differ.
                int batch=old.indexOf("CREATE TABLE TSS_BCOMP_BATCH_NEW");
                if(batch>=0) old=old.substring(0,batch);
                old=old.replace("    BCOMP_SHARD_STRATEGY VARCHAR(20),\n    REVISER_NAME VARCHAR(100),\n    REVISER_USERNAME VARCHAR(100)\n","    BCOMP_SHARD_STRATEGY VARCHAR(20)\n")+"COMMIT;";
                RunScript.execute(c,new java.io.StringReader(old.replace("START TRANSACTION;","BEGIN;")));
                for(String migration:List.of("batch_tables","daily_results","field_patterns","pattern_overflow","result_module","reviser")) {
                    String sql=Files.readString(Path.of("../sql/add_bcomp_"+migration+"_"+dialect+".sql"));
                    // H2's MySQL mode accepts BEGIN for the vendor START TRANSACTION wrapper.
                    RunScript.execute(c,new java.io.StringReader(sql.replace("START TRANSACTION;","BEGIN;")));
                }
                statement.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,REVISER_NAME) VALUES(1,'preserved','原负责人')");
                statement.execute("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT) VALUES(1,'20261118','old','20261118','old','A',1,1,1,0,0,0,0,0,0,0,'20261118000000')");
                for(String migration:List.of("field_counts","result_reviser","full_details")) {
                    String sql=Files.readString(Path.of("../sql/add_bcomp_"+migration+"_"+dialect+".sql"));
                    // H2's MySQL mode accepts BEGIN for the vendor START TRANSACTION wrapper.
                    RunScript.execute(c,new java.io.StringReader(sql.replace("START TRANSACTION;","BEGIN;")));
                }
                try(var rows=statement.executeQuery("SELECT FIELD_COUNTS_VERSION,FIELD_COUNTS_SIZE,REVISER_NAME FROM TSS_BCOMP_RESULT_NEW")) {
                    rows.next();assertEquals(0,rows.getInt(1));assertEquals(0,rows.getInt(2));assertNull(rows.getString(3));
                }
                try(var rows=statement.executeQuery("SELECT BCOMP_NAME,REVISER_NAME FROM TSS_BCOMP_CONF_NEW")) {
                    rows.next();assertEquals("preserved",rows.getString(1));assertEquals("原负责人",rows.getString(2));
                }
                insertTask(statement,"new");
                String[] args=new String[21];args[6]="1";args[7]="20261118";args[8]="new";args[9]="A";args[20]="20261118";
                var result=new ResultAggregate(0);
                result.difference("4","key",List.of("value"),()->{throw new AssertionError();});
                DailyResultPublisher.publish(c,args,new DailyResultPublisher.Context("table","dept",null,null,null,null),result);
                try(var rows=statement.executeQuery("SELECT FIELD_COUNTS_VERSION,FIELD_COUNTS_SIZE FROM TSS_BCOMP_RESULT_NEW")) {
                    rows.next();assertEquals(1,rows.getInt(1));assertEquals(1,rows.getInt(2));
                }
            }
        }
    }

    private static void insertTask(Statement statement,String batch) throws SQLException {
        statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261118','"+batch+"','A','2')");
    }
}
