package cn.sunline.tss.tssveri.spark;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SparkLocalStorageTest {
    @TempDir Path directory;

    @Test void rowMappingLogsActualLocalStorageOnceForEachApplication() {
        var messages=new CopyOnWriteArrayList<String>();
        var appender=new AbstractAppender("local-storage-test",null,null,true,Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        try {
            for(int application=0;application<2;application++) {
                boolean compressed=application==0;
                var conf=new SparkConf().setAppName("storage-diagnostics-"+application).setMaster("local[2]")
                    .set("spark.ui.enabled","false").set("spark.driver.host","127.0.0.1")
                    .set("spark.driver.bindAddress","127.0.0.1").set("spark.default.parallelism","2")
                    .set("spark.local.dir",directory.resolve("application-"+application).toString())
                    .set("spark.rdd.compress",Boolean.toString(compressed));
                try(var spark=new JavaSparkContext(conf)) {
                    var logger=(org.apache.logging.log4j.core.Logger)LogManager.getLogger(
                        "cn.sunline.tss.tssveri.spark.SparkLocalStorage");
                    Level previousLevel=logger.getLevel();
                    logger.setLevel(Level.INFO);
                    logger.addAppender(appender);
                    try {
                        var fields=spark.broadcast(List.of(field("ID",1,"1"),field("VALUE",2,"0")));
                        try {
                            var input=spark.parallelize(List.of(RowFactory.create("SENSITIVE_KEY_ONE","business-value-one"),
                                RowFactory.create("SENSITIVE_KEY_TWO","business-value-two"),RowFactory.create("SENSITIVE_KEY_THREE","business-value-three")),3);
                            var mapped=SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_OLD_INDEX,input,fields);
                            assertEquals(application,messages.size(),"Mapping should remain lazy");
                            var result=mapped.collectAsMap();
                            assertEquals(3,result.size());
                            assertArrayEquals(new String[]{"SENSITIVE_KEY_ONE","business-value-one"},
                                result.get("[\"SENSITIVE_KEY_ONE\"]").values);
                            assertEquals(3,SparkHandlerTable.RddToPair(SparkHandlerTable.FIELD_NEW_INDEX,input,fields).count());
                            assertEquals(application+1,messages.size(),"Both input sides should share one executor diagnostic");

                            String message=messages.get(application);
                            assertTrue(message.contains("executorId=driver"),message);
                            assertTrue(message.contains("spark.rdd.compress="+compressed),message);
                            assertTrue(message.matches(".*usableBytes=[0-9]+.*"),message);
                            assertTrue(message.matches(".*totalBytes=[1-9][0-9]*.*"),message);
                            for(var actualDirectory:SparkEnv.get().blockManager().diskBlockManager().localDirs()) {
                                assertTrue(message.contains(actualDirectory.getAbsolutePath()),message);
                                assertTrue(actualDirectory.getName().startsWith("blockmgr-"));
                            }
                            assertFalse(message.contains("business-value"),message);
                            assertFalse(message.contains("SENSITIVE_KEY_"),message);
                        } finally {
                            fields.destroy();
                        }
                    } finally {
                        logger.removeAppender(appender);
                        logger.setLevel(previousLevel);
                    }
                }
            }
        } finally {
            appender.stop();
        }
    }

    private static Row field(String name,int position,String keyFlag) {
        var schema=new StructType().add("FIELD_NAME",DataTypes.StringType).add("FIELD_TYPE",DataTypes.StringType)
            .add("FIELD_OLD_INDEX",DataTypes.IntegerType).add("FIELD_NEW_INDEX",DataTypes.IntegerType)
            .add("FIELD_INDEX_FLAG",DataTypes.StringType);
        return new GenericRowWithSchema(new Object[]{name,"1",position,position,keyFlag},schema);
    }
}
