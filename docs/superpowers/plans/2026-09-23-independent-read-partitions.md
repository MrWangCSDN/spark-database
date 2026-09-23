# Independent Database Read Partitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Calculate and persist database A and database B JDBC HASH read partitions independently from each side's live row count, while preserving correct Spark key-based comparison and exposing the calculation on task details.

**Architecture:** `AutoHashPartitionPlanner` returns one immutable plan containing both row counts and both independently calculated partition counts. `SparkHandlerTable` builds each `SidePlan` with its own count, atomically persists the four plan facts plus the compatibility summary, and then lets the existing key-based `cogroup` shuffle compare the data. The service exposes nullable snapshot fields through `TaskSummary`, and the React task detail renders each database's `row count → partition count` without parsing `readMode`.

**Tech Stack:** Java 17, Spark 3.5.3 Java API, JDBC, Spring Boot/JdbcTemplate, PostgreSQL/GaussDB-compatible SQL, React 18, TypeScript, Vitest/Testing Library, Maven.

**Spec:** `/Users/java/obsidian/.worktrees/bd-independent-read-partitions/01 Engineering/bd/库表比对拆分-系统设计.md`

## Global Constraints

- Database A and database B must each use `min(64, nextPowerOfTwo(max(1, ceil(rows / 1_000_000))))` based only on that side's live count.
- Both configured SQL statements are counted after `${HDRQ}` replacement; either count failure fails the task and never falls back to a configured/default partition count.
- Different JDBC input partition counts must not change comparison correctness; downstream comparison remains the existing full-business-key `cogroup` shuffle.
- Persist `ORIG_SOURCE_ROW_COUNT`, `DEST_SOURCE_ROW_COUNT`, `ORIG_READ_PARTITION_NUM`, and `DEST_READ_PARTITION_NUM` as nullable per-run snapshots; they are not configuration.
- Preserve `BCOMP_READ_MODE` as a compatibility summary containing both actual side strategies.
- Historical rows with null snapshots display `未记录`; never derive snapshot fields from final counts or parse them from `readMode`.
- Do not modify unrelated dirty-worktree files, especially the current metadata-password changes.
- Use `-Dgauss-jdbc.version=6.0.0` for local Maven verification because the uncommitted POM currently selects an unavailable `6.0.0-htrunk8` artifact.

## Review Focus

- Exact boundary counts (`0`, `1_000_000`, `1_000_001`, over 64 million) must retain the documented rounding and cap independently for each side; Task 2 pins these values.
- One side's large count must never inflate the other side's JDBC predicate count; Tasks 2 and 3 assert a `1` versus `2` asymmetric plan.
- A missing task-table migration must fail plan persistence instead of silently showing a misleading successful run; Task 3 tests the required columns through the integration schema.
- SQL `NULL` snapshot values must remain JSON `null`, including waiting and historical tasks; Task 4 covers repository and API behavior.
- The daily-result task-detail branch and legacy execution tab must both show plan facts, and a partially/null snapshot must display `未记录`; Task 5 covers both branches.

---

### Task 1: Add the task read-plan snapshot schema

