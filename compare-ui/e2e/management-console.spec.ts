import { expect, test } from '@playwright/test'

const task = {
  bcompIndex: 8, configurationName: '客户余额核对', taskBusinessDate: '20260920', verifyBusinessDate: '20260920',
  taskBatch: '1', timeNode: 'D', createdAt: '20260920120000', startedAt: null, endedAt: null,
  taskState: '1', taskResult: null, failureReason: null, file1Count: null, file2Count: null,
  file1SuccessCount: null, file2SuccessCount: null, file1FailureCount: null, file2FailureCount: null, readMode: '1'
}

test('creates a task and inspects its differences', async ({ page }) => {
  let created = false
  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/database-context')) return route.fulfill({ json: { originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat' } })
    if (url.pathname === '/api/configurations') return route.fulfill({ json: { items: [{ bcompIndex: 8, name: '客户余额核对', module: 'CORE', memo: '', range: '', timeNode: 'D', state: '1', partitionNum: 8, dateMode: '1' }], page: 0, size: 100, total: 1 } })
    if (url.pathname === '/api/tasks' && route.request().method() === 'POST') { created = true; return route.fulfill({ status: 201, json: task }) }
    if (url.pathname === '/api/tasks') return route.fulfill({ json: { items: [task], page: 0, size: 20, total: 1 } })
    if (url.pathname.endsWith('/differences')) return route.fulfill({ json: { items: [{ businessKey: 'C001', oldContent: '100.00|2026-09-20', newContent: '101.00|2026-09-20', lineResult: '4', reason: '字段值不一致' }], page: 0, size: 20, total: 1 } })
    if (url.pathname === '/api/tasks/8/20260920/1/D') return route.fulfill({ json: { ...task, taskState: '3', taskResult: '2', endedAt: '20260920123000', file1Count: 20, file2Count: 20 } })
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found', traceId: 'e2e' } })
  })

  await page.goto('/tasks')
  await expect(page.getByRole('heading', { name: '核对任务' })).toBeVisible()
  await page.getByRole('button', { name: '新建核对任务' }).click()
  await page.getByLabel('核对配置').selectOption('8')
  await page.getByLabel('任务业务日期').fill('20260920')
  await page.getByLabel('批次').fill('1')
  await page.getByLabel('时点').fill('D')
  await page.getByRole('button', { name: '创建任务' }).click()
  await expect.poll(() => created).toBe(true)
  await page.getByRole('link', { name: '查看' }).click()
  await expect(page.getByText('值不一致', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看两侧内容' }).click()
  await expect(page.getByText('100.00|2026-09-20')).toBeVisible()
  await expect(page.getByText('101.00|2026-09-20')).toBeVisible()
})

test('manager reviews dashboard, filters failed tasks, and opens a result', async ({ page }) => {
  const failed = { ...task, taskState: '4', taskResult: '1', failureReason: 'Spark 任务退出' }
  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/database-context')) return route.fulfill({ json: { originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat' } })
    if (url.pathname === '/api/batches/runtime') return route.fulfill({ json: { tasksEnabled: true, concurrency: 1, maxBatchSize: 5000 } })
    if (url.pathname === '/api/batches') return route.fulfill({ json: { items: [], page: 0, size: 5, total: 0 } })
    if (url.pathname === '/api/tasks') return route.fulfill({ json: { items: [failed], page: 0, size: 20, total: 1 } })
    if (url.pathname === '/api/tasks/8/20260920/1/D') return route.fulfill({ json: failed })
    if (url.pathname.endsWith('/differences')) return route.fulfill({ json: { items: [], page: 0, size: 20, total: 0 } })
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found', traceId: 'e2e' } })
  })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '核对工作台' })).toBeVisible()
  await page.getByRole('link', { name: /查看全部单表任务/ }).click()
  await page.getByLabel('任务状态').selectOption('4')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page).toHaveURL(/taskState=4/)
  await page.getByRole('link', { name: '查看' }).first().click()
  await expect(page.getByRole('tab', { name: '差异明细' })).toBeVisible()
  await page.getByRole('tab', { name: '执行信息' }).click()
  await expect(page.getByRole('tabpanel')).toContainText('Spark 任务退出')
})

test('tests and saves a database connection, then retries cache refresh', async ({ page }) => {
  let testedPassword: string | null = null
  let savedPassword: string | null = null
  let refreshCalls = 0
  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/database-context')) return route.fulfill({ json: { originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat' } })
    if (url.pathname === '/api/databases' && route.request().method() === 'GET') {
      return route.fulfill({ json: { items: [], page: 0, size: 100, total: 0 } })
    }
    if (url.pathname === '/api/databases/test') {
      testedPassword = (route.request().postDataJSON() as { newPassword: string }).newPassword
      return route.fulfill({ json: { success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 31 } })
    }
    if (url.pathname === '/api/databases' && route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as Record<string, unknown>
      savedPassword = body.newPassword as string
      return route.fulfill({ status: 201, json: { ...body, newPassword: undefined, passwordConfigured: true } })
    }
    if (url.pathname === '/api/cache/refresh') {
      refreshCalls += 1
      return route.fulfill({ json: refreshCalls === 1 ? { code: 500, msg: '刷新失败' } : { code: 200, msg: '刷新成功' } })
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found', traceId: 'e2e' } })
  })

  await page.goto('/databases')
  await page.getByRole('button', { name: '新建连接' }).click()
  await page.getByLabel('连接编号').fill('9')
  await page.getByLabel('连接名称').fill('GaussDB A')
  await page.getByLabel('JDBC URL').fill('jdbc:postgresql://db/core?currentSchema=business')
  await page.getByLabel('用户名').fill('reader')
  await page.getByLabel('新密码').fill('browser-secret')
  await page.getByRole('button', { name: '测试连接' }).click()
  await expect(page.getByText('连接成功 · GaussDB V500 · 31 ms')).toBeVisible()
  expect(testedPassword).toBe('browser-secret')

  await page.getByRole('button', { name: '保存连接' }).click()
  await expect(page.getByText('连接已保存，但缓存刷新失败')).toBeVisible()
  expect(savedPassword).toBe('browser-secret')
  await expect(page.getByText('browser-secret')).toHaveCount(0)
  await page.getByRole('button', { name: '重试刷新缓存' }).click()
  await expect(page.getByText('新连接已加载到当前服务实例。')).toBeVisible()
  expect(refreshCalls).toBe(2)
})


