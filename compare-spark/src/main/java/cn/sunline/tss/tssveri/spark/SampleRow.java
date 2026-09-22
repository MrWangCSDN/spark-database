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
        public String originValue;
        public String destinationValue;
        public FieldValue() {}
        FieldValue(String name,String a,String b) { this.name=name;originValue=a;destinationValue=b; }
    }
    static String keyValue(String value,String fieldType) {
        if(value==null||value.trim().isEmpty()) throw new IllegalArgumentException("SQL结果出现空核对主键");
        return "2".equals(fieldType) ? new java.math.BigDecimal(value.trim()).stripTrailingZeros().toPlainString() : value.trim();
    }
    static String key(List<String> values) {
        try { return JSON.writeValueAsString(values); }
        catch(JsonProcessingException e) { throw new IllegalArgumentException("核对键无法编码",e); }
    }
    static SampleRow of(String key,String type,List<String> names,String[] a,String[] b,List<String> different) {
        if(key.length()>1000) throw new IllegalArgumentException("核对键超过1000字符，无法保存结果");
        SampleRow row=new SampleRow();row.key=key;row.type=type;
        int remaining=8192;
        for(int i=0;i<names.size();i++) {
            if("4".equals(type) && !different.contains(names.get(i))) continue;
            if(row.fields.size()>=200 || remaining<names.get(i).length()+2) { row.truncated=true;break; }
            remaining-=names.get(i).length();
            String av=a==null?null:a[i],bv=b==null?null:b[i];
            String ac=clip(av,Math.min(1024,remaining));remaining-=ac==null?0:ac.length();
            String bc=clip(bv,Math.min(1024,remaining));remaining-=bc==null?0:bc.length();
            if(!Objects.equals(av,ac)||!Objects.equals(bv,bc)) row.truncated=true;
            row.fields.add(new FieldValue(names.get(i),ac,bc));
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