**Files:**
- Create: `sql/add_bcomp_read_plan_new.sql`
- Modify: `sql/create_bcomp_new.sql:22-42`
- Modify: `compare-service/src/test/resources/legacy-schema.sql:3`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/NewSchemaTest.java`

**Interfaces:**
- Produces: nullable columns `ORIG_SOURCE_ROW_COUNT BIGINT`, `DEST_SOURCE_ROW_COUNT BIGINT`, `ORIG_READ_PARTITION_NUM INT`, `DEST_READ_PARTITION_NUM INT` on `TSS_BCOMP_TASK_NEW`.
- Consumes: no application code.

- [ ] **Step 1: Write the failing schema test**

Add a real H2 schema assertion that executes the relevant DDL and queries `DatabaseMetaData.getColumns(...)`. Assert the four literal column names and compatible JDBC types, for example:

```java
assertEquals(Types.BIGINT, columns.get("ORIG_SOURCE_ROW_COUNT"));
assertEquals(Types.BIGINT, columns.get("DEST_SOURCE_ROW_COUNT"));
assertEquals(Types.INTEGER, columns.get("ORIG_READ_PARTITION_NUM"));
assertEquals(Types.INTEGER, columns.get("DEST_READ_PARTITION_NUM"));
```

The production change this catches is omitting or mistyping a required task snapshot column.

- [ ] **Step 2: Run the schema test and verify RED**

Run:

```bash
mvn -Dgauss-jdbc.version=6.0.0 -pl compare-service -Dtest=NewSchemaTest test
```

Expected: FAIL because one or more read-plan columns are absent.

- [ ] **Step 3: Add creation and upgrade DDL**

Add the four nullable columns to `CREATE TABLE TSS_BCOMP_TASK_NEW`. Create an idempotence-by-procedure migration file consistent with the repository's existing one-shot migrations:

```sql
BEGIN;
ALTER TABLE TSS_BCOMP_TASK_NEW ADD COLUMN ORIG_SOURCE_ROW_COUNT BIGINT;
ALTER TABLE TSS_BCOMP_TASK_NEW ADD COLUMN DEST_SOURCE_ROW_COUNT BIGINT;
ALTER TABLE TSS_BCOMP_TASK_NEW ADD COLUMN ORIG_READ_PARTITION_NUM INT;
ALTER TABLE TSS_BCOMP_TASK_NEW ADD COLUMN DEST_READ_PARTITION_NUM INT;
COMMIT;
```

Update the H2 `legacy-schema.sql` fixture with the same columns so service integration tests exercise the new contract.

- [ ] **Step 4: Run the schema test and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit the schema deliverable**

```bash
git add sql/add_bcomp_read_plan_new.sql sql/create_bcomp_new.sql compare-service/src/test/resources/legacy-schema.sql compare-service/src/test/java/cn/sunline/tss/compare/NewSchemaTest.java
git commit -m "feat: add task read plan snapshots"
```

### Task 2: Return independent per-side counts and partitions

**Files:**
- Modify: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/AutoHashPartitionPlanner.java`
- Modify: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/AutoHashPartitionPlannerTest.java`

**Interfaces:**
- Produces: `AutoHashPartitionPlanner.Plan(long originRows, int originPartitions, long destinationRows, int destinationPartitions)` and `static Plan plan(String[] args)`.
- Consumes: the existing argument indices and `static int partitions(long rows)` boundary function.

- [ ] **Step 1: Change the integration test to require an asymmetric plan**

Keep the real H2 source table. Use an origin query with exactly `1_000_000` rows and a destination query with `1_000_001` rows, then assert literal values:

```java
var plan = AutoHashPartitionPlanner.plan(args);
assertEquals(1_000_000L, plan.originRows());
assertEquals(1, plan.originPartitions());
assertEquals(1_000_001L, plan.destinationRows());
assertEquals(2, plan.destinationPartitions());
```

Also retain the missing-table assertion so a destination count failure does not return a partial plan.

- [ ] **Step 2: Run the planner test and verify RED**

```bash
mvn -Dgauss-jdbc.version=6.0.0 -pl compare-spark -Dtest=AutoHashPartitionPlannerTest test
```

Expected: compilation/test failure because `plan` still returns one shared `int`.

- [ ] **Step 3: Implement the minimal immutable plan**

Replace the shared maximum calculation with:

```java
record Plan(long originRows, int originPartitions,
            long destinationRows, int destinationPartitions) { }

