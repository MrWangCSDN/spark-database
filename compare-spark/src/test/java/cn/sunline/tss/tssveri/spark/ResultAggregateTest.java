package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResultAggregateTest {
    @Test void mergesCountsButCapsEachCategoryGlobally() {
        var combined = new ResultAggregate(10);
        for (int p=0;p<20;p++) {
            var part = new ResultAggregate(10);
            for(int n=0;n<100;n++) for(String type:List.of("2","3","4")) {
                String key=String.format("%03d-%03d",p,n);
                part.difference(type,key,"4".equals(type)?List.of("amount"):List.of(),() -> SampleRow.of(key,type,List.of("amount"),new String[]{"100"},new String[]{"200"},List.of("amount")));
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
    @Test void bulkDifferencesKeepLongCountsAndBoundDuplicateSamples() {
        long occurrences=(long)Integer.MAX_VALUE+47;
        for(int limit:new int[]{0,1,3}) {
            var result=new ResultAggregate(limit);
            var materialized=new AtomicInteger();
            for(String type:List.of("2","3","4")) {
                result.difference(type,"[\"internal-content\"]",List.of("amount"),occurrences,()-> {
                    materialized.incrementAndGet();
                    return SampleRow.of("[null]",type,List.of("amount"),new String[]{"100"},new String[]{"200"},List.of("amount"));
                });
                assertEquals(limit,result.samples(type).size());
                assertTrue(result.samples(type).stream().allMatch(row->"[null]".equals(row.key)));
            }
            assertEquals(occurrences,result.onlyOrigin);
            assertEquals(occurrences,result.onlyDestination);
            assertEquals(occurrences,result.different);
            assertEquals(occurrences,result.patternCount());
            assertEquals(limit,result.patternSampleCount());
            assertEquals(limit==0?0:3,materialized.get());
        }
    }
    @Test void bulkPatternSamplesRespectTheirSeparateLimit() {
        var result=new ResultAggregate(3,null,1);
        result.difference("4","[\"content\"]",List.of("amount"),7,
            ()->SampleRow.of("display-key","4",List.of("amount"),new String[]{"100"},new String[]{"200"},List.of("amount")));
        assertEquals(7,result.different);
        assertEquals(7,result.patternCount());
        assertEquals(3,result.samples("4").size());
        assertEquals(1,result.patternSampleCount());
    }
    @Test void bulkSamplesRemainDistinctAndStableAcrossMergeOrder() {
        var first=new ResultAggregate(4);
        var second=new ResultAggregate(4);
        for(String type:List.of("2","3","4")) {
            first.difference(type,"[\"a\"]",List.of("amount"),3,
                ()->SampleRow.of("same-displayed-key",type,List.of("amount"),new String[]{"a"},new String[]{"b"},List.of("amount")));
            second.difference(type,"[\"b\"]",List.of("amount"),3,
                ()->SampleRow.of("same-displayed-key",type,List.of("amount"),new String[]{"b"},new String[]{"c"},List.of("amount")));
        }
        var forward=new ResultAggregate(4).merge(first).merge(second);
        var reverse=new ResultAggregate(4).merge(second).merge(first);
        for(String type:List.of("2","3","4")) {
            assertEquals(4,forward.samples(type).size());
            assertEquals(List.of("a","a","a","b"),forward.samples(type).stream().map(row->row.fields.get(0).originValue).toList());
            assertEquals(forward.samples(type),reverse.samples(type));
            assertTrue(forward.samples(type).stream().allMatch(row->"same-displayed-key".equals(row.key)));
        }
        assertEquals(6,forward.onlyOrigin);
        assertEquals(6,forward.onlyDestination);
        assertEquals(6,forward.different);
        assertEquals(6,forward.patternCount());
        assertEquals(4,forward.patternSampleCount());
        assertEquals(forward.patterns().get(0).sampleRows(),reverse.patterns().get(0).sampleRows());
    }
    @Test void bulkCountsRejectNonpositiveOccurrencesAndOverflow() {
        var result=new ResultAggregate(0);
        for(long occurrences:new long[]{0,-1}) {
            assertThrows(IllegalArgumentException.class,()->result.difference("2","key",List.of(),occurrences,()->null));
        }
        result.difference("2","key",List.of(),Long.MAX_VALUE,()-> { throw new AssertionError("sample should not be built"); });
        assertThrows(ArithmeticException.class,()->result.difference("2","other",List.of(),1,()->null));
        assertEquals(Long.MAX_VALUE,result.onlyDestination);
    }
    @Test void clipsPreviewOnlyAndKeepsNullSeparateFromEmpty() {
        var row=SampleRow.of("key","4",List.of("a","b"),new String[]{null,"x".repeat(20000)},new String[]{"","y".repeat(20000)},List.of("a","b"));
        assertTrue(row.truncated);
        assertNull(row.fields.get(0).originValue);
        assertEquals("",row.fields.get(0).destinationValue);
        assertTrue(row.fields.get(1).originValue.length()<=1024);
    }
    @Test void snapshotsChineseFieldLabelsAndFallsBackToNames() throws Exception {
        var row=SampleRow.of("key","3",List.of("ID","CUSTOMER_NO"),List.of("编号","  "),
            new String[]{"1","C001"},null,List.of());
        assertEquals("编号",row.fields.get(0).label);
        assertEquals("CUSTOMER_NO",row.fields.get(1).label);
        assertTrue(row.json().contains("\"label\":\"编号\""));
    }
    @Test void numericKeysPreservePrecisionButIgnoreScale() {
        assertEquals("1",SampleRow.keyValue(" 1.00 ","2"));
        assertEquals("9007199254740993",SampleRow.keyValue("9007199254740993.00","2"));
        assertEquals("001",SampleRow.keyValue("001","1"));
        assertNull(SampleRow.keyValue(null,"1"));
        assertEquals("",SampleRow.keyValue(" ","1"));
        assertNull(SampleRow.keyValue(null,"2"));
        assertEquals("",SampleRow.keyValue(" ","2"));
    }
    @Test void compositeKeysDoNotCollide() {
        assertNotEquals(SampleRow.key(List.of("a, b","c")),SampleRow.key(List.of("a","b, c")));
    }
}
