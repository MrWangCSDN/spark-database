# GaussDB/PostgreSQL currentSchema Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require a non-empty `currentSchema` JDBC URL parameter for GaussDB, openGauss, and PostgreSQL comparison database connections before save, connection test, or task creation.

**Architecture:** Add one stateless backend policy that identifies compatible connections from URL and driver and validates query parameters. Reuse it at database save/test boundaries and at task creation, then mirror the rule in the database drawer for immediate feedback while keeping the backend authoritative.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring JDBC, JUnit 5/MockMvc, React 18, TypeScript, Vitest, Testing Library.

**Spec:** `/Users/java/obsidian-bd-current-schema/01 Engineering/bd/数据库比对管理台-系统设计.md`

## Global Constraints

- Apply the rule only to `TSS_BCOMP_DATABASE_CONFIG` comparison connections identified as GaussDB, openGauss, or PostgreSQL compatible.
- Do not apply the rule to Oracle, MySQL, `spring.datasource.*`, or `spark.datasource.*` metadata connections.
- Treat the parameter name case-insensitively and require a non-blank decoded value.
- Do not rewrite existing JDBC URLs or infer a schema from username/defaults.
- Existing invalid connections remain listable; save, test, and new task creation return HTTP 400 `CURRENT_SCHEMA_REQUIRED`.
- Preserve existing tables, request/response shapes, password handling, and Spark 21-argument protocol.

## Review Focus

- A PostgreSQL URL with no query string must fail before persistence or connection attempts.
- `currentSchema=` and percent-encoded whitespace must fail; a non-empty value among reordered parameters must pass.
- URL and driver identification must catch `jdbc:gaussdb`, `jdbc:opengauss`, PostgreSQL driver, and openGauss/GaussDB drivers without affecting Oracle/MySQL.
- Saved legacy GaussDB connections must remain visible but fail saved-connection testing and new task creation.
- Both origin and destination connections must be checked before inserting `TSS_BCOMP_TASK`.

---

### Task 1: Central JDBC Schema Policy and Database API Enforcement

**Files:**
- Create: `compare-service/src/main/java/cn/sunline/tss/compare/database/JdbcCurrentSchemaPolicy.java`
- Create: `compare-service/src/test/java/cn/sunline/tss/compare/database/JdbcCurrentSchemaPolicyTest.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseManagementService.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/database/DatabaseConnectionTestService.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/database/DatabaseManagementApiTest.java`

**Interfaces:**
- Produces: `JdbcCurrentSchemaPolicy.requireValid(String url, String driver): void`, throwing `ApiException.badRequest("CURRENT_SCHEMA_REQUIRED", ...)` only for compatible connections missing a non-empty parameter.
- Consumes: existing `DatabaseSaveRequest.url()` and `driver()` values.

- [ ] **Step 1: Write policy and API tests that fail**

Cover URL-prefix and driver-based detection, case-insensitive parameter names, blank/encoded-blank values, reordered parameters, Oracle/MySQL bypass, create/update rejection, draft-test rejection, and saved-test rejection before the probe executes.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl compare-service -Dtest=JdbcCurrentSchemaPolicyTest,DatabaseManagementApiTest test
```

Expected: failures because `JdbcCurrentSchemaPolicy` and `CURRENT_SCHEMA_REQUIRED` do not exist.

- [ ] **Step 3: Implement the policy**

Implement a dependency-free parser that examines the substring after `?`, splits on `&`, URL-decodes keys and values as UTF-8, compares the key to `currentSchema` ignoring case, and accepts only a non-blank decoded value. Compatibility detection uses lower-cased URL prefixes `jdbc:postgresql:`, `jdbc:gaussdb:`, `jdbc:opengauss:` plus driver names containing `postgresql`, `gaussdb`, or `opengauss`.

- [ ] **Step 4: Apply the policy to save and connection-test services**

Call `requireValid` after completeness validation and before encryption, persistence, password decryption, executor submission, or JDBC probing. Saved tests validate the stored URL/driver.

- [ ] **Step 5: Run focused tests and commit**

Expected: all focused tests pass.

```bash
git add compare-service/src/main/java/cn/sunline/tss/compare/database compare-service/src/test/java/cn/sunline/tss/compare/database
git commit -m "feat: require schema for compatible database connections"
```

### Task 2: Task-Creation Guard for Legacy Connections

**Files:**
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskManagementRepository.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskManagementService.java`
- Modify: `compare-service/src/test/java/cn/sunline/tss/compare/task/TaskManagementApiTest.java`

**Interfaces:**
- Consumes: `JdbcCurrentSchemaPolicy.requireValid(String,String)` from Task 1.
- Produces: `TaskManagementRepository.configurationConnections(long)` returning origin and destination URL/driver pairs for a configuration.

