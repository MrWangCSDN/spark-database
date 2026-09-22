# Batch Workbench Implementation Plan

**Goal:** 一次创建全量核对任务、按批次看进度及只重跑失败项。
**Architecture:** 持久批次和条目快照，预检指纹，事务批量入队，复用现有Worker/Spark。
**Tech Stack:** Java17 / Spring Boot3.3 / JDBC / React18 / TypeScript / Vite。
**Spec:** /Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/批量核对工作台-系统设计.md（另见数据模型、API接口）。

## Global Constraints
- 原9张_NEW表和Spark语义保持；新增2张_NEW批次表，提供增量DDL。
- 全量由后端解析，上限5000项；批次号不超过20字符。
- 阻塞项整批拒绝；预检变动拒绝；重复请求返回同批次。
- 单服务实例控制面锁串行变更，历史不依赖当前配置存在。

## Review Focus
- 200项跨100条分页是否全部进入同事务。
- 网络重试和配置变动是否造成重复或不同于预检的任务。
- 已有活跃任务和导入是否与批量入队交错。
- 失败重跑是否保持日期/时点且不重跑差异项。
- 新增DDL缺失、历史配置删除及异常任务状态的展示。

## Task 1: Backend batch API
- [x] 在 batch 包新增请求/预检/汇总对象、Service/Repository/Controller，契约以API设计为准。
- [x] 先写H2/MockMvc测试：200项全量、幂等、整批回滚、过期指纹、失败重跑、历史快照。
- [x] 执行测试观察失败，实现后运行 mvn -pl compare-service test -Dskip.installnodenpm -Dskip.npm。
- [x] 实现控制面锁与配置变更协同；提交前缓存刷新；稳定错误。

## Task 2: UI workflow
- [x] 新增 BatchCreatePage、BatchesPage、BatchDetailPage；扩展api/types，改AppShell/router/Dashboard。
- [x] 配置页跨页勾选，导入后缓存就绪才给发起入口；保留所有任务入口。
- [x] 增加组件测试验证全部范围、排除问题项、输入变更预检失效、网络失败幂等ID、失败重跑。
- [x] 执行 npm test -- --run 与 npm run build，修复回归。

## Task 3: Schema/history integration
- [x] 新增 sql/add_bcomp_batch_tables_new.sql，追加完整DDL与测试schema。
- [x] TaskManagementRepository 使用LEFT JOIN快照保留历史，增回归测试。
- [x] 同步README与迁移说明，完整验证 mvn verify。

## Task 4: Delivery
- [x] 浏览器检查工作台、发起/预检/批次详情，确认UI字段契约和交互。
- [x] 代码独立复查，修复影响本流程的问题。
- [x] 前端重新构建内置后端，去除交付POM前端构建插件，验证源码包无需Node构建。
- [x] 打包带配置脚本、增量SQL及提交标识的源码ZIP，校验ZIP和JAR静态资源。

## Verification evidence

完整仓库 mvn verify 通过（/tmp/bd-batch-full-verify.log）。编号严格整数补丁后，内置前端的交付源码单独执行 mvn clean verify 通过（/tmp/bd-batch-delivery-verify.log）：service 91项（真实Gauss连接1项按环境跳过）、Spark 19项。前端56项组件测试和4项浏览器测试通过。本地真实后端浏览器联调200项入队，模拟2项失败后生成2项新批次，保留原批次；未连接内网或提交真实Spark作业。交付ZIP完整性与JAR静态文件一致性验证通过。
