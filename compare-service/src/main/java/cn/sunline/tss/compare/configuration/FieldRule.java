package cn.sunline.tss.compare.configuration;

public record FieldRule(String name,String type,String memo,long oldIndex,long newIndex,
                        String indexFlag,String enumFlag,Long enumIndex,String partitionFlag,
                        String ignoreValue,String ignoreFlag) {}
