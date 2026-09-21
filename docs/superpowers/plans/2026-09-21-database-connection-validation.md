# Database Connection Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe database connection testing, save-time cache refresh feedback, GaussDB driver delivery guidance, and an opt-in real GaussDB verification path without changing the existing tables, save responses, or Spark comparison flow.

**Architecture:** Add a bounded `DatabaseConnectionTester` behind a dedicated database test service and two additive REST endpoints. The tester loads the configured JDBC driver, opens one connection, uses JDBC validity and metadata APIs, and maps failures to stable non-sensitive API errors. The React drawer tests either the current draft or the unchanged saved connection and invalidates stale test results whenever connection inputs change; the database page reuses the existing cache refresh endpoint after a successful save.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring MVC, JDBC, JUnit 5, MockMvc, React 18, TypeScript 5.6, TanStack React Query, Vitest, Testing Library, MSW, Playwright.

**Spec:** `/Users/java/obsidian/01 Engineering/bd/数据库比对管理台-系统设计.md`

## Global Constraints

- Do not create or alter production tables, columns, indexes, triggers, or stored values.
- Preserve the existing `GET/POST/PUT /api/databases` request and response contracts.
- Add only `POST /api/databases/test` and `POST /api/databases/{id}/test`.
- Never return or log JDBC URLs, usernames, passwords, encryption keys, raw driver exceptions, or stack traces in connection-test responses.
- A draft connection test must not insert, update, or delete configuration data.
- A saved connection test with a temporary password must not replace the stored password or key.
- A connection test must not accept or execute arbitrary SQL.
- Default connection-test timeout is 10 seconds and maximum concurrency is 4; both are configurable.
- PostgreSQL, Oracle, and MySQL drivers remain bundled; proprietary GaussDB drivers are supplied externally through `drivers/`, Boot `loader.path`, and `compare.spark-jars`.
- Preserve the original Spark job, 21 argument positions, Axon script generation, and `FIELD_TYPE='1'` temporal-string rule.

## Review Focus

- A JDBC driver can ignore interruption during connect; the bounded executor must cap stuck work and reject overload instead of creating unbounded threads.
- Editing URL, user, or driver with a blank password must not test the old saved connection while presenting the result as validation of the draft.
- Authentication, TLS, DNS, routing, and driver failures must never expose their raw exception messages to the browser.
- Saving the connection can succeed while cache refresh fails; the UI must preserve the saved result and offer a retry.
- Native GaussDB drivers may not be present in ordinary builds; the UI and API must report a stable driver-missing error and deployment docs must describe both service and Spark classpaths.

---

### Task 1: Bounded JDBC Connection Tester

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseConnectionSpec.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseConnectionTestResult.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseConnectionProbe.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/JdbcDatabaseConnectionProbe.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseConnectionTester.java`
- Create: `compare-service/src/test/java/cn/sunline/tss/compare/database/DatabaseConnectionTesterTest.java`

**Interfaces:**
- Consumes: `DatabaseConnectionSpec(url, user, password, driver)` containing request-local plaintext only.
- Produces: `DatabaseConnectionTester.test(DatabaseConnectionSpec)` returning `DatabaseConnectionTestResult(success, databaseProduct, databaseVersion, elapsedMs)` or throwing a stable `ApiException`.
- Produces: package-visible `DatabaseConnectionTester(DatabaseConnectionProbe, Duration, int)` for deterministic tests.

- [ ] **Step 1: Write failing tester tests**

Create tests with fake probes that assert:

```java
@Test void returnsOnlyProductVersionAndElapsedTime() {
    var tester = new DatabaseConnectionTester(spec -> new DatabaseConnectionProbe.Metadata("GaussDB", "V500"), Duration.ofSeconds(1), 1);
    var result = tester.test(new DatabaseConnectionSpec("jdbc:secret", "reader", "password", "driver"));
    assertThat(result.success()).isTrue();
    assertThat(result.databaseProduct()).isEqualTo("GaussDB");
    assertThat(result.databaseVersion()).isEqualTo("V500");
    assertThat(result.elapsedMs()).isNotNegative();
    tester.close();
}

