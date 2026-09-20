# TSS Spark 数据库比对微服务

从 `/Users/wangshanhe/Downloads/tss-verisvr 2` 拆出的独立工程。运行时不依赖旧 tss-verisvr 服务、文件服务器或 Redis。

- `compare-service`：Spring Boot 服务，轮询原任务表，只领取 `BCOMP_TYPE='2'` 库表任务，提交并等待 Spark。
- `compare-spark`：独立普通 JAR，保留原 `cn.sunline.tss.tssveri.spark.SparkHandlerTable` 和比对主体。
- `compare-ui`：按独立服务 API 新开发的 React 管理台，随 `compare-service` JAR 同包发布。原工程没有页面源码，因此没有从其他工程拼接页面。

## 不变的兼容边界

不提供生产建表、改表、迁移或配置初始化脚本。现有数据库及配置记录直接复用：

| 原表 | 用途 |
|---|---|
| TSS_BCOMP_CONF | 核对类型、名称、分片等配置 |
| TSS_BCOMP_DATABASE_CONFIG | 两侧连接信息及原AES密文/密钥 |
| TSS_BCOMP_TABLE_SQL | 两侧SQL和数据库ID |
| TSS_BCOMP_FIELD | 字段位置、类型、业务键、分区列、忽略规则 |
| TSS_BCOMP_ENUM / TSS_IGNORE_VALUE | 枚举映射与忽略值 |
| TSS_BCOMP_TASK | 原任务状态与统计 |
| TSS_BCOMP_DTL | 原差异明细 |
| TSS_BCOMP_DIFFFIELD | 原作业保留的预编译查询依赖 |

类型代码、从1开始的字段位置、`${HDRQ}`替换、`|+|`内容分隔符、重复键`#MD5`、明细结果代码全部保留。服务发送原来的21个位置参数。可选字段 `BCOMP_SHARD_STRATEGY`、`BCOMP_READ_MODE` 缺失时沿用原回退行为。

Spark 源文件只改了入口的参数数量检查及明文参数日志；其余比对主体保持一致。原文件 SHA-256 见 `docs/migration/source-provenance.json`。

## 构建与测试

要求 JDK 17、Maven 3.9+。构建会自动安装固定版本 Node 22.14.0/npm 10.9.2，执行前端测试与构建，再复制到 Boot JAR。与原 POM 一致，使用 Spark 3.5.3 / Scala 2.12 和 Spring Boot 3.3.10。不要沿用旧示例中指向 Spark 3.3.2 的 `spark_home`；集群发行版需要匹配 3.5.3。

```bash
mvn clean verify
bin/package.sh
```

测试只使用隔离 H2 和前端模拟 API，不连接业务库：服务任务领取/并发、原21参数/AES、管理 API、刷新/失败回写、HTTP/SPA 启动，以及本地 Spark 相同/差异/缺行/重复键入库。H2测试表只在 `src/test` 中，不随服务发布。

产物：

```text
target/tss-compare-1.0.0-SNAPSHOT.tar.gz
  tss-compare/
    lib/compare-service-1.0.0-SNAPSHOT.jar
    jobs/compare-spark-1.0.0-SNAPSHOT.jar
    drivers/                  # Oracle/PostgreSQL/MySQL运行时驱动
    config/application-local.properties.example
    bin/start.sh
```

Spark Core/SQL 不进入服务依赖或作业包，由 Spark 集群提供。Spark 模块的 provided Jackson 使用 2.15.2，与 Spark 3.5.3 的 Scala Jackson 模块匹配，避免继承 Boot Jackson 版本造成运行冲突。

## 管理台

服务根路径打开管理台，提供运行总览、任务创建与分页查询、任务详情和差异分页、核对配置创建/编辑/复制、数据库连接创建/编辑，以及 light/dark/system 主题。列表默认每页20条，最多100条；页面仅管理 `BCOMP_TYPE='2'`。

管理台继续写原 `TSS_BCOMP_*` 表，不新增生产 DDL，也不改变字段编码。配置以基本信息、两侧 SQL、字段规则为一个事务保存；状态1或2任务引用配置或数据库连接时拒绝修改。数据库密码与生成密钥不会出现在 API 响应，编辑时留空密码表示保留原密文。

主要 API：

