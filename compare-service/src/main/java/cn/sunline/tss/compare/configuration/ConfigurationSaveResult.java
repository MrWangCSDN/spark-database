package cn.sunline.tss.compare.configuration;

public record ConfigurationSaveResult(ConfigurationDetail configuration,boolean saved,
                                      boolean cacheRefreshed,String warning) {}
