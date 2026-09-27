package cn.sunline.tss.tssveri.spark;

import java.io.Serializable;
import java.util.*;

/** Exact field-pattern counts used to select a deterministic global Top N. */
final class PatternCounts implements Serializable {
    private final TreeMap<String,PatternCount> counts=new TreeMap<>();

    private static final class PatternCount implements Serializable {
        final String id,json;
        long count;
        PatternCount(FieldPattern pattern,long count) {this.id=pattern.id;this.json=pattern.json;this.count=count;}
    }

    record Selection(Set<String> retainedIds,int collapsedPatternCount,long collapsedDifferentCount,int patternSampleLimit) implements Serializable {
        Selection {
            retainedIds=Collections.unmodifiableSet(new LinkedHashSet<>(retainedIds));
        }
    }

    PatternCounts add(List<String> fields) {return add(new FieldPattern(fields),1);}

    PatternCounts add(FieldPattern pattern,long delta) {
        if(delta<=0) throw new IllegalArgumentException("字段组合计数增量必须为正数");
        var current=counts.get(pattern.id);
        if(current==null) counts.put(pattern.id,new PatternCount(pattern,delta));
        else {
            if(!current.json.equals(pattern.json)) throw new IllegalStateException("差异字段组合标识冲突");
            current.count=Math.addExact(current.count,delta);
        }
        return this;
    }

    PatternCounts merge(PatternCounts other) {
        other.counts.values().forEach(incoming->add(new FieldPattern(incoming.json,incoming.id),incoming.count));
        return this;
    }

    Map<String,Long> snapshot() {
        var result=new TreeMap<String,Long>();
        counts.forEach((id,value)->result.put(id,value.count));
        return Collections.unmodifiableMap(result);
    }

    Selection select(int retainedLimit,int configuredSampleLimit) {
        if(retainedLimit<0) throw new IllegalArgumentException("保留字段组合数不能为负数");
        if(configuredSampleLimit<0||configuredSampleLimit>10000) throw new IllegalArgumentException("差异明细上限必须在0..10000之间");
        var ranked=new ArrayList<>(counts.values());
        ranked.sort(Comparator.comparingLong((PatternCount value)->value.count).reversed().thenComparing(value->value.id));
        var retained=new LinkedHashSet<String>();
        long collapsedRows=0;
        for(int i=0;i<ranked.size();i++) {
            if(i<retainedLimit) retained.add(ranked.get(i).id);
            else collapsedRows=Math.addExact(collapsedRows,ranked.get(i).count);
        }
        int collapsedPatterns=Math.max(0,ranked.size()-retained.size());
        int sampleLimit=configuredSampleLimit==0||retained.isEmpty()?0:
            Math.max(1,Math.min(configuredSampleLimit,ResultAggregate.MAX_PATTERN_SAMPLES/retained.size()));
        return new Selection(retained,collapsedPatterns,collapsedRows,sampleLimit);
    }
}
