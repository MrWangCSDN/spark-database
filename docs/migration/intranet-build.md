# 内网源码构建说明

## 源码包内容

源码 ZIP 只包含后端 `compare-service`、Spark 作业 `compare-spark` 及构建/部署文件，不包含 `compare-ui` 工程。前端已在外网构建，并直接放入 `compare-service/src/main/resources/static/`；内网 Maven 会将这些静态资源打入后端 JAR 的 `BOOT-INF/classes/static/`。使用下文的跳过参数时，无需安装 Node/npm 或重新编译前端。如需修改前端页面，须回到含 `compare-ui` 的完整工程重新构建并生成源码 ZIP。

源码包不包含 `.git`、`compare-ui` 源码、`node_modules`、`target`、本地数据库配置、密码或依赖缓存。`SOURCE-COMMIT.txt` 记录打包提交，ZIP 旁的 `.sha256` 文件用于传输校验。

## 内网构建前置条件

| 依赖 | 要求 |
|---|---|
| JDK | 17；不要使用本机默认 JDK 25 构建当前 Lombok 版本 |
| Maven | 3.9 或更高版本 |
| Maven 仓库 | 内网 Nexus/Artifactory 必须代理或预置 Spring Boot 3.3.10、Spark 3.5.3、Jasypt Starter 3.0.5、BES Lite Starter `bes.springframework.boot:bes-lite-spring-boot-starter:3.x-11.5.0.004`、前端 Maven 插件、JDBC 驱动及全部传递依赖。导出报表需要 `org.apache.poi:poi-ooxml:5.3.0` 及其传递依赖。还要预置 `org.opengauss:opengauss-jdbc:6.0.0-htrunk8`，Spark 3.4 现场需预置该 profile 的 Spark 3.4.0 和 Jackson 2.14.2 依赖。BES 与该 openGauss 版本由企业制品库提供 |
| Node/npm | 使用预编译前端的构建命令时不需要；只有在内网修改并重编前端时，才需 Node `22.14.0`、npm `10.9.2` 和锁定的 npm 包 |
| 构建资源 | 建议至少 4 CPU、8 GiB 内存和 5 GiB 可用磁盘 |

源码 ZIP 不包含 Maven/npm/Node 依赖，因此只有在上述内网镜像齐全时才属于可构建包。完全隔离且没有依赖镜像的环境不能只靠该源码 ZIP 完成构建，需要另行制作依赖缓存包或在可信联网环境生成发布包。

## 推荐内网配置

在内网 Maven `settings.xml` 中把 Central 映射到企业 Maven 仓库。npm 使用企业 Registry：

```bash
npm config set registry https://your-internal-npm/repository/npm-group/
```

本源码 ZIP 不含前端源码；若需重新编译前端，请使用完整工程。Node 下载地址由 `frontend-maven-plugin` 使用。内网不能访问默认地址时，需要在企业制品库镜像 Node 官方目录，并提供插件对应的 `nodeDownloadRoot` 配置。

字段映射默认使用名称模式，配置 `FIELD_NAME` 与两侧 SQL 返回列名或 `AS` 别名按大小写不敏感方式匹配。SQL 列顺序可以不同，配置英文名必须唯一，缺列或返回重名列时任务失败；此模式忽略历史位置值。需要历史位置映射时，在部署 `config/bd.env` 中显式设置 `BD_FIELD_MATCH_BY_NAME=false`，两侧位置须为正整数且各自唯一。开关对应 `compare.field-match-by-name=${BD_FIELD_MATCH_BY_NAME:true}`。升级时同步部署匹配版本的服务、Spark 作业 JAR 和随服务打包的前端。

部署时核对最终生效的 `app_resource`，推荐值为 `${COMPARE_HOME}/jobs/compare-spark-1.0.0-SNAPSHOT.jar`。名称模式会在任务提交前检查这个实际 JAR 中的主类和 `QueryFieldResolver`，部署错误会连同路径返回任务详情。提交日志中的 `resource`、`sha256`、`fieldMatch` 及 Spark Driver 日志中的“作业类来源”用于核对实际执行文件。构建发布物应在项目根目录运行 `bin/package.sh`，该入口同时构建服务和 Spark 模块。

