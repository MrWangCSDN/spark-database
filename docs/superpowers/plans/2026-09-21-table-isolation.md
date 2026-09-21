# 六表隔离与双版本下载实施计划

设计来源：/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/业务表隔离切换-系统设计.md

1. 测试数据切换到六张新表，确认现有查询失败。
2. 切换 bd 服务、Spark、导入白名单和页面说明，提供六表建表 SQL。
3. Axon 增加 LEGACY/NEW 下载选择，保持原文件不变，只转换 SQL 标识符，验证字符串不受影响。
4. 运行服务、Spark、Axon 相关测试及前端构建，检查交付包，生成含预编译前端的源码 ZIP。

保留已有启停脚本和 POM 修改。旧数据不迁移。

## 验证记录

- 仅新表测试：旧任务查询失败，切换后服务全套通过。
- 审查发现并修复：BIGINT/BigDecimal 不兼容；SparkCompatibilityTest 切换 BIGINT 后复现 ClassCastException，按 Number 读取修复。
- 审查发现并修复：旧表缺分区列；legacyDropsOnlyUnsupportedPartitionColumnsAndTheirValues 先失败，再实现旧版8列导出。
- 真实 GaussDB/Spark 集群验证由现场完成，本地使用 H2 和 local Spark。
