# TSS Spark 数据库比对微服务

从 `/Users/wangshanhe/Downloads/tss-verisvr 2` 拆出的独立工程。运行时不依赖旧 tss-verisvr 服务、文件服务器或 Redis。

- `compare-service`：Spring Boot 服务，轮询 TSS_BCOMP_TASK_NEW，只领取 `BCOMP_TYPE='2'` 库表任务，提交并等待 Spark。
- `compare-spark`：独立普通 JAR，保留原 `cn.sunline.tss.tssveri.spark.SparkHandlerTable` 和比对主体。
- `compare-ui`：按独立服务 API 新开发的 React 管理台，随 `compare-service` JAR 同包发布。原工程没有页面源码，因此没有从其他工程拼接页面。

## 不变的兼容边界

通过 `sql/create_bcomp_new.sql` 新建十四张空表，包含九张核对业务表、两张批次表、一张每日汇总表、一张差异字段组合汇总表和一张完整字段统计表。枚举、忽略值和差异字段依赖同样使用独占新表。

| 运行时表 | 用途 |
|---|---|
| `TSS_BCOMP_RESULT_NEW` | 每日全量汇总与最新成功结果版本 |
| `TSS_BCOMP_FIELD_DIFF_NEW` | 差异字段组合及折叠统计 |
| `TSS_BCOMP_FIELD_COUNT_NEW` | 报表使用的完整字段差异行计数 |
| TSS_BCOMP_CONF_NEW | 核对类型、名称、分片等配置 |
| TSS_BCOMP_DATABASE_CONFIG_NEW | 两侧连接信息及原AES密文/密钥 |
| TSS_BCOMP_TABLE_SQL_NEW | 两侧SQL和数据库ID |
| TSS_BCOMP_FIELD_NEW | 字段位置、类型、业务键、分区列、忽略规则 |
| TSS_BCOMP_ENUM_NEW / TSS_IGNORE_VALUE_NEW | 枚举映射与忽略值 |
| TSS_BCOMP_BATCH_NEW / TSS_BCOMP_BATCH_ITEM_NEW | 批次请求身份、任务清单与名称快照 |
| TSS_BCOMP_TASK_NEW | 原任务状态与统计 |
| TSS_BCOMP_DTL_NEW | 原差异明细 |
| TSS_BCOMP_DIFFFIELD_NEW | 原作业保留的预编译查询依赖 |

类型代码、从1开始的字段位置、`${HDRQ}`替换、`|+|`内容分隔符、重复键`#MD5`、明细结果代码全部保留。服务发送原来的21个位置参数。可选字段 `BCOMP_SHARD_STRATEGY`、`BCOMP_READ_MODE` 缺失时沿用原回退行为。

Spark 固定使用主键 HASH 读取，全量计数与限额样本由 Driver 事务发布；兼容 BIGINT/NUMERIC 字段位置。原文件 SHA-256 见 `docs/migration/source-provenance.json`。

## 构建与测试

要求 JDK 17、Maven 3.9+。构建会自动安装固定版本 Node 22.14.0/npm 10.9.2，执行前端测试与构建，再复制到 Boot JAR。与原 POM 一致，使用 Spark 3.5.3 / Scala 2.12 和 Spring Boot 3.3.10。不要沿用旧示例中指向 Spark 3.3.2 的 `spark_home`；集群发行版需要匹配 3.5.3。

```bash
mvn clean verify
bin/package.sh
bin/package-source.sh
```

需要送入内网重新构建时，先在本机完成 `mvn verify`，再使用 `bin/package-source.sh` 生成后端源码 ZIP 和 SHA-256 校验文件。打包脚本会把预编译前端直接放进 ZIP 中的 `compare-service/src/main/resources/static/`，ZIP 不含 `compare-ui` 工程。内网可用 `mvn -Dskip.installnodenpm=true -Dskip.npm=true clean verify` 构建后端和 Spark，前端不再重新编译；详见 [`docs/migration/intranet-build.md`](docs/migration/intranet-build.md)。源码 ZIP 不包含 Maven 依赖缓存，完全离线且没有企业制品镜像时不能单独完成构建。

测试只使用隔离 H2 和前端模拟 API，不连接业务库：服务任务领取/并发、原21参数/AES、管理 API、刷新/失败回写、HTTP/SPA 启动，以及本地 Spark 相同/差异/缺行/重复键入库。H2测试表只在 `src/test` 中，不随服务发布。

产物：

```text
target/tss-compare-1.0.0-SNAPSHOT.tar.gz
  tss-compare/
    start.sh                  # 后台启动并记录bd.pid
    stop.sh                   # SIGTERM优雅停止
    lib/compare-service-1.0.0-SNAPSHOT.jar
    jobs/compare-spark-1.0.0-SNAPSHOT.jar
    drivers/                  # Oracle/PostgreSQL/MySQL/openGauss运行时驱动
    config/application-local.properties.example
    bin/start.sh
```