发布入口会先移除两个模块的旧目标 JAR，再校验本次 Maven 构建生成的 service 和 Spark 产物。使用 `-pl` 时需要同时包含 `compare-service,compare-spark`。这样可以防止单独构建一个模块时沿用另一个模块目录中的旧 JAR。

在实际 bd 部署目录执行下列检查，`job_jar` 使用当前生效 `app_resource` 指向的文件。

```bash
job_jar='jobs/compare-spark-1.0.0-SNAPSHOT.jar'
jar tf "$job_jar" | grep 'QueryFieldResolver.class'
javap -classpath "$job_jar" -p cn.sunline.tss.tssveri.spark.SparkHandlerTable \
  | grep 'lambda.*RddToPair'
```

本次 JDK 17 构建的行映射方法为 `lambda$RddToPair$64ed6cd6$1`，历史位置读取实现包含 `lambda$RddToPair$4f96395$1`。结合本次任务的提交日志与 Driver 作业类来源，可进一步定位实际提交文件或 Spark 类路径中的旧同名类。

本版管理台导入兼容 Axon 自定义 SQL 中的反斜杠。`orig_sql`、`dest_sql` 的 `CONVERT(0x... USING utf8mb4)` 由服务解码后通过 JDBC 参数保存，普通字符串查询也按标准 SQL 单引号规则解析后参数化保存。该修复升级 bd 服务即可生效，原下载脚本可直接重新导入，本次兼容修复无需数据库迁移。

## Spark 本地磁盘配置

新版服务会将应用配置中的非空 `spark.local.dir` 传入 Spark 提交命令，支持含空格路径及逗号分隔的多个目录。缺失或空白时由 Spark 自身配置选择目录。可在部署的 `config/application-local.properties` 中设置以下属性，将示例路径替换为实际的大磁盘目录。

```properties
spark.local.dir=/data/spark-local
spark.rdd.compress=true
```

正常服务提交默认启用 `spark.rdd.compress=true`，压缩 `MEMORY_AND_DISK_SER` 的序列化 RDD 缓存及其落盘内容，可显式设置 `false`。压缩会消耗 CPU，磁盘收益取决于实际数据。两阶段统计缓存完整 cogroup 数据，包含全部配置字段和一致记录，样本上限控制最终保存的差异样本数量。

Standalone 部署还需在每个 Worker 实际使用的 `${SPARK_CONF_DIR:-$SPARK_HOME/conf}/spark-env.sh` 中设置本地根目录。

```bash
export SPARK_LOCAL_DIRS=/data/spark-local
```

各节点目录需存在于预期挂载点，且 Spark 运行账户可写。等待在途作业结束后重启相应 Worker，再发起新任务。Spark 3.4 的 Standalone Worker 会为 Executor 设置 `SPARK_EXECUTOR_DIRS`，该值优先于应用的 `spark.local.dir`。因此需要逐个 Worker 核对运行环境与最终缓存目录。

每个 Executor 每个应用首次读取时会输出“Spark 本地存储”日志，包含实际 `DiskBlockManager` 目录、executorId、可用字节数、总字节数及缓存压缩开关。Spark 自身的 `Created local directory at .../blockmgr-...` 日志也可以核对实际路径。在报错 Executor 所在节点执行下面的只读检查，替换为日志中的实际目录。

```bash
spark_dir='/data/spark-local'
df -hT "$spark_dir"
df -i "$spark_dir"
findmnt -T "$spark_dir"
```

核对可用容量、inode 及挂载点，结合任务执行时的峰值占用判断容量。磁盘总容量 600 GB 仍需结合该节点实际剩余空间、缓存行宽、压缩率和并发任务评估。

## Spark 失败与退出原因

服务提交日志会记录任务编号、任务日期、批次、时点、application ID 及实际超时秒数。`compare.spark-timeout-seconds=0` 表示等待 Spark 终态，正数表示本次提交的等待上限。超时配置在创建子进程前解析。

