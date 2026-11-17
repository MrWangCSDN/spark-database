package cn.sunline.tss.tssveri.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FieldNameComparisonTest {
    @Test void resolvesLabelsDespiteStalePositionsAndPreservesPublishedRunWhenAFieldIsMissing() throws Exception {
        String url="jdbc:h2:mem:field_names_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        String origin="(SELECT ID AS \"id\",VAL AS \"VaL\",CARD_TP_ID AS \"CARD_TP_ID\" FROM NAME_OLD) old_src";
        String destination="(SELECT CARD_TP_ID AS \"card_tp_id\",ID AS \"ID\",VAL AS \"val\" FROM NAME_NEW) new_src";
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            try(var schema=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {
                org.h2.tools.RunScript.execute(connection,schema);
            }
            statement.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_SAMPLE_LIMIT) VALUES(1,'field name matching',10)");
            statement.execute("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'origin'),(2,'destination')");
            statement.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID) VALUES(1,1,2)");
            statement.execute("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_IGNORE_FLAG) VALUES(1,'ID','1',99,99,'1','0'),(1,'VAL','1',7,7,'0','0'),(1,'card_tp_id','1',37,37,'0','0')");
            statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261117','1','A','2')");
            statement.execute("CREATE TABLE NAME_OLD(ID VARCHAR(30),VAL VARCHAR(30),CARD_TP_ID VARCHAR(30))");
            statement.execute("CREATE TABLE NAME_NEW(ID VARCHAR(30),VAL VARCHAR(30),CARD_TP_ID VARCHAR(30))");
            statement.execute("INSERT INTO NAME_OLD VALUES('1','same','common'),('2','old-value','common'),('3','same','old-card'),('4','origin-only','origin-card')");
            statement.execute("INSERT INTO NAME_NEW VALUES('1','same','common'),('2','new-value','common'),('3','same','new-card'),('5','destination-only','destination-card')");

            runWithDefaultNameMode(url,"1",origin,destination);

            String publishedRun;
            try(var result=statement.executeQuery("SELECT * FROM TSS_BCOMP_RESULT_NEW")) {
                assertTrue(result.next());
                publishedRun=result.getString("RUN_ID");
                assertNotNull(publishedRun);
                assertEquals(4,result.getLong("ORIGIN_COUNT"));
                assertEquals(4,result.getLong("DESTINATION_COUNT"));
                assertEquals(1,result.getLong("MATCHED_COUNT"));
                assertEquals(2,result.getLong("DIFFERENT_COUNT"));
                assertEquals(1,result.getLong("ONLY_ORIGIN_COUNT"));
                assertEquals(1,result.getLong("ONLY_DESTINATION_COUNT"));
                assertFalse(result.next());
            }
            try(var task=statement.executeQuery("SELECT TASK_STATE,TASK_RESULT FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH='1'")) {
                assertTrue(task.next());
                assertEquals("3",task.getString("TASK_STATE"));
                assertEquals("1",task.getString("TASK_RESULT"));
            }
            var mapper=new ObjectMapper();
            var changedFields=new HashMap<String,String>();
            try(var details=statement.executeQuery("SELECT DETAIL_JSON FROM TSS_BCOMP_DTL_NEW WHERE LINE_RESULT='4'")) {
                while(details.next()) {
                    var fields=mapper.readTree(details.getString("DETAIL_JSON")).get("fields");
                    assertEquals(1,fields.size());
                    var field=fields.get(0);
                    changedFields.put(field.get("name").asText(),field.get("originValue").asText()+"/"+field.get("destinationValue").asText());
                }
            }
            assertEquals(Map.of("VAL","old-value/new-value","card_tp_id","old-card/new-card"),changedFields);
            try(var fields=statement.executeQuery("SELECT FIELD_NAME,FIELD_OLD_INDEX,FIELD_NEW_INDEX FROM TSS_BCOMP_FIELD_NEW")) {
                var savedPositions=new HashMap<String,Integer>();
                while(fields.next()) {
                    assertEquals(fields.getInt("FIELD_OLD_INDEX"),fields.getInt("FIELD_NEW_INDEX"));
                    savedPositions.put(fields.getString("FIELD_NAME"),fields.getInt("FIELD_OLD_INDEX"));
                }
                assertEquals(Map.of("ID",99,"VAL",7,"card_tp_id",37),savedPositions);
            }

            statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261117','2','A','2')");
            String missingDestination="(SELECT CARD_TP_ID AS \"missing_card\",ID AS \"ID\",VAL AS \"val\" FROM NAME_NEW) new_src";
            assertThrows(Exception.class,()->runWithDefaultNameMode(url,"2",origin,missingDestination));
            try(var task=statement.executeQuery("SELECT TASK_STATE,FTASK_REASON FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH='2'")) {
                assertTrue(task.next());
                assertEquals("4",task.getString("TASK_STATE"));
                String reason=task.getString("FTASK_REASON");
                assertNotNull(reason);
                assertTrue(reason.contains("目标"),reason);
                assertTrue(reason.toLowerCase(java.util.Locale.ROOT).contains("card_tp_id"),reason);
            }
            try(var result=statement.executeQuery("SELECT RUN_ID,TASK_BATCH,MATCHED_COUNT,DIFFERENT_COUNT FROM TSS_BCOMP_RESULT_NEW")) {
                assertTrue(result.next());
                assertEquals(publishedRun,result.getString("RUN_ID"));
                assertEquals("1",result.getString("TASK_BATCH"));
                assertEquals(1,result.getLong("MATCHED_COUNT"));
                assertEquals(2,result.getLong("DIFFERENT_COUNT"));
                assertFalse(result.next());
            }
            try(var details=statement.executeQuery("SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW")) {
                assertTrue(details.next());
                assertEquals(4,details.getLong(1));
            }

            statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261117','3','A','2')");
            String duplicateDestination="(SELECT CARD_TP_ID,ID AS \"ID\",ID AS \"id\",VAL FROM NAME_NEW) new_src";
            assertThrows(QueryFieldResolver.FieldResolutionException.class,
                ()->runWithDefaultNameMode(url,"3",origin,duplicateDestination));
            try(var task=statement.executeQuery("SELECT TASK_STATE,FTASK_REASON FROM TSS_BCOMP_TASK_NEW WHERE TASK_BATCH='3'")) {
                assertTrue(task.next());
                assertEquals("4",task.getString("TASK_STATE"));
                String reason=task.getString("FTASK_REASON");
                assertTrue(reason.contains("目标"),reason);
                assertTrue(reason.contains("[id]"),reason);
                assertTrue(reason.contains("重复"),reason);
            }
            try(var result=statement.executeQuery("SELECT RUN_ID FROM TSS_BCOMP_RESULT_NEW")) {
                assertTrue(result.next());
                assertEquals(publishedRun,result.getString("RUN_ID"));
                assertFalse(result.next());
            }
        }
    }

    private static void runWithDefaultNameMode(String url,String batch,String origin,String destination) throws Exception {
        var properties=Map.of("spark.master","local[2]","spark.ui.enabled","false",
            "spark.driver.host","127.0.0.1","spark.driver.bindAddress","127.0.0.1",
            "spark.default.parallelism","2","spark.sql.shuffle.partitions","2");
        var previous=new HashMap<String,String>();
        properties.forEach((key,value)->{previous.put(key,System.getProperty(key));System.setProperty(key,value);});
        String modeKey="spark.compare.fieldMatchByName";
        previous.put(modeKey,System.getProperty(modeKey));
        System.clearProperty(modeKey);
        try {
            SparkHandlerTable.main(new String[]{"field-names",url,"sa","","org.h2.Driver","2","1","20261117",batch,"A",
                url,"sa","","org.h2.Driver",origin,url,"sa","","org.h2.Driver",destination,"20261117"});
        } finally {
            if(SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
            SparkSession.clearActiveSession();
            SparkSession.clearDefaultSession();
            previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
        }
    }
}