Spark Core/SQL 不进入服务依赖或作业包，由 Spark 集群提供。Spark 模块的 provided Jackson 使用 2.15.2，与 Spark 3.5.3 的 Scala Jackson 模块匹配，避免继承 Boot Jackson 版本造成运行冲突。

字段映射默认按配置 `FIELD_NAME` 与两侧 SQL 返回列名或 `AS` 别名匹配，不区分大小写。两侧 SQL 可使用不同列顺序，例如 `source_column AS FIELD_NAME`；配置字段英文名必须唯一，SQL 缺少对应列或返回重名列时任务失败。旧 `FIELD_OLD_INDEX`、`FIELD_NEW_INDEX` 继续保留，名称模式忽略其位置值。部署属性 `compare.field-match-by-name=${BD_FIELD_MATCH_BY_NAME:true}` 控制此行为；显式设置 `BD_FIELD_MATCH_BY_NAME=false` 可使用历史位置映射，位置必须为正整数且两侧各自唯一。升级时同时部署匹配版本的服务与 Spark 作业 JAR，并使用本次构建中集成的前端。

名称模式提交任务前，服务检查最终 `app_resource` 指向的真实 JAR，确认其中包含所选主类和 `QueryFieldResolver`。缺少必需类时，任务会直接显示实际作业路径及部署错误。提交日志记录作业真实路径、SHA-256、主类和字段匹配模式，并通过 SHA-256 与 application ID 关联。Spark Driver 启动时记录实际加载的作业类来源和匹配模式。配置文件中的 `app_resource` 应指向本次发布物的 `jobs/compare-spark-1.0.0-SNAPSHOT.jar`。

Spark 3.4.0 集群使用 `-Pspark-3.4` 构建，配套作业模块 Jackson 2.14.2。默认构建使用 Spark 3.5.3 和作业模块 Jackson 2.15.2。两种构建均保持服务独立的 Spring Boot Jackson 依赖管理。

`SparkJobLauncher` 显式传递应用配置中的非空 `spark.local.dir`，支持逗号分隔多个目录。Standalone Executor 的缓存目录由运行节点的 Worker 环境决定，各 Worker 应在实际使用的 `spark-env.sh` 设置 `SPARK_LOCAL_DIRS`，等待在途任务结束后重启 Worker，再发起新任务。默认提交 `spark.rdd.compress=true` 压缩序列化 RDD 缓存，可在应用配置中显式设为 `false`。每个 Executor 首次读取时记录实际 `blockmgr` 目录、可用/总字节数和压缩开关。两阶段比较会缓存完整分组数据，磁盘预算需结合全量行数、行宽及压缩率评估。部署检查见 [内网源码构建说明](docs/migration/intranet-build.md)。

## 管理台

服务根路径打开管理台，提供运行总览、任务创建与分页查询、任务详情和差异分页、核对配置创建/编辑/复制、数据库连接创建/编辑，以及 light/dark/system 主题。列表默认每页20条，最多100条；页面仅管理 `BCOMP_TYPE='2'`。

管理台使用 `TSS_BCOMP_*_NEW` 表；每日结果升级新增汇总表并扩展任务与明细列，升级前须执行增量 SQL。配置以基本信息、两侧 SQL、字段规则为一个事务保存；状态1或2任务引用配置或数据库连接时拒绝修改。数据库密码与生成密钥不会出现在 API 响应，编辑时留空密码表示保留原密文。GaussDB、openGauss、PostgreSQL 兼容的比对连接必须在 JDBC URL 中提供非空 `currentSchema`；新建、修改、连接测试和创建任务都会校验，两侧连接缺少该参数时返回 `CURRENT_SCHEMA_REQUIRED`。

主要 API：

| 路径 | 用途 |
|---|---|
| `GET /api/dashboard` | 服务与当日任务总览 |
| `GET/POST /api/tasks` | 分页查询、创建状态1任务 |
| `GET /api/tasks/{index}/{date}/{batch}/{timeNode}` | 精确任务详情 |
| `GET .../differences` | 差异明细分页和结果过滤 |
| `GET/POST/PUT /api/configurations...` | 配置聚合查询、创建、修改、复制 |
| `GET/POST/PUT /api/databases...` | 数据库连接查询、创建、修改 |
| `POST /api/databases/test` | 测试未保存连接，不落库 |
| `POST /api/databases/{id}/test` | 复用已保存密码或使用本次临时密码测试 |
| `GET /api/results` | 按业务日期分页查询每日成功结果，支持领域、表名及负责人筛选 |
| `GET /api/results/export?date=yyyyMMdd` | 下载该日期全部成功结果的 Excel 报表 |

页面静态路由只覆盖管理台已知路径；`/api` 与 `/tss` 不进入 SPA 兜底。配置保存成功而进程内缓存刷新失败时，API 返回 `saved=true, cacheRefreshed=false`，页面明确提示刷新警告。

## 部署