主动终止前，服务日志记录固定原因 `BATCH_CANCELLED`、`WAIT_TIMEOUT` 或 `WAIT_INTERRUPTED`，并关联 application ID 或批次。服务停止时会先记录等待运行中任务的宽限秒数，超出宽限后记录中断等待线程的事件。部署重启及停止批次都应结合这些日志核对。

任务失败详情保留已识别的磁盘空间耗尽、内存不足及数组下标越界等固定诊断，字段名称解析失败继续显示对应字段提示。未知失败显示有界异常类型链，异常消息中的 SQL、业务值和连接参数沿保密规则处理。Spark 正文执行失败时先保存主异常原因，再关闭 SparkContext。关闭异常作为附加异常保留在最终安全日志及重抛异常中。

Executor 的 `Driver commanded a shutdown`、`disconnected during shutdown` 和 `RECEIVED SIGNAL TERM` 记录退出阶段。定位时，先按 application ID 对齐服务端的主动终止原因，再查看 Driver 的首个异常和任务失败详情。Spark Launcher 通知 `FINISHED` 后，服务还会检查任务表的发布结果是否完整。

## 构建与验收

现场 Spark 为 3.4.0 时，在源码根目录使用以下入口，`spark-3.4` 同时选择匹配的 Spark 3.4.0 和作业模块 Jackson 2.14.2。

```bash
bin/package.sh -Pspark-3.4 -Dskip.installnodenpm=true -Dskip.npm=true clean
```

默认构建选择 Spark 3.5.3 和作业模块 Jackson 2.15.2。服务的 Spring Boot Jackson 依赖使用独立依赖管理。完成构建后，部署发布包里的 `lib/compare-service-1.0.0-SNAPSHOT.jar` 和 `jobs/compare-spark-1.0.0-SNAPSHOT.jar`，并核对 `app_resource` 的最终路径。

唯一索引空键支持通过新版 Spark 作业生效。重新构建并部署服务与 Spark 发布物后，按原核对配置重跑业务日期，核对 NULL、空串、重复次数及两侧独有统计。含空键行按 SQL 返回的原始整行值计次，部署验收时使用两侧业务约定的金额、日期等输出格式。样本中的业务键保留 NULL，重复行按照样本限额保存。

升级已有元数据库时，先在 bd 服务与 Spark 使用的 schema 执行 `sql/add_bcomp_result_module_new.sql`，再替换服务和 Spark JAR。新建环境直接使用包含领域列的 `sql/create_bcomp_new.sql`。历史结果的领域为空；新导入 Axon 配置并成功运行后，每日结果才会保存领域快照。

Axon 新版 `_new` 导出为 13 列配置，在原 11 列后追加 nullable 的 `reviser_name,reviser_username`（各最长 100 字符）；导入同时兼容 8、10、11 列旧脚本。已有元数据库升级时，先停止 bd 并确认全部在途 Spark 作业结束，在服务与 Spark 使用的同一库执行修订人增量。GaussDB 先设置正确 `search_path`，执行 `sql/add_bcomp_reviser_new.sql`；GDB/MySQL 先选择正确数据库，执行 `sql/add_bcomp_reviser_gdb.sql`。确认两个 `VARCHAR(100)` 可空列已存在后，再启动新版服务并导入脚本。增量保留原配置，旧行修订人字段为 NULL；只执行一次，部分执行后先检查列是否存在。GDB/MySQL DDL 会隐式提交，事务不能保证升级回滚。全新空库分别使用最新 `sql/create_bcomp_new.sql`、`sql/create_bcomp_gdb.sql`，无需重复执行此增量。

### 恢复导出报表时升级结果结构

先停止 bd 并等待在途 Spark 作业全部结束，再检查服务与 Spark 实际使用的元数据库 schema。已有库按当前结构补齐以下迁移。

| 元数据库 | 完整字段统计 | 结果负责人快照 |
|---|---|---|
| GaussDB / PostgreSQL 兼容 | `sql/add_bcomp_field_counts_new.sql` | `sql/add_bcomp_result_reviser_new.sql` |
| GDB / MySQL | `sql/add_bcomp_field_counts_gdb.sql` | `sql/add_bcomp_result_reviser_gdb.sql` |

