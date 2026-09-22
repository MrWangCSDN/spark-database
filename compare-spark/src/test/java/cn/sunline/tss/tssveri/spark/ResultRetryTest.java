package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import org.apache.spark.*;
import org.apache.spark.api.java.JavaSparkContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultRetryTest {
    @Test void failedPartitionAttemptDoesNotDoubleCountOrExceedGlobalLimit() {
        SparkConf conf=new SparkConf().setAppName("result-retry").setMaster("local[2,2]")
            .set("spark.ui.enabled","false").set("spark.driver.host","127.0.0.1").set("spark.driver.bindAddress","127.0.0.1");
        try(JavaSparkContext spark=new JavaSparkContext(conf)) {
            var numbers=new ArrayList<Integer>();for(int i=0;i<1000;i++) numbers.add(i);
            ResultAggregate result=spark.parallelize(numbers,8).mapPartitions(values->{
                var part=new ResultAggregate(7);
                while(values.hasNext()) {String key=String.format("%04d",values.next());part.difference("3",key,()->SampleRow.of(key,"3",List.of("id"),new String[]{key},null,List.of()));}
                if(TaskContext.getPartitionId()==0&&TaskContext.get().attemptNumber()==0) throw new IllegalStateException("intentional first attempt failure");
                return Collections.singletonList(part).iterator();
            }).treeReduce(ResultAggregate::merge,3);
            assertEquals(1000,result.onlyOrigin);assertEquals(7,result.samples("3").size());
            assertEquals("0000",result.samples("3").get(0).key);
        }
    }
}
