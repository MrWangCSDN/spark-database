package cn.sunline.tss.tssveri.spark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.*;

/** Bounded display values; all comparison happens before this projection. */
public class SampleRow implements Serializable {
    private static final ObjectMapper JSON = new ObjectMapper();
    public String key;
    public String type;
    public List<FieldValue> fields = new ArrayList<>();
    public boolean truncated;
    public SampleRow() {}
    public static class FieldValue implements Serializable {
        public String name;
        public String label;
        public String originValue;
        public String destinationValue;
        public FieldValue() {}
        FieldValue(String name,String label,String a,String b) { this.name=name;this.label=label;originValue=a;destinationValue=b; }
    }
    static String keyValue(String value,String fieldType) {
        if(value==null) return null;
        String trimmed=value.trim();
        if(trimmed.isEmpty()) return trimmed;
        return "2".equals(fieldType) ? new java.math.BigDecimal(trimmed).stripTrailingZeros().toPlainString() : trimmed;
    }
    static String key(List<String> values) {
        try { return JSON.writeValueAsString(values); }
        catch(JsonProcessingException e) { throw new IllegalArgumentException("核对键无法编码",e); }
    }
    static SampleRow of(String key,String type,List<String> names,String[] a,String[] b,List<String> different) {
        return of(key,type,names,names,a,b,different);
    }
    static SampleRow of(String key,String type,List<String> names,List<String> labels,String[] a,String[] b,List<String> different) {
        if(key.length()>1000) throw new IllegalArgumentException("核对键超过1000字符，无法保存结果");
        if(names.size()!=labels.size()) throw new IllegalArgumentException("字段名称和中文名称数量不一致");
        SampleRow row=new SampleRow();row.key=key;row.type=type;
        int remaining=8192;
        for(int i=0;i<names.size();i++) {
            if("4".equals(type) && !different.contains(names.get(i))) continue;
            String memo=labels.get(i);
            String label=memo==null||memo.isBlank()?names.get(i):memo.trim();
            if(label.length()>80) {label=clip(label,80);row.truncated=true;}
            if(row.fields.size()>=200 || remaining<names.get(i).length()+label.length()+2) { row.truncated=true;break; }
            remaining-=names.get(i).length()+label.length();
            String av=a==null?null:a[i],bv=b==null?null:b[i];
            String ac=clip(av,Math.min(1024,remaining));remaining-=ac==null?0:ac.length();
            String bc=clip(bv,Math.min(1024,remaining));remaining-=bc==null?0:bc.length();
            if(!Objects.equals(av,ac)||!Objects.equals(bv,bc)) row.truncated=true;
            row.fields.add(new FieldValue(names.get(i),label,ac,bc));
        }
        return row;
    }
    private static String clip(String value,int max) {
        if(value==null || value.length()<=max) return value;
        // Do not split a UTF-16 surrogate pair.
        int end=max;
        if(end>0 && Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end);
    }
    String json() throws JsonProcessingException { return JSON.writeValueAsString(this); }
}
