package cn.sunline.tss.compare.result;

public record DailyResultSummary(long bcompIndex,String resultDate,String runId,String taskBusiDate,String taskBatch,
    String timeNode,String configurationName,String configurationModule,String reviserName,String reviserUsername,Long originDatabaseId,String originDatabaseName,Long destinationDatabaseId,
    String destinationDatabaseName,long originCount,long destinationCount,long matchedCount,long onlyOriginCount,
    long onlyDestinationCount,long differentCount,int detailLimit,long originSamples,long destinationSamples,
    long differentSamples,String publishedAt,String detailsExpired) {
    public DailyResultSummary(long bcompIndex,String resultDate,String runId,String taskBusiDate,String taskBatch,
            String timeNode,String configurationName,String configurationModule,Long originDatabaseId,String originDatabaseName,
            Long destinationDatabaseId,String destinationDatabaseName,long originCount,long destinationCount,long matchedCount,
            long onlyOriginCount,long onlyDestinationCount,long differentCount,int detailLimit,long originSamples,
            long destinationSamples,long differentSamples,String publishedAt,String detailsExpired) {
        this(bcompIndex,resultDate,runId,taskBusiDate,taskBatch,timeNode,configurationName,configurationModule,null,null,
            originDatabaseId,originDatabaseName,destinationDatabaseId,destinationDatabaseName,originCount,destinationCount,
            matchedCount,onlyOriginCount,onlyDestinationCount,differentCount,detailLimit,originSamples,destinationSamples,
            differentSamples,publishedAt,detailsExpired);
    }
}
