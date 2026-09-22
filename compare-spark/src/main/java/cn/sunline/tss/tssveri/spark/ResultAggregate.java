package cn.sunline.tss.tssveri.spark;

import java.io.Serializable;
import java.util.*;
import java.util.function.Supplier;

/** Associative count + top-K reduction. No executor writes or accumulator side effects. */
public class ResultAggregate implements Serializable {
    public int limit;
    public long matched, onlyOrigin, onlyDestination, different;
    private final TreeMap<String,SampleRow> origin=new TreeMap<>(),destination=new TreeMap<>(),changed=new TreeMap<>();
    public ResultAggregate(int limit) {
        if(limit<0||limit>10000) throw new IllegalArgumentException("差异明细上限必须在0..10000之间");
        this.limit=limit;
    }
    void difference(String type,String key,Supplier<SampleRow> factory) {
        switch(type) { case "2" -> onlyDestination=Math.addExact(onlyDestination,1); case "3" -> onlyOrigin=Math.addExact(onlyOrigin,1); case "4" -> different=Math.addExact(different,1); default -> throw new IllegalArgumentException("未知差异类型"); }
        TreeMap<String,SampleRow> target=map(type);
        if(limit>0 && (target.size()<limit || key.compareTo(target.lastKey())<0)) {
            target.put(key,factory.get());
            if(target.size()>limit) target.pollLastEntry();
        }
    }
    ResultAggregate merge(ResultAggregate other) {
        if(limit!=other.limit) throw new IllegalArgumentException("样本阈值不一致");
        matched=Math.addExact(matched,other.matched);onlyOrigin=Math.addExact(onlyOrigin,other.onlyOrigin);
        onlyDestination=Math.addExact(onlyDestination,other.onlyDestination);different=Math.addExact(different,other.different);
        for(String type:List.of("2","3","4")) {
            TreeMap<String,SampleRow> target=map(type);
            other.map(type).forEach((key,value)->{
                if(limit>0 && (target.size()<limit||key.compareTo(target.lastKey())<0)) {
                    target.put(key,value);if(target.size()>limit) target.pollLastEntry();
                }
            });
        }
        return this;
    }
    private TreeMap<String,SampleRow> map(String type) {
        return switch(type) { case "2" -> destination;case "3" -> origin;case "4" -> changed;default -> throw new IllegalArgumentException("未知差异类型"); };
    }
    List<SampleRow> samples(String type) { return new ArrayList<>(map(type).values()); }
    long originCount() { return Math.addExact(Math.addExact(matched,different),onlyOrigin); }
    long destinationCount() { return Math.addExact(Math.addExact(matched,different),onlyDestination); }
}
