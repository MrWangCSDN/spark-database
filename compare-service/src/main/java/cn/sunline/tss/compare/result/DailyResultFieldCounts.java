package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import java.math.BigInteger;
import java.util.*;

/** Exact per-field counts. Never infer full totals from samples or collapsed patterns. */
final class DailyResultFieldCounts {
    record StoredFieldCount(String runId,String fieldName,long differentCount) {}
    private DailyResultFieldCounts() {}

    static Map<String,Long> resolve(DailyResultSummary summary,int version,List<StoredFieldCount> counts,
                                   List<DailyResultRepository.FieldPatternResult> patterns) {
        if(summary.differentCount()<0) throw invalid("表级差异数量不能为负数");
        var result=new TreeMap<String,Long>();
        try {
            if(version==1) {
                for(var count:counts) {
                    if(!summary.runId().equals(count.runId())) throw invalid("字段统计结果版本不一致");
                    requireField(count.fieldName());
                    if(result.putIfAbsent(count.fieldName(),count.differentCount())!=null) throw invalid("字段统计名称重复");
                }
            } else if(version==0) {
                if(summary.differentCount()==0) return Map.of();
                long covered=0;
                for(var pattern:patterns) {
                    if("__OTHER__".equals(pattern.patternId())) throw unavailable();
                    if(pattern.fields()==null||pattern.fields().isEmpty()||pattern.differentCount()<=0||pattern.collapsedPatternCount()!=1)
                        throw invalid("历史字段组合内容不完整或数量非法");
                    covered=Math.addExact(covered,pattern.differentCount());
                    for(String field:new HashSet<>(pattern.fields())) {
                        requireField(field);
                        result.merge(field,pattern.differentCount(),Math::addExact);
                    }
                }
                if(covered>summary.differentCount()) throw invalid("历史字段组合数量超出表级差异总数");
                if(covered<summary.differentCount()) throw unavailable();
            } else throw invalid("字段统计协议版本不支持");
        } catch(ArithmeticException error) {
            throw ApiException.internal("RESULT_EXPORT_FAILED","字段统计数量溢出",error);
        }
        BigInteger covered=BigInteger.ZERO;
        for(long count:result.values()) {
            if(count<=0||count>summary.differentCount()) throw invalid("字段统计数量超出有效范围");
            covered=covered.add(BigInteger.valueOf(count));
        }
        if(covered.compareTo(BigInteger.valueOf(summary.differentCount()))<0) throw invalid("字段统计未覆盖全部差异行");
        return Collections.unmodifiableMap(result);
    }
    private static void requireField(String name) {
        if(name==null||name.isBlank()||name.length()>100) throw invalid("字段名称不能为空或超过100字符");
    }
    private static ApiException unavailable() {
        return ApiException.conflict("FIELD_COUNTS_UNAVAILABLE","历史结果缺少完整字段统计，请重新核对后导出");
    }
    private static ApiException invalid(String message) { return ApiException.internal("RESULT_EXPORT_FAILED",message,null); }
}
