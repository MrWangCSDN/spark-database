package cn.sunline.tss.compare.result;

import java.util.List;
import java.util.Map;

/** Fully materialized in a short transaction; rendering never holds result locks. */
public record DailyResultExportSnapshot(List<TableResult> results) {
    public DailyResultExportSnapshot { results=List.copyOf(results); }
    public record TableResult(DailyResultSummary summary,Map<String,Long> fieldCounts) {
        public TableResult { fieldCounts=Map.copyOf(fieldCounts); }
    }
}
