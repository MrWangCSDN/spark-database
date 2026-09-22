package cn.sunline.tss.compare.batch;

import java.util.List;

public record BatchPreview(String fingerprint, String taskBusinessDate, String verifyBusinessDate,
                           int totalCount, int readyCount, int blockedCount, int changedCount,
                           List<BatchPreviewItem> items) {}
