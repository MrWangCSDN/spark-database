package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DailyResultFieldCountsTest {
    @Test void sumsCompleteLegacyPatternsExactly() {
        var counts=DailyResultFieldCounts.resolve(summary(260),0,List.of(),List.of(
            pattern("a",List.of("A","A"),80),pattern("ab",List.of("A","B"),180)));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("A",260L,"B",180L));
        assertThatThrownBy(()->counts.put("X",1L)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsCollapsedOrMissingLegacyStatistics() {
        for(var patterns:List.of(List.<DailyResultRepository.FieldPatternResult>of(),
            List.of(pattern("a",List.of("A"),1)),
            List.of(new DailyResultRepository.FieldPatternResult("__OTHER__",List.of(),2,0,30000))))
            assertError("FIELD_COUNTS_UNAVAILABLE",()->DailyResultFieldCounts.resolve(summary(2),0,List.of(),patterns));
        for(var bad:List.of(pattern("a",List.of(),2),pattern("a",List.of(""),2),
            pattern("a",List.of("A"),0),pattern("a",List.of("A"),3)))
            assertError("RESULT_EXPORT_FAILED",()->DailyResultFieldCounts.resolve(summary(2),0,List.of(),List.of(bad)));
    }
    @Test void validatesNativeCountsWithoutFallingBack() {
        var fallback=List.of(pattern("a",List.of("A"),2));
        for(var bad:List.of(List.<DailyResultFieldCounts.StoredFieldCount>of(),
            List.of(new DailyResultFieldCounts.StoredFieldCount("old","A",2)),
            List.of(new DailyResultFieldCounts.StoredFieldCount("run","A",-1)),
            List.of(new DailyResultFieldCounts.StoredFieldCount("run","A",3)),
            List.of(new DailyResultFieldCounts.StoredFieldCount("run","A",1))))
            assertError("RESULT_EXPORT_FAILED",()->DailyResultFieldCounts.resolve(summary(2),1,bad,fallback));
        assertError("RESULT_EXPORT_FAILED",()->DailyResultFieldCounts.resolve(summary(0),2,List.of(),List.of()));
    }
    @Test void acceptsZeroDifferencesAndLargeLongCounts() {
        for(int version:List.of(0,1)) assertThat(DailyResultFieldCounts.resolve(summary(0),version,List.of(),List.of())).isEmpty();
        var counts=DailyResultFieldCounts.resolve(summary(200_000_000),1,
            List.of(new DailyResultFieldCounts.StoredFieldCount("run","A",200_000_000L)),List.of());
        assertThat(counts).containsEntry("A",200_000_000L);
        assertError("RESULT_EXPORT_FAILED",()->DailyResultFieldCounts.resolve(summary(0),1,
            List.of(new DailyResultFieldCounts.StoredFieldCount("run","A",1)),List.of()));
    }
    @Test void validatesNamesDuplicatesAndSumWithoutLongOverflow() {
        for(String name:List.of(" ","x".repeat(101))) assertError("RESULT_EXPORT_FAILED",()->DailyResultFieldCounts.resolve(summary(1),1,
            List.of(new DailyResultFieldCounts.StoredFieldCount("run",name,1)),List.of()));
        var one=new DailyResultFieldCounts.StoredFieldCount("run","A",1);
        assertError("RESULT_EXPORT_FAILED",()->DailyResultFieldCounts.resolve(summary(2),1,List.of(one,one),List.of()));
        var counts=DailyResultFieldCounts.resolve(summary(Long.MAX_VALUE),1,List.of(
            new DailyResultFieldCounts.StoredFieldCount("run","A",Long.MAX_VALUE),
            new DailyResultFieldCounts.StoredFieldCount("run","B",Long.MAX_VALUE)),List.of());
        assertThat(counts).containsEntry("A",Long.MAX_VALUE).containsEntry("B",Long.MAX_VALUE);
    }
    static DailyResultSummary summary(long different) {
        return new DailyResultSummary(1,"20260928","run","20260928","batch","D","表","dept",null,null,1L,"来源",2L,"目标",
            different,different,0,0,0,different,0,0,0,0,"20260928120000","0");
    }
    private static DailyResultRepository.FieldPatternResult pattern(String id,List<String> fields,long count) {
        return new DailyResultRepository.FieldPatternResult(id,fields,count,0,1);
    }
    private static void assertError(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(code));
    }
}
