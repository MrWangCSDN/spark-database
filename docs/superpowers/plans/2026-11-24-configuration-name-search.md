# Configuration Name Search Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans; keep the existing native worktree.

**Goal:** Search every table configuration by a case-insensitive literal name substring, with correct server pagination and retained selections.

**Architecture:** Add optional keyword to the existing configuration list API, sharing the filter between COUNT and SELECT. Add the existing-style filter bar to the list, persist committed keyword/page in the URL and preserve cross-page selection.

**Tech Stack:** Java17/Spring JDBC with GaussDB and GDB dialects; React/TypeScript/React Query/Router; JUnit/H2/Vitest/Chromium.

**Spec:** Canonical bd vault `数据库比对管理台-{系统设计,数据模型,API接口}.md`, 2026-11-24 sections; active isolated vault checkout `/Users/wangshanhe/.codex/worktrees/table-hash-limit/obsidian/01 Engineering/bd/`.

## Global Constraints

- Work only in `/Users/wangshanhe/.codex/worktrees/table-hash-limit/bd`, base6b54194 (latest Axon import fixes2b554d9 plus previously delivered metadata fix94d1cdb); retain all previous fixes.
- API GET `/api/configurations?keyword=...&page=0&size=20`; keyword optional, trimmed, max100, case-insensitive literal substring of BCOMP_NAME, only BCOMP_TYPE2. `%/_/!` are literal, all values parameter-bound.
- Response shape/order/page bounds unchanged, total counts all matches. Blank/omitted keyword means original unfiltered behavior; over100 ->400 INVALID_CONFIGURATION_QUERY.
- UI input “配置名称”, query/Enter/reset; URL keyword/page, page reset on filter changes, restore back/forward, query cache includes keyword, clamp obsolete pages with replace.
- Preserve selections across filters/pages; select-all affects visible current page only; selected batch includes all selected IDs; global all-batch action remains global.
- Keep filters visible in empty/error/loading states, distinguish filtered no-match from no configurations. No DDL/Spark/dependency changes or production deployment.

## Review Focus

- Underscores in real table names must not behave as LIKE wildcards (API literal escaping in both dialects).
- Matching rows outside the first page and filtered COUNT must remain correct (multi-page fixtures).
- A user changes keyword on page2 or uses browser back/refresh (URL/reset and request behavior tests).
- Selected entries hidden by a new filter must survive; current-page select-all must not select all matches (selection/payload tests).
- An empty or failed search must remain recoverable through reset; incoming out-of-range page must not yield a blank page (UI tests).

### Task1: Backend contract (backend agent)

**Files:** ConfigurationController/Service/Repository and focused configuration/dialect API tests under compare-service.

- [x] Add meaningful failing API tests for omitted/blank keyword, mixed case/Chinese, matches beyond page1, matching total/order, literal %/_/!/quote, type2 isolation, over100 and old pagination validation.
- [x] Observe RED before implementation. Implement keyword through existing list methods; retain overloads if existing Java consumers need them. Use LIKE ESCAPE! and consistent bound parameter ordering for GaussDB/GDB.
- [x] Run focused then full service tests with JDK17/local-servlet-tests, report all failures/skips and freeze owned files without committing.

### Task2: Configuration list UI (UI agent)

**Files:** ConfigurationsPage.tsx/tests, existing filter CSS only if needed; fixtures/E2E as needed under compare-ui.

- [x] Add failing tests for keyword query/Enter/reset, page reset and preservation, URL/back navigation, filtered empty recovery, out-of-range page, query-key separation, selected IDs across different filters and visible-page-only selection.
- [x] Observe RED before source changes; reuse FilterBar and current styles, keep global actions and copy/edit functional. Native maxLength100, encoded query values and server-provided total.
- [x] Run full UI tests/build plus real Chromium E2E for filtered results/reset/selections in light and dark themes; no production APIs. Freeze files without committing.

### Task3: Integration and delivery (root/reviewer)

- [x] Update README/intranet upgrade guidance: service plus embedded frontend update, no database migration or Spark update required.
- [x] Independently review the complete diff, resolve important findings and inspect fresh verification evidence; run the integrated Spark3.4 suite after restoring the earlier delivered connection fix onto the latest Axon import branch.
- [x] Commit only feature files, build/verify source ZIP with new embedded frontend, migrations and exact source commit; preserve previous artifacts and credentials.
- [x] Publish design verification evidence via isolated vault; retain the native branch/worktree and report behavior, tests and deployment steps.

## Verification (2026-11-24)

- Latest Axon import fixes2b554d9 retained; previously delivered metadata lifecycle94d1cdb restored as6b54194 before feature work. Branchcodex/configuration-name-search.
- Backend RED10:8 expected missing-filter/limit failures,2 compatibility cases pass; focused62/62; fullservice224 tests,0 failures/errors,1 external GaussDB connection test skipped.
- UI RED:9 added failures; GREEN12; reviewer old-cache page clamp P3 reproduced by2 real-cache/delayed-response regressions, then final14/14. FullUI102/102, production build passed, realChromium10/10 light/dark search/reset/selection flows.
- Integrated Spark3.4 regression95/95 after restoring the connection fix. No new Spark or DDL changes for name search.
- Independent final review: no open findings. Source ZIP includes new built UI and both existing migration scripts; deployment updates service/embedded frontend only, using the intranet BES build.
- No production deployment or live database validation performed.