1. 解压发布包到独立目录，例如 `/opt/tss-compare`。
2. 将 `config/application-local.properties.example` 复制为 `config/application-local.properties`，设置元数据库连接和 Spark 地址。也可把旧 `application-datasource.properties`、`application-executor.properties`、`application-spark.properties` 放在 `config/` 中；原数据库 `primary=frw` 及 `spring.datasource.dynamic.datasource.frw.*` 无需改名。不要复制旧 `application.properties` 的文件/Redis/MyBatis profiles。
3. 修改**部署属性** `app_resource`，指向新 `jobs/compare-spark-1.0.0-SNAPSHOT.jar`；设置 `spark_home` 指向本机 Spark 3.5.3。`spark_mainClass` 支持原“文件类,库表类”配置或单个库表类。没有修改数据库中任何表配置。
4. 按数据库实际协议选择驱动：
   - PostgreSQL 兼容的 GaussDB A（本项目元库默认配置）：`jdbc:postgresql://host:port/database?currentSchema=业务schema` 与 `org.postgresql.Driver`，发布包已包含 PostgreSQL 驱动。元库使用此协议时，`bd.env` 不需要填写 `BD_JDBC_JAR`。
   - 华为原生协议：`jdbc:gaussdb://host:port/database?currentSchema=业务schema` 与现场厂商文档指定的驱动类；将有合法授权的原生驱动 JAR 放入 `drivers/`。
   - openGauss：按现场版本确认 JDBC URL，例如 `jdbc:opengauss://host:port/database?currentSchema=业务schema`，驱动类为 `org.opengauss.Driver`。工程默认依赖 `org.opengauss:opengauss-jdbc:6.0.0-htrunk8`，完整构建后会自动进入服务 JAR 和发布包的 `drivers/`，无需手工修改 POM 或复制该 JAR。
   PostgreSQL 驱动不能处理 `jdbc:gaussdb` 等其他协议。openGauss 依赖不等于华为原生 GaussDB 驱动；`jdbc:gaussdb` 仍需现场厂商 JAR。`6.0.0-htrunk8` 不在 Maven Central，内网 Maven 仓库必须已有该制品。
   `currentSchema` 参数名大小写不敏感，但解码后的值不能为空。已有不合规连接仍可查看，服务不会推断或自动改写 schema；再次保存、测试或用于新任务前必须人工补齐。该规则只约束 `TSS_BCOMP_DATABASE_CONFIG_NEW` 中的比对连接，不约束微服务自身的 `spring.datasource.*` 或 Spark 元数据库的 `spark.datasource.*`。
5. `bin/start.sh` 使用 Boot PropertiesLauncher 的 `loader.path` 把 `drivers/` 加入服务类路径，并通过 `COMPARE_SPARK_JARS` 将同目录 JAR 传给 Spark `--jars`。如直接沿用旧Spark属性文件，需要补充 `compare.spark-jars=${COMPARE_SPARK_JARS:}` 或显式填写逗号分隔的驱动绝对路径。原生驱动必须在每个 Spark 节点可读，Driver和Executor节点必须能访问原库、目标库、元数据库。
6. 元库凭据及源库凭据保持原加密协议；例子中的环境变量如下，由部署环境设置真实值。也可写入发布目录的 `config/bd.env`，根目录 `start.sh` 会通过 `bin/start.sh` 自动加载：

### GDB 控制面与 GDB 比对连接

需要让 bd 控制面运行在 GDB 时，将 `BD_METADATA_DB_TYPE` 设为 `GDB`，使用 `config/application-gdb.properties.example` 的 MySQL 兼容 URL，并把现场提供的 `gdb_mysql-connector-java-5.1.46.jar`（或同版本厂商驱动）放入 `drivers/`。默认驱动类为 `com.mysql.jdbc.Driver`；如果厂商驱动使用其他类名或 URL 前缀，只修改 `TSS_METADATA_DRIVER`、`TSS_METADATA_URL` 和 `SPARK_GDB_JDBC_URL_PREFIXES`。

GDB 控制面首次部署执行 `sql/create_bcomp_gdb.sql`；已有环境按停服务、排空 Spark 后执行对应 `sql/*_gdb.sql` 增量脚本。应用启动会校验 `BD_METADATA_DB_TYPE` 与实际 JDBC 产品是否匹配，GDB 不需要 `currentSchema`；GaussDB/PostgreSQL 连接仍必须提供非空 `currentSchema`。应用不会自动执行 DDL。

任务两侧连接可以独立选择 GDB 或 GaussDB，支持 GDB-GDB、GDB-GaussDB、GaussDB-GDB 和 GaussDB-GaussDB。两侧仍必须把完整业务键配置为 `FIELD_INDEX_FLAG='1'`；数据库唯一索引不会被服务自动读取。GDB 分区读取使用 MySQL 兼容 CRC32 HASH，服务会把同一驱动 JAR 传给 Spark Driver 和 Executor。

部署验收顺序：停止旧 bd 并确认在途 Spark 已结束 → 创建/升级 GDB 元数据库 → 将 GDB 驱动放入服务和 Spark 节点的 `drivers/` → 设置类型、URL、驱动和凭据 → 页面分别测试元库及两侧业务连接 → 用小表执行一笔 GDB-GDB 和一笔混合数据库任务 → 检查 `BCOMP_READ_MODE` 后再恢复正常调度。回滚时先停新服务并确认 Spark 终态，再恢复旧服务和旧 GaussDB 配置；不要让旧 Spark JAR 读取新 GDB 控制面脚本。

