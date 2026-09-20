# Axon Config Script Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Axon-generated database comparison scripts safe for direct use by the independent comparison service while preserving the existing production `TSS_BCOMP_*` schema.

**Architecture:** Axon remains the source of selected tables and fields and emits an immutable reviewed SQL artifact. Its internal registration and version snapshots gain stable production indexes and source data types; the script maps date/timestamp fields to legacy type `4` and everything else to type `1`. The comparison service keeps consuming the same three tables and only adds a dashboard action for its existing cache refresh endpoint.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, Flyway SQL, JUnit 5, React, TypeScript, Vitest, Testing Library.

**Spec:** `/Users/java/obsidian/01 Engineering/bd/Axon配置脚本接入-系统设计.md`

## Global Constraints

- Do not create or alter production `TSS_BCOMP_*` tables.
- Keep source and destination database IDs fixed at `1` and `2`.
- Map `date` and every `timestamp` variant to `FIELD_TYPE='4'`; map all other source types to `FIELD_TYPE='1'`.
- Keep generated SQL manually reviewed and executed; do not add remote SQL execution or push APIs.
- Preserve immutable scripts that were already generated and stored.
- Do not stage or overwrite unrelated uncommitted changes in `/Users/java/axon-link-server`.

## Review Focus

- Existing registrations must receive unique positive production indexes without moving after later inserts or ordering changes; migration and service tests cover this.
- Existing version rows with missing type information must not silently generate all-string scripts; generator validation tests cover this.
- `timestamp without time zone`, `timestamp with time zone`, precision suffixes, whitespace, and case must map to type `4`; mapper unit tests cover this.
- A cache refresh HTTP success with business `code=500` must show failure rather than success; frontend tests cover this.
- Existing generated script download must continue returning its stored bytes and SHA even after templates change; the existing immutable artifact test remains in the targeted suite.

---

### Task 1: Persist Stable Production Indexes and Source Data Types

**Files:**
- Create: `/Users/java/axon-link-server/src/main/resources/db/daoindex/V72__replay_db_compare_production_contract.sql`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareField.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareRegistration.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDao.java`
- Test: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonDaoTest.java`

**Interfaces:**
- Produces: `ReplayDbCompareRegistration.productionBcompIndex(): Long` and `ReplayDbCompareField.dataType(): String` for version generation.
- Preserves: existing JSON request constructors and current registration behavior through compatibility constructors.

- [ ] **Step 1: Write failing persistence tests**

Add tests that create two registrations, assert each receives a distinct positive production index, edit table metadata without changing that index, add a third registration, and assert the first two indexes remain unchanged. Add a field round-trip assertion for `timestamp(6) without time zone`.

- [ ] **Step 2: Run the persistence test and confirm failure**

Run:

```bash
./mvnw -Dtest=ReplayDatabaseComparisonDaoTest test
```

Expected: compilation or assertion failure because production indexes and field data types are not persisted.

- [ ] **Step 3: Add the internal migration**

Add nullable columns, backfill registration indexes in the legacy domain/table sort order, backfill known field types only where available, and add a unique index for `production_bcomp_index`. Keep Axon tables as the only migration target; do not touch production comparison tables.

- [ ] **Step 4: Extend DTO and DAO mappings**

Expose the two new values, allocate a new production index as `MAX(production_bcomp_index)+1` inside the existing registration transaction, enforce `1..2147483647`, and persist live metadata `dataType` with each selected field. Keep old constructors so unrelated tests and callers still compile.

- [ ] **Step 5: Run persistence and service regression tests**

Run:

```bash
./mvnw -Dtest=ReplayDatabaseComparisonDaoTest,ReplayDatabaseComparisonServiceTest test
```

Expected: PASS.

### Task 2: Carry the Contract into Immutable Version Snapshots

**Files:**
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionField.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/dto/ReplayDbCompareVersionTableItem.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionService.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigurationHasher.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/persistence/ReplayDatabaseComparisonVersionDao.java`
- Test: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonVersionServiceTest.java`
- Test: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigurationHasherTest.java`

**Interfaces:**
- Consumes: registration `productionBcompIndex` and live BASE `ReplayBaseColumnOption.dataType`.
- Produces: immutable table `bcompIndex` and field `dataType` snapshot values used only by the script generator.

- [ ] **Step 1: Write failing version and hash tests**

Assert that a generated version stores the registration production index, stores live `timestamp without time zone`, and changes `configurationHash` when only a selected field data type changes.

- [ ] **Step 2: Run the tests and confirm failure**

Run:

```bash
./mvnw -Dtest=ReplayDatabaseComparisonVersionServiceTest,ReplayDatabaseComparisonConfigurationHasherTest test
```

Expected: failure because snapshot DTOs and SQL omit both values.

- [ ] **Step 3: Enrich the validated snapshot**

When version validation matches a registered field to BASE metadata, copy `column.dataType()` into the snapshot field and copy `registration.productionBcompIndex()` into the snapshot table. Reject missing or out-of-range indexes before writing the version.

- [ ] **Step 4: Persist, load, and hash the new snapshot values**

Update insert and row mapping SQL for both version tables. Normalize data type with trim and lowercase before hashing. Keep already stored script bytes authoritative in `ReplayDatabaseComparisonConfigScriptService`.

- [ ] **Step 5: Run the focused version tests**

Run the command from Step 2. Expected: PASS.

### Task 3: Generate Consumer-Compatible SQL

**Files:**
- Create: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonFieldTypeMapper.java`
- Modify: `/Users/java/axon-link-server/src/main/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptGenerator.java`
- Test: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonFieldTypeMapperTest.java`
- Test: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptGeneratorTest.java`
- Test: `/Users/java/axon-link-server/src/test/java/com/axonlink/ai/replay/dbcompare/service/ReplayDatabaseComparisonConfigScriptServiceTest.java`

