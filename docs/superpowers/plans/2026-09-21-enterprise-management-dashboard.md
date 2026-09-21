# Enterprise Management Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the database comparison console as an enterprise management dashboard for managers reviewing service health, run results, and statistics, while preserving every existing API, database value, and Spark behavior.

**Architecture:** Keep the current React 18 + React Query + React Router application and introduce a small shared presentation layer for page headings, filters, loading/error/empty states, and status summaries. Each page continues to own its queries and mutations; task filters move into URL search parameters and are serialized through the existing `query()` helper. Styling remains dependency-free in the existing token, base, and layout stylesheets.

**Tech Stack:** React 18.3, TypeScript 5.6, React Router 6.30, TanStack React Query 5.59, Vite 5.4, Vitest, Testing Library, MSW, Playwright.

**Spec:** `/Users/java/obsidian/01 Engineering/bd/数据库比对管理台-系统设计.md`

## Global Constraints

- Preserve the existing `/api` contracts, original `TSS_BCOMP_*` tables, field encodings, and Spark comparison semantics.
- Use only the existing `/api/dashboard` fields: `serviceStatus`, `runningCount`, `todayCompletedCount`, `todayDifferenceCount`, `updatedAt`, and `recentTasks`.
- Do not invent trend, comparison-period, owner, notification-count, or user-profile data.
- Keep all lists server-paginated; task pages request 20 rows and use the supported filter and sort parameters.
- Use deep ink-blue navigation, a cool-white workspace, one indigo action color, and semantic red/yellow/green only for status.
- Status must always include readable text and may not rely on color alone.
- Preserve light, dark, and system theme modes.
- Desktop is optimized for 1280px and wider; narrow screens collapse navigation and remove secondary table columns.
- Motion must respect `prefers-reduced-motion`.
- Do not add a component library, charting library, state-management library, or icon package.

## Review Focus

- Dashboard data can be loading, failed, empty, or contain no attention items; the page must show a stable state without invented values.
- Task filters may contain empty values or special characters; the request URL must include only active, encoded parameters and reset pagination when filters change.
- Task state and task result are separate meanings; completed-consistent, completed-different, running, and failed tasks must retain the existing backend code mapping.
- Long JDBC URLs, SQL, failure reasons, business keys, and difference payloads must wrap or truncate without expanding the page beyond the viewport.
- Keyboard and reduced-motion users must be able to reach navigation, filters, drawers, tabs, row actions, and visible focus states.

---

### Task 1: Enterprise Design Foundation

**Files:**
- Create: `compare-ui/src/components/PageHeader.tsx`
- Create: `compare-ui/src/components/ContentState.tsx`
- Create: `compare-ui/src/components/PageHeader.test.tsx`
- Modify: `compare-ui/src/components/StatusBadge.tsx`
- Create: `compare-ui/src/components/StatusBadge.test.tsx`
- Modify: `compare-ui/src/styles/tokens.css`
- Modify: `compare-ui/src/styles/base.css`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: React nodes and existing global CSS class conventions.
- Produces: `PageHeader({ eyebrow, title, description, actions, meta })`, `ContentState({ state, title, description, action })`, and corrected `StatusBadge({ value, kind, taskState })` semantics for every page.

- [ ] **Step 1: Write failing shared-component tests**

```tsx
test('renders title metadata and actions as one labelled page header', () => {
  render(<PageHeader eyebrow="OVERVIEW" title="运行总览" description="运行状态" meta="更新于 12:00" actions={<button>刷新</button>} />)
  expect(screen.getByRole('heading', { name: '运行总览' })).toBeVisible()
  expect(screen.getByText('更新于 12:00')).toBeVisible()
  expect(screen.getByRole('button', { name: '刷新' })).toBeVisible()
})

test('announces failed content state', () => {
  render(<ContentState state="error" title="任务加载失败" description="请稍后重试" />)
  expect(screen.getByRole('alert')).toHaveTextContent('任务加载失败')
})

test.each([
  ['3', '2', '一致'],
  ['3', '1', '有差异'],
  ['4', '1', '执行失败']
])('maps task state %s and result %s to %s', (taskState, value, label) => {
  render(<StatusBadge kind="result" taskState={taskState} value={value} />)
  expect(screen.getByText(label)).toBeVisible()
})
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `cd compare-ui && npm test -- --run src/components/PageHeader.test.tsx src/components/StatusBadge.test.tsx`

Expected: FAIL because `PageHeader.tsx` and `ContentState.tsx` do not exist.

- [ ] **Step 3: Implement the shared components**

```tsx
export function PageHeader({ eyebrow, title, description, actions, meta }: PageHeaderProps) {
  return <header className="page-header">
    <div className="page-header-copy"><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p>{meta && <small>{meta}</small>}</div>
    {actions && <div className="page-header-actions">{actions}</div>}
  </header>
}