static Plan plan(String[] args) throws Exception {
    long originRows = count("数据库A", args[SparkHandlerTable.OLD_URL_INDEX],
        args[SparkHandlerTable.OLD_USER_INDEX], args[SparkHandlerTable.OLD_PASSWORD_INDEX],
        args[SparkHandlerTable.OLD_DRIVER_INDEX], args[SparkHandlerTable.OLD_TABLE_INDEX]);
    long destinationRows = count("数据库B", args[SparkHandlerTable.NEW_URL_INDEX],
        args[SparkHandlerTable.NEW_USER_INDEX], args[SparkHandlerTable.NEW_PASSWORD_INDEX],
        args[SparkHandlerTable.NEW_DRIVER_INDEX], args[SparkHandlerTable.NEW_TABLE_INDEX]);
    return new Plan(originRows, partitions(originRows),
                    destinationRows, partitions(destinationRows));
}
```

Keep `partitions(long)` unchanged so all existing boundary expectations continue to protect the 1-million bands and 64 cap. Update the class comment to say it chooses independent counts.

- [ ] **Step 4: Run the planner test and verify GREEN**

Run the Step 2 command. Expected: 2 tests, 0 failures/errors.

- [ ] **Step 5: Commit the planner deliverable**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/AutoHashPartitionPlanner.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/AutoHashPartitionPlannerTest.java
git commit -m "feat: plan database read partitions independently"
```

### Task 3: Build and persist asymmetric Spark read plans

**Files:**
- Modify: `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkHandlerTable.java:133-141,487-568`
- Modify: `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/SparkCompatibilityTest.java`

**Interfaces:**
- Consumes: `AutoHashPartitionPlanner.Plan` from Task 2 and the four task columns from Task 1.
- Produces: `resolveReadPlan(..., int originPartitions, int destinationPartitions)` and strict `writeReadPlan(Connection, ReadPlan, Plan, String[]) throws SQLException`.

- [ ] **Step 1: Write failing asymmetric read-plan and persistence assertions**

Add a direct plan test using literal `1` and `2` counts. Assert A uses options/no predicates while B uses exactly two predicates and the compatibility description contains both distinct values:

```java
ReadPlan read = resolveReadPlan(args, fields, 1, 2);
assertFalse(read.oldSide.usePredicates);
assertEquals(2, read.newSide.predicates.length);
assertEquals("数据库A=HASH主键读取(1分区); 数据库B=HASH主键读取(2分区)", read.describe());
```

Extend `SparkCompatibilityTest` to query the four snapshot columns after a successful small run and assert `row counts = 3/3`, `partitions = 1/1`. This catches a missing database write even when `BCOMP_READ_MODE` still updates.

- [ ] **Step 2: Run focused Spark tests and verify RED**

```bash
mvn -Dgauss-jdbc.version=6.0.0 -pl compare-spark -Dtest=SparkCompatibilityTest test
```

Expected: FAIL because `resolveReadPlan` accepts one shared `ShardConf` and snapshot columns are never written.

- [ ] **Step 3: Wire independent counts into the two SidePlans**

In the run path:

```java
AutoHashPartitionPlanner.Plan partitionPlan = AutoHashPartitionPlanner.plan(args);
ReadPlan readPlan = resolveReadPlan(args, fields,
    partitionPlan.originPartitions(), partitionPlan.destinationPartitions());
writeReadPlan(connectionOut, readPlan, partitionPlan, args);
```

Remove `ShardConf`. Validate each integer through `PrimaryKeyHashPlanner.partitionCount(...)`, and pass the origin value only to the origin `hashSidePlan` and destination value only to the destination `hashSidePlan`.

- [ ] **Step 4: Atomically persist the compatibility text and four facts**

Replace the best-effort single-column update with one required update:

```sql
UPDATE TSS_BCOMP_TASK_NEW
SET BCOMP_READ_MODE=?, ORIG_SOURCE_ROW_COUNT=?, DEST_SOURCE_ROW_COUNT=?,
    ORIG_READ_PARTITION_NUM=?, DEST_READ_PARTITION_NUM=?
WHERE BCOMP_INDEX=? AND TASK_BUSI_DATE=? AND TASK_BATCH=? AND BCOMP_TIME_NODE=?
```