```bash
export JAVA_HOME=/path/to/jdk17
export SPARK_HOME=/path/to/spark-3.5.3-bin-hadoop3
export SPARK_MASTER=spark://your-spark-master:7077
export TSS_METADATA_URL='jdbc:your-metadata-url'
export TSS_METADATA_USER='your-user'
read -r -s -p 'Jasypt 口令：' JASYPT_ENCRYPTOR_PASSWORD; export JASYPT_ENCRYPTOR_PASSWORD
export TSS_METADATA_PASSWORD='ENC(your-encrypted-password)'
export TSS_METADATA_DRIVER='your.driver.Class'
./start.sh
```

根目录 `start.sh` 后台启动服务，将PID写入 `bd.pid`，控制台日志写入 `logs/bd-console.log`。执行 `./stop.sh` 发送 `SIGTERM` 并等待优雅退出；等待时间默认120秒，可通过 `BD_STOP_TIMEOUT_SECONDS` 调整，超时后不会自动强杀Spark作业。`bin/start.sh` 保留为前台启动入口，适合交给systemd等进程管理器。

内网正式构建默认使用 BES Lite Starter `3.x-11.5.0.004`，并从内网 Maven 仓库解析该厂商制品；Web Starter 已排除内嵌 Tomcat。外网本地没有 BES 制品时可用 `mvn -Plocal-servlet-tests -Dgauss-jdbc.version=6.0.0 verify` 验证业务逻辑，该 profile 只在测试范围加入 Tomcat，不能作为 BES 发布物。内网用默认 profile 构建后，先检查服务 JAR 中存在 BES Starter 且没有 Tomcat，再启动验证页面和 API。

元数据库密码在 `config/bd.env` 中使用 `TSS_METADATA_PASSWORD='ENC(...)'`；`JASYPT_ENCRYPTOR_PASSWORD` 由外部运行环境注入，不能放进 `bd.env`、源码或源码包。可在部署目录执行 `bin/encrypt-password.sh`，按提示输入明文密码，将输出密文填入 `bd.env`。加密与解密必须使用同一口令；脚本采用 Jasypt 3.0.5 的 `PBEWITHHMACSHA512ANDAES_256`、随机盐与 IV。启动时会检查密文格式及解密口令是否存在，不会输出密码。`spark.datasource.password` 引用同一个已解密的元库密码。业务库连接密码仍沿用表内现有 AES 协议，不能把这两种密文格式混用。

若要清空全部比对运行记录，先在部署目录执行 `./stop.sh`，再到 Spark Master / YARN 确认本服务提交的比对应用均已结束。随后连接 **bd 元数据库**（使用与服务相同的 schema）执行 `sql/clear_bcomp_runtime_data_new.sql`。脚本清空任务、批次、结果汇总和差异样本六张表，保留核对配置、字段规则及数据库连接；SQL 末尾会查询六张表的剩余行数。确认全为 0 后再执行 `./start.sh`。此操作会永久删除历史运行记录，执行前可先备份元数据库。

新服务也支持常规 `spring.datasource.url/username/password/driver-class-name`；若采用这组属性，`password` 同样必须填写 Jasypt `ENC(...)` 密文。元库连接池改为 Hikari；兼容原动态数据源主库键和 `max-active`，其余 Druid 特有监控/过滤器配置不生效。新增连接池调优使用 `spring.datasource.hikari.*` 中文档支持的 `maximum-pool-size`、`minimum-idle`、`connection-timeout`。

Spark写任务/结果的 `spark.datasource.*` 和服务元数据源**必须指向同一逻辑库**。原Spark资源参数值保留在示例中，仅作为原配置记录，不代表适合当前服务器或保证2000万行安全。`maxPoolSize=1`表示一次一个Spark作业；并发数是每个服务实例的并发，实例数增加会叠加。

管理台连接测试默认超时10秒、每个服务实例最多4个并发，可通过 `compare.database-test-timeout-seconds` 和 `compare.database-test-max-concurrency` 调整。测试只建立一条 JDBC 连接、调用标准有效性检查并读取产品/版本，不执行配置 SQL。驱动缺失、超时和连接失败只返回稳定错误分类，不回显 URL、用户、密码或驱动异常原文。`currentSchema` 校验只能保证 URL 明确选择 schema，不能替代现场网络、TLS、账号权限和真实 GaussDB 连接测试。

有现场测试库时，可显式执行真实 GaussDB 探测；以下变量只在当前命令进程中读取，不写入仓库或测试报告：

```bash
GAUSSDB_TEST_URL='jdbc:postgresql://host:port/database' \
GAUSSDB_TEST_USER='compare_user' \
GAUSSDB_TEST_PASSWORD='request-only-password' \
GAUSSDB_TEST_DRIVER='org.postgresql.Driver' \
JAVA_HOME=/path/to/jdk17 \
mvn -pl compare-service -Dtest=GaussDbConnectionIntegrationTest test
```

