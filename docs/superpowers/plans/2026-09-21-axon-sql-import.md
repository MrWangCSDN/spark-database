# Axon SQL 受限导入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将“新建配置”改为上传或粘贴 Axon 生成的 SQL，并安全地事务替换三张核对配置表、刷新缓存。

**Architecture:** 后端新增固定语法解析器和导入服务，仅接受 Axon 生成器的三表脚本；事务中检查活动任务、定向清空、逐批执行 INSERT 并核对行数。前端 `/configurations/new` 改为独立导入页，详情编辑和任务创建流程保持不变。

**Tech Stack:** Java 17、Spring Boot 3.3、JdbcTemplate、React 18、TypeScript、TanStack Query、Vitest、MockMvc。

**Spec:** `/Users/java/obsidian/01 Engineering/bd/Axon配置脚本接入-系统设计.md`（对应远端设计提交 `bac1e29`）

## Global Constraints

- 不新增或修改生产表结构。
- 只接受 `TSS_BCOMP_CONF`、`TSS_BCOMP_FIELD`、`TSS_BCOMP_TABLE_SQL` 的 Axon 固定脚本。
- 存在 `TASK_STATE IN ('1','2')` 的任务时禁止导入。
- 数据提交与缓存刷新状态分别返回；缓存失败不能伪装成数据库回滚。
- `date`、`timestamp` 继续由 Axon 生成 `FIELD_TYPE='1'`，导入器不改写字段类型。

## Review Focus

- SQL 字符串中包含分号、括号和转义单引号时仍应被当作一个值解析。
- 其他表、额外语句、函数表达式、列顺序变化必须在执行前或事务内拒绝并回滚。
- 合法多批 INSERT 的累计行数必须准确，配置数与两侧 SQL 数不一致必须回滚。
- 活动任务检查和三表写入必须在同一事务内完成。
- 前端 FormData 不能手工设置 JSON Content-Type，否则浏览器不会生成 multipart boundary。

---

### Task 1: 受限脚本解析与事务导入

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/AxonConfigScriptParser.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigScriptImportResult.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigurationRepository.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigurationService.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigurationController.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/api/ApiException.java`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/configuration/ConfigurationApiTest.java`

**Interfaces:**
- Produces: `POST /api/configurations/import` multipart field `file` and `ConfigScriptImportResult`.
- Consumes: existing `CacheReloader`, `JdbcTemplate`, and original three configuration tables.

- [x] **Step 1: Write failing API tests** for valid full replacement, active-task conflict, forbidden SQL rollback, SQL literals containing semicolons and cache refresh failure.
- [x] **Step 2: Run `mvn -pl compare-service -Dtest=ConfigurationApiTest test`** and confirm the new endpoint tests fail because it does not exist.
- [x] **Step 3: Implement the parser** with statement streaming, strict statement order, exact column lists, and integer/string-literal-only VALUES tuples.
- [x] **Step 4: Implement transactional import** that maps the three truncate directives to fixed-table deletes, executes validated inserts, checks counts, and translates database failures without leaking SQL.
- [x] **Step 5: Add controller/service boundary** including required file, 50 MiB size limit, activity conflict, response counts, and cache warning.
- [x] **Step 6: Run the focused test and full backend suite**; expect all tests to pass.

### Task 2: Axon 脚本导入页面

**Files:**
- Create: `compare-ui/src/pages/ConfigurationImportPage.tsx`
- Create: `compare-ui/src/pages/ConfigurationImportPage.test.tsx`
- Modify: `compare-ui/src/pages/ConfigurationsPage.tsx`
- Modify: `compare-ui/src/pages/ConfigurationsPage.test.tsx`
- Modify: `compare-ui/src/app/router.tsx`
- Modify: `compare-ui/src/api/client.ts`
- Modify: `compare-ui/src/api/types.ts`
- Modify: `compare-ui/src/styles/layout.css`
- Modify: `compare-ui/src/test/server.ts`

**Interfaces:**
- Consumes: Task 1 multipart endpoint and `ConfigScriptImportResult`.
- Produces: `/configurations/new` upload/paste workflow; existing `/configurations/:index` remains the edit page.

- [x] **Step 1: Write failing component tests** for the new action label, empty-script validation, multipart submission, success counts, and cache warning.
- [x] **Step 2: Run `npm test -- --run ConfigurationImportPage ConfigurationsPage`** and confirm failures describe the missing import UI.
- [x] **Step 3: Update the API client** so FormData requests omit the JSON Content-Type header.
- [x] **Step 4: Build the import page** with file/paste modes, destructive-scope notice, filename/size display, pending state, result counts, error message, and return-to-list action.
- [x] **Step 5: Route and relabel the list action** from “新建配置” to “导入 Axon 配置”.
- [x] **Step 6: Run focused and full frontend tests plus TypeScript/Vite build**; expect all to pass.

### Task 3: 配置、联调和回归验证

**Files:**
- Modify: `compare-service/src/main/resources/application.properties`
- Modify: `README.md` if the existing deployment section documents request limits.
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/ApplicationSmokeTest.java`

**Interfaces:**
- Consumes: Tasks 1–2 complete feature.
- Produces: deployable Boot JAR with frontend route fallback and multipart limits.

- [x] **Step 1: Add a smoke assertion** that the import route is wired and invalid empty upload returns the stable error contract.
- [x] **Step 2: Configure multipart file/request limits** at 50 MiB/51 MiB and document `compare.config-import-max-bytes`.
- [x] **Step 3: Run `mvn clean verify`** and confirm backend, frontend and packaging checks pass.
- [x] **Step 4: Run browser smoke verification** against the local preview and inspect the upload page at `/configurations/new`.
- [x] **Step 5: Review `git diff --check`, secret exposure and final diff**, then commit the implementation.
