package cn.sunline.tss.compare.batch;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

public record BatchRequest(String scope,
                           @JsonDeserialize(contentUsing = StrictConfigurationIdDeserializer.class) List<Long> configurationIds,
                           @JsonDeserialize(contentUsing = StrictConfigurationIdDeserializer.class) List<Long> excludedIds,
                           String taskBusinessDate, String verifyBusinessDate, String timeNode,
                           String sourceBatchId, String requestId, String previewFingerprint) {}
