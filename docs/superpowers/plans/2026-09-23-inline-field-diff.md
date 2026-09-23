# Inline Field Difference Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make paired sit/uat values readable in one scan line per sample row.

**Architecture:** Change only the `type === '4'` presentation in `DailySampleGrid` and `DailyResultDetailPage`; keep the existing sample API, saved data, grouping, and one-sided tables. Add a single sit → uat legend, horizontal value pairs, subdued difference-cell styling, and a compact field-group picker.

**Tech Stack:** React, TypeScript, CSS, Vitest/Testing Library, Playwright mock preview.

**Spec:** `/Users/java/obsidian-worktrees/bd-task-table-new/01 Engineering/bd/每日核对结果-系统设计.md` (2026-09-23 差异值单行对照).

## Global Constraints

- No backend or database change.
- Keep Chinese field labels, fixed key column, horizontal scrolling, sample pagination, and truncation notice.
- Colors indicate sides and difference location, never correctness.

## Review Focus

- Paired values remain distinguishable to screen readers without repeating side labels visually in every cell.
- NULL and empty string remain distinct.
- One-sided samples keep their existing single-value display.
- Legacy results without field groups remain readable.
- Long values wrap without covering adjacent cells.

### Task 1: Inline paired values

**Files:** `compare-ui/src/pages/DailySampleGrid.tsx`, `compare-ui/src/pages/DailyResultDetailPage.tsx`, `compare-ui/src/pages/DailyResultDetailPage.test.tsx`, `compare-ui/src/styles/layout.css`.

- [x] Update the detail-page test: select the field-difference tab, assert one visible sit → uat legend, paired 100 and 200 values, and no repeated visible side labels inside a pair. Verify one-sided display still shows only its own value.
- [x] Run `npm test -- --run src/pages/DailyResultDetailPage.test.tsx` and confirm the new assertion fails for the existing stacked layout.
- [x] Render one compact legend and inline pair with hidden accessible side labels; style the changed cell, value sides, separator, wrapping, and narrow-screen behavior.
- [x] Run the focused test, full frontend suite, and production build.
- [x] Refresh the mock preview and inspect desktop dark/light screenshots; package `compare-service` to embed the new frontend.
- [x] Verify diffs and commit the change.

### Task 2: Compact field-group picker

**Files:** `compare-ui/src/pages/DailyResultDetailPage.tsx`, `compare-ui/src/pages/DailyResultDetailPage.test.tsx`, `compare-ui/src/styles/layout.css`.

- [x] Add a failing test for a named group of selectable combinations that preserves exact row and saved-sample counts.
- [x] Run the focused test and confirm the current table layout fails the new contract.
- [x] Replace the three-column pattern table with accessible compact buttons, retain selected state and switching, and limit picker height for many groups.
- [x] Run focused/full tests and inspect dark/light mock screenshots at the user’s viewport.