未提供 URL、用户或密码时该测试自动跳过。现场验收仍需在与生产相同的网络、TLS、驱动版本和 Spark 节点条件下完成一次连接测试与最小核对任务。

## 原接口

| 请求 | 响应 |
|---|---|
| `GET /tss/runStatus` | 字符串 `0/1/2/3/4` = 停止/启动中/运行中/停止中/失败 |
| `POST /api/cache/refresh` | HTTP200，JSON `{"code":200,"msg":"刷新成功"}`；失败业务码500 |

状态值以原枚举为准，原控制器注释的0/1/2说明不准确。关闭轮询时，运行状态2仍表示服务已启动，并不表示正在执行任务。

刷新等待当前作业结束，最多 `compare.refresh-timeout-seconds=300` 秒；超时或加载异常保留旧快照，工作线程继续运行。快照覆盖配置名称与数据库连接；SQL按任务读取，字段/枚举/分片仍由Spark按原逻辑读取。管理台创建任务仍写原任务表并沿用状态1；同一 `(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH)` 已存在时拒绝重复创建。

## 接入 Axon 生成的配置脚本

Axon 的数据库比对配置版本可生成本服务直接消费的 `TSS_BCOMP_CONF_NEW`、`TSS_BCOMP_FIELD_NEW`、`TSS_BCOMP_TABLE_SQL_NEW` 数据脚本。脚本只使用既有表和字段，不创建或修改生产表结构。管理台“核对配置 → 导入 Axon 配置”负责校验和执行该固定格式，不提供通用 SQL 控制台。

Axon 自定义 SQL 含反斜杠时，导出的 `orig_sql`、`dest_sql` 使用 `CONVERT(0x... USING utf8mb4)` 保存 UTF-8 正文。通过管理台导入时，服务严格解码十六进制内容，并用 JDBC 参数保存两侧查询。普通字符串形式的查询也按标准 SQL 单引号规则解析后参数化保存，反斜杠、中文、引号和换行均保留原值。此兼容修复随 bd 服务升级生效，可重新导入原下载脚本。

上线按以下顺序操作：

1. 暂停或处理状态1（等待中）任务，并等待状态2（运行中）任务结束；导入接口发现任一活动任务会返回 `CONFIG_IMPORT_TASKS_ACTIVE`，不会改变配置。
2. 核对生产库数据库连接编号：老系统必须为 `DATABASE_ID=1`，新系统必须为 `DATABASE_ID=2`；Axon 生成的表 SQL 固定引用这两个编号。
3. 在 Axon 生成不可变版本并下载 SQL，审核表名、字段顺序、主键标记、字段类型、筛选条件和限制条数；打开“核对配置 → 导入 Axon 配置”，上传文件或粘贴脚本并确认整体替换。
4. 后端只允许 Axon 固定的事务、三表清空和三表 INSERT，默认最大 50 MiB；所有写入在应用事务内完成，成功后自动刷新缓存。若页面显示“配置已导入，但缓存刷新失败”，三表数据已经提交，应点击“重试刷新缓存”，不要重复导入同一脚本。
5. 先创建一笔已知小表任务，确认两侧读取、timestamp/date 字符串判定和差异分页结果，再逐步恢复批量任务。

脚本大小可通过 `compare.config-import-max-bytes` 调整，同时应把 `spring.servlet.multipart.max-file-size` 和 `spring.servlet.multipart.max-request-size` 设为不小于该值。默认分别为 50 MiB 和 51 MiB。

### 授权配置主键哈希读取分区

新版 Axon-link 不再提供表级分区数调参；样本上限仍可由授权人员配置（0–10000，默认 100，0 表示只保存汇总）。Axon 后端部署属性 `replay-database-comparison.partition-admin-emp-nos` 指定授权工号名单，默认空名单。历史分区数接口和字段只作兼容，不能影响 bd 的实际运行分区数。

样本上限进入不可变版本快照。新 SQL 在 `TSS_BCOMP_CONF_NEW` 的原 8 列之后追加 `bcomp_partition_num,bcomp_shard_strategy,bcomp_sample_limit`，值为兼容值 1、`'HASH'` 和每个差异字段组合的样本上限（0–10000，Axon 新建配置默认 100）；业务键仍通过 `TSS_BCOMP_FIELD_NEW.FIELD_INDEX_FLAG` 指定，包含全部联合主键，不需要分区字段标记。新版导出还在末尾追加 `reviser_name,reviser_username` 两列，形成 13 列脚本；两列允许 NULL 或最长 100 字符的修订人姓名、用户名。比对端兼容原 8 列、前版 10 列、11 列及新版 13 列脚本，新格式的非法兼容列值、样本上限、非 HASH 策略或缺失业务键会使整批导入回滚。旧脚本缺少分区数时默认 1，同样执行主键 HASH；运行时不会按这个列值确定实际分区数；全部表配置都必须包含主键。