export function ContentState({ state, title, description, action }: ContentStateProps) {
  return <div className={`content-state content-state-${state}`} role={state === 'error' ? 'alert' : 'status'}>
    <strong>{title}</strong>{description && <p>{description}</p>}{action}
  </div>
}
```

Correct the legacy result mapping without changing its encoded values: result `2` means consistent; result `1` means differences when the task completed and execution failure when `taskState === '4'`. Every page rendering a result badge must pass the task state:

```tsx
<StatusBadge kind="result" value={item.taskResult} taskState={item.taskState} />
```

- [ ] **Step 4: Replace the visual tokens and shared primitives**

Define indigo action tokens, separate semantic state backgrounds/borders/text, navigation group typography, 4/8/12/16/24/32 spacing steps, dense 40px controls, visible `:focus-visible`, table sticky headers, drawer/modal focus outlines, responsive hidden-column utilities, and this reduced-motion rule:

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
}
```

- [ ] **Step 5: Run shared tests and commit**

Run: `cd compare-ui && npm test -- --run src/components/PageHeader.test.tsx src/components/StatusBadge.test.tsx`

Expected: PASS.

```bash
git add compare-ui/src/components/PageHeader.tsx compare-ui/src/components/ContentState.tsx compare-ui/src/components/PageHeader.test.tsx compare-ui/src/components/StatusBadge.tsx compare-ui/src/components/StatusBadge.test.tsx compare-ui/src/styles
git commit -m "feat(ui): add enterprise design foundation"
```

### Task 2: Management Navigation and Global Shell

**Files:**
- Modify: `compare-ui/src/components/AppShell.tsx`
- Create: `compare-ui/src/components/AppShell.test.tsx`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: React Router `NavLink`, existing `ThemeSwitcher`, and CSS tokens from Task 1.
- Produces: grouped navigation with “管理视图” and “基础配置”, a desktop top bar, and a mobile navigation drawer.

- [ ] **Step 1: Write the shell behavior tests**

```tsx
test('groups management and configuration navigation', () => {
  renderPage(<Routes><Route element={<AppShell />}><Route index element={<div>内容</div>} /></Route></Routes>)
  expect(screen.getByText('管理视图')).toBeVisible()
  expect(screen.getByText('基础配置')).toBeVisible()
  expect(screen.getByRole('link', { name: /运行总览/ })).toHaveAttribute('href', '/')
  expect(screen.getByRole('link', { name: /数据库连接/ })).toHaveAttribute('href', '/databases')
})

test('opens and closes mobile navigation with accessible labels', async () => {
  const user = userEvent.setup()
  renderPage(<Routes><Route element={<AppShell />}><Route index element={<div>内容</div>} /></Route></Routes>)
  await user.click(screen.getByRole('button', { name: '打开导航' }))
  expect(screen.getByRole('navigation', { name: '主导航' })).toHaveClass('open')
  await user.click(screen.getByRole('button', { name: '关闭导航' }))
  expect(screen.getByRole('navigation', { name: '主导航' })).not.toHaveClass('open')
})
```

- [ ] **Step 2: Run the shell test and verify failure**

Run: `cd compare-ui && npm test -- --run src/components/AppShell.test.tsx`

Expected: FAIL because the current shell has no navigation groups or labelled navigation element.

- [ ] **Step 3: Implement grouped navigation and top bar**

Use this data shape so names and routes remain centralized:

