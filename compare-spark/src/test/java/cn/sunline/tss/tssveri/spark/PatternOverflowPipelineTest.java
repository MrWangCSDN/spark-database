package cn.sunline.tss.tssveri.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkException;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.*;
import org.apache.spark.storage.StorageLevel;
import org.junit.jupiter.api.Test;
import scala.Tuple2;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatternOverflowPipelineTest {
    private static Row field(String name) {
        StructType schema=new StructType().add("FIELD_NAME",DataTypes.StringType).add("FIELD_MEMO",DataTypes.StringType).add("FIELD_TYPE",DataTypes.StringType).add("FIELD_IGNORE_FLAG",DataTypes.StringType).add("FIELD_IGNORE_VALUE",DataTypes.StringType).add("FIELD_ENUM_INDEX",DataTypes.IntegerType);
        return new GenericRowWithSchema(new Object[]{name,name,"1","0",null,null},schema);
    }

    private static JavaPairRDD<String,Tuple2<Iterable<String[]>,Iterable<String[]>>> groups(JavaSparkContext spark,int count) {
        var rows=new ArrayList<Tuple2<String,Tuple2<Iterable<String[]>,Iterable<String[]>>>>();
        for(int pattern=1;pattern<=count;pattern++) {
            String[] origin=new String[8],destination=new String[8];
            Arrays.fill(origin,"0");Arrays.fill(destination,"0");
            for(int bit=0;bit<8;bit++) if((pattern&(1<<bit))!=0) destination[bit]="1";
            rows.add(new Tuple2<>(String.format("key-%03d",pattern),new Tuple2<>(Collections.singletonList(origin),Collections.singletonList(destination))));
        }
        Collections.shuffle(rows,new Random(27));
        return spark.parallelizePairs(rows,7);
    }

    @Test void publishesExactTop127AndOneSampleFreeOtherRow() {
        SparkConf conf=new SparkConf().setAppName("pattern-overflow").setMaster("local[2]")
            .set("spark.ui.enabled","false").set("spark.driver.host","127.0.0.1").set("spark.driver.bindAddress","127.0.0.1");
        try(JavaSparkContext spark=new JavaSparkContext(conf)) {
            var fields=new ArrayList<Row>();for(int i=0;i<8;i++) fields.add(field("f"+i));
            var cached=groups(spark,130);
            var result=SparkHandlerTable.runTwoPass(cached,0,
                values->SparkHandlerTable.countPatternsPartition(values,fields,Map.of(),new HashMap<>()),
                (values,selection)->SparkHandlerTable.comparePartition(values,fields,Map.of(),new HashMap<>(),0,selection.retainedIds(),selection.patternSampleLimit()));
            assertEquals(127,result.patterns().stream().filter(pattern->!"__OTHER__".equals(pattern.id)).count());
            assertEquals(1,result.patterns().stream().filter(pattern->"__OTHER__".equals(pattern.id)).count());
            assertEquals(130,result.patternCount());
            assertEquals(result.different,result.patternCount());
            assertEquals(0,result.patterns().stream().filter(pattern->"__OTHER__".equals(pattern.id)).findFirst().orElseThrow().sampleCount());
            assertEquals(StorageLevel.NONE(),cached.getStorageLevel());
        }
    }

    @Test void unpersistsCogroupWhenSecondPassFails() {
        SparkConf conf=new SparkConf().setAppName("pattern-overflow-failure").setMaster("local[2]")
            .set("spark.ui.enabled","false").set("spark.driver.host","127.0.0.1").set("spark.driver.bindAddress","127.0.0.1");
        try(JavaSparkContext spark=new JavaSparkContext(conf)) {
            var fields=List.of(field("f0"));
            var cached=groups(spark,1);
            assertThrows(SparkException.class,()->SparkHandlerTable.runTwoPass(cached,0,
                values->SparkHandlerTable.countPatternsPartition(values,fields,Map.of(),new HashMap<>()),
                (values,selection)->{throw new IllegalStateException("second pass failed");}));
            assertEquals(StorageLevel.NONE(),cached.getStorageLevel());
        }
    }
}
