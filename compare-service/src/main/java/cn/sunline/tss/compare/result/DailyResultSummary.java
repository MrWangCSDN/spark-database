package cn.sunline.tss.compare.result;

public record DailyResultSummary(long bcompIndex,String resultDate,String runId,String taskBusiDate,String taskBatch,
    String timeNode,String configurationName,String configurationModule,Long originDatabaseId,String originDatabaseName,Long destinationDatabaseId,
    String destinationDatabaseName,long originCount,long destinationCount,long matchedCount,long onlyOriginCount,
    long onlyDestinationCount,long differentCount,int detailLimit,int originSamples,int destinationSamples,
    int differentSamples,String publishedAt,String detailsExpired) {}
