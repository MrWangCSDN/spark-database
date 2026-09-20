package cn.sunline.tss.compare.database;

public record DatabaseSaveRequest(long databaseId, String databaseName, String url, String user,
                                  String driver, String encryption, String newPassword) {}
