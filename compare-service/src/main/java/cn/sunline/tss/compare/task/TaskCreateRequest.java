package cn.sunline.tss.compare.task;

public record TaskCreateRequest(long bcompIndex, String taskBusinessDate, String verifyBusinessDate,
                                String taskBatch, String timeNode) {}