Bind counts with `setLong`, partitions with `setInt`, verify exactly one row changed, and throw `SQLException` on persistence failure. Do not catch and downgrade a missing migration to a warning.

- [ ] **Step 5: Run focused Spark tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass, including real comparison with different input partition plans.

- [ ] **Step 6: Commit the Spark integration deliverable**

```bash
git add compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/SparkHandlerTable.java compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/SparkCompatibilityTest.java
git commit -m "feat: persist independent Spark read plans"
```

### Task 4: Expose nullable snapshots through every TaskSummary query

**Files:**
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskSummary.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/task/TaskManagementRepository.java`
- Modify: `compare-service/src/main/java/cn/sunline/tss/compare/batch/BatchRepository.java`
- Modify: all test constructors of `TaskSummary` reported by `rg -n 'new TaskSummary' compare-service/src`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/task/TaskManagementApiTest.java`
- Test: `compare-service/src/test/java/cn/sunline/tss/compare/batch/BatchApiTest.java`

**Interfaces:**
- Produces: nullable JSON properties `originSourceRowCount`, `destinationSourceRowCount`, `originReadPartitionNum`, `destinationReadPartitionNum` on `TaskSummary`.
- Consumes: the Task 1 database columns.

- [ ] **Step 1: Write failing API tests for populated and historical rows**

Insert one task with literal values `1000000,10000000,1,16` and assert the exact endpoint JSON fields. Insert/retain a task with all four columns null and assert JSON null, not zero. Add the same populated assertion to a batch task response so its separate SQL mapper cannot drift.

- [ ] **Step 2: Run focused service tests and verify RED**

```bash
mvn -Dgauss-jdbc.version=6.0.0 -pl compare-service -Dtest=TaskManagementApiTest,BatchApiTest test
```

Expected: FAIL because the four JSON properties do not exist.

- [ ] **Step 3: Extend the record and both SQL mappers**

Append the four nullable `Long` fields to `TaskSummary`:

```java
Long originSourceRowCount, Long destinationSourceRowCount,
Long originReadPartitionNum, Long destinationReadPartitionNum
```

Select the four columns in `TaskManagementRepository.SELECT_TASK` and `BatchRepository.tasks`, and map every value with `nullableLong`. Waiting-task constructors pass four trailing nulls.

- [ ] **Step 4: Run focused service tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass with populated values and historical nulls.

- [ ] **Step 5: Commit the service API deliverable**

```bash
git add compare-service/src/main/java/cn/sunline/tss/compare/task/TaskSummary.java compare-service/src/main/java/cn/sunline/tss/compare/task/TaskManagementRepository.java compare-service/src/main/java/cn/sunline/tss/compare/batch/BatchRepository.java compare-service/src/test
git commit -m "feat: expose task read plan snapshots"
```

### Task 5: Show each side's calculation on both task-detail layouts

**Files:**
- Modify: `compare-ui/src/api/types.ts`
- Modify: `compare-ui/src/test/server.ts`
- Modify: `compare-ui/src/pages/TaskDetailPage.tsx`
- Modify: `compare-ui/src/pages/TaskDetailPage.test.tsx`

**Interfaces:**
- Consumes: the four nullable `TaskSummary` properties from Task 4.
- Produces: two labeled execution facts, each rendered as `<formatted row count> → <N>分区`, or `未记录` if either member is null.

- [ ] **Step 1: Write failing UI tests for values and null compatibility**

For the normal execution tab, return `originSourceRowCount: 1000000`, `destinationSourceRowCount: 10000000`, `originReadPartitionNum: 1`, and `destinationReadPartitionNum: 16`; assert both database labels and literal calculations are visible. Repeat the assertion through the `resultDate` daily-result branch, which currently has a separate compact execution grid. Add a historical response with null fields and assert `未记录` appears twice and no `0分区` is rendered.

The production changes these tests catch are updating only one rendering branch or coercing null to zero.

- [ ] **Step 2: Run the component test and verify RED**

