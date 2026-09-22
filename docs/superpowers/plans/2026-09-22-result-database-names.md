# 比对结果名称展示实施计划

目标：结果页和配置编辑页使用实际数据库连接名称。
设计：Obsidian `01 Engineering/bd/数据库比对管理台-{系统设计,数据模型,API接口}.md` 的 2026-09-22 章节。

- [x] 后端 TaskDatabaseContext 和 database-context API：完整身份校验、左连接名称查询；测试连接顺序颠倒、缺失连接/配置、404、响应不包含凭证。
- [x] 前端 databaseLabels 与差异说明函数：测试 code 2/3/4/5/未知、重名/空名、非固定原因保留。
- [x] 结果页分别显示名称和总量，缺失/值差异口径准确；配置 SQL 和字段位置标题一致；测试展开明细及接口失败回退。
- [x] Java17 service测试、UI测试/构建、浏览器回归，检查没有遗留固定新旧侧文案。
- [x] 前端内置后端源码 ZIP，移除交付版 Node 构建步骤，保留现有现场配置模板并验证 Java-only 构建。

验证：service 92 项（跳过真实 Gauss 1 项）、Spark 19 项、UI 61 项、浏览器 5 项；交付版 JDK17 `mvn clean verify` 成功。
