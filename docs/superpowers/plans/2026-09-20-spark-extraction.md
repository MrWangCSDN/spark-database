# Spark 库表比对拆分 Implementation Plan

执行方式：本会话按 executing-plans 逐项实施，使用 test-driven-development 验证新增边界。

Goal：在原配置和算法不变的前提下交付独立服务和 Spark 作业包。
Architecture：compare-service 调度原任务表；compare-spark 保留 SparkHandlerTable。
Tech Stack：Java17、Boot3.3.10、Spark3.5.3/Scala2.12、JDBC。
Spec：/Users/java/obsidian/01 Engineering/bd/库表比对拆分-系统设计.md

## 约束
仅使用 tss-verisvr 2；不变更任何生产DDL或配置记录。原工程无页面可迁移。不连接真实数据库进行测试。Spark算法不优化，只修改入口参数检查和敏感参数日志。

## 1. 兼容任务领取与参数
文件：compare-service 下 LegacyRepository、JobArguments、Database、Task；测试 LegacyRepositoryTest、JobArgumentsTest。
- [x] H2原表子集插入类型1/2及状态1任务，先验证只有类型2能领取。
- [x] 两线程争同任务只有一个返回；同index/date/batch多时点歧义回滚。
- [x] 参数测试断言完整21个位置、SQL占位符不提前改写、原AES密文解密。
- [x] 实现受限查询和状态条件更新；验证 `mvn -pl compare-service test`。

## 2. 调度生命周期与接口
文件：TaskWorker、SparkJobLauncher、ConfigStore、StatusController、CompareApplication。
- [x] 测试启动/停止状态、失败任务回写、刷新等待在途作业后继续领取。
- [x] Spark launcher保留原配置键和类名列表，配置Hadoop环境和外部JDBC jars。
- [x] 复用原GET/POST路径和响应；验证MockMvc与H2隔离启动。

## 3. Spark迁移与打包
文件：compare-spark下SparkHandlerTable、SparkCompatibilityTest；config、bin、README。
- [x] 迁移原类，保留比对主体；入口只接受21参数，不打印完整参数。
- [x] 本地Spark/H2小数据验证相同、字段差异、缺行、重复键以及原结果表编码。
- [x] `mvn verify`验证两个模块，服务Boot包与Spark普通JAR分开。
- [x] 对照源文件差异、无生产DDL/凭据、文档说明外部驱动、切换及回滚。

执行结果见 docs/migration/verification.md。页面因唯一来源工程没有源码，未迁移；真实GaussDB集群联调未执行。
