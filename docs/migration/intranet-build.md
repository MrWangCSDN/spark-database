# 内网源码构建说明

## 源码包内容

源码 ZIP 只包含后端 `compare-service`、Spark 作业 `compare-spark` 及构建/部署文件，不包含 `compare-ui` 工程。前端已在外网构建，并直接放入 `compare-service/src/main/resources/static/`；内网 Maven 会将这些静态资源打入后端 JAR 的 `BOOT-INF/classes/static/`。使用下文的跳过参数时，无需安装 Node/npm 或重新编译前端。如需修改前端页面，须回到含 `compare-ui` 的完整工程重新构建并生成源码 ZIP。

源码包不包含 `.git`、`compare-ui` 源码、`node_modules`、`target`、本地数据库配置、密码或依赖缓存。`SOURCE-COMMIT.txt` 记录打包提交，ZIP 旁的 `.sha256` 文件用于传输校验。

## 内网构建前置条件

| 依赖 | 要求 |
|---|---|
| JDK | 17；不要使用本机默认 JDK 25 构建当前 Lombok 版本 |
| Maven | 3.9 或更高版本 |
| Maven 仓库 | 内网 Nexus/Artifactory 必须代理或预置 Spring Boot 3.3.10、Spark 3.5.3、前端 Maven 插件、JDBC 驱动及全部传递依赖；尤其要预置 `org.opengauss:opengauss-jdbc:6.0.0-htrunk8`，该版本不在 Maven Central |
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

tar -tzf target/tss-compare-1.0.0-SNAPSHOT.tar.gz
```

第一条命令必须找到管理台首页，证明后端源码中的静态页面已进入后端 JAR。发布包应包含服务 JAR、Spark 作业 JAR、运行时 JDBC 驱动、配置示例和启动脚本。上述跳过参数只跳过 Node/npm，不跳过后端和 Spark 的测试。

真实 GaussDB、Spark Master、厂商驱动和生产配置不属于源码构建条件，必须在部署验收阶段单独提供和验证。