```tsx
const navigation = [
  { label: '管理视图', items: [{ to: '/', label: '运行总览', icon: 'overview' }, { to: '/tasks', label: '核对任务', icon: 'tasks' }] },
  { label: '基础配置', items: [{ to: '/configurations', label: '核对配置', icon: 'config' }, { to: '/databases', label: '数据库连接', icon: 'database' }] }
]
```

Render inline SVG icons with `aria-hidden`, retain theme switching at the bottom, add a top bar with product context and a neutral “内部管理” identity marker, and keep all overlays dismissible by their labelled close buttons.

- [ ] **Step 4: Run shell and regression tests, then commit**

Run: `cd compare-ui && npm test -- --run src/components/AppShell.test.tsx`

Expected: PASS.

```bash
git add compare-ui/src/components/AppShell.tsx compare-ui/src/components/AppShell.test.tsx compare-ui/src/styles/layout.css
git commit -m "feat(ui): rebuild management console shell"
```

### Task 3: Executive Dashboard

**Files:**
- Modify: `compare-ui/src/pages/DashboardPage.tsx`
- Modify: `compare-ui/src/pages/DashboardPage.test.tsx`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: `DashboardSummary`, `PageHeader`, `ContentState`, `StatusBadge`, and `formatTime`.
- Produces: health-first KPI row, derived attention list from bounded `recentTasks`, and recent task table.

- [ ] **Step 1: Add failing dashboard hierarchy tests**

```tsx
test('shows health metrics and only attention-worthy recent tasks', async () => {
  server.use(http.get('/api/dashboard', () => HttpResponse.json({
    ...dashboard,
    runningCount: 2,
    todayCompletedCount: 12,
    todayDifferenceCount: 1,
    recentTasks: [failedTask, differentTask, consistentTask]
  })))
  renderPage(<DashboardPage />)
  expect(await screen.findByRole('heading', { name: '运行总览' })).toBeVisible()
  expect(screen.getByLabelText('核心运行指标')).toHaveTextContent('12')
  expect(screen.getByRole('region', { name: '需要关注' })).toHaveTextContent(failedTask.configurationName)
  expect(screen.getByRole('region', { name: '需要关注' })).not.toHaveTextContent(consistentTask.configurationName)
})

test('shows a stable error state when dashboard loading fails', async () => {
  server.use(http.get('/api/dashboard', () => HttpResponse.json({}, { status: 500 })))
  renderPage(<DashboardPage />)
  expect(await screen.findByRole('alert')).toHaveTextContent('总览加载失败')
})
```

- [ ] **Step 2: Run dashboard tests and verify the new assertions fail**

Run: `cd compare-ui && npm test -- --run src/pages/DashboardPage.test.tsx`

Expected: FAIL because attention and dashboard error regions do not exist.

- [ ] **Step 3: Implement health-first content**

Derive attention items without new API data:

```tsx
const attentionTasks = data?.recentTasks.filter(item => item.taskState === '4' || (item.taskState === '3' && item.taskResult === '1')) ?? []
```

Render the four existing metrics in the approved order, a “需要关注” region that shows at most five matching recent tasks, and the full bounded recent-task table. Retain cache refresh behavior and current tests. Show `ContentState` for query loading, failure, no attention items, and no recent tasks.

- [ ] **Step 4: Run dashboard tests and commit**

Run: `cd compare-ui && npm test -- --run src/pages/DashboardPage.test.tsx`

Expected: all dashboard tests PASS.

```bash
git add compare-ui/src/pages/DashboardPage.tsx compare-ui/src/pages/DashboardPage.test.tsx compare-ui/src/styles/layout.css
git commit -m "feat(ui): build executive run dashboard"
```

### Task 4: Filterable Task Operations Page

**Files:**
- Create: `compare-ui/src/components/FilterBar.tsx`
- Modify: `compare-ui/src/pages/TasksPage.tsx`
- Modify: `compare-ui/src/pages/TasksPage.test.tsx`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: supported `/api/tasks` parameters `bcompIndex`, `taskState`, `taskResult`, `dateFrom`, `dateTo`, `keyword`, `sort`, plus `page` and `size`.
- Produces: URL-backed `TaskFilters`, reset behavior, request serialization, responsive task table, and unchanged create-task drawer behavior.

