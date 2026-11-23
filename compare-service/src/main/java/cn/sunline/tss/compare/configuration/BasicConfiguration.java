package cn.sunline.tss.compare.configuration;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record BasicConfiguration(long bcompIndex,String name,String module,String memo,String range,
                                 String timeNode,String state,Integer partitionNum,String dateMode,
                                 @JsonDeserialize(using=StrictMaxPartitionNumDeserializer.class) Integer maxPartitionNum) {}
