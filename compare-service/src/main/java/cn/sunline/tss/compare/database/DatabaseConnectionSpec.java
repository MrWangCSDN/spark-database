package cn.sunline.tss.compare.database;

public record DatabaseConnectionSpec(
    String url,
    String user,
    String password,
    String driver
) {}
