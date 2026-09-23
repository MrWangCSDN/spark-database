package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FieldPatternResultTest {
    @Test void countsDisjointExactCombinationsAndCapsEachAt100AcrossPartitions() {
        var combined=new ResultAggregate(100);
        var combinations=List.of(List.of("a"),List.of("a","b"),List.of("c"),List.of("a","b","c"));
        int[] counts={1000,4000,7000,8999};
        for(int i=0;i<combinations.size();i++) {
            List<String> fields=combinations.get(i);
            int remaining=counts[i];
            while(remaining>0) {
                var part=new ResultAggregate(100);
                int n=Math.min(remaining,250);
                for(int k=0;k<n;k++) {
                    String key=String.format("%s-%05d",i,remaining-k);
                    part.difference("4",key,fields,()->SampleRow.of(key,"4",List.of("a","b","c"),new String[]{"1","1","1"},new String[]{"2","2","2"},fields));
                }
                combined.merge(part);remaining-=n;
            }
        }
        assertEquals(20999,combined.different);
        assertEquals(combined.different,combined.patternCount());
        assertEquals(4,combined.patterns().size());
        assertEquals(400,combined.patternSampleCount());
        for(int i=0;i<4;i++) {
            int index=i;
            var stats=combined.patterns().stream().filter(p->p.json.equals(new FieldPattern(combinations.get(index)).json)).findFirst().orElseThrow();
            assertEquals(counts[i],stats.count);assertEquals(100,stats.sampleCount());
        }
    }
    @Test void zeroLimitStillCountsAllPatternsAndRejectsOverflow() {
        var aggregate=new ResultAggregate(0);
        aggregate.difference("4","key",List.of("a","b"),()->{throw new AssertionError("No sample expected");});
        assertEquals(1,aggregate.patternCount());assertEquals(0,aggregate.patternSampleCount());
        for(int i=1;i<ResultAggregate.MAX_PATTERNS;i++) aggregate.difference("4","key"+i,List.of("f"+i),()->{throw new AssertionError("No sample expected");});
        assertThrows(IllegalArgumentException.class,()->aggregate.difference("4","overflow",List.of("overflow"),()->null));
    }
}