导入新脚本前，目标元数据库必须已具备 `TSS_BCOMP_CONF_NEW.BCOMP_PARTITION_NUM`、`BCOMP_SHARD_STRATEGY` 和 nullable 的 `BCOMP_SAMPLE_LIMIT` 列（策略列需能保存 `HASH`）；已有元数据库先执行 `sql/add_bcomp_sample_limit_new.sql`，旧行保持 NULL，不回填默认值；应用不会自动修改生产表结构。旧版 Spark 不识别此哈希策略，必须同步升级服务和 Spark 作业 JAR，再使用新版脚本。历史版本已经生成的 SQL 保持不变；样本上限调整需生成新版本。

修订人字段升级适用于已有 `_NEW` 元数据库。先停止 bd 并确认全部在途 Spark 作业已结束，再连接服务与 Spark 使用的同一元数据库。GaussDB 设置正确 `search_path` 后执行 `sql/add_bcomp_reviser_new.sql`；GDB/MySQL 选择正确数据库后执行 `sql/add_bcomp_reviser_gdb.sql`。确认 `TSS_BCOMP_CONF_NEW` 已有 nullable 的 `REVISER_NAME VARCHAR(100)`、`REVISER_USERNAME VARCHAR(100)`，再启动新版服务并导入 13 列脚本。两种增量脚本均保留已有配置，旧行两列为 NULL；按实际结构执行一次，部分执行后应先检查列是否存在。GDB/MySQL DDL 会隐式提交，不能依靠事务回滚升级。新建环境直接执行对应的最新 `create_bcomp_new.sql` 或 `create_bcomp_gdb.sql`，无需另跑修订人增量脚本。

`HASH` 分区数为 1 时使用单分区读取；大于 1 时在数据库查询中按完整业务键生成互斥的哈希条件，由 Spark JDBC 并行读取各片，再执行既有核对逻辑。每侧实际读取方式写入 `BCOMP_READ_MODE`（目标列存在时）。不支持的数据库方言或不可用的哈希函数会明确失败，不自动伪装成单分区成功。

哈希分片只保证按谓词划分读取集合，不保证行数严格相等，也不保证提升速度：哈希条件可能让数据库为每片全表扫描。源数据应在核对期间保持稳定，分区读取不提供跨库统一快照。现场需要检查查询计划、JDBC 流式读取、数据库连接容量和集群资源；本地 H2/Spark 测试不代替厂商数据库压测。

## 切换及回滚

1. 停止原服务的库表调度，并等待其在途 Spark 作业结束。**不能让未改造的原调度器与新服务同时领取同一批任务**；新服务的条件更新无法约束旧服务的非原子领取。若旧服务还必须跑文件任务，需要在旧服务单独实现类型过滤后再并行运行，本次没有改动源工程。
2. 检查并处理遗留状态2任务；新服务不会自动重置、清理明细或重跑它们。人工重跑前先确认Spark应用已停止，以及原明细是否需要按现有流程清理。
3. 核对 `(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH)` 对应唯一任务行。原Spark最终更新缺少时点条件，新服务跳过三元身份重复的任务，避免跨时点误写；不自动修改任何行或增加约束。
4. 初次启动可设 `tssveritask.executor.thread.enable=false`，检查两个接口及缓存读取，确认后改为true并重启。先用已知小表验证真实GaussDB、业务SQL及结果查询页面，再逐步扩大数据量。
5. 回滚时停止新服务并等待/核实在途Spark终止，再启动旧服务；如果已经导入 `HASH` 配置，必须先恢复旧版可识别的配置脚本，不能让旧 Spark 作业继续使用新策略。应用本身不执行生产 DDL。

`SIGTERM`先停止领取、等待 `awaitTerminationSeconds`，超过宽限后中断等待并请求终止Spark进程。默认采用client部署；cluster模式下SparkLauncher的kill不等于资源管理器保证应用已停止，回滚/重试前必须确认集群应用终态。容器强杀或机器故障不会自动恢复状态2任务。

## 保留的旧问题与验证范围

本次是兼容拆分，**没有将“20分钟OOM”或“GaussDB timestamp显示一致”判定为已修复**。Spark仍存在同业务键整组列表、每分区规则加载、驱动游标行为、批量写异常处理等风险；大量差异仍直接写关系库。没有把2000万行业务数据加载进新服务，但Spark本身仍需容量验证。SQL AQE并不消除RDD cogroup的热点键。

日期解析、数值归一化、忽略值匹配、重复键的MD5比较、任务统计更新和失败处理保持原实现，因此既有行为和缺陷一起保留。源作业部分入库异常会吞掉、Spark重试可能重复插入明细；新服务的终态检查不能证明每一条明细都落库。后续应独立修复并做结果回归。

没有真实GaussDB/Spark集群、现场配置和OOM日志，本地测试不能代替现场驱动兼容验证、全量性能压测。21参数为了兼容包含连接密码，虽然去除了主动打印，仍需限制操作系统进程信息和Spark作业详情的访问。


## 九张业务表隔离部署

