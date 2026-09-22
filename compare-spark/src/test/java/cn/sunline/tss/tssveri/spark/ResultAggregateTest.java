package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResultAggregateTest {
    @Test void mergesCountsButCapsEachCategoryGlobally() {
        var combined = new ResultAggregate(10);
        for (int p=0;p<20;p++) {
            var part = new ResultAggregate(10);
            for(int n=0;n<100;n++) for(String type:List.of("2","3","4")) {
                String key=String.format("%03d-%03d",p,n);
                part.difference(type,key,() -> SampleRow.of(key,type,List.of("amount"),new String[]{"100"},new String[]{"200"},List.of("amount")));
            }
            part.matched += 5;
            combined.merge(part);
        }
        assertEquals(2000,combined.onlyOrigin);
        assertEquals(2000,combined.onlyDestination);
        assertEquals(2000,combined.different);
        assertEquals(100,combined.matched);
        assertEquals(4100,combined.originCount());
        for(String type:List.of("2","3","4")) assertEquals(10,combined.samples(type).size());
        assertEquals("000-000",combined.samples("4").get(0).key);
    }
    @Test void zeroLimitStillCountsAndDoesNotMaterializeDetails() {
        var result=new ResultAggregate(0);
        result.difference("2","key",()-> { throw new AssertionError("sample should not be built"); });
        assertEquals(1,result.onlyDestination);
        assertTrue(result.samples("2").isEmpty());
        assertThrows(IllegalArgumentException.class,()->new ResultAggregate(-1));
    }
    @Test void clipsPreviewOnlyAndKeepsNullSeparateFromEmpty() {
        var row=SampleRow.of("key","4",List.of("a","b"),new String[]{null,"x".repeat(20000)},new String[]{"","y".repeat(20000)},List.of("a","b"));
        assertTrue(row.truncated);
        assertNull(row.fields.get(0).originValue);
        assertEquals("",row.fields.get(0).destinationValue);
        assertTrue(row.fields.get(1).originValue.length()<=1024);
    }
    @Test void numericKeysPreservePrecisionButIgnoreScale() {
        assertEquals("1",SampleRow.keyValue(" 1.00 ","2"));
        assertEquals("9007199254740993",SampleRow.keyValue("9007199254740993.00","2"));
        assertEquals("001",SampleRow.keyValue("001","1"));
        assertThrows(IllegalArgumentException.class,()->SampleRow.keyValue(null,"1"));
        assertThrows(IllegalArgumentException.class,()->SampleRow.keyValue(" ","1"));
    }
    @Test void compositeKeysDoNotCollide() {
        assertNotEquals(SampleRow.key(List.of("a, b","c")),SampleRow.key(List.of("a","b, c")));
    }
}
