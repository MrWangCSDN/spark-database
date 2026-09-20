# 数据库比对管理台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为独立数据库比对微服务增加可创建任务、维护原配置、查看结果的 React 管理台及同域 Spring Boot API。

**Architecture:** `compare-service` 新增管理台应用服务和 JDBC 仓储，所有写入继续使用原 `TSS_BCOMP_*` 表；`compare-ui` 使用 React、TypeScript、Vite，通过 `/api` 同域访问。Maven 构建先执行前端测试和构建，再把 `dist` 复制进 Boot JAR。

**Tech Stack:** Java 17、Spring Boot 3.3.10、Spring JDBC、React 18.3、TypeScript 5.6、Vite 5.4、TanStack Query 5、React Router 6、Vitest、Testing Library、Playwright。

**Spec:** `/Users/java/obsidian/01 Engineering/bd/数据库比对管理台-系统设计.md`

## Global Constraints

- 唯一迁移来源仍为 `/Users/wangshanhe/Downloads/tss-verisvr 2`；不使用其他项目页面或接口。
- 不新增或修改生产表、列、索引、触发器和字段编码。
- 页面只管理 `BCOMP_TYPE='2'`；Spark 比对算法和21参数协议不变。
- 所有列表服务端分页，默认20、最大100；排序字段后端白名单映射。
- 密码和密钥永不出现在API响应或日志；空新密码表示保留原密文。
- 配置聚合在一个数据库事务中保存；状态1或2任务引用时拒绝修改。
- 任务创建沿用状态1，并拒绝重复 `(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH)`。
- 页面提供 light、dark、system 三种主题，操作结果不能只靠颜色表达。

---

### Task 1: 管理 API 公共契约与错误处理

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/api/ApiException.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/api/ApiError.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/api/ApiExceptionHandler.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/api/PageResult.java`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/api/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ApiException(HttpStatus status, String code, String message)`、`ApiError(code,message,traceId)`、`PageResult<T>(items,page,size,total)`。

- [x] **Step 1: Write the failing test**

```java
mockMvc.perform(get("/test/conflict"))
  .andExpect(status().isConflict())
  .andExpect(jsonPath("$.code").value("TASK_IDENTITY_CONFLICT"))
  .andExpect(jsonPath("$.traceId").isNotEmpty());
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -pl compare-service -Dtest=ApiExceptionHandlerTest test`
Expected: compilation failure because the API error classes do not exist.

- [x] **Step 3: Implement the public contract**

```java
public record PageResult<T>(List<T> items, int page, int size, long total) {
  public PageResult {
    items = List.copyOf(items);
    if (page < 0 || size < 1 || size > 100 || total < 0) throw new IllegalArgumentException();
  }
}
```

Map `ApiException` to its status and unexpected exceptions to `INTERNAL_ERROR`; create a 16-character trace id and never include exception details in the response.

- [x] **Step 4: Run the focused test**

Run: `mvn -pl compare-service -Dtest=ApiExceptionHandlerTest test`
Expected: PASS.

### Task 2: 任务创建、列表、总览和差异分页

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskManagementRepository.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskManagementService.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskController.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/task/DashboardController.java`
- Create: DTO records under `compare-service/src/main/java/cn/sunline/tss/compare/task/`
- Modify: `compare-service/src/test/resources/legacy-schema.sql`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/task/TaskManagementApiTest.java`

**Interfaces:**
- Consumes: original `TSS_BCOMP_CONF/TABLE_SQL/FIELD/DATABASE_CONFIG/TASK/DTL` tables.
- Produces: `POST /api/tasks`, `GET /api/tasks`, exact task lookup, paged differences, `GET /api/dashboard`.

- [x] **Step 1: Write failing integration cases**

```java
mockMvc.perform(post("/api/tasks").contentType(APPLICATION_JSON).content(validRequest))
  .andExpect(status().isCreated())
  .andExpect(jsonPath("$.taskState").value("1"));
assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_TASK where TASK_STATE='1'", Integer.class)).isEqualTo(1);
```

Add independent cases for duplicate three-part identity returning409, incomplete config returning400, page size101 returning400, sort injection returning400, exact four-part task lookup, and `LINE_RESULT` filtering.

- [x] **Step 2: Verify RED**

Run: `mvn -pl compare-service -Dtest=TaskManagementApiTest test`
Expected: 404 for the new API routes.

- [x] **Step 3: Implement repository and service**

```java
public TaskSummary create(TaskCreateRequest request) {
  validateConfiguration(request.bcompIndex());
  if (repository.identityExists(request.bcompIndex(), request.taskBusinessDate(), request.taskBatch()))
    throw ApiException.conflict("TASK_IDENTITY_CONFLICT", "同一核对编号、业务日期和批次的任务已存在");
  return repository.insertWaiting(request);
}
```

Use `PreparedStatement#setMaxRows`, `LIMIT ? OFFSET ?` for H2/PostgreSQL-compatible tests, count query plus page query, and a fixed map from public sort values to SQL fragments. Format new timestamps as original `yyyyMMddHHmmss`.

