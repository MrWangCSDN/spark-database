package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.metadata.MetadataDbType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ResultHistoryCleanupTest {
    private static final String CUTOFF="20261119120000";
    private static final String BEFORE="20261119115959";
    private static final String AFTER="20261119120001";
    private static final String RECENT="20261126110000";

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void removesOnlyExpiredPublicationGroupsAndKeepsBoundaryAndHistory(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        f.jdbc().update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) VALUES(100,'current','2')");
        publication(f,100,"20261118",BEFORE,"0",2,2,3);
        // No current configuration 999; an expired flag must not exempt its result.
        publication(f,999,"20261118","20261001000000","1",1,1,1);
        publication(f,100,"20261119",CUTOFF,"0",1,1,1);
        publication(f,101,"20000101",RECENT,"0",1,1,1);
        publication(f,102,"20261120",AFTER,"0",1,1,1);
        publication(f,103,"20261121","20261127000000","0",1,1,1);
        publication(f,777,"20000101",BEFORE,"0",1,1,1);
        f.jdbc().update("DELETE FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=777");
        retainedHistory(f);
        var history=history(f);

        assertThat(clean(f)).isEqualTo(new ResultCleanupSummary(7,CUTOFF,2,3,3,4,0));

        assertThat(f.jdbc().query("SELECT BCOMP_INDEX,RESULT_DATE FROM TSS_BCOMP_RESULT_NEW ORDER BY BCOMP_INDEX,RESULT_DATE",
            (rs,n)->rs.getLong(1)+":"+rs.getString(2)))
            .containsExactly("100:20261119","101:20000101","102:20261120","103:20261121");
        assertThat(resultCounts(f)).containsExactly(4L,5L,5L,5L);
        for(String table:List.of("TSS_BCOMP_DTL_NEW","TSS_BCOMP_FIELD_DIFF_NEW","TSS_BCOMP_FIELD_COUNT_NEW")) {
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM "+table+" WHERE BCOMP_INDEX=100 AND RESULT_DATE='20261119'",Long.class)).isEqualTo(1);
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM "+table+" WHERE BCOMP_INDEX=777",Long.class)).isEqualTo(1);
        }
        assertThat(history(f)).isEqualTo(history);
        assertThat(clean(f)).isEqualTo(new ResultCleanupSummary(7,CUTOFF,0,0,0,0,0));
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void cleansLegacyDetailsOnlyForReliableEndedTasksBeforeTheCutoff(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        for(int id=200;id<=202;id++) {
            task(f,id,"20000101","old","D",String.valueOf(id-197),BEFORE);
            legacy(f,id,"20000101","old",null);
        }
        legacy(f,200,"20000101","old",null);
        f.jdbc().update("UPDATE TSS_BCOMP_TASK_NEW SET VERIFY_BUSINESS_DATE='19990101',RESULT_DATE='20261125' WHERE BCOMP_INDEX=200");
        String[][] keep={{"3",RECENT},{"3",CUTOFF},{"3",AFTER},{"3",null},{"3",""},{"3"," "},
            {"2",BEFORE},{"1",BEFORE},{"9",BEFORE}};
        for(int i=0;i<keep.length;i++) {
            task(f,203+i,"20000101","old","D",keep[i][0],keep[i][1]);
            legacy(f,203+i,"20000101","old",null);
        }
        legacy(f,212,"20000101","orphan",null);
        task(f,214,"20270101","old","D","3",BEFORE);legacy(f,214,"20270101","old",null);
        task(f,216,"20000101","old","D","3","20240229010203");legacy(f,216,"20000101","old",null);
        task(f,217,"20000101","old","D","3",BEFORE);legacy(f,217,"20000101","old","");
        String[] invalid={"20260230000000","20250229000000","20260101240000","20260101006000",
            "20260101000060","20260001000000","20261119","0' OR '1'='1","２０２６０１０１００００００"};
        for(int i=0;i<invalid.length;i++) {
            task(f,230+i,"20000101","old","D","3",invalid[i]);legacy(f,230+i,"20000101","old",null);
        }
        var history=history(f);

        assertThat(clean(f)).isEqualTo(new ResultCleanupSummary(7,CUTOFF,0,6,0,0,6));

        assertThat(f.jdbc().queryForList("SELECT BCOMP_INDEX FROM TSS_BCOMP_DTL_NEW ORDER BY BCOMP_INDEX",Long.class))
            .containsExactly(203L,204L,205L,206L,207L,208L,209L,210L,211L,212L,217L,
                230L,231L,232L,233L,234L,235L,236L,237L,238L);
        assertThat(history(f)).isEqualTo(history);
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void anyRecentUnknownOrUnfinishedDuplicateTaskProtectsTheWholeLegacyIdentity(MetadataDbType type) throws Exception {
        // The supported legacy test schema intentionally has no task identity PK.
        var f=fixture(type,true);
        for(int id=300;id<=311;id++) {
            task(f,id,"20000101","old","first","3",BEFORE);legacy(f,id,"20000101","old",null);
        }
        String[][] blockers={{"3",RECENT},{"3",CUTOFF},{"2",BEFORE},{null,BEFORE},{"3",null},
            {"3","20260230000000"},{"3",""},{"3"," "},{"9",BEFORE}};
        for(int i=0;i<blockers.length;i++)task(f,300+i,"20000101","old","second",blockers[i][0],blockers[i][1]);
        task(f,309,"20000101","old","failed","4",BEFORE);
        task(f,309,"20000101","old","cancelled","5",BEFORE);
        task(f,310,"20000101","another","second","3",RECENT);
        task(f,311,"20000102","old","second","3","20260230000000");
        task(f,320,null,"old","D","3",BEFORE);legacy(f,320,null,"old",null);
        task(f,321,"20000101",null,"D","3",BEFORE);legacy(f,321,"20000101",null,null);
        var history=history(f);

        assertThat(clean(f)).isEqualTo(new ResultCleanupSummary(7,CUTOFF,0,3,0,0,3));

        assertThat(f.jdbc().queryForList("SELECT BCOMP_INDEX FROM TSS_BCOMP_DTL_NEW ORDER BY BCOMP_INDEX",Long.class))
            .containsExactly(300L,301L,302L,303L,304L,305L,306L,307L,308L,320L,321L);
        assertThat(history(f)).isEqualTo(history);
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void aCallerRollbackRestoresModernAndLegacyRowsAndItsOtherWrites(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        publication(f,1,"20261118",BEFORE,"0",2,2,3);
        task(f,200,"20000101","old","D","3",BEFORE);legacy(f,200,"20000101","old",null);
        f.jdbc().update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE) VALUES(55,'before','2')");
        retainedHistory(f);
        var counts=resultCounts(f);var history=history(f);
        var failure=new IllegalStateException("caller requested rollback");

        assertThatThrownBy(()->f.transaction().execute(status->{
            f.jdbc().update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_NAME='inside' WHERE BCOMP_INDEX=55");
            assertThat(ResultHistoryCleanup.clean(f.jdbc(),CUTOFF)).isEqualTo(new ResultCleanupSummary(7,CUTOFF,1,3,2,3,1));
            throw failure;
        })).isSameAs(failure);

        assertThat(resultCounts(f)).isEqualTo(counts);
        assertThat(history(f)).isEqualTo(history);
        assertThat(f.jdbc().queryForObject("SELECT BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=55",String.class)).isEqualTo("before");
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void aLateDatabaseDeleteFailureRollsBackTheAlreadyDeletedChildren(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        publication(f,1,"20261118",BEFORE,"0",2,2,3);
        f.jdbc().execute("CREATE TABLE RESULT_PIN(BCOMP_INDEX BIGINT,RESULT_DATE VARCHAR(8),FOREIGN KEY(BCOMP_INDEX,RESULT_DATE) REFERENCES TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE))");
        f.jdbc().update("INSERT INTO RESULT_PIN VALUES(1,'20261118')");
        var counts=resultCounts(f);

        assertThatThrownBy(()->clean(f)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(resultCounts(f)).isEqualTo(counts);
        assertThat(f.jdbc().queryForObject("SELECT PUBLISHED_AT FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=1",String.class)).isEqualTo(BEFORE);
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void tooManyDistinctInvalidLegacyTimesFailClosedAndRollBackModernCleanup(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        publication(f,1,"20261118",BEFORE,"0",2,1,1);
        invalidTimes(f,4097);
        var counts=resultCounts(f);

        assertThatThrownBy(()->clean(f)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(resultCounts(f)).isEqualTo(counts);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW",Long.class)).isEqualTo(4097);
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void the4096LimitCountsDistinctInvalidValuesAndDoesNotBlockReliableOtherTasks(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        publication(f,1,"20261118",BEFORE,"0",2,1,1);
        invalidTimes(f,4096);
        task(f,60000,"20000101","bad","D","3",invalidTime(0));legacy(f,60000,"20000101","bad",null);
        task(f,60001,"20000101","good","D","3",BEFORE);legacy(f,60001,"20000101","good",null);

        assertThat(clean(f)).isEqualTo(new ResultCleanupSummary(7,CUTOFF,1,3,1,1,1));

        assertThat(resultCounts(f)).containsExactly(0L,4097L,0L,0L);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW",Long.class)).isEqualTo(4098);
    }

    @ParameterizedTest @EnumSource(MetadataDbType.class)
    void refusesAutocommitCleanupWithoutTheCallersTransaction(MetadataDbType type) throws Exception {
        var f=fixture(type,false);
        publication(f,1,"20261118",BEFORE,"0",1,1,1);
        var counts=resultCounts(f);

        assertThatThrownBy(()->ResultHistoryCleanup.clean(f.jdbc(),CUTOFF)).isInstanceOf(IllegalStateException.class);

        assertThat(resultCounts(f)).isEqualTo(counts);
    }

    private static ResultCleanupSummary clean(Fixture f) {
        return f.transaction().execute(status->ResultHistoryCleanup.clean(f.jdbc(),CUTOFF));
    }

    private static void publication(Fixture f,long index,String date,String published,String expired,int details,int patterns,int fields) {
        String run="run-"+index+"-"+date;
        f.jdbc().update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT,DETAILS_EXPIRED) VALUES(?,?,?,'20000101','history','D',10,10,5,0,0,5,100,0,0,?,?,?)",index,date,run,details,published,expired);
        for(int i=0;i<details;i++)f.jdbc().update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,COMP_INDEX_STR,LINE_RESULT,RESULT_DATE,SAMPLE_NO,DETAIL_JSON,PATTERN_ID) VALUES(?,'20000101','history',?,'4',?,?,?,?)",index,"sample-"+i,date,i+1,"{\"fields\":[]}","p0");
        for(int i=0;i<patterns;i++)f.jdbc().update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(?,?,?,?,?,1,0,1)",index,date,run,"p"+i,"[\"F\"]");
        for(int i=0;i<fields;i++)f.jdbc().update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,FIELD_NAME,DIFFERENT_COUNT) VALUES(?,?,?,?,1)",index,date,run,"F"+i);
    }

    private static void task(Fixture f,long index,String date,String batch,String node,String state,String ended) {
        f.jdbc().update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE,END_DATETIME,FILE1_ACOUNT,FILE2_ACOUNT) VALUES(?,?,?,?,?,?,11,12)",index,date,batch,node,state,ended);
    }

    private static void legacy(Fixture f,long index,String date,String batch,String resultDate) {
        f.jdbc().update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,COMP_INDEX_STR,LINE_RESULT,RESULT_DATE,LINE_CONTENT1) VALUES(?,?,?,'legacy','3',?,'untouched sample content')",index,date,batch,resultDate);
    }

    private static void retainedHistory(Fixture f) {
        task(f,9900,"20000101","history","D","3",BEFORE);
        f.jdbc().update("INSERT INTO TSS_BCOMP_BATCH_NEW(BATCH_ID,REQUEST_ID,REQUEST_HASH,TASK_BUSI_DATE,CREATED_AT,TOTAL_COUNT) VALUES('history','request-history','hash','20000101','20000101000000',1)");
        f.jdbc().update("INSERT INTO TSS_BCOMP_BATCH_ITEM_NEW(BATCH_ID,BCOMP_INDEX,CONFIGURATION_NAME,TIME_NODE,CONFIGURATION_HASH) VALUES('history',9900,'old name','D','old hash')");
    }

    private static Map<String,List<Map<String,Object>>> history(Fixture f) {
        return Map.of("tasks",f.jdbc().queryForList("SELECT BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE,END_DATETIME,VERIFY_BUSINESS_DATE,FILE1_ACOUNT,FILE2_ACOUNT FROM TSS_BCOMP_TASK_NEW ORDER BY BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE"),
            "batches",f.jdbc().queryForList("SELECT * FROM TSS_BCOMP_BATCH_NEW ORDER BY BATCH_ID"),
            "items",f.jdbc().queryForList("SELECT * FROM TSS_BCOMP_BATCH_ITEM_NEW ORDER BY BATCH_ID,BCOMP_INDEX"));
    }

    private static List<Long> resultCounts(Fixture f) {
        return List.of("TSS_BCOMP_RESULT_NEW","TSS_BCOMP_DTL_NEW","TSS_BCOMP_FIELD_DIFF_NEW","TSS_BCOMP_FIELD_COUNT_NEW").stream()
            .map(table->f.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).toList();
    }

    private static void invalidTimes(Fixture f,int count) {
        var tasks=new ArrayList<Object[]>(count);var details=new ArrayList<Object[]>(count);
        for(int i=0;i<count;i++) {
            tasks.add(new Object[]{10000+i,invalidTime(i)});details.add(new Object[]{10000+i});
        }
        f.jdbc().batchUpdate("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE,END_DATETIME) VALUES(?,'20000101','bad','D','3',?)",tasks);
        f.jdbc().batchUpdate("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,COMP_INDEX_STR,LINE_RESULT) VALUES(?,'20000101','bad','legacy','3')",details);
    }

    private static String invalidTime(int index) {return String.format(Locale.ROOT,"%014d",index);}

    private static Fixture fixture(MetadataDbType type,boolean legacyTasks) throws Exception {
        String mode=type==MetadataDbType.GDB?"MySQL":"PostgreSQL";
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE="+mode+";DB_CLOSE_DELAY=-1","sa","");
        if(legacyTasks)new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        else {
            String schema=Files.readString(Path.of(type==MetadataDbType.GDB?"../sql/create_bcomp_gdb.sql":"../sql/create_bcomp_new.sql"))
                .replace("START TRANSACTION;","BEGIN;");
            new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        return new Fixture(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private record Fixture(JdbcTemplate jdbc,TransactionTemplate transaction) {}
}