**Interfaces:**
- Consumes: `ReplayDbCompareVersionTableItem.bcompIndex()` and `ReplayDbCompareVersionField.dataType()`.
- Produces: SQL rows accepted by the unchanged `TSS_BCOMP_CONF`, `TSS_BCOMP_FIELD`, and `TSS_BCOMP_TABLE_SQL` contract.

- [ ] **Step 1: Write failing mapping and script assertions**

Cover `date`, `timestamp`, `timestamp(6) without time zone`, `TIMESTAMP WITH TIME ZONE`, varchar, numeric, null data type, duplicate indexes, and indexes beyond `Integer.MAX_VALUE`. Assert generated configuration rows use snapshot indexes, contain `bcomp_date_mode='1'`, and field rows explicitly contain enum/partition/ignore flags with timestamp type `4`.

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
./mvnw -Dtest=ReplayDatabaseComparisonFieldTypeMapperTest,ReplayDatabaseComparisonConfigScriptGeneratorTest,ReplayDatabaseComparisonConfigScriptServiceTest test
```

Expected: compilation or assertion failure against the current hard-coded type and positional index behavior.

- [ ] **Step 3: Implement the type mapper**

Normalize with `trim().toLowerCase(Locale.ROOT)`. Return `4` for exact `date` or strings beginning with `timestamp`; return `1` for other nonblank values. Throw the existing structured generation exception path for null or blank values.

- [ ] **Step 4: Update script generation**

Validate production indexes as unique positive `int` values, use them in all three inserts, add explicit default columns, and keep fields and SELECT lists ordered by `comparisonOrder`. Preserve batching, quoting, GZIP, SHA-256, immutable artifact lookup, aliases, database IDs, and transaction framing.

- [ ] **Step 5: Run the focused generator tests**

Run the command from Step 2. Expected: PASS, including the existing 100,000-field and immutable download tests.

### Task 4: Add an Explicit Cache Refresh Action to the Management Console

**Files:**
- Modify: `/Users/wangshanhe/Documents/ChatGPT/bd/compare-ui/src/pages/DashboardPage.tsx`
- Modify: `/Users/wangshanhe/Documents/ChatGPT/bd/compare-ui/src/styles.css`
- Create: `/Users/wangshanhe/Documents/ChatGPT/bd/compare-ui/src/pages/DashboardPage.test.tsx`

**Interfaces:**
- Consumes: existing `POST /api/cache/refresh` response `{code:number,msg:string}`.
- Produces: one dashboard button with idle, loading, success, business failure, and network failure states.

- [ ] **Step 1: Write failing UI tests**

Assert the button calls the existing endpoint once, disables while loading, shows “缓存已刷新” only for `code=200`, shows the server message for `code=500`, and shows a retryable network failure message when the request rejects.

- [ ] **Step 2: Run the UI test and confirm failure**

Run:

```bash
npm test -- --run src/pages/DashboardPage.test.tsx
```

Expected: failure because the dashboard has no cache action.

- [ ] **Step 3: Implement the dashboard action**

Add a compact “刷新配置缓存” action near the existing dashboard refresh controls. Reuse the existing API helper, avoid a new backend endpoint, and keep the message visible without navigating away.

- [ ] **Step 4: Run the focused UI tests**

Run the command from Step 2. Expected: PASS.

### Task 5: Verify the Cross-Project Contract and Release Readiness

**Files:**
- Modify if evidence requires: `/Users/wangshanhe/Documents/ChatGPT/bd/README.md`
- Modify if evidence requires: `/Users/java/obsidian/01 Engineering/bd/Axon配置脚本接入-系统设计.md`

**Interfaces:**
- Consumes: final Axon script and unchanged comparison service repositories.
- Produces: verified evidence that the selected fields, indexes, types, defaults, SQL, and cache refresh path form one usable deployment flow.

- [ ] **Step 1: Run all Axon database-comparison tests**

Run:

```bash
./mvnw -Dtest='com.axonlink.ai.replay.dbcompare.**' test
```

Expected: PASS.

- [ ] **Step 2: Run the comparison service and Spark tests**

Run from `/Users/wangshanhe/Documents/ChatGPT/bd`:

```bash
mvn clean verify
```

Expected: PASS.

- [ ] **Step 3: Run the complete frontend suite and build**

Run:

```bash
npm test -- --run
npm run build
```

Expected: PASS.

- [ ] **Step 4: Inspect generated SQL against the consumer contract**

Generate a test script containing a primary key, varchar, date, and timestamp field. Confirm all three tables use the same stable index, positions start at 1, time fields use type 4, other fields use type 1, default flags are explicit, database IDs are 1/2, and no production DDL is present.

- [ ] **Step 5: Check diffs and document rollout limits**

Run `git diff --check` in both repositories. Update README/design only for facts proven by the final code, including the requirement to stop waiting/running tasks, verify database IDs, execute the reviewed SQL, and refresh cache afterward.