字段统计迁移新增 `TSS_BCOMP_FIELD_COUNT_NEW` 和结果表的 `FIELD_COUNTS_VERSION`、`FIELD_COUNTS_SIZE`。结果负责人迁移为 `TSS_BCOMP_RESULT_NEW` 增加可空的 `REVISER_NAME`、`REVISER_USERNAME`，可单独用于已经完成配置表修订人升级的环境。

GaussDB 设置正确 `search_path` 后可以用下列查询检查现有列。GDB/MySQL 将 `current_schema()` 换成 `database()`。

```sql
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND lower(table_name) IN ('tss_bcomp_result_new', 'tss_bcomp_field_count_new')
ORDER BY table_name, ordinal_position;
```

每日结果表、字段组合表及领域快照应已存在，字段组合表需有 `COLLAPSED_PATTERN_COUNT`。缺失时先按字段统计脚本注释补对应数据库的前置迁移。各增量按实际结构执行一次，部分执行过的脚本应检查后只执行缺失语句。GDB/MySQL 的 DDL 会隐式提交。全新空库直接使用最新 `create_bcomp_new.sql` 或 `create_bcomp_gdb.sql` 建齐十四张表。

结构升级完成后，同步替换 service、Spark JAR 和内嵌前端，再启动服务。页面右上角显示“导出报表”，按已查询日期导出全部成功结果，包含领域汇总、表明细和表字段明细。样本过期后新版全量字段统计仍可导出。历史结果缺少精确统计时会提示 `FIELD_COUNTS_UNAVAILABLE`，需用新版 Spark 重跑对应业务日期。该日期无成功结果时会提示没有可导出的结果。

### 执行构建

```bash
unzip tss-compare-source-1.0.0-SNAPSHOT.zip
cd tss-compare-source-1.0.0-SNAPSHOT
export JAVA_HOME=/path/to/jdk17
mvn -Dskip.installnodenpm=true -Dskip.npm=true clean verify
bin/package.sh -Dskip.installnodenpm=true -Dskip.npm=true
```

构建成功后检查：

```bash
jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar \
  | grep '^BOOT-INF/classes/static/index.html$'
jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar \
  | grep 'BOOT-INF/lib/bes-lite-spring-boot-starter-3.x-11.5.0.004.jar'
jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar \
  | grep '^BOOT-INF/lib/poi-ooxml-5.3.0.jar$'
if jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar | grep -E 'BOOT-INF/lib/(spring-boot-starter-tomcat|tomcat-embed)'; then
  echo 'BES 发布 JAR 中不应包含内嵌 Tomcat' >&2
  exit 1
fi

tar -tzf target/tss-compare-1.0.0-SNAPSHOT.tar.gz
tar -tzf target/tss-compare-1.0.0-SNAPSHOT.tar.gz \
  | grep -E 'sql/add_bcomp_(field_counts|result_reviser)_(new|gdb)\.sql$'
```

验收输出应包含管理台首页、BES Starter、POI 5.3.0 和四个导出升级脚本。发布包还应包含 Spark 作业 JAR、运行时 JDBC 驱动、配置示例和启动脚本。上述跳过参数会继续运行后端和 Spark 测试。外网 `-Plocal-servlet-tests` 用于本地测试，内网 BES 发布使用默认 profile。

部署时在 `config/bd.env` 中填写 `TSS_METADATA_PASSWORD='ENC(...)'`。使用 `bin/encrypt-password.sh` 从终端读取元库明文密码并生成密文；加密与运行时都需由外部环境注入同一个 `JASYPT_ENCRYPTOR_PASSWORD`，不要把口令写入源码包或 `bd.env`。启动后检查 BES 的 WebServer 日志和管理台/API 实际可访问；仅通过 Maven 构建不能证明厂商容器运行兼容。

真实 GaussDB、Spark Master、厂商驱动和生产配置不属于源码构建条件，必须在部署验收阶段单独提供和验证。
