# 迁移验证记录（2026-09-20）

环境：JDK 17.0.12、Maven 3.9、Spark 3.5.3 local[2]、H2测试库。生产元数据库和业务库均未连接。

## 已验证

- 服务模块管理与兼容测试：库表类型过滤、两个线程条件领取、歧义任务跳过、失败回写、21位置参数、原AES协议、旧frw属性、原主类列表、结果完整性、刷新成功/失败/超时、在途任务后继续领取、停机中断与停止领取、管理 API、原HTTP接口，以及无文件/Redis依赖的完整Boot启动。
- Spark模块2项测试：参数不完整时在启动Spark前拒绝；通过原main创建SparkSession并执行原Kryo、JDBC读取、cogroup、批量入库路径，小数据包含相同/差异/缺行/重复键。
- 前端5项组件交互测试和1项Chromium流程测试：创建任务、任务列表刷新、打开完成任务、差异代码映射与两侧内容展开、配置缓存刷新警告与重试、密码不回填及空密码保留。
- 原Spark源码SHA-256校验；从SparkRun开始直至文件结束的业务代码与来源逐字一致。Git归档统一换行符，逻辑不变。
- Boot 冒烟测试确认 `/tasks` 返回同包 SPA，未知 `/api/*` 保持404；前端 Maven 构建固定 Node/npm 版本。
- 服务包不含Spark Core/SQL及作业类，作业包不含Boot嵌套布局；测试建表脚本不在发布包中；服务 JAR 包含 `BOOT-INF/classes/static/index.html`。

## 尚未验证

真实GaussDB A模式驱动、目标Spark资源管理器、现场SQL/字段配置、2000万行容量、差异写入负载，以及OOM/时间字段异常修复。管理台是按现有表和新 API 重新开发，源工程没有页面源码可做像素或交互迁移对照。

构建命令：`mvn clean verify`；前端浏览器流程：`cd compare-ui && npm run e2e`；发布命令：`bin/package.sh`。本机Maven镜像较慢，执行时用临时settings指向Maven Central，未修改全局Maven配置。
