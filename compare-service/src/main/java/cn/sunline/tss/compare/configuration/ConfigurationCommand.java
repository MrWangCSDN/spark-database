package cn.sunline.tss.compare.configuration;

import java.util.List;

public record ConfigurationCommand(BasicConfiguration basic,TableSqlConfiguration tableSql,List<FieldRule> fields) {}
