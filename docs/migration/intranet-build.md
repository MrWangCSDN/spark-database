# 内网源码构建说明

## 源码包内容

源码 ZIP 只包含后端 `compare-service`、Spark 作业 `compare-spark` 及构建/部署文件，不包含 `compare-ui` 工程。前端已在外网构建，并直接放入 `compare-service/src/main/resources/static/`；内网 Maven 会将这些静态资源打入后端 JAR 的 `BOOT-INF/classes/static/`。使用下文的跳过参数时，无需安装 Node/npm 或重新编译前端。如需修改前端页面，须回到含 `compare-ui` 的完整工程重新构建并生成源码 ZIP。

源码包不包含 `.git`、`compare-ui` 源码、`node_modules`、`target`、本地数据库配置、密码或依赖缓存。`SOURCE-COMMIT.txt` 记录打包提交，ZIP 旁的 `.sha256` 文件用于传输校验。

## 内网构建前置条件

| 依赖 | 要求 |
|---|---|
| JDK | 17；不要使用本机默认 JDK 25 构建当前 Lombok 版本 |
| Maven | 3.9 或更高版本 |
| Maven 仓库 | 内网 Nexus/Artifactory 必须代理或预置 Spring Boot 3.3.10、Spark 3.5.3、Jasypt Starter 3.0.5、BES Lite Starter `bes.springframework.boot:bes-lite-spring-boot-starter:3.x-11.5.0.004`、前端 Maven 插件、JDBC 驱动及全部传递依赖；还要预置 `org.opengauss:opengauss-jdbc:6.0.0-htrunk8`。BES 与该 openGauss 版本不在 Maven Central |
| Node/npm | 使用预编译前端的构建命令时不需要；只有在内网修改并重编前端时，才需 Node `22.14.0`、npm `10.9.2` 和锁定的 npm 包 |
| 构建资源 | 建议至少 4 CPU、8 GiB 内存和 5 GiB 可用磁盘 |

源码 ZIP 不包含 Maven/npm/Node 依赖，因此只有在上述内网镜像齐全时才属于可构建包。完全隔离且没有依赖镜像的环境不能只靠该源码 ZIP 完成构建，需要另行制作依赖缓存包或在可信联网环境生成发布包。

## 推荐内网配置

在内网 Maven `settings.xml` 中把 Central 映射到企业 Maven 仓库。npm 使用企业 Registry：

```bash
npm config set registry https://your-internal-npm/repository/npm-group/
```

本源码 ZIP 不含前端源码；若需重新编译前端，请使用完整工程。Node 下载地址由 `frontend-maven-plugin` 使用。内网不能访问默认地址时，需要在企业制品库镜像 Node 官方目录，并提供插件对应的 `nodeDownloadRoot` 配置。

## 构建与验收

升级已有元数据库时，先在 bd 服务与 Spark 使用的 schema 执行 `sql/add_bcomp_result_module_new.sql`，再替换服务和 Spark JAR。新建环境直接使用包含领域列的 `sql/create_bcomp_new.sql`。历史结果的领域为空；新导入 Axon 配置并成功运行后，每日结果才会保存领域快照。

Axon 新版 `_new` 导出为 13 列配置，在原 11 列后追加 nullable 的 `reviser_name,reviser_username`（各最长 100 字符）；导入同时兼容 8、10、11 列旧脚本。已有元数据库升级时，先停止 bd 并确认全部在途 Spark 作业结束，在服务与 Spark 使用的同一库执行修订人增量。GaussDB 先设置正确 `search_path`，执行 `sql/add_bcomp_reviser_new.sql`；GDB/MySQL 先选择正确数据库，执行 `sql/add_bcomp_reviser_gdb.sql`。确认两个 `VARCHAR(100)` 可空列已存在后，再启动新版服务并导入脚本。增量保留原配置，旧行修订人字段为 NULL；只执行一次，部分执行后先检查列是否存在。GDB/MySQL DDL 会隐式提交，事务不能保证升级回滚。全新空库分别使用最新 `sql/create_bcomp_new.sql`、`sql/create_bcomp_gdb.sql`，无需重复执行此增量。

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
if jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar | grep -E 'BOOT-INF/lib/(spring-boot-starter-tomcat|tomcat-embed)'; then
  echo 'BES 发布 JAR 中不应包含内嵌 Tomcat' >&2
  exit 1
fi

tar -tzf target/tss-compare-1.0.0-SNAPSHOT.tar.gz
```

第一条命令必须找到管理台首页，第二条必须找到 BES Starter，且 JAR 不应包含 Tomcat。发布包应包含服务 JAR、Spark 作业 JAR、运行时 JDBC 驱动、配置示例和启动脚本。上述跳过参数只跳过 Node/npm，不跳过后端和 Spark 的测试。外网 `-Plocal-servlet-tests` 只用于本地测试，不能作为 BES 发布构建。

部署时在 `config/bd.env` 中填写 `TSS_METADATA_PASSWORD='ENC(...)'`。使用 `bin/encrypt-password.sh` 从终端读取元库明文密码并生成密文；加密与运行时都需由外部环境注入同一个 `JASYPT_ENCRYPTOR_PASSWORD`，不要把口令写入源码包或 `bd.env`。启动后检查 BES 的 WebServer 日志和管理台/API 实际可访问；仅通过 Maven 构建不能证明厂商容器运行兼容。

真实 GaussDB、Spark Master、厂商驱动和生产配置不属于源码构建条件，必须在部署验收阶段单独提供和验证。
