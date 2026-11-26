# Axon Import Seven-Day Result Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 清理 Axon 配置导入前已超过七天的结果与样本，保留任务和批次历史。

**Architecture:** 在现有配置导入事务全部校验成功后调用固定 SQL 清理器，再共同提交。清理器先锁过期汇总，再集合删除关联子表及汇总；旧版明细仅按可靠原任务结束时间清理。响应增加兼容的 cleanup 摘要，前端显示真实数量并使结果/明细缓存失效。

**Tech Stack:** Java 17, Spring JDBC/TransactionTemplate, GaussDB/PostgreSQL and GDB/MySQL, React/TypeScript, JUnit/H2, Vitest.

**Spec:** `/Users/wangshanhe/.codex/worktrees/bd-import-retention-20261126/obsidian/01 Engineering/bd/Axon导入历史结果七天保留-系统设计.md` 及同目录数据模型/API三件套；用户于2026-11-26明确批准继续实施。

## Global Constraints

- 固定七天，截止 `LocalDateTime.now(clock).minusDays(7)`，严格小于才删，等于保留；不按业务日期判定。
- 不删除任务、批次、批次项或数据库连接/规则；覆盖不再导入的旧编号及已标样本过期的结果。
- 新增删除必须参加原导入事务；失败共同回滚，缓存刷新仍在提交后执行。
- 不新增DDL，不修改Spark、Axon导出格式或业务SQL，保留bc615a0修复。
- 旧版未知年龄或孤立明细保留；不把样本或全部样本行号读入Java内存。
- 当前已有隔离git worktree；本功能分支codex/axon-import-seven-day-retention。

## Review Focus

- 固定截止时间前一秒、恰等于、后一秒；近期重跑旧业务日期应保留（Task 1/2）。
- 删除已过期样本标记及已移除配置编号，保留所有任务/批次与孤立未知明细（Task 1/2）。
- 旧明细严格校验原任务结束时间，空/异常/近期/未结束保留，不能使用TASK_DATE推断年龄（Task 1）。
- 删除子表后故障时原配置和已删数据均恢复；缓存失败则已提交的摘要继续返回（Task 2）。
- 定时清理候选消失及旧服务响应缺cleanup时不误报；缓存包括differences（Task 3/4）。

### Task 1: Transaction-participating history cleaner

**Files:** Create `compare-service/src/main/java/cn/sunline/tss/compare/configuration/ResultCleanupSummary.java`, `ResultHistoryCleanup.java`; test `ResultHistoryCleanupTest.java`.

**Interfaces:** `public record ResultCleanupSummary(int retentionDays,String cutoff,long deletedResults,long deletedDetails,long deletedFieldPatterns,long deletedFieldCounts,long deletedLegacyDetails)`; package-private `ResultHistoryCleanup.clean(JdbcTemplate jdbc,String cutoff)` returns it and uses the caller's transaction, with no independent commit.

- [x] Write real H2 PostgreSQL/MySQL-mode transaction tests for cutoff boundaries, four-table cleanup, removed IDs, expired flags, legacy/unknown rows and retained task/batch data; run RED.
- [x] Implement fixed SQL header locking in `(BCOMP_INDEX,RESULT_DATE)` order, child set deletes, header delete last. Avoid Java sample materialization and per-result network round trips.
- [x] Clean `DTL.RESULT_DATE IS NULL` by precise task identity and ended state 3/4/5 plus reliable earlier END_DATETIME. Strictly validate timestamp values in Java from task metadata (not sample payload); invalid/missing values must never qualify. Keep invalid metadata bounded and fail closed if it cannot be safely processed.
- [x] Return actual affected counts (details includes legacy subset); run GREEN for both dialect modes, transaction rollback and idempotence.

### Task 2: Integrate atomic import and response

**Files:** Modify `ConfigurationRepository.java`, `ConfigurationService.java`, `ConfigScriptImportResult.java`; add integration tests in configuration package.

**Interfaces:** Add repository `ImportOutcome(AxonConfigScriptParser.ParseResult counts,ResultCleanupSummary cleanup)`. Keep public constructor signatures and add package-local Clock injection for deterministic tests. Extend `ConfigScriptImportResult` with `cleanup` after existing fields. Cleaner signature from Task 1 is authoritative.

- [x] Write RED import tests with fixed Clock: valid import cleans; invalid parser/reference/key and active task do not clean; late SQL failure restores configuration/results; cache failure retains committed cleanup; repeated import returns zero deletion.
- [x] After existing validation/normalization/cap inheritance, compute cutoff once and call cleaner inside original transaction. Service uses outcome counts and cleanup after commit.
- [x] Keep existing error code CONFIG_SCRIPT_EXECUTION_FAILED; explain rollback of import and cleanup without leaking SQL/credentials.
- [x] Run affected API/configuration/batch tests; commit verified backend work.

### Task 3: Make sample expiry tolerate deleted candidates

**Files:** Modify `compare-service/src/main/java/cn/sunline/tss/compare/result/DailyResultRepository.java`; extend result tests.

- [x] Write RED concurrency test: expiry selects a result, import-style removal commits before expiry acquires the header; expiry skips the missing candidate without RESULT_NOT_FOUND, and does not swallow unrelated failures.
- [x] Change only expiry's locked lookup to handle an absent row; preserve normal result API 404 behavior and sample-only retention.
- [x] Run result expiry/export tests and both dialect paths.

### Task 4: Import page confirmation and real cleanup feedback

**Files:** Modify `compare-ui/src/api/types.ts`, `pages/ConfigurationImportPage.tsx`, its tests; update README.

**Interfaces:** `ConfigScriptImportResult.cleanup?` mirrors Task 1 record; absence means old server, hide cleanup success/statistics. Cache failure does not negate saved cleanup.

- [x] Write RED tests for seven-day warning/confirmation, actual cleanup counts/cutoff, saved+cache failure, old response without cleanup and invalidation of configuration/results/differences caches.
- [x] Implement minimal UI using existing components/styles; preserve original upload/paste and retry-cache behavior.
- [x] Run complete UI tests and build. Document fixed import-only rule, retained task/batch history and conservative legacy handling.

### Task 5: Full verification and delivery

- [x] Run complete service tests/package with Java17 and available JDBC6.0.0; run relevant Spark NULL-key regressions; no competing Maven builds.
- [x] Fresh whole-branch review of SQL/transaction/legacy boundaries and UI compatibility; address material findings with RED/GREEN.
- [x] Update approved design/overview/index/log with actual implementation and checks in the isolated vault, commit and push non-force.
- [x] Commit application changes and package credential-free source ZIP with current frontend plus service update if build available; verify archive contents, checksums, clean git diff.

Verification so far: service247 (1 external GaussDB skip), UI107/build, Chromium12, Spark3.4 nullable-key/aggregate/hash24 all green. Full fresh read-only branch review: no actionable findings. Production deployment not performed; source delivery uses the existing intranet BES build.

Delivery verified: credential-free source ZIP includes current frontend and upgrade guide; SHA-256/ZIP integrity/source identity checked. Implementation commit0eaa6df; isolated vault implementation/verification commit65cdc57 pushed to origin/main. No production deployment or data deletion performed.
