# Table HASH Partition Limit Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Track the steps below.

**Goal:** Allow each table configuration to cap automatic HASH partitioning at an integer from 1 to 256, default 64.

**Architecture:** Persist an independent cap in metadata and expose it through the existing configuration API/editor. Spark reads it on its existing metadata connection, counts both sides as before, rounds the required count up to a power of two and clamps to the cap. Keep the 21 Spark arguments unchanged.

**Tech Stack:** Java/JDBC/Spring, Spark 3.5.3 and 3.4.0 profiles, React/TypeScript, GaussDB and GDB SQL.

**Spec:** `/Users/java/obsidian/01 Engineering/bd/表级HASH分区上限-系统设计.md`, `表级HASH分区上限-数据模型.md`, `表级HASH分区上限-API接口.md` (published vault commit `319e99a`).

## Global Constraints

- Work only in `/Users/wangshanhe/.codex/worktrees/table-hash-limit/bd`; preserve unrelated work in the original checkout.
- `BCOMP_MAX_PARTITION_NUM INT NOT NULL DEFAULT 64`, range 1..256; API `basic.maxPartitionNum` (`Integer`). Never repurpose legacy `BCOMP_PARTITION_NUM`.
- Create omitted/null -> 64; read null -> 64; update omitted/null -> preserve existing; copy inherits.
- Existing Axon formats remain unchanged; import preserves caps of matching type-2 IDs transactionally, new IDs use 64.
- Strict JSON integers only. Invalid types, overflow and bounds produce `INVALID_MAX_PARTITION_NUM`.
- No production access, credential artifacts, unrelated RuntimeConfiguration edits or duplicate repository design specs.

## Review Focus

- Old clients omitting/nulling the cap must not erase an existing 128 setting (API update regression).
- A failed replacing import must roll back both old rows and caps (transaction regression).
- Missing metadata row/column and corrupt non-null cap must fail before source COUNT (Spark metadata and invalid-plan tests).
- Non-power-of-two cap100 and Long.MAX_VALUE rows must terminate and yield100/256 (planner boundaries).
- Legacy UI response without the new field must display/save64; blank and fractional editor input must be rejected (editor tests).

### Task 1: Service contract and schema (backend agent)

**Files:** Configuration DTO/repository/service; strict deserializer; batch validator; configuration and batch API tests; legacy test schema; both full-create SQL files and two new `sql/add_bcomp_max_partition_num_{new,gdb}.sql` migrations.

**Interface:** Store `BCOMP_MAX_PARTITION_NUM`; return `basic.maxPartitionNum`, preserve omitted updates and matching import IDs; copy cap; batch fingerprint already includes `c.*`.

- [x] Add API regressions for 1/64/128/256, defaults, omitted/null update preservation, copy, invalid JSON types/ranges/overflow, import retention/new defaults/rollback, active-task lock and stale batch preview.
- [x] Run focused API tests and observe expected failures.
- [x] Implement minimal persistence/validation/compatibility and migrations, retaining legacy Axon formats.
- [x] Run focused tests, migration effects on existing rows and full service suite; report exact results. Root integrates commits after review.

### Task 2: Configuration editor (UI agent)

**Files:** `compare-ui/src/api/types.ts`, `src/pages/ConfigurationEditorPage.tsx`, corresponding tests and fixtures/e2e data if required.

**Interface:** Read/send `basic.maxPartitionNum`; missing/null response normalizes to64.

- [x] Add editor tests for returned128, save256, old response default64, invalid blank/0/257/fractional values, and preserving returned cap while editing other fields.
- [x] Observe tests fail for the absent input/contract.
- [x] Add numeric “最大 HASH 分区数” in the current basic grid with min1/max256/step1 and automatic-calculation help text; reuse styles.
- [x] Run all UI tests and production build. Root integrates commits after review.

### Task 3: Spark automatic planner (root)

**Files:** `AutoHashPartitionPlanner.java`, `SparkHandlerTable.java`, `AutoHashPartitionPlannerTest.java`, metadata limit tests under compare-spark.

**Interfaces:** `plan(String[] args, int maxPartitionNum)`, `partitions(long rows, int maxPartitionNum)` with default64 overloads; `SparkHandlerTable.readMaxPartitionNum(Connection,long)` reads/validates cap on the existing metadata connection before COUNT.

- [x] Run current planner/key HASH baselines.
- [x] Add failing planner tests: 3m/64->4, 50m/32->32, 200m/128->128, 200m/100->100, huge/256->256, empty->1, negative rows and invalid caps rejected before connection/count.
- [x] Add failing real JDBC metadata tests for valid values, null default, missing row/column, fractional/out-of-range/overflow values, and connection reuse.
- [x] Implement overloads and integrate metadata read into SparkRun before COUNT; both sides retain one actual count.
- [x] Run all Spark tests and repeat under `spark-3.4`.

### Task 4: Release integration and independent verification (root/reviewer)

**Files:** `README.md`, `docs/migration/intranet-build.md`, packaging scripts/tests only as needed, implementation ledger.

- [x] Document incremental migration and coordinated service/Spark/embedded UI upgrade; ensure both migrations enter release/source packages.
- [x] Run complete Maven tests with JDK17/local-servlet-tests, UI tests/build and packaging tests; inspect output rather than rely on agent reports.
- [x] Delegate an independent full diff review, resolve material findings with focused regressions and rerun affected verification.
- [x] Update canonical vault implementation/test status via isolated vault checkout, commit/push without touching unrelated canonical dirty files.
- [x] Commit only feature files on `codex/table-hash-limit`, preserve isolated workspace and report migration, behavior and verification.

## Verification results (2026-11-23)

- Service: 205 tests, 0 failures/errors; 1 external GaussDB connection test skipped without supplied connection.
- Spark default 3.5.3: 85/85; Spark 3.4.0: 85/85.
- UI: 91/91; TypeScript/Vite production build passed; real Chromium E2E 8/8, including light/dark cap validation and saving256.
- Lifecycle/packaging scripts: 8/8; migration defaults/constraints/preserved rows tested in both H2 compatibility modes (GDB transaction directive adapted only in test).
- Independent review: malformed-JSON error envelope finding fixed with RED/GREEN regression; final review has no open findings.
- No production migration, vendor database load test or deployment performed. The native feature worktree/branch is retained; original checkout changes are preserved.