- [x] **Step 4: Verify GREEN**

Run: `mvn -pl compare-service -Dtest=TaskManagementApiTest test`
Expected: PASS.

### Task 3: 数据库连接管理与密码保护

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseManagementRepository.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseManagementService.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseController.java`
- Create: request/response records in the same package
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/Database.java`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/database/DatabaseManagementApiTest.java`

**Interfaces:**
- Produces: paged `GET /api/databases`, `POST /api/databases`, `PUT /api/databases/{id}`; no password/key fields in responses.

- [x] **Step 1: Write failing security behavior tests**

```java
String body = mockMvc.perform(get("/api/databases")).andReturn().getResponse().getContentAsString();
assertThat(body).doesNotContain("plain-password").doesNotContain("DATABASE_GENERATERKEY");
```

Add cases proving an empty update password retains byte-identical cipher/key, a non-empty password generates a decryptable replacement, and a connection referenced by a waiting task returns409.

- [x] **Step 2: Verify RED**

Run: `mvn -pl compare-service -Dtest=DatabaseManagementApiTest test`
Expected: 404 for `/api/databases`.

- [x] **Step 3: Implement minimal API**

```java
public record DatabaseSummary(long databaseId, String databaseName, String url,
  String user, String driver, String encryption, boolean passwordConfigured) {}
```

Add `PasswordEncryption.generateKey/encrypt/decrypt`; keep the legacy AES algorithm exactly. Do not include password material in DTO `toString` or structured logs.

- [x] **Step 4: Verify GREEN**

Run: `mvn -pl compare-service -Dtest=DatabaseManagementApiTest test`
Expected: PASS.

### Task 4: 配置聚合事务、复制和缓存刷新

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigurationRepository.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigurationService.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ConfigurationController.java`
- Create: configuration DTO records in the same package
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/configuration/ConfigurationApiTest.java`

**Interfaces:**
- Consumes: `ConfigStore.reload()` after a committed write.
- Produces: list/detail/create/update/copy/options endpoints from the API spec.

- [x] **Step 1: Write failing transactional tests**

```java
mockMvc.perform(put("/api/configurations/22").contentType(APPLICATION_JSON).content(invalidSecondField))
  .andExpect(status().isBadRequest());
assertThat(jdbc.queryForObject("select ORIG_SQL from TSS_BCOMP_TABLE_SQL where BCOMP_INDEX=22", String.class))
  .isEqualTo("OLD_SQL_BEFORE");
```

Add cases for unique positive positions, at least one business key, maximum one partition field, enum IDs kept unchanged, running task returns409, copy excludes tasks, and refresh failure returns `saved=true/cacheRefreshed=false`.

- [x] **Step 2: Verify RED**

Run: `mvn -pl compare-service -Dtest=ConfigurationApiTest test`
Expected: 404 for `/api/configurations`.

- [x] **Step 3: Implement aggregate transaction**

```java
transaction.executeWithoutResult(status -> {
  repository.saveBasic(command.basic());
  repository.saveSql(command.tableSql());
  repository.replaceFields(command.basic().bcompIndex(), command.fields());
});
boolean refreshed = configStore.tryReload();
return new ConfigurationSaveResult(savedConfiguration, true, refreshed,
  refreshed ? null : "配置已保存，但缓存刷新失败");
