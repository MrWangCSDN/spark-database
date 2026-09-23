package cn.sunline.tss.tssveri.spark;

import java.io.Serializable;
import java.util.*;
import java.util.function.Supplier;

/** Associative count + bounded samples. No executor writes or retry-sensitive accumulators. */
public class ResultAggregate implements Serializable {
    static final int MAX_PATTERNS=128, MAX_PATTERN_SAMPLES=20000;
    public int limit;
    public long matched, onlyOrigin, onlyDestination, different;
    private final TreeMap<String,SampleRow> origin=new TreeMap<>(),destination=new TreeMap<>(),changed=new TreeMap<>();
    private final TreeMap<String,PatternStats> patterns=new TreeMap<>();
    static final class PatternStats implements Serializable {
        final String id,json;
        long count;
        final TreeMap<String,SampleRow> samples=new TreeMap<>();
        PatternStats(FieldPattern pattern) { this(pattern.id,pattern.json); }
        PatternStats(String id,String json) { this.id=id;this.json=json; }
        int sampleCount() { return samples.size(); }
        List<SampleRow> sampleRows() { return new ArrayList<>(samples.values()); }
    }
    public ResultAggregate(int limit) {
        if(limit<0||limit>10000) throw new IllegalArgumentException("差异明细上限必须在0..10000之间");
        this.limit=limit;
    }
    void difference(String type,String key,Supplier<SampleRow> factory) {
        if("4".equals(type)) throw new IllegalArgumentException("字段差异必须携带完整的字段组合");
        difference(type,key,List.of(),factory);
    }
    void difference(String type,String key,List<String> differingFields,Supplier<SampleRow> factory) {
        switch(type) { case "2" -> onlyDestination=Math.addExact(onlyDestination,1); case "3" -> onlyOrigin=Math.addExact(onlyOrigin,1); case "4" -> different=Math.addExact(different,1); default -> throw new IllegalArgumentException("未知差异类型"); }
        SampleRow[] cached=new SampleRow[1];
        Supplier<SampleRow> sample=()->{ if(cached[0]==null) cached[0]=factory.get();return cached[0]; };
        if("4".equals(type)) {
            FieldPattern pattern=new FieldPattern(differingFields);
            PatternStats stats=patterns.get(pattern.id);
            if(stats==null) {
                if(patterns.size()>=MAX_PATTERNS) throw new IllegalArgumentException("差异字段组合超过"+MAX_PATTERNS+"种，请缩小比对字段范围");
                stats=new PatternStats(pattern);patterns.put(pattern.id,stats);
            } else if(!stats.json.equals(pattern.json)) throw new IllegalStateException("差异字段组合标识冲突");
            stats.count=Math.addExact(stats.count,1);
            if(eligible(stats.samples,key,limit)) put(stats.samples,key,limit,sample.get());
            if(patternSampleCount()>MAX_PATTERN_SAMPLES) throw new IllegalArgumentException("字段组合样本超过"+MAX_PATTERN_SAMPLES+"条，请降低样本上限");
        }
        TreeMap<String,SampleRow> target=map(type);
        if(eligible(target,key,limit)) put(target,key,limit,sample.get());
    }
    ResultAggregate merge(ResultAggregate other) {
        if(limit!=other.limit) throw new IllegalArgumentException("样本阈值不一致");
        matched=Math.addExact(matched,other.matched);onlyOrigin=Math.addExact(onlyOrigin,other.onlyOrigin);
        onlyDestination=Math.addExact(onlyDestination,other.onlyDestination);different=Math.addExact(different,other.different);
        for(String type:List.of("2","3","4")) {
            TreeMap<String,SampleRow> target=map(type);
            other.map(type).forEach((key,value)->{ if(eligible(target,key,limit)) put(target,key,limit,value); });
        }
        other.patterns.forEach((id,incoming)->{
            PatternStats current=patterns.get(id);
            if(current==null) {
                if(patterns.size()>=MAX_PATTERNS) throw new IllegalArgumentException("差异字段组合超过"+MAX_PATTERNS+"种，请缩小比对字段范围");
                current=new PatternStats(incoming.id,incoming.json);
                patterns.put(id,current);
            } else if(!current.json.equals(incoming.json)) throw new IllegalStateException("差异字段组合标识冲突");
            current.count=Math.addExact(current.count,incoming.count);
            TreeMap<String,SampleRow> samples=current.samples;
            incoming.samples.forEach((key,value)->{ if(eligible(samples,key,limit)) put(samples,key,limit,value); });
        });
        if(patternSampleCount()>MAX_PATTERN_SAMPLES) throw new IllegalArgumentException("字段组合样本超过"+MAX_PATTERN_SAMPLES+"条，请降低样本上限");
        return this;
    }
    private static boolean eligible(TreeMap<String,SampleRow> map,String key,int limit) {
        return limit>0 && (map.size()<limit || key.compareTo(map.lastKey())<0);
    }
    private static void put(TreeMap<String,SampleRow> map,String key,int limit,SampleRow value) {
        map.put(key,value);if(map.size()>limit) map.pollLastEntry();
    }
    private TreeMap<String,SampleRow> map(String type) {
        return switch(type) { case "2" -> destination;case "3" -> origin;case "4" -> changed;default -> throw new IllegalArgumentException("未知差异类型"); };
    }
    List<SampleRow> samples(String type) { return new ArrayList<>(map(type).values()); }
    List<PatternStats> patterns() { return new ArrayList<>(patterns.values()); }
    int patternSampleCount() { return patterns.values().stream().mapToInt(PatternStats::sampleCount).sum(); }
    long patternCount() { long count=0;for(var stats:patterns.values()) count=Math.addExact(count,stats.count);return count; }
    long originCount() { return Math.addExact(Math.addExact(matched,different),onlyOrigin); }
    long destinationCount() { return Math.addExact(Math.addExact(matched,different),onlyDestination); }
}
