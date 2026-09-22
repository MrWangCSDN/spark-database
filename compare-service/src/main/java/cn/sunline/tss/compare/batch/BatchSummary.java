package cn.sunline.tss.compare.batch;

public record BatchSummary(String batchId, String taskBusinessDate, String verifyBusinessDate,
                           String createdAt, String sourceBatchId, int totalCount, int waitingCount,
                           int runningCount, int consistentCount, int differenceCount, int failedCount,
                           int otherCount, int endedCount) {}
