package cn.sunline.tss.compare.task;

public record DifferenceSummary(String businessKey, String oldContent, String newContent,
                                String lineResult, String reason) {}