- [ ] **Step 1: Write failing task filter tests**

```tsx
test('loads active URL filters and sends them to the task API', async () => {
  let requested = ''
  server.use(http.get('/api/tasks', ({ request }) => { requested = request.url; return HttpResponse.json({ items: [], page: 0, size: 20, total: 0 }) }))
  renderPage(<TasksPage />, '/tasks?taskState=4&keyword=A%26B&sort=endedAt%2Cdesc')
  await screen.findByText('没有符合条件的任务')
  expect(requested).toContain('taskState=4')
  expect(requested).toContain('keyword=A%26B')
  expect(requested).toContain('sort=endedAt%2Cdesc')
})

test('applying a filter resets the page and keeps it in the URL', async () => {
  const user = userEvent.setup()
  renderPage(<TasksPage />, '/tasks?page=3')
  await user.selectOptions(await screen.findByLabelText('任务状态'), '2')
  await user.click(screen.getByRole('button', { name: '查询' }))
  expect(screen.getByTestId('task-search')).toHaveTextContent('taskState=2')
  expect(screen.getByTestId('task-search')).not.toHaveTextContent('page=3')
})
```

- [ ] **Step 2: Run task tests and verify failure**

Run: `cd compare-ui && npm test -- --run src/pages/TasksPage.test.tsx`

Expected: FAIL because task filters and URL synchronization do not exist.

- [ ] **Step 3: Implement URL-backed filters**

Define the exact view model in `TasksPage.tsx`:

```tsx
type TaskFilters = { bcompIndex: string; taskState: string; taskResult: string; dateFrom: string; dateTo: string; keyword: string; sort: string }
const emptyFilters: TaskFilters = { bcompIndex: '', taskState: '', taskResult: '', dateFrom: '', dateTo: '', keyword: '', sort: 'createdAt,desc' }
```

Use `useSearchParams()` as the committed filter state and a local draft for form inputs. Parse `page` defensively with `Math.max(0, Number(value) || 0)`. On query, remove empty values and set page to zero. On reset, restore `emptyFilters` and page zero. Include all committed filters in the React Query key and request URL.

- [ ] **Step 4: Build the enterprise table and preserve task creation**

Render `PageHeader`, a visible `FilterBar`, result count and refresh action, a dense table with secondary columns hidden below 900px, explicit loading/error/empty states, and existing pagination. Keep the create drawer mutation and required-value validation unchanged; close it only after successful creation.

- [ ] **Step 5: Run task tests and commit**

Run: `cd compare-ui && npm test -- --run src/pages/TasksPage.test.tsx`

Expected: all task tests PASS.

```bash
git add compare-ui/src/components/FilterBar.tsx compare-ui/src/pages/TasksPage.tsx compare-ui/src/pages/TasksPage.test.tsx compare-ui/src/styles/layout.css
git commit -m "feat(ui): add task filters and operations table"
```

### Task 5: Task Result Detail Workspace

**Files:**
- Modify: `compare-ui/src/pages/TaskDetailPage.tsx`
- Modify: `compare-ui/src/pages/TaskDetailPage.test.tsx`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: exact route identity, `TaskSummary`, paginated `DifferenceSummary`, `StatusBadge`, and `Pagination`.
- Produces: task identity header, result summary, accessible detail tabs, failure/read-strategy section, and expandable difference rows.

- [ ] **Step 1: Add failing detail-state and tab tests**

```tsx
test('separates differences from execution information', async () => {
  const user = userEvent.setup()
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D')
  expect(await screen.findByRole('tab', { name: '差异明细' })).toHaveAttribute('aria-selected', 'true')
  await user.click(screen.getByRole('tab', { name: '执行信息' }))
  expect(screen.getByRole('tabpanel')).toHaveTextContent('读取策略')
  expect(screen.getByRole('tabpanel')).toHaveTextContent('开始时间')
})

test('shows a task request failure without requesting misleading content', async () => {
  server.use(http.get('/api/tasks/8/20260920/1/D', () => HttpResponse.json({}, { status: 500 })))
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D')
  expect(await screen.findByRole('alert')).toHaveTextContent('任务详情加载失败')
})
```

