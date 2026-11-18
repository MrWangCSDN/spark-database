package cn.sunline.tss.tssveri.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkException;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NullableUniqueKeyTest {
    private static Row field(String name,String type,int oldIndex,int newIndex,String key,String ignore) {
        StructType schema=new StructType()
            .add("FIELD_NAME",DataTypes.StringType).add("FIELD_MEMO",DataTypes.StringType)
            .add("FIELD_TYPE",DataTypes.StringType).add("FIELD_OLD_INDEX",DataTypes.IntegerType)
            .add("FIELD_NEW_INDEX",DataTypes.IntegerType).add("FIELD_INDEX_FLAG",DataTypes.StringType)
            .add("FIELD_IGNORE_FLAG",DataTypes.StringType).add("FIELD_IGNORE_VALUE",DataTypes.StringType)
            .add("FIELD_ENUM_INDEX",DataTypes.IntegerType);
        return new GenericRowWithSchema(new Object[]{name,name,type,oldIndex,newIndex,key,"0",ignore,null},schema);
    }

    private static SparkConf configuration(String name) {
        return new SparkConf().setAppName(name).setMaster("local[2]")
            .set("spark.serializer","org.apache.spark.serializer.KryoSerializer")
            .set("spark.ui.enabled","false").set("spark.driver.host","127.0.0.1")
            .set("spark.driver.bindAddress","127.0.0.1");
    }

    @Test void matchesRawNullableRowsByMultiplicityWhileOrdinaryKeysKeepFieldComparison() {
        try(var spark=new JavaSparkContext(configuration("nullable-key-multiset"))) {
            var fields=List.of(field("key","1",1,2,"1",null),
                field("amount","2",2,1,"0",null),field("ignored","1",3,3,"0","7"));
            var origin=new ArrayList<Row>();
            var destination=new ArrayList<Row>();
            for(int i=0;i<5;i++) origin.add(RowFactory.create(null,"10","same"));
            for(int i=0;i<3;i++) destination.add(RowFactory.create("10",null,"same"));
            origin.add(RowFactory.create(null,"20","same"));
            destination.add(RowFactory.create("30",null,"same"));
            origin.add(RowFactory.create("","40","same"));
            destination.add(RowFactory.create("40","","same"));
            origin.add(RowFactory.create(null,"1.00","IGNORE"));
            destination.add(RowFactory.create("1",null,"different"));
            origin.add(RowFactory.create("null","50","same"));
            destination.add(RowFactory.create("50","null","same"));
            origin.add(RowFactory.create("ordinary","2","IGNORE"));
            destination.add(RowFactory.create("3","ordinary","different"));
            var broadcast=spark.broadcast(fields);
            var oldPairs=SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_OLD_INDEX,spark.parallelize(origin,4),broadcast);
            var newPairs=SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_NEW_INDEX,spark.parallelize(destination,3),broadcast);
            var grouped=oldPairs.cogroup(newPairs);
            var ignored=Map.of(7,Set.of("IGNORE"));
            var result=SparkHandlerTable.runTwoPass(grouped,2,
                values->SparkHandlerTable.countPatternsPartition(values,fields,ignored,new HashMap<>()),
                (values,selection)->SparkHandlerTable.comparePartition(values,fields,ignored,new HashMap<>(),2,selection.retainedIds(),selection.patternSampleLimit()));
            assertEquals(5,result.matched);
            assertEquals(4,result.onlyOrigin);
            assertEquals(2,result.onlyDestination);
            assertEquals(1,result.different);
            assertEquals(10,result.matched+result.onlyOrigin+result.different);
            assertEquals(8,result.matched+result.onlyDestination+result.different);
            assertEquals(2,result.samples("3").size());
            assertEquals(2,result.samples("2").size());
            assertEquals(1,result.patternCount());
            assertEquals(Map.of("amount",1L),result.fieldCounts());
            assertEquals("amount",result.samples("4").get(0).fields.get(0).name);
            assertEquals(1,result.samples("4").get(0).fields.size());
            assertTrue(result.samples("3").stream().allMatch(sample->sample.key.equals("[null]")));
            assertTrue(result.samples("3").stream().flatMap(sample->sample.fields.stream()).allMatch(field->field.destinationValue==null));
            assertTrue(result.samples("2").stream().flatMap(sample->sample.fields.stream()).allMatch(field->field.originValue==null));
            broadcast.destroy();
        }
    }

    @Test void distinguishesNullEmptyWhitespaceAndLiteralNullInCompositeKeys() {
        try(var spark=new JavaSparkContext(configuration("nullable-key-identities"))) {
            var fields=List.of(field("key1","1",1,1,"1",null),field("key2","1",2,2,"1",null),
                field("value","1",3,3,"0",null));
            var origin=List.of(RowFactory.create("tenant",null,"x"),RowFactory.create("tenant","","x"),
                RowFactory.create("tenant"," ","x"),RowFactory.create("tenant","null","x"));
            var destination=List.of(RowFactory.create("tenant",null,"x"),RowFactory.create("tenant","","x"),
                RowFactory.create("tenant","  ","x"),RowFactory.create("tenant","null","x"));
            var broadcast=spark.broadcast(fields);
            var oldPairs=SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_OLD_INDEX,spark.parallelize(origin,3),broadcast);
            var newPairs=SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_NEW_INDEX,spark.parallelize(destination,2),broadcast);
            var result=SparkHandlerTable.runTwoPass(oldPairs.cogroup(newPairs),10,
                values->SparkHandlerTable.countPatternsPartition(values,fields,Map.of(),new HashMap<>()),
                (values,selection)->SparkHandlerTable.comparePartition(values,fields,Map.of(),new HashMap<>(),10,selection.retainedIds(),selection.patternSampleLimit()));
            assertEquals(3,result.matched);
            assertEquals(1,result.onlyOrigin);
            assertEquals(1,result.onlyDestination);
            assertEquals(0,result.different);
            assertEquals(" ",result.samples("3").get(0).fields.get(1).originValue);
            assertEquals("  ",result.samples("2").get(0).fields.get(1).destinationValue);
            broadcast.destroy();
        }
    }

    @Test void publishesNullableUniqueIndexMultiplicityThroughJdbcAndProductionMain() throws Exception {
        String url="jdbc:h2:mem:nullable_"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            try(var schema=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("../sql/create_bcomp_new.sql"))) {
                org.h2.tools.RunScript.execute(connection,schema);
            }
            statement.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_SAMPLE_LIMIT) VALUES(1,'nullable unique',3)");
            statement.execute("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'origin'),(2,'destination')");
            statement.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID) VALUES(1,1,2)");
            statement.execute("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_IGNORE_FLAG) VALUES(1,'ID','1',1,1,'1','0'),(1,'VAL','1',2,2,'0','0')");
            statement.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261117','1','A','2')");
            statement.execute("CREATE TABLE NULLABLE_OLD(ID VARCHAR(30),VAL VARCHAR(30))");
            statement.execute("CREATE TABLE NULLABLE_NEW(ID VARCHAR(30),VAL VARCHAR(30))");
            statement.execute("CREATE UNIQUE INDEX nullable_old_id ON NULLABLE_OLD(ID)");
            statement.execute("CREATE UNIQUE INDEX nullable_new_id ON NULLABLE_NEW(ID)");
            statement.execute("INSERT INTO NULLABLE_OLD VALUES(NULL,'same'),(NULL,'same'),(NULL,'same'),(NULL,'same'),(NULL,'same'),(NULL,'old'),('','blank'),('ordinary','a')");
            statement.execute("INSERT INTO NULLABLE_NEW VALUES(NULL,'same'),(NULL,'same'),(NULL,'same'),(NULL,'new'),('','blank'),('ordinary','b')");
            var properties=Map.of("spark.master","local[2]","spark.ui.enabled","false",
                "spark.driver.host","127.0.0.1","spark.driver.bindAddress","127.0.0.1",
                "spark.default.parallelism","2","spark.sql.shuffle.partitions","2");
            var previous=new HashMap<String,String>();
            properties.forEach((key,value)->{previous.put(key,System.getProperty(key));System.setProperty(key,value);});
            try {
                SparkHandlerTable.main(new String[]{"nullable",url,"sa","","org.h2.Driver","2","1","20261117","1","A",
                    url,"sa","","org.h2.Driver","NULLABLE_OLD",url,"sa","","org.h2.Driver","NULLABLE_NEW","20261117"});
            } finally {
                if(org.apache.spark.sql.SparkSession.getDefaultSession().isDefined()) org.apache.spark.sql.SparkSession.getDefaultSession().get().stop();
                org.apache.spark.sql.SparkSession.clearActiveSession();
                org.apache.spark.sql.SparkSession.clearDefaultSession();
                previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
            }
            try(var result=statement.executeQuery("SELECT * FROM TSS_BCOMP_RESULT_NEW")) {
                assertTrue(result.next());
                assertEquals(8,result.getLong("ORIGIN_COUNT"));
                assertEquals(6,result.getLong("DESTINATION_COUNT"));
                assertEquals(4,result.getLong("MATCHED_COUNT"));
                assertEquals(3,result.getLong("ONLY_ORIGIN_COUNT"));
                assertEquals(1,result.getLong("ONLY_DESTINATION_COUNT"));
                assertEquals(1,result.getLong("DIFFERENT_COUNT"));
                assertEquals(3,result.getInt("DETAIL_LIMIT"));
                assertEquals(3,result.getInt("ORIGIN_SAMPLES"));
                assertEquals(1,result.getInt("DESTINATION_SAMPLES"));
                assertFalse(result.next());
            }
            try(var task=statement.executeQuery("SELECT * FROM TSS_BCOMP_TASK_NEW")) {
                assertTrue(task.next());
                assertEquals("3",task.getString("TASK_STATE"));
                assertEquals("1",task.getString("TASK_RESULT"));
                assertEquals(8,task.getLong("FILE1_ACOUNT"));
                assertEquals(6,task.getLong("FILE2_ACOUNT"));
            }
            try(var counts=statement.executeQuery("SELECT FIELD_NAME,DIFFERENT_COUNT FROM TSS_BCOMP_FIELD_COUNT_NEW")) {
                assertTrue(counts.next());assertEquals("VAL",counts.getString(1));assertEquals(1,counts.getLong(2));assertFalse(counts.next());
            }
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            int originSamples=0,destinationSamples=0,repeatedSamples=0;
            try(var detail=statement.executeQuery("SELECT LINE_RESULT,COMP_INDEX_STR,DETAIL_JSON FROM TSS_BCOMP_DTL_NEW WHERE LINE_RESULT IN ('2','3')")) {
                while(detail.next()) {
                    assertEquals("[null]",detail.getString("COMP_INDEX_STR"));
                    var json=mapper.readTree(detail.getString("DETAIL_JSON"));
                    boolean originOnly="3".equals(detail.getString("LINE_RESULT"));
                    if(originOnly) originSamples++; else destinationSamples++;
                    for(var field:json.get("fields")) {
                        assertTrue(field.get(originOnly?"destinationValue":"originValue").isNull());
                        if(originOnly&&"VAL".equals(field.get("name").asText())&&"same".equals(field.get("originValue").asText())) repeatedSamples++;
                    }
                }
            }
            assertEquals(3,originSamples);
            assertEquals(1,destinationSamples);
            assertEquals(2,repeatedSamples);
        }
    }

    @Test void ordinaryDuplicateKeysStillFailAfterDistributedReduction() {
        try(var spark=new JavaSparkContext(configuration("ordinary-key-duplicates"))) {
            var fields=List.of(field("key","2",1,1,"1",null),field("value","1",2,2,"0",null));
            var broadcast=spark.broadcast(fields);
            var rows=List.of(RowFactory.create("1.00","a"),RowFactory.create("1","b"));
            var pairs=SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_OLD_INDEX,spark.parallelize(rows,2),broadcast);
            assertThrows(SparkException.class,pairs::collect);
            broadcast.destroy();
        }
    }
}
