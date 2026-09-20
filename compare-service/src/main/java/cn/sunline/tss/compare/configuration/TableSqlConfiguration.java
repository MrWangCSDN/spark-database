package cn.sunline.tss.compare.configuration;

public record TableSqlConfiguration(long origDatabaseId,long destDatabaseId,String origSql,String destSql) {}