- [ ] **Step 2: Run detail tests and verify failure**

Run: `cd compare-ui && npm test -- --run src/pages/TaskDetailPage.test.tsx`

Expected: FAIL because the current page has no tabs or explicit task-query failure state.

- [ ] **Step 3: Implement the detail hierarchy**

Use local state `type DetailTab = 'differences' | 'execution'`. Render both tab buttons with `role="tab"`, `aria-selected`, and `aria-controls`; render one `role="tabpanel"`. Keep task identity, state, and result above tabs. In the execution panel show creation/start/end times, old/new counts, success/failure counts, `readMode`, and `failureReason`, mapping absent values to `—`.

Only render and page difference rows in the differences panel. Preserve escaped `<pre>` values, show long-content wrapping, and retain the existing difference-result mapping and expansion test.

- [ ] **Step 4: Run detail tests and commit**

Run: `cd compare-ui && npm test -- --run src/pages/TaskDetailPage.test.tsx`

Expected: all detail tests PASS.

```bash
git add compare-ui/src/pages/TaskDetailPage.tsx compare-ui/src/pages/TaskDetailPage.test.tsx compare-ui/src/styles/layout.css
git commit -m "feat(ui): restructure task result details"
```

### Task 6: Configuration and Database Administration Pages

**Files:**
- Modify: `compare-ui/src/pages/ConfigurationsPage.tsx`
- Create: `compare-ui/src/pages/ConfigurationsPage.test.tsx`
- Modify: `compare-ui/src/pages/ConfigurationEditorPage.tsx`
- Modify: `compare-ui/src/pages/ConfigurationEditorPage.test.tsx`
- Modify: `compare-ui/src/pages/DatabasesPage.tsx`
- Modify: `compare-ui/src/pages/DatabasesPage.test.tsx`
- Modify: `compare-ui/src/components/DatabaseDrawer.tsx`
- Modify: `compare-ui/src/components/FieldRulesEditor.tsx`
- Modify: `compare-ui/src/styles/layout.css`

**Interfaces:**
- Consumes: all existing configuration and database types and mutations without contract changes.
- Produces: consistent enterprise list pages, structured full-page configuration editor, accessible database drawer, and robust content states.

- [ ] **Step 1: Add failing administration-page tests**

```tsx
test('shows configuration list loading and empty states without changing table semantics', async () => {
  server.use(http.get('/api/configurations', () => HttpResponse.json({ items: [], page: 0, size: 20, total: 0 })))
  renderPage(<ConfigurationsPage />, '/configurations')
  expect(await screen.findByText('还没有核对配置')).toBeVisible()
  expect(screen.getByRole('link', { name: '新建配置' })).toHaveAttribute('href', '/configurations/new')
})

test('labels configuration editor sections for direct navigation', async () => {
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  expect(await screen.findByRole('region', { name: '基本信息' })).toBeVisible()
  expect(screen.getByRole('region', { name: '两侧查询' })).toBeVisible()
  expect(screen.getByRole('region', { name: '字段规则' })).toBeVisible()
})
```

- [ ] **Step 2: Run administration tests and verify failure**

Run: `cd compare-ui && npm test -- --run src/pages/ConfigurationsPage.test.tsx src/pages/ConfigurationEditorPage.test.tsx src/pages/DatabasesPage.test.tsx`

Expected: FAIL because list empty state and named editor regions do not exist.

- [ ] **Step 3: Rebuild configuration list and editor presentation**

Use `PageHeader` and `ContentState` on the list; retain server pagination, edit and copy behavior. Add semantic `section aria-labelledby` regions to the editor, a sticky save action bar on desktop, a configuration identity summary, paired source/target SQL panels, and a dense field-rule table. Keep `FIELD_TYPE='1'` behavior and all existing field encodings unchanged.

- [ ] **Step 4: Rebuild database list and drawer presentation**

Add result count, explicit loading/error/empty states, protected-password copy, and responsive URL/driver columns. In `DatabaseDrawer`, connect every label through `htmlFor`/`id`, retain `newPassword: null` when the password input is blank, disable duplicate submissions, and keep a labelled close action.