```bash
cd compare-ui && npm test -- --run src/pages/TaskDetailPage.test.tsx
```

Expected: FAIL because no per-side calculation facts are rendered.

- [ ] **Step 3: Add typed properties and a shared renderer**

Extend `TaskSummary` with the four optional nullable numbers. Add a small local pure formatter:

```tsx
function readPlan(rows?: number | null, partitions?: number | null) {
  return rows == null || partitions == null
    ? '未记录'
    : `${rows.toLocaleString('zh-CN')}行 → ${partitions}分区`
}
```

Render `names.origin + ' 运行前读取计划'` and the destination equivalent in both the daily-result compact grid and the legacy execution tab. Keep `读取策略` as compatibility text rather than replacing the structured display.

- [ ] **Step 4: Run the component test and verify GREEN**

Run the Step 2 command. Expected: all `TaskDetailPage` tests pass.

- [ ] **Step 5: Run the full frontend suite and production build**

```bash
cd compare-ui && npm test -- --run && npm run build
```

Expected: all tests pass and Vite production build exits 0.

- [ ] **Step 6: Commit the UI deliverable**

```bash
git add compare-ui/src/api/types.ts compare-ui/src/test/server.ts compare-ui/src/pages/TaskDetailPage.tsx compare-ui/src/pages/TaskDetailPage.test.tsx
git commit -m "feat: show per-database read plans"
```

### Task 6: Update operator documentation and verify the whole repository

**Files:**
- Modify: `README.md:214-230`
- Modify: `docs/migration/intranet-build.md` where SQL upgrade order is documented
- Modify: `docs/superpowers/plans/2026-09-23-independent-read-partitions.md` (check completed boxes only; no behavior changes)

**Interfaces:**
- Consumes: all previous task behavior.
- Produces: deploy order that explicitly runs `sql/add_bcomp_read_plan_new.sql` before starting the new Spark job.

- [ ] **Step 1: Update the operational contract**

Document that each worker counts A and B independently, calculates each side's JDBC partitions independently, persists both inputs/outputs, and then performs the unchanged key shuffle. Add the `100万 → 1` and `1000万 → 16` example. State that the migration is required and old task rows remain null/`未记录`.

- [ ] **Step 2: Run whitespace and change-scope checks**

```bash
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors; only files named in this plan plus pre-existing user-owned changes appear.

- [ ] **Step 3: Run the complete backend suite**

```bash
mvn -Dgauss-jdbc.version=6.0.0 clean verify
```

Expected: reactor success, zero test failures/errors. Report any pre-existing or environment failure by exact module and test instead of claiming completion.

- [ ] **Step 4: Re-run the complete frontend suite and build from a clean output state**

```bash
cd compare-ui && npm test -- --run && npm run build
```

Expected: zero test failures and successful build.

- [ ] **Step 5: Inspect the packaged Spark artifact and source package**

```bash
unzip -l compare-spark/target/compare-spark-1.0.0-SNAPSHOT.jar | rg 'AutoHashPartitionPlanner|SparkHandlerTable'
unzip -l target/tss-compare-source-1.0.0-SNAPSHOT.zip | rg 'add_bcomp_read_plan_new.sql|AutoHashPartitionPlanner.java|TaskDetailPage.tsx'
```

Expected: the runtime classes and all upgrade/source files are present. If the root build does not produce the source ZIP, run the repository's documented packaging command before repeating the second check.

- [ ] **Step 6: Commit documentation and final plan state**

```bash
git add README.md docs/migration/intranet-build.md docs/superpowers/plans/2026-09-23-independent-read-partitions.md
git commit -m "docs: explain independent read partition planning"
```

- [ ] **Step 7: Review final diff against the approved design**

Confirm explicitly that: A and B counts are independent; each count controls only its own JDBC predicates; both snapshots persist; `BCOMP_READ_MODE` names both sides; null history stays null; both UI branches render; count and persistence failures are not downgraded; no unrelated configuration or comparison semantics changed.
