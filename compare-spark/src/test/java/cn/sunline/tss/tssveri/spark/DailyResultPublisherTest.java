package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import org.h2.tools.RunScript;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyResultPublisherTest {
    @Test void replacesSameBusinessDateAtomicallyAndPreservesOtherDates() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL","sa","")) {
            RunScript.execute(c,Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql")));
            String[] args=new String[21];args[6]="1";args[7]="20260923";args[8]="first";args[9]="D";args[20]="20260922";
            var context=new DailyResultPublisher.Context("客户表",1L,"sit",2L,"uat");
            var result=new ResultAggregate(2);result.matched=100;
            result.difference("3","A",()->SampleRow.of("A","3",List.of("ID"),new String[]{"A"},null,List.of()));
            insertTask(c,args);DailyResultPublisher.publish(c,args,context,result);
            assertEquals(101,count(c,"SELECT ORIGIN_COUNT FROM TSS_BCOMP_RESULT_NEW"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
            assertThrows(SQLException.class,()->DailyResultPublisher.publish(c,args,context,result));
            args[8]="cancelled";insertTask(c,args);
            try(var ps=c.prepareStatement("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='5' WHERE TASK_BATCH='cancelled'")){ps.executeUpdate();}
            assertThrows(SQLException.class,()->DailyResultPublisher.publish(c,args,context,result));
            assertEquals(101,count(c,"SELECT ORIGIN_COUNT FROM TSS_BCOMP_RESULT_NEW"));
            args[8]="second";insertTask(c,args);
            try(var s=c.createStatement()){s.execute("ALTER TABLE TSS_BCOMP_DTL_NEW ADD CONSTRAINT no_samples CHECK(COMP_INDEX_STR<>'B')");}
            var failed=new ResultAggregate(2);
            failed.difference("3","B",()->SampleRow.of("B","3",List.of("ID"),new String[]{"B"},null,List.of()));
            assertThrows(SQLException.class,()->DailyResultPublisher.publish(c,args,context,failed));
            assertEquals(101,count(c,"SELECT ORIGIN_COUNT FROM TSS_BCOMP_RESULT_NEW"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
            var replacement=new ResultAggregate(0);replacement.matched=200;
            DailyResultPublisher.publish(c,args,context,replacement);
            assertEquals(200,count(c,"SELECT ORIGIN_COUNT FROM TSS_BCOMP_RESULT_NEW"));
            assertEquals(0,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
            args[8]="next-day";args[20]="20260923";insertTask(c,args);
            DailyResultPublisher.publish(c,args,context,result);
            assertEquals(2,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_RESULT_NEW"));
        }
    }
    @Test void publishesExactFieldPatternsAndReplacesThemOnlyOnSuccess() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL","sa","")) {
            RunScript.execute(c,Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql")));
            String[] args=new String[21];args[6]="1";args[7]="20260923";args[8]="first";args[9]="D";args[20]="20260922";
            var context=new DailyResultPublisher.Context("A表",1L,"sit",2L,"uat");
            var result=new ResultAggregate(2);
            for(int i=0;i<3;i++) { String key="a"+i;result.difference("4",key,List.of("a"),()->SampleRow.of(key,"4",List.of("a","b"),new String[]{"1","1"},new String[]{"2","1"},List.of("a"))); }
            for(int i=0;i<4;i++) { String key="ab"+i;result.difference("4",key,List.of("a","b"),()->SampleRow.of(key,"4",List.of("a","b"),new String[]{"1","1"},new String[]{"2","2"},List.of("a","b"))); }
            insertTask(c,args);DailyResultPublisher.publish(c,args,context,result);
            assertEquals(7,count(c,"SELECT DIFFERENT_COUNT FROM TSS_BCOMP_RESULT_NEW"));
            assertEquals(4,count(c,"SELECT DIFFERENT_SAMPLES FROM TSS_BCOMP_RESULT_NEW"));
            assertEquals(2,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_FIELD_DIFF_NEW"));
            assertEquals(7,count(c,"SELECT SUM(DIFFERENT_COUNT) FROM TSS_BCOMP_FIELD_DIFF_NEW"));
            assertEquals(4,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW WHERE LINE_RESULT='4' AND PATTERN_ID IS NOT NULL"));
            args[8]="second";insertTask(c,args);
            try(var s=c.createStatement()) {s.execute("ALTER TABLE TSS_BCOMP_DTL_NEW ADD CONSTRAINT fail_republish CHECK(COMP_INDEX_STR<>'new-key')");}
            var failed=new ResultAggregate(2);
            failed.difference("4","new-key",List.of("a"),()->SampleRow.of("new-key","4",List.of("a"),new String[]{"1"},new String[]{"2"},List.of("a")));
            assertThrows(SQLException.class,()->DailyResultPublisher.publish(c,args,context,failed));
            assertEquals(7,count(c,"SELECT SUM(DIFFERENT_COUNT) FROM TSS_BCOMP_FIELD_DIFF_NEW"));
            assertEquals(4,count(c,"SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW"));
        }
    }
    private static void insertTask(Connection c,String[] a) throws SQLException {
        try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,?,?,?,'2')")) {ps.setString(1,a[7]);ps.setString(2,a[8]);ps.setString(3,a[9]);ps.executeUpdate();}
    }
    private static long count(Connection c,String sql) throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
}
