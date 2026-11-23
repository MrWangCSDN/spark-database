# Spark Metadata Connection Lifecycle Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans; preserve the existing native feature worktree.

**Goal:** Remove Driver metadata connections spanning source COUNT and distributed comparison, then publish on a fresh connection with safe diagnostics.

**Architecture:** Read cap/sample/context in one short connection, write the read plan with another short connection, and open the publication connection only after both comparison passes. Keep the existing one-shot publication transaction and independent conditional failure update.

**Tech Stack:** Java17/JDBC, Spark3.4.0 and3.5.3, JUnit/H2/local Spark.

**Spec:** Canonical bd vault `库表比对拆分-{系统设计,数据模型,API接口}.md`, 2026-11-23 connection lifecycle sections; isolated working copy `/Users/wangshanhe/.codex/worktrees/table-hash-limit/obsidian/01 Engineering/bd/` preserves unrelated canonical-vault edits.

## Global Constraints

- Work only in `/Users/wangshanhe/.codex/worktrees/table-hash-limit/bd`, base71cb683; retain per-table1..256/default64 and optional date-mode fixes.
- No new DDL, API fields, Spark arguments, UI changes, heartbeats or dependencies.
- Snapshot startup configuration before COUNT; close that connection before COUNT and any long distributed work. Read-mode write remains best effort on a separate short connection.
- Create a new metadata connection after `runTwoPass` to publish once; never blindly reconnect/retry an in-flight or uncertain publication transaction.
- Preserve atomic result replacement, old successful results on rollback, success/cancel states, original/suppressed exceptions and failure-before-Spark-close ordering.
- Only fixed phase labels and validated five-character `[0-9A-Z]{5}` SQLState are added to safe diagnostics; no raw JDBC messages/SQL/passwords/URLs.
- Actual site EOF cause is unconfirmed. Fault injection verifies the connection mechanism, not a production timeout diagnosis.

## Review Focus

- Startup metadata connection accidentally remains open across COUNT/comparison (instrumented connection + actual saved result regression).
- Publication uses an old or retried connection after idle failure (old-connection EOF injection; fresh publication succeeds).
- A truly failing publication is silently replayed or erases old results (one-shot fault + stored result/state assertions).
- Commit acknowledgement loss overwrites already committed success (existing conditional failure rule and no retry).
- SQLState/error messages contain sensitive text or grow beyond limits (safe-format/bounds/suppressed-chain regressions).

### Task 1: Spark lifecycle and diagnostic implementation (agent)

**Files:** `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/{SparkHandlerTable,SparkFailureDiagnostics}.java`; lifecycle, publication and diagnostic tests in corresponding Spark package.

**Interfaces:** Keep `DailyResultPublisher.publish(Connection,String[],Context,ResultAggregate)` and the21 argument contract. Reuse `SparkFailureDiagnostics.summary(Throwable)` with optional safe SQLState annotation. Add fixed execution-phase value to Driver logs; preserve stored summary shape except SQLState.

- [x] Write fault-injection lifecycle regression using a real local Spark/H2 job: startup/read-mode connections close before long work; a connection marked as startup must fail if reused for publication, whereas a new publication connection succeeds and commits correct results.
- [x] Write genuine-publication-failure/no-retry and safe SQLState regressions; retain existing lifecycle invariants.
- [x] Run the new focused tests against original code and observe the expected RED failures. Use only one Maven invocation at a time in this shared module.
- [x] Implement the minimal three-connection scopes, fixed phase logs and bounded valid SQLState. Existing publisher transaction body remains unchanged.
- [x] Run full Spark3.4 and default3.5 suites with JDK17; report logs/counts, flag every failure and freeze owned files. Do not commit.

### Task 2: Deployment guidance and delivery (root)

**Files:** `README.md`, `docs/migration/intranet-build.md`; vault design verification status; native progress ledger.

- [x] Explain the original long-lived connection risk, new lifecycle, absence of DDL, no publication replay, and the need to deploy the rebuilt Spark JAR after in-flight jobs finish.
- [x] Preserve site credential/configuration files and existing per-table schema migration requirements. Hikari tuning is not the lifecycle fix.
- [x] Request independent diff review; fix important findings and run only affected checks again as justified.
- [x] Inspect fresh verification outputs, commit only this change, build updated source ZIP with embedded existing frontend, and verify sources/migrations/commit/hash/exclusions.
- [x] Update/publish vault implementation evidence without touching unrelated edits, retain the feature worktree and report deployment steps plus the site-validation limit.

## Verification (2026-11-23)

- Valid lifecycle RED after fixing Spark driver test visibility: real local Spark completes two passes, then the reused startup connection throws SQLState08006/EOF at publication. Invalid fixture failures were excluded from evidence.
- Focused GREEN:29/29; full default Spark3.5.3:95/95; additional lifecycle connection-open ordering assertion:7/7; clean Spark3.4.0 full:95/95. Zero failures/errors/skips.
- Faults include old-connection reuse, read-mode connection open/SQL/close, rolled-back publication, actual commit followed by acknowledgement failure, success/cancel protection, and bounded/private SQLState.
- Independent review completed with no findings; deployment stage name aligned to actual `PUBLISH`.
- Delivery: source ZIP plus Spark3.4 job-only ZIP; package classes are checked against the exact clean3.4-tested classes. No production deployment or real two-hundred-million-row load test performed.