test('previews all configurations, excludes a blocked item, creates a batch and opens the task', async ({ page }) => {
  const requests: Record<string, unknown>[] = []
  const summary = { batchId: '20260922-001', taskBusinessDate: '20260922', verifyBusinessDate: '20260922', createdAt: '20260922120000', sourceBatchId: null, totalCount: 1, waitingCount: 1, runningCount: 0, consistentCount: 0, differenceCount: 0, failedCount: 0, otherCount: 0, endedCount: 0 }
  const ready = { bcompIndex: 8, configurationName: '客户余额核对', module: 'CORE', timeNode: 'D', verifyBusinessDate: '20260922', ready: true, issueCode: null, issueMessage: null, configurationChanged: false }
  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/database-context')) return route.fulfill({ json: { originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat' } })
    if (url.pathname === '/api/batches/preview') {
      const body = route.request().postDataJSON(); requests.push(body)
      const excluded = body.excludedIds?.includes(9)
      const items = excluded ? [ready] : [ready, { ...ready, bcompIndex: 9, configurationName: '未配置主键表', ready: false, issueCode: 'BUSINESS_KEY_REQUIRED', issueMessage: '请设置核对主键' }]
      return route.fulfill({ json: { fingerprint: 'preview-1', taskBusinessDate: '20260922', verifyBusinessDate: '20260922', totalCount: items.length, readyCount: 1, blockedCount: excluded ? 0 : 1, changedCount: 0, items } })
    }
    if (url.pathname === '/api/batches' && route.request().method() === 'POST') { requests.push(route.request().postDataJSON()); return route.fulfill({ json: summary, status: 201 }) }
    if (url.pathname === '/api/batches/20260922-001') return route.fulfill({ json: summary })
    if (url.pathname === '/api/batches/20260922-001/tasks') return route.fulfill({ json: { items: [task], page: 0, size: 20, total: 1 } })
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found', traceId: 'e2e' } })
  })
  await page.goto('/batches/new')
  await page.getByLabel('任务业务日期').fill('20260922')
  await page.getByRole('button', { name: '预检配置' }).click()
  await expect(page.getByRole('button', { name: '确认发起' })).toBeDisabled()
  await page.getByRole('checkbox', { name: '排除 未配置主键表' }).check()
  await page.getByRole('button', { name: '排除勾选的 1 项并重新预检' }).click()
  await expect(page.getByRole('button', { name: '确认发起' })).toBeEnabled()
  await page.getByRole('button', { name: '确认发起' }).click()
  await expect(page).toHaveURL('/batches/20260922-001')
  await expect(page.getByRole('heading', { name: '批次详情' })).toBeVisible()
  await expect(page.getByRole('progressbar')).toHaveAttribute('value', '0')
  expect(requests[0]).toEqual({ scope: 'ALL', taskBusinessDate: '20260922', verifyBusinessDate: '20260922' })
  expect(requests[2]).toMatchObject({ excludedIds: [9], previewFingerprint: 'preview-1' })
  expect(requests[2].requestId).toBeTruthy()
  await expect(page.getByRole('link', { name: '查看任务' })).toHaveAttribute('href', '/tasks/8/20260920/1/D?batchId=20260922-001')
})

test('shows sit and uat names with the correct missing direction', async ({ page }) => {
  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/database-context')) return route.fulfill({ json: { originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat' } })
    if (path.endsWith('/differences')) return route.fulfill({ json: { items: [
      { businessKey: 'AC001', oldContent: null, newContent: 'AC001|+|100.00', lineResult: '2', reason: '生产少' },
      { businessKey: 'AC002', oldContent: 'AC002|+|200.00', newContent: null, lineResult: '3', reason: '信创少' },
      { businessKey: 'AC003', oldContent: 'AC003|+|300.00', newContent: 'AC003|+|301.00', lineResult: '4', reason: 'AMOUNT 字段值不一致' }
    ], page: 0, size: 20, total: 3 } })
    return route.fulfill({ json: { ...task, taskState: '3', taskResult: '1', file1Count: 7, file2Count: 1729516, file1FailureCount: 1729516, file2FailureCount: 7 } })
  })
  await page.goto('/tasks/8/20260920/1/D')
  await expect(page.getByText('仅 uat 存在', { exact: true })).toBeVisible()
  await expect(page.getByText('sit 缺少记录', { exact: true })).toBeVisible()
  await expect(page.getByText('仅 sit 存在', { exact: true })).toBeVisible()
  await expect(page.getByText('uat 缺少记录', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看两侧内容' }).first().click()
  await expect(page.getByText('sit 内容', { exact: true })).toBeVisible()
  await expect(page.getByText('uat 内容', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText(/旧侧|新侧|生产少|信创少/)
  await page.screenshot({ path: '../output/playwright/result-database-names.png', fullPage: true })
})