- [ ] **Step 5: Run administration tests and commit**

Run: `cd compare-ui && npm test -- --run src/pages/ConfigurationsPage.test.tsx src/pages/ConfigurationEditorPage.test.tsx src/pages/DatabasesPage.test.tsx`

Expected: all administration tests PASS, including the existing password and cache-warning assertions.

```bash
git add compare-ui/src/pages/ConfigurationsPage.tsx compare-ui/src/pages/ConfigurationsPage.test.tsx compare-ui/src/pages/ConfigurationEditorPage.tsx compare-ui/src/pages/ConfigurationEditorPage.test.tsx compare-ui/src/pages/DatabasesPage.tsx compare-ui/src/pages/DatabasesPage.test.tsx compare-ui/src/components/DatabaseDrawer.tsx compare-ui/src/components/FieldRulesEditor.tsx compare-ui/src/styles/layout.css
git commit -m "feat(ui): align administration pages"
```

### Task 7: End-to-End Verification and Production Build

**Files:**
- Modify: `compare-ui/e2e/management-console.spec.ts`
- Modify: `compare-ui/src/test/server.ts`
- Modify if assertions expose defects: files owned by Tasks 1–6.

**Interfaces:**
- Consumes: the complete management console and existing Spring Boot static-resource integration.
- Produces: browser-level coverage of the primary management workflow and a verified production bundle.

- [ ] **Step 1: Extend the browser scenario**

```ts
test('manager reviews dashboard, filters failed tasks, and opens a result', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '运行总览' })).toBeVisible()
  await page.getByRole('link', { name: /核对任务/ }).click()
  await page.getByLabel('任务状态').selectOption('4')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page).toHaveURL(/taskState=4/)
  await page.getByRole('link', { name: '查看' }).first().click()
  await expect(page.getByRole('tab', { name: '差异明细' })).toBeVisible()
})
```

- [ ] **Step 2: Run all component tests**

Run: `cd compare-ui && npm test -- --run`

Expected: all Vitest files PASS with no unhandled request warnings.

- [ ] **Step 3: Run the production build**

Run: `cd compare-ui && npm run build`

Expected: TypeScript and Vite complete successfully and emit `dist/`.

- [ ] **Step 4: Run browser tests**

Run: `cd compare-ui && npm run e2e`

Expected: the management workflow and existing browser scenarios PASS. If the environment lacks the Playwright browser binary, record the exact setup failure and perform the same route checks against the running preview with the available browser tooling.

- [ ] **Step 5: Verify the integrated Java build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean verify`

Expected: Maven completes successfully, including frontend packaging and backend tests.

- [ ] **Step 6: Inspect responsive layouts and commit verification updates**

Check `/`, `/tasks`, one task detail, `/configurations`, one configuration editor, and `/databases` at 1440×900, 1024×768, and 390×844. Confirm no horizontal page overflow, visible keyboard focus, correct status text, usable drawer width, and secondary-column hiding.

```bash
git add compare-ui/e2e/management-console.spec.ts compare-ui/src/test/server.ts compare-ui/src
git commit -m "test(ui): verify management dashboard workflows"
```

### Task 8: Final Review and Runtime Preview

**Files:**
- Modify only files required by review findings.

**Interfaces:**
- Consumes: completed Tasks 1–7.
- Produces: a review-ready branch and an updated local preview at `http://127.0.0.1:4173/`.

- [ ] **Step 1: Review the full branch diff**

Run: `git diff --check && git status --short && git log --oneline --decorate -10`

Expected: no whitespace errors, only intentional files changed, and task commits present.

- [ ] **Step 2: Run final verification once after review fixes**

Run: `cd compare-ui && npm test -- --run && npm run build`

Expected: all tests and the production build PASS.

- [ ] **Step 3: Start or refresh the local preview**

Run: `cd compare-ui && npm run dev -- --host 127.0.0.1 --port 4173`

Expected: the console is reachable at `http://127.0.0.1:4173/` and the approved management dashboard is visible.

- [ ] **Step 4: Commit any final review fixes**

```bash
git add compare-ui
git commit -m "fix(ui): address enterprise dashboard review"
```

Skip this commit when review produces no file changes.