| 路径 | 用途 |
|---|---|
| `GET /api/dashboard` | 服务与当日任务总览 |
| `GET/POST /api/tasks` | 分页查询、创建状态1任务 |
| `GET /api/tasks/{index}/{date}/{batch}/{timeNode}` | 精确任务详情 |
| `GET .../differences` | 差异明细分页和结果过滤 |
| `GET/POST/PUT /api/configurations...` | 配置聚合查询、创建、修改、复制 |
| `GET/POST/PUT /api/databases...` | 数据库连接查询、创建、修改 |

页面静态路由只覆盖管理台已知路径；`/api` 与 `/tss` 不进入 SPA 兜底。配置保存成功而进程内缓存刷新失败时，API 返回 `saved=true, cacheRefreshed=false`，页面明确提示刷新警告。

## 部署

1. 解压发布包到独立目录，例如 `/opt/tss-compare`。
2. 将 `config/application-local.properties.example` 复制为 `config/application-local.properties`，设置元数据库连接和 Spark 地址。也可把旧 `application-datasource.properties`、`application-executor.properties`、`application-spark.properties` 放在 `config/` 中；原数据库 `primary=frw` 及 `spring.datasource.dynamic.datasource.frw.*` 无需改名。不要复制旧 `application.properties` 的文件/Redis/MyBatis profiles。
3. 修改**部署属性** `app_resource`，指向新 `jobs/compare-spark-1.0.0-SNAPSHOT.jar`；设置 `spark_home` 指向本机 Spark 3.5.3。`spark_mainClass` 支持原“文件类,库表类”配置或单个库表类。没有修改数据库中任何表配置。
4. `drivers/` 放置原数据库配置对应的厂商驱动。**GaussDB A 模式需加入现场使用的 GaussDB JDBC JAR**，保留原 `DATABASE_URL`/`DATABASE_DRIVER`。PostgreSQL驱动不能被当成所有GaussDB URL的替代品。部署包不擅自捆绑另一版本的厂商私有驱动。
5. 服务使用 Boot PropertiesLauncher 的 `loader.path` 加载 `drivers/`；启动脚本通过 `COMPARE_SPARK_JARS` 将同目录驱动传给 Spark `--jars`。如直接沿用旧Spark属性文件，需要补充 `compare.spark-jars=${COMPARE_SPARK_JARS:}` 或显式填写逗号分隔的驱动路径。Driver和Executor节点必须能访问原库、目标库、元数据库。
6. 元库凭据及源库凭据保持原加密协议；例子中的环境变量如下，由部署环境设置真实值：

```bash
export JAVA_HOME=/path/to/jdk17
export SPARK_HOME=/path/to/spark-3.5.3-bin-hadoop3
export SPARK_MASTER=spark://your-spark-master:7077
export TSS_METADATA_URL='jdbc:your-metadata-url'
export TSS_METADATA_USER='your-user'
export TSS_METADATA_PASSWORD='your-password'
export TSS_METADATA_DRIVER='your.driver.Class'
./bin/start.sh
```

新服务也支持常规 `spring.datasource.url/username/password/driver-class-name`。元库连接池改为 Hikari；兼容原动态数据源主库键和 `max-active`，其余 Druid 特有监控/过滤器配置不生效。新增连接池调优使用 `spring.datasource.hikari.*` 中文档支持的 `maximum-pool-size`、`minimum-idle`、`connection-timeout`。

Spark写任务/结果的 `spark.datasource.*` 和服务元数据源**必须指向同一逻辑库**。原Spark资源参数值保留在示例中，仅作为原配置记录，不代表适合当前服务器或保证2000万行安全。`maxPoolSize=1`表示一次一个Spark作业；并发数是每个服务实例的并发，实例数增加会叠加。

## 原接口

| 请求 | 响应 |
|---|---|
| `GET /tss/runStatus` | 字符串 `0/1/2/3/4` = 停止/启动中/运行中/停止中/失败 |
| `POST /api/cache/refresh` | HTTP200，JSON `{"code":200,"msg":"刷新成功"}`；失败业务码500 |

状态值以原枚举为准，原控制器注释的0/1/2说明不准确。关闭轮询时，运行状态2仍表示服务已启动，并不表示正在执行任务。

