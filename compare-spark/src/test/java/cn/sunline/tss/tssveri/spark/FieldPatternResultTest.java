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
    @Test void zeroLimitRetainsTop127AndAccountsForCollapsedPatternWithoutSamples() {
        var counts=new PatternCounts();
        var combinations=new ArrayList<List<String>>();
        for(int i=0;i<128;i++) {var fields=List.of("f"+i);combinations.add(fields);counts.add(fields);}
        var selection=counts.select(127,0);
        var aggregate=new ResultAggregate(0,selection.retainedIds(),selection.patternSampleLimit());
        for(int i=0;i<combinations.size();i++) {
            int index=i;
            aggregate.difference("4","key"+i,combinations.get(i),()->{throw new AssertionError("No sample expected for "+index);});
        }
        aggregate.collapse(selection.collapsedPatternCount(),selection.collapsedDifferentCount());
        assertEquals(128,aggregate.different);
        assertEquals(128,aggregate.patternCount());
        assertEquals(128,aggregate.patterns().size());
        assertEquals(0,aggregate.patternSampleCount());
        var other=aggregate.patterns().get(127);
        assertEquals("__OTHER__",other.id);
        assertEquals(1,other.collapsedPatternCount);
        assertEquals(1,other.count);
    }
}
