package cn.sunline.tss.compare.task;

public record TaskSummary(long bcompIndex, String configurationName, String taskBusinessDate,
                          String verifyBusinessDate, String taskBatch, String timeNode,
                          String createdAt, String startedAt, String endedAt,
                          String taskState, String taskResult, String failureReason,
                          Long file1Count, Long file2Count, Long file1SuccessCount,
                          Long file2SuccessCount, Long file1FailureCount, Long file2FailureCount,
                          String readMode) {}