刷新等待当前作业结束，最多 `compare.refresh-timeout-seconds=300` 秒；超时或加载异常保留旧快照，工作线程继续运行。快照覆盖配置名称与数据库连接；SQL按任务读取，字段/枚举/分片仍由Spark按原逻辑读取。管理台创建任务仍写原任务表并沿用状态1；同一 `(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH)` 已存在时拒绝重复创建。

## 接入 Axon 生成的配置脚本

Axon 的数据库比对配置版本可生成本服务直接消费的 `TSS_BCOMP_CONF`、`TSS_BCOMP_FIELD`、`TSS_BCOMP_TABLE_SQL` 数据脚本。脚本只使用既有表和字段，不创建或修改生产表结构；生成和下载不会自动在生产库执行。

上线按以下顺序操作：

1. 停止比对任务领取，暂停或清理状态1（等待中）任务，并等待状态2（运行中）任务结束；确认没有任务会在切换期间读取半套配置。
2. 核对生产库数据库连接编号：老系统必须为 `DATABASE_ID=1`，新系统必须为 `DATABASE_ID=2`；Axon 生成的表 SQL 固定引用这两个编号。
3. 在 Axon 生成不可变版本并下载 SQL，审核表名、字段顺序、主键标记、字段类型、筛选条件和限制条数，然后由数据库变更流程执行该 SQL。脚本会清空并重建三张既有配置表的数据，因此必须整份审核和执行。
4. 执行完成后打开 Dashboard，点击“刷新配置缓存”。页面显示“缓存已刷新”后再恢复任务领取；若返回服务端错误或网络错误，保留任务停止状态并重试，不能在缓存刷新失败时继续切换。
5. 先创建一笔已知小表任务，确认两侧读取、timestamp/date 判定和差异分页结果，再逐步恢复批量任务。

## 切换及回滚

1. 停止原服务的库表调度，并等待其在途 Spark 作业结束。**不能让未改造的原调度器与新服务同时领取同一批任务**；新服务的条件更新无法约束旧服务的非原子领取。若旧服务还必须跑文件任务，需要在旧服务单独实现类型过滤后再并行运行，本次没有改动源工程。
2. 检查并处理遗留状态2任务；新服务不会自动重置、清理明细或重跑它们。人工重跑前先确认Spark应用已停止，以及原明细是否需要按现有流程清理。
3. 核对 `(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH)` 对应唯一任务行。原Spark最终更新缺少时点条件，新服务跳过三元身份重复的任务，避免跨时点误写；不自动修改任何行或增加约束。
4. 初次启动可设 `tssveritask.executor.thread.enable=false`，检查两个接口及缓存读取，确认后改为true并重启。先用已知小表验证真实GaussDB、业务SQL及结果查询页面，再逐步扩大数据量。
5. 回滚时停止新服务并等待/核实在途Spark终止，再启动旧服务；表配置从未迁移，不需要回滚DDL。

`SIGTERM`先停止领取、等待 `awaitTerminationSeconds`，超过宽限后中断等待并请求终止Spark进程。默认采用client部署；cluster模式下SparkLauncher的kill不等于资源管理器保证应用已停止，回滚/重试前必须确认集群应用终态。容器强杀或机器故障不会自动恢复状态2任务。

## 保留的旧问题与验证范围

本次是兼容拆分，**没有将“20分钟OOM”或“GaussDB timestamp显示一致”判定为已修复**。Spark仍存在同业务键整组列表、每分区规则加载、驱动游标行为、批量写异常处理等风险；大量差异仍直接写关系库。没有把2000万行业务数据加载进新服务，但Spark本身仍需容量验证。SQL AQE并不消除RDD cogroup的热点键。

日期解析、数值归一化、忽略值匹配、重复键的MD5比较、任务统计更新和失败处理保持原实现，因此既有行为和缺陷一起保留。源作业部分入库异常会吞掉、Spark重试可能重复插入明细；新服务的终态检查不能证明每一条明细都落库。后续应独立修复并做结果回归。

没有真实GaussDB/Spark集群、现场配置和OOM日志，本地测试不能代替现场驱动兼容验证、全量性能压测。21参数为了兼容包含连接密码，虽然去除了主动打印，仍需限制操作系统进程信息和Spark作业详情的访问。
