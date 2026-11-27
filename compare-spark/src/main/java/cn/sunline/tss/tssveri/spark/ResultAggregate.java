package cn.sunline.tss.tssveri.spark;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;

/** Associative count + bounded samples. No executor writes or retry-sensitive accumulators. */
public class ResultAggregate implements Serializable {
    static final int MAX_PATTERN_SAMPLES=20000;
    public int limit;
    public long matched, onlyOrigin, onlyDestination, different;
    private final TreeMap<String,SampleRow> origin=new TreeMap<>(),destination=new TreeMap<>(),changed=new TreeMap<>();
    private final TreeMap<String,PatternStats> patterns=new TreeMap<>();
    private final TreeMap<String,Long> fieldCounts=new TreeMap<>();
    private final Set<String> retainedPatternIds;
    private final int patternSampleLimit;
    private PatternStats collapsed;
    static final class PatternStats implements Serializable {
        final String id,json;
        final int collapsedPatternCount;
        long count;
        final TreeMap<String,SampleRow> samples=new TreeMap<>();
        PatternStats(FieldPattern pattern) { this(pattern.id,pattern.json,1); }
        PatternStats(String id,String json) { this(id,json,1); }
        PatternStats(String id,String json,int collapsedPatternCount) { this.id=id;this.json=json;this.collapsedPatternCount=collapsedPatternCount; }
        int sampleCount() { return samples.size(); }
        List<SampleRow> sampleRows() { return new ArrayList<>(samples.values()); }
    }
    public ResultAggregate(int limit) {
        this(limit,null,limit);
    }
    ResultAggregate(int limit,Set<String> retainedPatternIds,int patternSampleLimit) {
        if(limit<0||limit>10000) throw new IllegalArgumentException("差异明细上限必须在0..10000之间");
        if(patternSampleLimit<0||patternSampleLimit>10000) throw new IllegalArgumentException("字段组合样本上限必须在0..10000之间");
        this.limit=limit;
        this.retainedPatternIds=retainedPatternIds==null?null:Collections.unmodifiableSet(new HashSet<>(retainedPatternIds));
        this.patternSampleLimit=patternSampleLimit;
    }
    void difference(String type,String key,Supplier<SampleRow> factory) {
        if("4".equals(type)) throw new IllegalArgumentException("字段差异必须携带完整的字段组合");
        difference(type,key,List.of(),factory);
    }
    void difference(String type,String key,List<String> differingFields,Supplier<SampleRow> factory) {
        difference(type,key,differingFields,1,factory);
    }
    void difference(String type,String key,List<String> differingFields,long occurrences,Supplier<SampleRow> factory) {
        countDifference(type,differingFields,occurrences);
        SampleRow[] cached=new SampleRow[1];
        Supplier<SampleRow> sample=()->{ if(cached[0]==null) cached[0]=factory.get();return cached[0]; };
        PatternStats stats=null;
        if("4".equals(type)) {
            FieldPattern pattern=new FieldPattern(differingFields);
            if(retainedPatternIds==null||retainedPatternIds.contains(pattern.id)) {
                stats=patterns.get(pattern.id);
                if(stats==null) {stats=new PatternStats(pattern);patterns.put(pattern.id,stats);}
                else if(!stats.json.equals(pattern.json)) throw new IllegalStateException("差异字段组合标识冲突");
                stats.count=Math.addExact(stats.count,occurrences);
            }
        }
        TreeMap<String,SampleRow> target=map(type);
        int sampleCount=(int)Math.min(occurrences,Math.max(limit,patternSampleLimit));
        for(int occurrence=0;occurrence<sampleCount;occurrence++) {
            String sampleKey=occurrences==1?key:key+'\0'+String.format(Locale.ROOT,"%05d",occurrence);
            if(stats!=null && eligible(stats.samples,sampleKey,patternSampleLimit)) put(stats.samples,sampleKey,patternSampleLimit,sample.get());
            if(eligible(target,sampleKey,limit)) put(target,sampleKey,limit,sample.get());
        }
        if(patternSampleCount()>MAX_PATTERN_SAMPLES) throw new IllegalArgumentException("字段组合样本超过"+MAX_PATTERN_SAMPLES+"条，请降低样本上限");
    }
    void countDifference(String type,List<String> differingFields,long occurrences) {
        if(occurrences<=0) throw new IllegalArgumentException("差异行数必须为正数");
        switch(type) { case "2" -> onlyDestination=Math.addExact(onlyDestination,occurrences); case "3" -> onlyOrigin=Math.addExact(onlyOrigin,occurrences); case "4" -> different=Math.addExact(different,occurrences); default -> throw new IllegalArgumentException("未知差异类型"); }
        if("4".equals(type)) {
            for(String field:new HashSet<>(differingFields)) {
                requireFieldName(field);
                fieldCounts.merge(field,occurrences,Math::addExact);
            }
        }
    }
    ResultAggregate merge(ResultAggregate other) {
        if(limit!=other.limit||patternSampleLimit!=other.patternSampleLimit||!Objects.equals(retainedPatternIds,other.retainedPatternIds)) throw new IllegalArgumentException("样本阈值或保留组合不一致");
        if(collapsed!=null||other.collapsed!=null) throw new IllegalStateException("折叠组合只能在聚合完成后注入");
        matched=Math.addExact(matched,other.matched);onlyOrigin=Math.addExact(onlyOrigin,other.onlyOrigin);
        onlyDestination=Math.addExact(onlyDestination,other.onlyDestination);different=Math.addExact(different,other.different);
        other.fieldCounts.forEach((field,count)->fieldCounts.merge(field,count,Math::addExact));
        for(String type:List.of("2","3","4")) {
            TreeMap<String,SampleRow> target=map(type);
            other.map(type).forEach((key,value)->{ if(eligible(target,key,limit)) put(target,key,limit,value); });
        }
        other.patterns.forEach((id,incoming)->{
            PatternStats current=patterns.get(id);
            if(current==null) {
                current=new PatternStats(incoming.id,incoming.json);
                patterns.put(id,current);
            } else if(!current.json.equals(incoming.json)) throw new IllegalStateException("差异字段组合标识冲突");
            current.count=Math.addExact(current.count,incoming.count);
            TreeMap<String,SampleRow> samples=current.samples;
            incoming.samples.forEach((key,value)->{ if(eligible(samples,key,patternSampleLimit)) put(samples,key,patternSampleLimit,value); });
        });
        if(patternSampleCount()>MAX_PATTERN_SAMPLES) throw new IllegalArgumentException("字段组合样本超过"+MAX_PATTERN_SAMPLES+"条，请降低样本上限");
        return this;
    }
    void collapse(int patternCount,long differentCount) {
        if(patternCount==0&&differentCount==0) return;
        if(patternCount<=0||differentCount<=0) throw new IllegalArgumentException("折叠组合种数和差异行数必须同时为正数");
        if(collapsed!=null) throw new IllegalStateException("折叠组合已注入");
        collapsed=new PatternStats("__OTHER__","[]",patternCount);collapsed.count=differentCount;
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
    Map<String,Long> fieldCounts() { return Collections.unmodifiableMap(new TreeMap<>(fieldCounts)); }
    void validateFieldCounts() {
        if(different<0) throw new IllegalArgumentException("字段差异总数不能为负数");
        BigInteger covered=BigInteger.ZERO;
        for(var entry:fieldCounts.entrySet()) {
            requireFieldName(entry.getKey());
            long count=entry.getValue();
            if(count<=0||count>different) throw new IllegalArgumentException("字段差异计数超出有效范围");
            covered=covered.add(BigInteger.valueOf(count));
        }
        if(covered.compareTo(BigInteger.valueOf(different))<0)
            throw new IllegalArgumentException("字段差异计数未覆盖全部差异行");
    }
    private static void requireFieldName(String field) {
        if(field==null||field.isBlank()||field.length()>100)
            throw new IllegalArgumentException("差异字段名称不能为空或超过100字符");
    }
    List<PatternStats> patterns() { var result=new ArrayList<>(patterns.values());if(collapsed!=null) result.add(collapsed);return result; }
    int patternSampleCount() { return patterns.values().stream().mapToInt(PatternStats::sampleCount).sum(); }
    long patternCount() { long count=0;for(var stats:patterns.values()) count=Math.addExact(count,stats.count);return collapsed==null?count:Math.addExact(count,collapsed.count); }
    long originCount() { return Math.addExact(Math.addExact(matched,different),onlyOrigin); }
    long destinationCount() { return Math.addExact(Math.addExact(matched,different),onlyDestination); }
}
