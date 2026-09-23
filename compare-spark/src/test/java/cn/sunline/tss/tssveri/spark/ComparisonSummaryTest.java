package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import scala.Tuple2;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonSummaryTest {
    private static Row field(String name,String type,String ignore) {
        StructType schema=new StructType().add("FIELD_NAME",DataTypes.StringType).add("FIELD_MEMO",DataTypes.StringType).add("FIELD_TYPE",DataTypes.StringType).add("FIELD_IGNORE_FLAG",DataTypes.StringType).add("FIELD_IGNORE_VALUE",DataTypes.StringType).add("FIELD_ENUM_INDEX",DataTypes.IntegerType);
        return new GenericRowWithSchema(new Object[]{name,name+"中文",type,"0",ignore,null},schema);
    }
    private static Tuple2<String,Tuple2<Iterable<String[]>,Iterable<String[]>>> group(String key,List<String[]> a,List<String[]> b) {return new Tuple2<>(key,new Tuple2<>(a,b));}
    @Test void countsDifferentRowOnceAndKeepsAllDifferentFieldsWithoutDoubleRounding() {
        var groups=List.of(group("key",Collections.singletonList(new String[]{"9007199254740992","a"}),Collections.singletonList(new String[]{"9007199254740993","b"})));
        var result=SparkHandlerTable.comparePartition(groups.iterator(),List.of(field("amount","2",null),field("name","1",null)),Map.of(),new HashMap<>(),1000);
        assertEquals(1,result.different);assertEquals(2,result.samples("4").get(0).fields.size());
        assertEquals("amount中文",result.samples("4").get(0).fields.get(0).label);
    }
    @Test void anIgnoredFieldCannotEraseAnotherFieldsDifference() {
        var groups=List.of(group("key",Collections.singletonList(new String[]{"a","IGNORE"}),Collections.singletonList(new String[]{"b","other"})));
        var result=SparkHandlerTable.comparePartition(groups.iterator(),List.of(field("name","1",null),field("ignored","1","7")),Map.of(7,Set.of("IGNORE")),new HashMap<>(),1000);
        assertEquals(1,result.different);assertEquals(1,result.samples("4").get(0).fields.size());
        assertEquals("name",result.samples("4").get(0).fields.get(0).name);
    }
    @Test void duplicatePrimaryKeyFailsInsteadOfInventingMissingRecords() {
        var groups=List.of(group("key",List.of(new String[]{"a"},new String[]{"b"}),Collections.singletonList(new String[]{"c"})));
        assertThrows(IllegalArgumentException.class,()->SparkHandlerTable.comparePartition(groups.iterator(),List.of(field("name","1",null)),Map.of(),new HashMap<>(),1000));
    }
}
