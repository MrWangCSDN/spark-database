# Direct Sample Grid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show saved samples as readable wide tables with Chinese field labels and no per-record expansion.

**Architecture:** Spark snapshots FIELD_MEMO into each bounded sample field; existing JSON/API paths remain compatible. React renders a direct field grid for one-sided rows and the selected exact field pattern, choosing the largest pattern automatically. A viewport-sized scroll area holds up to 100 rows per request.

**Tech Stack:** Java 17, Spark, React/TypeScript, Vitest, Playwright.

**Spec:** `/Users/java/obsidian/01 Engineering/bd/每日核对结果-{系统设计,数据模型,API接口}.md`

## Global Constraints

- Do not change exact summary counts, retention, 100 default sample cap, or existing DDL.
- Old sample JSON without `label` must display the technical field name.
- Preserve current result-version checks and truncated-sample notices.

## Review Focus

- Chinese memo blank or too long: fallback/clip label while preserving comparison.
- Sample has fewer fields because of the display budget: show a warning; do not imply it is a complete row.
- Explicit limits above 100: paginate in pages of 100.
- Result replacement while viewing a pattern: clear selection and use the new runId.

### Task 1: Label snapshot

**Files:** `compare-spark/src/main/java/cn/sunline/tss/tssveri/spark/{SampleRow,SparkHandlerTable}.java`, `compare-spark/src/test/java/cn/sunline/tss/tssveri/spark/ResultAggregateTest.java`

- [x] Add a failing test for Chinese label, fallback, and JSON output.
- [x] Run the focused test and confirm failure.
- [x] Implement bounded label snapshot from FIELD_MEMO.
- [x] Run Spark tests and commit.

### Task 2: Direct sample grid

**Files:** `compare-ui/src/pages/DailyResultDetailPage.tsx`, `compare-ui/src/api/types.ts`, `compare-ui/src/styles/layout.css`, `compare-ui/src/pages/DailyResultDetailPage.test.tsx`

- [x] Update tests for direct side-only rows and automatic largest-pattern display.
- [x] Run focused tests and confirm failure.
- [x] Implement the wide scrollable table, 100-row fetch, dynamic pair cells and inline truncation notice.
- [x] Run UI tests/build and commit.

### Task 3: Visual preview and integration

- [x] Update temporary mock data with labels and inspect side-only and pattern screenshots.
- [x] Run relevant backend/UI suites and clean-build the backend JAR with frontend embedded.
- [x] Update README and Obsidian design, verify diffs, commit/push documentation.
