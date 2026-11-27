package cn.sunline.tss.tssveri.spark;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FullDifferenceStageTest {
    private static final DailyResultPublisher.Context CONTEXT=new DailyResultPublisher.Context("table","domain",1L,"A",2L,"B");

    private static String[] task(Connection c,String batch) throws SQLException {
        String[] args=new String[21];
        args[6]="1";args[7]="20261126";args[8]=batch;args[9]="A";args[20]="20261126";
        try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261126',?,'A','2')")) {
            ps.setString(1,batch);ps.executeUpdate();
        }
        return args;
    }

    private static void schema(Connection c) throws Exception {
        try(var reader=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) { RunScript.execute(c,reader); }
    }

    private static long scalar(Connection c,String sql) throws SQLException {
        try(var ps=c.createStatement();var rs=ps.executeQuery(sql)) { rs.next();return rs.getLong(1); }
    }

    private static SampleRow row(String key,String type,String field) {
        return SampleRow.of(key,type,List.of(field),new String[]{"A"},new String[]{"B"},List.of(field));
    }

    @Test void storesAllOccurrencesAndFieldCombinationsWhileExcludingFailedAndSpeculativeAttempts() throws Exception {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL";
        try(var driver=DriverManager.getConnection(url)) {
            schema(driver);
            String[] args=task(driver,"all");
            String run=UUID.randomUUID().toString();
            FullDifferenceStage.start(driver,run,args);
            // A crashed attempt committed a batch before Spark retried the partition.
            try(var c=DriverManager.getConnection(url);var writer=new FullDifferenceStage.Writer(c,run,0,1)) {
                writer.append(row("loser","3","f"),null,101);
            }
            var winner=new FullDifferenceStage.PartitionManifest(0,2);
            try(var c=DriverManager.getConnection(url);var writer=new FullDifferenceStage.Writer(c,run,0,2)) {
                for(int i=0;i<130;i++) {
                    String field="f"+i;
                    long occurrences=i==0?20001:1;
                    writer.append(row("A"+i,"4",field),new FieldPattern(List.of(field)),occurrences);
                    winner.totals.countDifference("4",List.of(field),occurrences);
                }
                writer.flush();
            }
            var other=new FullDifferenceStage.PartitionManifest(1,3);
            try(var c=DriverManager.getConnection(url);var writer=new FullDifferenceStage.Writer(c,run,1,3)) {
                writer.append(row("destination","2","v"),null,3);
                other.totals.countDifference("2",List.of(),3);
                writer.append(row("origin","3","v"),null,2);
                other.totals.countDifference("3",List.of(),2);
                writer.flush();
            }
            assertEquals(0,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
            DailyResultPublisher.publishFull(driver,args,CONTEXT,run,List.of(other,winner),2);
            assertEquals(20130,scalar(driver,"SELECT DIFFERENT_SAMPLES FROM TSS_BCOMP_RESULT_NEW"));
            assertEquals(20135,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
            assertEquals(20135,scalar(driver,"SELECT COUNT(DISTINCT SAMPLE_NO) FROM TSS_BCOMP_DTL_NEW"));
            assertEquals(0,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW WHERE COMP_INDEX_STR='loser'"));
            assertEquals(3,scalar(driver,"SELECT SAMPLE_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW WHERE PATTERN_ID='__OTHER__'"));
            assertEquals(130,scalar(driver,"SELECT COUNT(DISTINCT PATTERN_ID) FROM TSS_BCOMP_DTL_NEW"));
            assertEquals(20001,scalar(driver,"SELECT DIFFERENT_COUNT FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE FIELD_NAME='f0'"));
            FullDifferenceStage.remove(driver,run);
            assertEquals(0,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_FULL_STAGE_NEW"));
            try(var c=DriverManager.getConnection(url);var late=new FullDifferenceStage.Writer(c,run,1,4)) {
                late.append(row("late","2","v"),null,1);
                assertThrows(SQLException.class,late::flush);
            }
            assertEquals(20135,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
        }
    }

    @Test void publishesEmptyAndFullyMatchedRunsWithoutDetailRows() throws Exception {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL";
        try(var driver=DriverManager.getConnection(url)) {
            schema(driver);
            for(long matched:new long[]{0,3_000_000_000L}) {
                String[] args=task(driver,"matched"+matched);
                String run=UUID.randomUUID().toString();
                FullDifferenceStage.start(driver,run,args);
                var winner=new FullDifferenceStage.PartitionManifest(0,1);
                winner.totals.matched=matched;
                DailyResultPublisher.publishFull(driver,args,CONTEXT,run,List.of(winner),1);
                assertEquals(matched,scalar(driver,"SELECT MATCHED_COUNT FROM TSS_BCOMP_RESULT_NEW"));
                assertEquals(0,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
                assertEquals(0,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_FIELD_DIFF_NEW"));
                FullDifferenceStage.remove(driver,run);
            }
        }
    }

    @Test void failedPublicationRollsBackDailyReplacementAndWinnerSelection() throws Exception {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL";
        try(var driver=DriverManager.getConnection(url)) {
            schema(driver);
            var saved=new ResultAggregate(1);
            saved.difference("3","previous",()->row("previous","3","v"));
            DailyResultPublisher.publish(driver,task(driver,"old"),CONTEXT,saved);
            String[] args=task(driver,"new");
            String run=UUID.randomUUID().toString();
            FullDifferenceStage.start(driver,run,args);
            var winner=new FullDifferenceStage.PartitionManifest(0,2);
            winner.totals.countDifference("3",List.of(),1);
            // Publication must verify that all reported rows were actually committed.
            assertThrows(SQLException.class,()->DailyResultPublisher.publishFull(driver,args,CONTEXT,run,List.of(winner),1));
            try(var c=DriverManager.getConnection(url);var writer=new FullDifferenceStage.Writer(c,run,0,2)) {
                writer.append(row("rejected","3","v"),null,1);writer.flush();
            }
            try(var ps=driver.createStatement()) {
                ps.execute("ALTER TABLE TSS_BCOMP_DTL_NEW ADD CONSTRAINT reject_write CHECK(COMP_INDEX_STR<>'rejected')");
            }
            assertThrows(SQLException.class,()->DailyResultPublisher.publishFull(driver,args,CONTEXT,run,List.of(winner),1));
            assertEquals(1,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW WHERE COMP_INDEX_STR='previous'"));
            assertEquals(1,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_RESULT_NEW WHERE TASK_BATCH='old'"));
            assertEquals(1,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH='new' AND TASK_STATE='2'"));
            assertEquals(0,scalar(driver,"SELECT COUNT(*) FROM TSS_BCOMP_FULL_WIN_NEW"));
        }
    }
}