- [ ] **Step 1: Add failing task API tests**

Create a complete configuration whose origin or destination row is PostgreSQL-compatible without `currentSchema`; assert `POST /api/tasks` returns 400 `CURRENT_SCHEMA_REQUIRED` and no task row is inserted. Add a passing case where both URLs contain non-empty schemas and an Oracle/MySQL case that remains accepted.

- [ ] **Step 2: Run the focused task tests and confirm failure**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl compare-service -Dtest=TaskManagementApiTest test
```

- [ ] **Step 3: Query and validate both configured endpoints**

Read origin and destination URL/driver values by joining `TSS_BCOMP_TABLE_SQL` to two aliases of `TSS_BCOMP_DATABASE_CONFIG`. After configuration completeness/date validation and before identity lookup/insert, call the shared policy for both endpoints.

- [ ] **Step 4: Run focused tests and commit**

```bash
git add compare-service/src/main/java/cn/sunline/tss/compare/task compare-service/src/test/java/cn/sunline/tss/compare/task/TaskManagementApiTest.java
git commit -m "feat: block tasks with ambiguous database schemas"
```

### Task 3: Database Drawer Guidance and Client Validation

**Files:**
- Create: `compare-ui/src/database/currentSchema.ts`
- Create: `compare-ui/src/database/currentSchema.test.ts`
- Modify: `compare-ui/src/components/DatabaseDrawer.tsx`
- Modify: `compare-ui/src/pages/DatabasesPage.test.tsx`
- Modify: `compare-ui/src/test/server.ts`

**Interfaces:**
- Produces: `currentSchemaError(url: string, driver: string): string | null` matching backend compatibility and non-empty-parameter rules.
- Consumes: existing `DatabaseSaveRequest.url` and `driver` form state.

- [ ] **Step 1: Add failing utility and drawer tests**

Assert the same representative URL matrix as the backend. In the drawer, verify the PostgreSQL example, `aria-invalid`, visible error, and prevention of test/save callbacks until `currentSchema` is supplied. Verify Oracle remains unaffected.

- [ ] **Step 2: Run focused frontend tests and confirm failure**

```bash
cd compare-ui && npm test -- --run src/database/currentSchema.test.ts src/pages/DatabasesPage.test.tsx
```

- [ ] **Step 3: Implement the utility and drawer behavior**

Show `jdbc:postgresql://host:5432/database?currentSchema=业务schema` as the placeholder/help example. Use the utility in both submit and test handlers, clear stale connection-test success through the existing `updateForm`, expose the error with `role="alert"`, and leave backend errors as the final authority.

- [ ] **Step 4: Run focused tests and commit**

```bash
git add compare-ui/src/database compare-ui/src/components/DatabaseDrawer.tsx compare-ui/src/pages/DatabasesPage.test.tsx compare-ui/src/test/server.ts
git commit -m "feat(ui): require schema for compatible JDBC URLs"
```

### Task 4: Documentation, Full Verification, and Delivery Packages

**Files:**
- Modify: `README.md`
- Modify: `docs/migration/verification.md`
- Regenerate: `target/tss-compare-source-1.0.0-SNAPSHOT.zip`
- Regenerate: `target/tss-compare-source-1.0.0-SNAPSHOT.zip.sha256`
- Regenerate: `target/tss-compare-1.0.0-SNAPSHOT.tar.gz`

**Interfaces:**
- Consumes: final behavior from Tasks 1-3.
- Produces: updated operator guidance and verified intranet artifacts.

- [ ] **Step 1: Update deployment guidance**

Document the URL examples, scope exclusions, migration behavior for existing rows, and task-creation rejection. Record that this validates URL selection but does not replace a real GaussDB connectivity test.

- [ ] **Step 2: Run complete verification with Java 17**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean verify
```

Expected: backend, Spark, 34+ frontend tests, frontend build, and Boot packaging succeed.

- [ ] **Step 3: Run browser flow and package delivery artifacts**

```bash
cd compare-ui && npm run e2e
cd .. && bin/package.sh && bin/package-source.sh
cd target && shasum -a 256 -c tss-compare-source-1.0.0-SNAPSHOT.zip.sha256
```

Expected: Chromium flows pass, release/source packages are regenerated, and checksum reports `OK`.

- [ ] **Step 4: Verify the source ZIP independently**

Extract the source ZIP into a new temporary directory and run `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean verify`; confirm the Boot JAR contains `BOOT-INF/classes/static/index.html`.

- [ ] **Step 5: Commit documentation and plan updates**

```bash
git add README.md docs/migration/verification.md docs/superpowers/plans/2026-09-21-current-schema-validation.md
git commit -m "docs: document comparison database schema requirement"
```
