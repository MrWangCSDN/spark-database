package cn.sunline.tss.compare.database;

public record DatabaseConnectionTestResult(
    boolean success,
    String databaseProduct,
    String databaseVersion,
    long elapsedMs
) {}