```

Validate the full request before deleting/replacing field rows. State options come from distinct non-null `BCOMP_STATE`; do not invent state codes.

- [x] **Step 4: Verify GREEN**

Run: `mvn -pl compare-service -Dtest=ConfigurationApiTest test`
Expected: PASS.

### Task 5: React shell, theme, dashboard and tasks

**Files:**
- Create: `compare-ui/package.json`, `tsconfig.json`, `vite.config.ts`, `index.html`
- Create: `compare-ui/src/main.tsx`, `app/App.tsx`, `app/router.tsx`, `app/queryClient.ts`
- Create: `compare-ui/src/styles/{tokens,base,layout}.css`
- Create: `compare-ui/src/components/AppShell.tsx`, `ThemeSwitcher.tsx`, `StatusBadge.tsx`, `Pagination.tsx`
- Create: `compare-ui/src/pages/DashboardPage.tsx`, `TasksPage.tsx`, `TaskDetailPage.tsx`
- Create: `compare-ui/src/api/client.ts`, `types.ts`
- Test: `compare-ui/src/pages/TasksPage.test.tsx`, `TaskDetailPage.test.tsx`

**Interfaces:**
- Consumes: dashboard/task/difference API from Task2.
- Produces: responsive app shell, 10-second visible-page polling, task drawer and paged difference view.

- [x] **Step 1: Scaffold the frontend and write failing behavior tests**

```tsx
render(<TasksPage />)
await user.click(screen.getByRole('button', { name: '新建核对任务' }))
expect(screen.getByRole('dialog', { name: '新建核对任务' })).toBeVisible()
```

Test task creation validation, state text plus icon, result code mapping, and expanded old/new difference content. Use MSW handlers that mirror the full API DTOs.

- [x] **Step 2: Verify RED**

Run: `npm test -- --run` in `compare-ui`
Expected: module-not-found for the page components.

- [x] **Step 3: Implement UI shell and task flow**

Use CSS variables for light/dark themes, a 190px sidebar on desktop and drawer navigation below 800px. Use TanStack Query with `refetchInterval` returning10,000 only when `document.visibilityState==='visible'` and running tasks exist.

- [x] **Step 4: Verify GREEN**

Run: `npm test -- --run && npm run build`
Expected: PASS and `compare-ui/dist/index.html` exists.

### Task 6: 配置与数据库页面

**Files:**
- Create: `compare-ui/src/pages/ConfigurationsPage.tsx`, `ConfigurationEditorPage.tsx`, `DatabasesPage.tsx`
- Create: `compare-ui/src/components/FieldRulesEditor.tsx`, `DatabaseDrawer.tsx`, `SaveWarning.tsx`
- Test: `compare-ui/src/pages/ConfigurationEditorPage.test.tsx`, `DatabasesPage.test.tsx`

**Interfaces:**
- Consumes: configuration/database APIs from Tasks3-4.
- Produces: configuration list/editor/copy, database list/editor, password leave-unchanged semantics.

- [x] **Step 1: Write failing interaction tests**

```tsx
await user.click(screen.getByRole('button', { name: '保存配置' }))
expect(await screen.findByText('配置已保存，但缓存刷新失败')).toBeVisible()
expect(screen.getByRole('button', { name: '重试刷新缓存' })).toBeEnabled()
```

Add tests for duplicate positions, missing business key, second partition field, password input never prefilled, blank password omitted from update, and copy navigation.

- [x] **Step 2: Verify RED**

Run: `npm test -- --run ConfigurationEditorPage DatabasesPage`
Expected: module-not-found for the page components.

- [x] **Step 3: Implement editor pages**

Use one form submission for the whole configuration aggregate. Field rows use an accessible table with explicit add/remove controls and inline errors. SQL editors are plain monospaced textareas; do not execute or reformat SQL.

- [x] **Step 4: Verify GREEN**

Run: `npm test -- --run && npm run build`
Expected: PASS.

### Task 7: Boot packaging, SPA routing and browser flow

**Files:**
- Modify: `compare-service/pom.xml`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/api/SpaForwardController.java`
- Modify: `bin/package.sh`, `README.md`, `docs/migration/verification.md`
- Create: `compare-ui/e2e/management-console.spec.ts`, `playwright.config.ts`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/ApplicationSmokeTest.java`

**Interfaces:**
- Consumes: frontend `dist`; produces one Boot JAR serving static assets and client routes.

- [x] **Step 1: Write failing packaging and route tests**

```java
assertThat(http.getForEntity("/tasks", String.class).getBody()).contains("<div id=\"root\">");
assertThat(http.getForEntity("/api/missing", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
```

Playwright flow creates a task, observes it in the list, opens a completed task and pages differences using seeded test APIs.

- [x] **Step 2: Verify RED**

Run: `mvn -pl compare-service -Dtest=ApplicationSmokeTest test`
Expected: `/tasks` returns404.

- [x] **Step 3: Integrate frontend build and fallback**

Use `frontend-maven-plugin` pinned to Node 22.14.0/npm10.9.2 for `npm ci`, `npm test -- --run`, and `npm run build`; copy `compare-ui/dist` into `${project.build.outputDirectory}/static`. Forward only non-dot, non-`/api`, non-`/tss` routes to `/index.html`.

- [x] **Step 4: Run complete verification and package**

Run: `mvn clean verify`, `npm run e2e`, `bin/package.sh`.
Expected: backend/frontend/E2E PASS; release archive contains `BOOT-INF/classes/static/index.html` and no test schema, password, key, or production credential.

### Task 8: Final compatibility audit

**Files:**
- Modify: `docs/migration/verification.md`, `docs/superpowers/plans/2026-09-20-management-console.md`

**Interfaces:**
- Produces: evidence that the management console did not alter Spark comparison code or production schema.

- [x] **Step 1: Compare the Spark job with source provenance**

Run the existing SHA/body comparison recorded in `docs/migration/source-provenance.json`.
Expected: source hash matches and `SparkRun` body remains byte-identical after line-ending normalization.

- [x] **Step 2: Inspect DDL and secrets**

Run: `rg -n "CREATE TABLE|ALTER TABLE|DROP TABLE|DATABASE_PASSWORD|DATABASE_GENERATERKEY" compare-service/src/main compare-ui/src README.md config`
Expected: no production DDL; password/key occur only in parameterized repository column lists and security documentation, never response DTOs or fixtures with real credentials.

- [x] **Step 3: Check scope and package contents**

Verify dashboard, task creation/detail/differences, configuration create/edit/copy, database create/edit, theme, refresh warning, and original endpoints against the spec; record real-GaussDB/Spark capacity testing as an external validation requirement.

- [x] **Step 4: Commit the completed implementation**

```bash
git add compare-service compare-ui bin README.md docs .gitignore pom.xml
git commit -m "feat: add database comparison management console"
```