执行 `sql/create_bcomp_new.sql` 创建十二张空 `_NEW` 表，先设置与服务和 Spark 元数据库一致的 search_path。旧表及其数据不变，不自动迁移。SQL/明细正文使用 TEXT，支持宽表配置。

停止 bd 并等待在途 Spark 作业结束后，同步替换 service 和 Spark 两个 JAR。在数据库连接页面重新维护连接（Axon 脚本使用 ID 1 和 2），从 Axon 选择“新版本（_new 表）”下载并导入配置，再创建小任务测试。旧版本 SQL 不可导入本版 bd。枚举、忽略值及差异字段表均使用 `_NEW` 表；规则表初始为空，启用规则前需维护相应数据。

回滚需同时恢复两个 JAR；新表数据不会自动复制回旧表。

已执行前一版六表脚本的环境，只需补执行 `sql/add_bcomp_rule_tables_new.sql`；新环境执行完整九表脚本 `sql/create_bcomp_new.sql`。差异字段表仅保留当前代码查询依赖的 BCOMP_INDEX 列，不复制旧系统未使用的字段。

发布包包含 `config/bd.env` 占位模板与 `config/application-local.properties`。启动前替换 CHANGE_ME、SPARK_HOME 和 SPARK_MASTER，并检查 Spark 资源参数。打包脚本始终从 `.example` 生成配置，不复制开发机的真实凭据。


## 启停与 openGauss 驱动检查

启停脚本共用 `bin/runtime.sh` 加载 `config/bd.env`。启动前检查 JDK 17、两个 JAR、配置及驱动类；重复启动不会拉起第二个实例。根 start.sh 最长等待 BD_START_WAIT_SECONDS（默认30秒）的本次启动完成日志，超时保留进程与 PID 供排查。停止脚本只对带本目录标识的服务 PID 发送 SIGTERM；不会停止 Spark Master/Worker 或旧核对服务。旧版脚本启动的进程需要先人工核对后停止，再使用新版脚本。日志按每次启动归档。

openGauss 使用 org.opengauss.Driver 和 jdbc:opengauss:，构建时自动将依赖 JAR 放入 drivers/。可执行 `bin/check-driver.sh` 离线验证驱动类和 URL 是否匹配；该检查不连接数据库。Spark 对 jdbc:opengauss 注册游标事务设置，在 fetchsize>0 时关闭自动提交。数据库版本及集群连通性仍需现场验证。


## 批量核对工作台（2026-09-22）

四个入口为核对工作台、核对批次、核对配置、数据库连接。导入 Axon 后选择“发起全部比对”，统一填写业务日期，先预检再创建批次。也可在配置列表跨页勾选部分配置。全部范围由后端取全量，不受列表分页限制，单批最多 5000 项。

批次任务整体进入等待队列，按 `BD_TASK_CONCURRENCY` 对应的 Worker 并发执行（默认 1，可按资源提高）。每个 Worker 领取一张表后，先依次统计该表两侧配置 SQL 的行数，自动计算本表的 HASH 分区数，再分区读取并比对；不同 Worker 的表任务可以同时运行，不会在批次开始时集中统计全部表。`BD_TASKS_ENABLED=false` 时可创建等待任务，工作台会显示调度未启用。预检存在问题时先修复或明确排除，再重新校验；不会静默跳过。双击或网络重试会复用同一请求身份，避免重复创建。

批次详情按等待、运行、一致、有差异、执行失败、已取消分别展示。进度按已结束任务数计算。点击“停止批次”并确认后，当前批次等待或运行的任务进入取消状态，服务向其正在运行的 Spark 应用发送终止请求；其他批次和已完成结果不受影响。停止请求返回后仍应在 Spark 页面核实应用终态。重新发起失败项会建立新批次，保留原业务日期、每项核验日期和时点，使用当前配置，配置变化时提示；有差异及已取消任务不属于失败项，不覆盖旧任务与明细。批次保存任务名称和配置摘要，后续重新导入不影响历史列表。

**已有内网部署升级：先停 bd，在元数据库的正确 schema 下执行一次 `sql/add_bcomp_batch_tables_new.sql`，再替换两个应用 JAR 和前端集成版本。** 此脚本只增加 `TSS_BCOMP_BATCH_NEW`、`TSS_BCOMP_BATCH_ITEM_NEW` 两张表，不修改原九张表、不清理数据。新环境使用最新 `sql/create_bcomp_new.sql` 一次建齐十四张表；不要在已有环境重复运行完整建表脚本。应用不会自动执行 DDL。

现有单表任务仍在 `/tasks` 查询；批次页面只展示本功能创建的批次。控制面变更在单个服务实例内串行化，当前部署按单实例运行；不要同时通过另一实例或外部 SQL 修改正在使用的配置。


## 每日结果与 HASH 唯一读取方式升级

