package cn.sunline.tss.compare.batch;

public record BatchPreviewItem(long bcompIndex, String configurationName, String module,
                               String timeNode, String verifyBusinessDate, boolean ready,
                               String issueCode, String issueMessage, boolean configurationChanged) {}
