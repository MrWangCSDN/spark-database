package cn.sunline.tss.compare.database;

public record DatabaseSummary(long databaseId, String databaseName, String url, String user,
                              String driver, String encryption, boolean passwordConfigured) {}
