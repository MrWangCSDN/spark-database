package cn.sunline.tss.tssveri.spark;

import java.io.Serializable;

/** One distinct comparison row with its distributed occurrence count. */
public final class CountedRow implements Serializable {
    public String businessKey;
    public String[] values;
    public long count;
    public boolean multiset;

    public CountedRow() {}

    CountedRow(String businessKey,String[] values,boolean multiset) {
        this.businessKey=businessKey;
        this.values=values;
        this.multiset=multiset;
        this.count=1;
    }

    CountedRow merge(CountedRow other) {
        if(!multiset || !other.multiset) {
            throw new IllegalArgumentException("SQL结果出现重复核对主键，无法按唯一主键统计差异");
        }
        count=Math.addExact(count,other.count);
        return this;
    }
}