@Test void timesOutWithoutGrowingPastTheConcurrencyLimit() {
    CountDownLatch release = new CountDownLatch(1);
    var tester = new DatabaseConnectionTester(spec -> {
        while (release.getCount() > 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        return new DatabaseConnectionProbe.Metadata("db", "version");
    }, Duration.ofMillis(30), 1);
    assertThatThrownBy(() -> tester.test(SPEC)).isInstanceOfSatisfying(ApiException.class,
        error -> assertThat(error.code()).isEqualTo("DATABASE_CONNECTION_TIMEOUT"));
    assertThatThrownBy(() -> tester.test(SPEC)).isInstanceOfSatisfying(ApiException.class,
        error -> assertThat(error.code()).isEqualTo("DATABASE_TEST_BUSY"));
    release.countDown();
    tester.close();
}

@Test void hidesProbeFailureDetails() {
    var tester = new DatabaseConnectionTester(spec -> { throw new SQLException("password=secret jdbc:private-host"); }, Duration.ofSeconds(1), 1);
    assertThatThrownBy(() -> tester.test(SPEC)).isInstanceOfSatisfying(ApiException.class, error -> {
        assertThat(error.code()).isEqualTo("DATABASE_CONNECTION_FAILED");
        assertThat(error.getMessage()).doesNotContain("secret", "private-host");
    });
    tester.close();
}
```

Also test `JdbcDatabaseConnectionProbe` with a nonexistent class and with a class that does not implement `java.sql.Driver`; both must map to `DATABASE_DRIVER_NOT_FOUND` without including the requested class name in the message.

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl compare-service -Dtest=DatabaseConnectionTesterTest test
```

Expected: FAIL because the connection-test classes do not exist.

- [ ] **Step 3: Implement the probe and bounded tester**

Use an exact-driver probe so the configured class, rather than any globally registered driver, is validated:

```java
final class JdbcDatabaseConnectionProbe implements DatabaseConnectionProbe {
    @Override public Metadata connect(DatabaseConnectionSpec spec) throws Exception {
        Class<?> type = Class.forName(spec.driver(), true, Thread.currentThread().getContextClassLoader());
        if (!Driver.class.isAssignableFrom(type)) throw new DriverUnavailableException();
        Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
        Properties properties = new Properties();
        properties.setProperty("user", spec.user());
        properties.setProperty("password", spec.password());
        try (Connection connection = driver.connect(spec.url(), properties)) {
            if (connection == null || !connection.isValid(5)) throw new SQLException("invalid connection");
            DatabaseMetaData metadata = connection.getMetaData();
            return new Metadata(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());
        }
    }
}
```

Implement `DatabaseConnectionTester` with a `ThreadPoolExecutor` using `maximumPoolSize=maxConcurrency`, a `SynchronousQueue`, daemon threads named `database-connection-test`, and `AbortPolicy`. Submit one callable per test, wait with `Future.get(timeout)`, cancel on timeout, reject when all workers are occupied, and shut down in `@PreDestroy close()`.

Map errors exactly:

```java
new ApiException(HttpStatus.BAD_REQUEST, "DATABASE_DRIVER_NOT_FOUND", "数据库驱动不可用，请检查驱动类和部署包");
new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DATABASE_TEST_BUSY", "连接测试任务繁忙，请稍后重试");
new ApiException(HttpStatus.GATEWAY_TIMEOUT, "DATABASE_CONNECTION_TIMEOUT", "数据库连接超时，请检查地址和网络");
new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_CONNECTION_FAILED", "数据库连接失败，请检查地址、网络和凭据");
```

The Spring constructor reads `compare.database-test-timeout-seconds` with default `10` and `compare.database-test-max-concurrency` with default `4`; reject values below 1 at startup.

- [ ] **Step 4: Run focused tests and commit**

Run the focused Maven command from Step 2. Expected: all tester tests PASS.

```bash
git add compare-service/src/main/java/cn/sunline/tss/compare/database compare-service/src/test/java/cn/sunline/tss/compare/database/DatabaseConnectionTesterTest.java
git commit -m "feat(service): add bounded database connection tester"
```

### Task 2: Connection Test API and Password Reuse

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/SavedDatabaseTestRequest.java`
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseConnectionTestService.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseController.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/database/DatabaseManagementApiTest.java`

**Interfaces:**
- Consumes: Task 1 `DatabaseConnectionTester.test(DatabaseConnectionSpec)`.
- Produces: `POST /api/databases/test` for draft requests and `POST /api/databases/{id}/test` for saved connections.
- Produces: `SavedDatabaseTestRequest(String newPassword)`; blank means reuse stored password.

- [ ] **Step 1: Add failing MockMvc tests**

Extend `DatabaseManagementApiTest` with a recording fake probe and assert:

```java
@Test void testsDraftWithoutPersistingIt() throws Exception {
    mvc.perform(post("/api/databases/test").contentType(APPLICATION_JSON).content("""
        {"databaseId":9,"databaseName":"draft","url":"jdbc:test","user":"reader","driver":"test.Driver","encryption":"1","newPassword":"draft-secret"}
        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.databaseProduct").value("GaussDB"));
    assertThat(jdbc.queryForObject("select count(*) from TSS_BCOMP_DATABASE_CONFIG where DATABASE_ID=9", Integer.class)).isZero();
    assertThat(recorded.password()).isEqualTo("draft-secret");
}

@Test void savedTestDecryptsPasswordWithoutReturningOrChangingIt() throws Exception {
    // Create encrypted ID 7, capture password/key, call /7/test with {}, then compare stored values.
    // Assert the probe received plaintext "secret" and the response contains neither plaintext nor ciphertext.
}

@Test void temporarySavedPasswordIsRequestOnly() throws Exception {
    // Call /7/test with {"newPassword":"temporary"}; assert the probe received it and stored password/key did not change.
}
```

Add invalid draft, missing saved ID, driver failure, timeout, and raw-error-redaction assertions using the error codes from the API design.

- [ ] **Step 2: Run API tests and verify failure**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl compare-service -Dtest=DatabaseManagementApiTest test
```

Expected: FAIL with 404 for the new routes.

- [ ] **Step 3: Implement test-service validation and routes**

Keep CRUD in `DatabaseManagementService`. Add a separate `DatabaseConnectionTestService` that:

```java
public DatabaseConnectionTestResult testDraft(DatabaseSaveRequest request) {
    validateDraft(request);
    return tester.test(new DatabaseConnectionSpec(request.url().trim(), request.user().trim(), request.newPassword(), request.driver().trim()));
}

public DatabaseConnectionTestResult testSaved(long id, SavedDatabaseTestRequest request) {
    StoredDatabase stored = repository.find(id);
    if (stored == null) throw ApiException.notFound("DATABASE_NOT_FOUND", "数据库连接不存在");
    String temporary = request == null ? null : request.newPassword();
    String password = temporary == null || temporary.isBlank() ? plaintext(stored) : temporary;
    return tester.test(new DatabaseConnectionSpec(stored.url(), stored.user(), password, stored.driver()));
}
```

Decrypt encryption `1` with the existing `PasswordEncryption.decrypt`; use the stored value for encryption `0`. Wrap decryption failures in the existing generic internal-error path without returning ciphertext or key material.

Add controller methods:

```java
@PostMapping("/test") DatabaseConnectionTestResult testDraft(@RequestBody DatabaseSaveRequest request)
@PostMapping("/{id}/test") DatabaseConnectionTestResult testSaved(@PathVariable long id, @RequestBody(required=false) SavedDatabaseTestRequest request)
```

- [ ] **Step 4: Run API tests and commit**

Run the focused command from Step 2. Expected: all database management API tests PASS.

```bash
git add compare-service/src/main/java/cn/sunline/tss/compare/database compare-service/src/test/java/cn/sunline/tss/compare/database/DatabaseManagementApiTest.java
git commit -m "feat(service): expose database connection tests"
```

### Task 3: Database Drawer Test Action and Cache Refresh

**Files:**
- Modify: `compare-ui/src/api/types.ts`
- Modify: `compare-ui/src/components/DatabaseDrawer.tsx`
- Modify: `compare-ui/src/components/SaveWarning.tsx`
- Modify: `compare-ui/src/components/SaveWarning.test.tsx`
- Modify: `compare-ui/src/pages/DatabasesPage.tsx`
- Modify: `compare-ui/src/pages/DatabasesPage.test.tsx`
- Modify: `compare-ui/src/test/server.ts`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: Task 2 connection-test endpoints and existing `POST /api/cache/refresh`.
- Produces: `DatabaseConnectionTestResult` TypeScript type and a drawer test action that never persists the password.
- Extends: `SaveWarning({ message, savedTitle?, successMessage? })` with backward-compatible defaults.

- [ ] **Step 1: Add failing UI tests**

Add component/page tests for these behaviors:

```tsx
test('tests a new database draft and shows product metadata without saving', async () => {
  // Open New Connection, fill required values, click Test Connection.
  // Assert POST /api/databases/test receives the draft, save endpoint is not called,
  // and "连接成功 · GaussDB V500 · 138 ms" is visible.
})

test('uses the saved connection only while connection fields remain unchanged', async () => {
  // Edit ID 1 with blank password and click test: expect /api/databases/1/test.
  // Change JDBC URL: previous success disappears and the page asks for a password before draft testing.
})

test('refreshes cache after save and preserves a refresh warning', async () => {
  // Save succeeds; /api/cache/refresh returns {code:500}.
  // Assert the drawer closes and "连接已保存，但缓存刷新失败" plus retry action remain visible.
})
```

Also verify buttons are disabled during test/save and that changing URL, user, driver, or password clears the previous success or failure result.

- [ ] **Step 2: Run database UI tests and verify failure**

Run:

```bash
npm --prefix compare-ui test -- --run src/pages/DatabasesPage.test.tsx src/components/SaveWarning.test.tsx
```

Expected: FAIL because no test action or database-specific save warning exists.

- [ ] **Step 3: Implement the drawer test state**

Add `DatabaseConnectionTestResult`:

```ts
export interface DatabaseConnectionTestResult {
  success: boolean
  databaseProduct: string
  databaseVersion: string
  elapsedMs: number
}
```

The drawer records its initial URL/user/driver. Test routing is:

```ts
const connectionChanged = form.url !== initial.url || form.user !== initial.user || form.driver !== initial.driver
if (database && !connectionChanged && !form.newPassword?.trim()) {
  await onTestSaved(database.databaseId, { newPassword: null })
} else {
  if (!form.newPassword?.trim()) showLocalError('测试当前连接参数时请输入密码')
  else await onTestDraft({ ...form, newPassword: form.newPassword.trim() })
}
```

Clear test state from a single `updateForm(patch)` helper before applying every change. Add a `datalist` with `org.postgresql.Driver`, `oracle.jdbc.OracleDriver`, `com.mysql.cj.jdbc.Driver`, `com.huawei.gaussdb.jdbc.Driver`, and `org.opengauss.Driver`; keep free-text entry for site-specific drivers. Add concise GaussDB help text that recommends PostgreSQL compatibility when available and says native drivers require a deployed JAR.

- [ ] **Step 4: Refresh cache after a successful save**

In `DatabasesPage`, after create/update succeeds:

```ts
try {
  const refresh = await api<{ code: number; msg: string }>('/api/cache/refresh', { method: 'POST' })
  setWarning(refresh.code === 200 ? null : '连接已保存，但缓存刷新失败')
} catch {
  setWarning('连接已保存，但缓存刷新失败')
}
```

Always close the drawer and invalidate the list after the save, even when refresh fails. Render `SaveWarning` with `savedTitle="连接已保存"` and `successMessage="新连接已加载到当前服务实例。"`. Keep the existing configuration-editor defaults unchanged.

- [ ] **Step 5: Run UI tests and commit**

Run the focused command from Step 2. Expected: all focused tests PASS.

```bash
git add compare-ui/src/api/types.ts compare-ui/src/components/DatabaseDrawer.tsx compare-ui/src/components/SaveWarning.tsx compare-ui/src/components/SaveWarning.test.tsx compare-ui/src/pages/DatabasesPage.tsx compare-ui/src/pages/DatabasesPage.test.tsx compare-ui/src/test/server.ts compare-ui/src/styles/layout.css
git commit -m "feat(ui): test and activate database connections"
```

### Task 4: Driver Delivery and Opt-In GaussDB Verification

**Files:**
- Modify: `config/application-local.properties.example`
- Modify: `README.md`
- Modify: `docs/migration/verification.md`
- Create: `compare-service/src/test/java/cn/sunline/tss/compare/database/GaussDbConnectionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `JdbcDatabaseConnectionProbe`.
- Produces: documented properties `compare.database-test-timeout-seconds` and `compare.database-test-max-concurrency`.
- Consumes optional environment variables `GAUSSDB_TEST_URL`, `GAUSSDB_TEST_USER`, `GAUSSDB_TEST_PASSWORD`, and `GAUSSDB_TEST_DRIVER`.

- [ ] **Step 1: Add the opt-in integration test**

Create a JUnit test that skips unless URL, user, and password exist:

```java
@Test void connectsToConfiguredGaussDb() throws Exception {
    String url = System.getenv("GAUSSDB_TEST_URL");
    String user = System.getenv("GAUSSDB_TEST_USER");
    String password = System.getenv("GAUSSDB_TEST_PASSWORD");
    Assumptions.assumeTrue(nonBlank(url) && nonBlank(user) && nonBlank(password),
        "设置 GAUSSDB_TEST_URL/USER/PASSWORD 后执行真实联调");
    String driver = System.getenv().getOrDefault("GAUSSDB_TEST_DRIVER", "org.postgresql.Driver");
    var metadata = new JdbcDatabaseConnectionProbe().connect(new DatabaseConnectionSpec(url, user, password, driver));
    assertThat(metadata.product()).isNotBlank();
    assertThat(metadata.version()).isNotBlank();
}
```

The test must not print environment values. Default `mvn verify` reports it skipped when credentials are absent.

- [ ] **Step 2: Document service and Spark driver loading**

Add to the example properties:

```properties
compare.database-test-timeout-seconds=10
compare.database-test-max-concurrency=4
```

Document these supported modes:

- PostgreSQL-compatible GaussDB A: `jdbc:postgresql://host:port/database`, `org.postgresql.Driver`, bundled driver.
- Huawei native: `jdbc:gaussdb://host:port/database`, site-provided driver class/JAR.
- openGauss: site-confirmed URL and `org.opengauss.Driver`, site-provided driver JAR.

State that `bin/start.sh` supplies `drivers/` to Boot through `loader.path` and to Spark through `COMPARE_SPARK_JARS`; a native driver must be readable on every Spark node. Do not download or bundle a proprietary driver.

- [ ] **Step 3: Verify the skipped and enabled test paths**

Run without credentials:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl compare-service -Dtest=GaussDbConnectionIntegrationTest test
```

Expected: one skipped test and no connection attempt.

When site credentials are available, run the same command with the four environment variables and record the database product/version and elapsed result in `docs/migration/verification.md` without recording credentials or URL. When credentials are unavailable, retain the explicit “real GaussDB not yet verified” entry.

- [ ] **Step 4: Commit driver delivery and verification support**

```bash
git add config/application-local.properties.example README.md docs/migration/verification.md compare-service/src/test/java/cn/sunline/tss/compare/database/GaussDbConnectionIntegrationTest.java
git commit -m "docs: add GaussDB connection verification path"
```

### Task 5: Full Regression and Runtime Preview

**Files:**
- Modify only files required by regression findings.

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: a review-ready branch and refreshed preview at `http://127.0.0.1:4173/`.

- [ ] **Step 1: Run component and production-build verification**

```bash
npm --prefix compare-ui test -- --run
npm --prefix compare-ui run build
```

Expected: all Vitest files pass and Vite emits `dist/`.

- [ ] **Step 2: Run browser verification**

```bash
npm --prefix compare-ui run e2e
```

Extend `compare-ui/e2e/management-console.spec.ts` only if the component tests do not cover the real drawer flow. The browser scenario must test a connection draft through a mocked API, save it, observe cache-refresh failure, retry refresh, and leave no password text in the rendered page.

- [ ] **Step 3: Run all Java and Spark verification**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean verify
```

Expected: `compare-service` and `compare-spark` succeed; the real GaussDB test is skipped unless explicitly configured.

- [ ] **Step 4: Review security and compatibility boundaries**

Run:

```bash
git diff --check
rg -n "newPassword|DATABASE_PASSWORD|DATABASE_GENERATERKEY|jdbc:" compare-service/src/main/java/cn/sunline/tss/compare/database compare-ui/src
git diff 18f37eb..HEAD -- compare-service compare-ui config README.md docs/migration/verification.md
```

Confirm no response/log string includes password material, existing save responses remain `DatabaseSummary`, production tables are unchanged, and Spark source is unchanged.

- [ ] **Step 5: Refresh preview and commit any review fixes**

Keep or restart:

```bash
npm --prefix compare-ui run dev -- --host 127.0.0.1 --port 4173
```

Verify HTTP 200 at `http://127.0.0.1:4173/`. Commit only actual review fixes; skip the commit when the review produces no changes.
