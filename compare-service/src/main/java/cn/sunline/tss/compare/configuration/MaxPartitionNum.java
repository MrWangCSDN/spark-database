package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiException;
import java.math.BigDecimal;

/** Shared validation for the persisted per-table HASH partition cap. */
public final class MaxPartitionNum {
    public static final int DEFAULT=64;
    private MaxPartitionNum() {}

    public static int effective(Integer value) {
        if(value==null) return DEFAULT;
        if(value<1||value>256) throw invalid();
        return value;
    }

    public static int fromMetadata(Object value) {
        if(value==null) return DEFAULT;
        try { return effective(new BigDecimal(value.toString()).intValueExact()); }
        catch(NumberFormatException|ArithmeticException error) { throw invalid(); }
    }

    static ApiException invalid() {
        return ApiException.badRequest("INVALID_MAX_PARTITION_NUM","最大 HASH 分区数必须为1～256的整数");
    }
}
