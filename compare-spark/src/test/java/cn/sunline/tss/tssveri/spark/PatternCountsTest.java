package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatternCountsTest {
    @Test void keeps127AndCollapsesTheRestWithExactCounts() {
        var counts=new PatternCounts();
        for(int i=0;i<130;i++) for(int n=0;n<=i;n++) counts.add(List.of("f"+i));
        var selection=counts.select(127,1000);
        assertEquals(127,selection.retainedIds().size());
        assertEquals(3,selection.collapsedPatternCount());
        assertEquals(6,selection.collapsedDifferentCount());
        assertEquals(157,selection.patternSampleLimit());
    }

    @Test void usesPatternIdAsStableTieBreakerAndDoesNotCollapse127() {
        var counts=new PatternCounts();
        var ids=new ArrayList<String>();
        for(int i=0;i<128;i++) {
            var fields=List.of("same-count-"+i);
            counts.add(fields);ids.add(new FieldPattern(fields).id);
        }
        var selection=counts.select(127,1000);
        ids.sort(String::compareTo);
        assertEquals(new LinkedHashSet<>(ids.subList(0,127)),selection.retainedIds());
        var exact=new PatternCounts();
        for(int i=0;i<127;i++) exact.add(List.of("f"+i));
        assertEquals(0,exact.select(127,1000).collapsedPatternCount());
    }

    @Test void mergeIsOrderIndependentUsesExactArithmeticAndHonorsZeroSampleLimit() {
        var leftThenRight=new PatternCounts().add(List.of("a")).merge(new PatternCounts().add(List.of("b")));
        var rightThenLeft=new PatternCounts().add(List.of("b")).merge(new PatternCounts().add(List.of("a")));
        assertEquals(leftThenRight.snapshot(),rightThenLeft.snapshot());
        assertEquals(0,leftThenRight.select(127,0).patternSampleLimit());
        var overflow=new PatternCounts();
        var pattern=new FieldPattern(List.of("overflow"));
        overflow.add(pattern,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->overflow.add(pattern,1));
    }
}
