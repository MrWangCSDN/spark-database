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
    if (url.pathname === '/api/configurations') return route.fulfill({ json: { items: [{ bcompIndex: 8, name: '客户余额核对', module: 'CORE', memo: '', range: '', timeNode: 'D', state: '1', partitionNum: 8, dateMode: '1' }], page: 0, size: 100, total: 1 } })
    if (url.pathname === '/api/tasks' && route.request().method() === 'POST') { created = true; return route.fulfill({ status: 201, json: task }) }
    if (url.pathname === '/api/tasks') return route.fulfill({ json: { items: [task], page: 0, size: 20, total: 1 } })
    if (url.pathname.endsWith('/differences')) return route.fulfill({ json: { items: [{ businessKey: 'C001', oldContent: '100.00|2026-09-20', newContent: '101.00|2026-09-20', lineResult: '3', reason: '字段值不一致' }], page: 0, size: 20, total: 1 } })
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
    if (url.pathname === '/api/dashboard') return route.fulfill({ json: { serviceStatus: '运行中', runningCount: 0, todayCompletedCount: 6, todayDifferenceCount: 1, updatedAt: '20260921100000', recentTasks: [failed] } })
    if (url.pathname === '/api/tasks') return route.fulfill({ json: { items: [failed], page: 0, size: 20, total: 1 } })
    if (url.pathname === '/api/tasks/8/20260920/1/D') return route.fulfill({ json: failed })
    if (url.pathname.endsWith('/differences')) return route.fulfill({ json: { items: [], page: 0, size: 20, total: 0 } })
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'not found', traceId: 'e2e' } })
  })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '运行总览' })).toBeVisible()
  await page.getByRole('link', { name: /核对任务/ }).click()
  await page.getByLabel('任务状态').selectOption('4')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page).toHaveURL(/taskState=4/)
  await page.getByRole('link', { name: '查看' }).first().click()
  await expect(page.getByRole('tab', { name: '差异明细' })).toBeVisible()
  await page.getByRole('tab', { name: '执行信息' }).click()
  await expect(page.getByRole('tabpanel')).toContainText('Spark 任务退出')
})
