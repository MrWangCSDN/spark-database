# 内网源码构建说明

## 源码包内容

源码 ZIP 同时包含 `compare-service`、`compare-spark` 和 `compare-ui`。前端不需要单独发布；构建 `compare-service` 时，`frontend-maven-plugin` 会安装固定版本 Node/npm、执行 `npm ci`、运行 Vitest、执行 Vite 构建，并把 `compare-ui/dist` 复制到后端 JAR 的 `BOOT-INF/classes/static`。

源码包不包含 `.git`、`node_modules`、`target`、前端 `dist`、本地数据库配置、密码或依赖缓存。`SOURCE-COMMIT.txt` 记录打包提交，ZIP 旁的 `.sha256` 文件用于传输校验。

## 内网构建前置条件

| 依赖 | 要求 |
|---|---|
| JDK | 17；不要使用本机默认 JDK 25 构建当前 Lombok 版本 |
| Maven | 3.9 或更高版本 |
| Maven 仓库 | 内网 Nexus/Artifactory 必须代理或预置 Spring Boot 3.3.10、Spark 3.5.3、前端 Maven 插件、JDBC 驱动及全部传递依赖 |
| Node 分发源 | 必须能提供 Node `22.14.0`；默认插件会访问 `nodejs.org` |
| npm 仓库 | 必须提供 `package-lock.json` 锁定的 npm 包和 npm `10.9.2`；可使用企业 npm Registry |
| 构建资源 | 建议至少 4 CPU、8 GiB 内存和 5 GiB 可用磁盘 |

源码 ZIP 不包含 Maven/npm/Node 依赖，因此只有在上述内网镜像齐全时才属于可构建包。完全隔离且没有依赖镜像的环境不能只靠该源码 ZIP 完成构建，需要另行制作依赖缓存包或在可信联网环境生成发布包。

## 推荐内网配置

在内网 Maven `settings.xml` 中把 Central 映射到企业 Maven 仓库。npm 使用企业 Registry：

```bash
npm config set registry https://your-internal-npm/repository/npm-group/
```

Node 下载地址由 `frontend-maven-plugin` 使用。若内网不能访问默认地址，需要在企业制品库镜像 Node 官方目录，并在构建时提供插件对应的 `nodeDownloadRoot` 配置；也可以由内网管理员将 Node 22.14.0 预热到构建节点使用的插件缓存。

## 构建与验收

```bash
unzip tss-compare-source-1.0.0-SNAPSHOT.zip
cd tss-compare-source-1.0.0-SNAPSHOT
export JAVA_HOME=/path/to/jdk17
mvn clean verify
bin/package.sh
```

构建成功后检查：

```bash
jar tf compare-service/target/compare-service-1.0.0-SNAPSHOT.jar \
  | grep '^BOOT-INF/classes/static/index.html$'

tar -tzf target/tss-compare-1.0.0-SNAPSHOT.tar.gz
```

第一条命令必须找到管理台首页，证明前端已进入后端 JAR。发布包应包含服务 JAR、Spark 作业 JAR、运行时 JDBC 驱动、配置示例和启动脚本。

真实 GaussDB、Spark Master、厂商驱动和生产配置不属于源码构建条件，必须在部署验收阶段单独提供和验证。