业务键可使用主键或唯一索引字段。各键分量均非空、非空白时，按业务键匹配并使用字段规则比较，同一侧重复的完整键会使任务失败。任一键分量为 NULL、空串或空白时，按业务键与本次 SQL 返回的全部配置字段原始值组成整行身份，分别归并两侧出现次数，再取较小次数作为匹配数，剩余记录计入各侧独有。例如同一原始行在原侧出现 5 次、目标侧出现 3 次，统计为匹配 3 条、原侧独有 2 条。空键整行计次区分 `1.0` 与 `1.00` 等不同原始表示。NULL、空串及字面字符串 `null` 分别编码；样本保留原业务键，相同业务键的重复样本按限额保存。

已部署环境先停止服务并等待所有 Spark 作业结束，在元数据库对应 schema 执行 `sql/add_bcomp_daily_results_new.sql`；首次空库使用 `sql/create_bcomp_new.sql`。重新构建并同时替换服务和 Spark 两个 JAR，保留现场连接、驱动及密码配置。

运行时先统计两侧本次 SQL 的准确行数，取较大值，按每 100 万行一档向上取 1、2、4、8、16、32、64 个分区；最大 64。两侧使用相同分区数和全部 `FIELD_INDEX_FLAG=1` 字段组成的主键做 HASH。统计失败直接使任务失败。`BCOMP_PARTITION_NUM` 仅作历史脚本兼容，运行时忽略。RANGE、NTILE 和分区字段选择已删除。旧策略列和分区标记列只为兼容旧导入格式保留，不能改变读取策略；保存/导入时分区标记清零。旧配置无需单独修改策略值。

每日结果按“配置编号＋核对业务日期”保存一份最新成功结果。核对业务日期优先取 VERIFY_BUSINESS_DATE，否则取 TASK_BUSI_DATE。汇总为全量准确计数：仅一侧存在各保存至多 `BCOMP_SAMPLE_LIMIT` 条；主键相同但内容不同的行按完整差异字段组合分别计数，每个组合各保存至多该上限的样本（0–10000）。例如 `[a]` 与 `[a,b]` 是互斥的两组，组数之和等于该表的字段差异总数。值为 0 时只保存汇总。该列为 NULL（含旧 8/10 列脚本）时使用全局 `BD_DIFF_DETAIL_LIMIT`（默认 100）；显式配置的旧值 1000 不变。结果 `detailLimit` 记录实际采用的上限；重复成功执行原子替换，失败保留上一份成功结果。每个任务最多允许 128 种字段组合、合计 20000 条组合样本，超出时任务失败并保留上次成功结果。字段过长时样本截断并提示，不影响总量统计。`BD_DIFF_RETENTION_DAYS=30` 控制按最后发布时间清理样本，保留汇总，允许 1–3650 天。历史全量明细不自动删除。

已部署环境在每日结果表已经建好的前提下，先停止 bd 并等待 Spark 作业结束，在同一个元数据库 schema 执行一次 `sql/add_bcomp_field_patterns_new.sql`，再同时替换服务与 Spark JAR。首次空库直接运行最新 `sql/create_bcomp_new.sql`，无需执行增量脚本。Axon-link 数据库升级使用其 `V76__replay_db_compare_sample_limit_default_100.sql`，只改变以后新建行的默认值，不改已有配置及历史版本。旧每日结果无法从有限样本反推完整字段组合，需按业务日期重跑后才显示组合汇总。

页面“每日结果”展示连接名称和全量统计。仅一侧存在的样本直接按字段宽表展示，默认一页最多100条；字段不同按完整组合展示准确数量，默认展开差异最多的组合，在下方直接并列显示每个字段的两侧值。字段表头优先使用执行时 `FIELD_MEMO` 的中文快照，旧样本没有中文快照时显示技术字段名。样本过宽会标记截断，核对仍使用完整值；超过100条已保存样本时分页。当前部署仍按单服务实例运行；真实内网 Gauss 和 Spark 集群需要现场验收。

## 每日结果报表导出恢复

“每日结果”页面右上角提供“导出报表”。报表包含“领域汇总”“表明细”“表字段明细”三个 Sheet，表及字段明细包含本次成功结果的负责人快照。导出使用已查询的业务日期，包含该日期全部成功结果，页面分页和筛选条件仅影响列表。日期尚无成功结果时，点击导出会返回提示。

新版 Spark 按全量字段差异行累计各字段出现次数，涵盖被折叠的字段组合，样本过期后仍可导出。历史结果有完整组合统计时可以精确回退，存在折叠或缺少完整统计时返回 `FIELD_COUNTS_UNAVAILABLE`，须用新版 Spark 重跑对应日期后导出。

已有库需要补齐 `TSS_BCOMP_FIELD_COUNT_NEW`，以及结果表的 `FIELD_COUNTS_VERSION`、`FIELD_COUNTS_SIZE`、`REVISER_NAME`、`REVISER_USERNAME`。GaussDB 使用 `sql/add_bcomp_field_counts_new.sql` 和 `sql/add_bcomp_result_reviser_new.sql`，GDB/MySQL 使用对应 `_gdb.sql`。升级前检查实际结构，执行缺失的迁移，同时部署 service、Spark JAR 和内嵌前端。新建库直接使用最新完整建表脚本。操作顺序见 [内网源码构建说明](docs/migration/intranet-build.md)。
