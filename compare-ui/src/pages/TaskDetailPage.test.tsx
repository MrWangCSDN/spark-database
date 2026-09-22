import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskDetailPage } from './TaskDetailPage'
import { renderPage } from '../test/render'
import { server, task } from '../test/server'

test('shows mapped difference result and expands original values', async () => {
  const user = userEvent.setup()
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D')
  expect(await screen.findByText('值不一致')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '查看两侧内容' }))
  expect(screen.getByText('100.00|2026-09-20')).toBeVisible()
  expect(screen.getByText('101.00|2026-09-20')).toBeVisible()
})

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

test('opens a failed task at its execution information and preserves the return batch', async () => {
  server.use(http.get('/api/tasks/8/20260920/1/D', () => HttpResponse.json({ ...task, taskState: '4', failureReason: 'Spark 执行失败' })))
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D?batchId=batch-001&tab=execution')
  expect(await screen.findByRole('tab', { name: '执行信息' })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('tabpanel')).toHaveTextContent('Spark 执行失败')
  expect(screen.getByRole('link', { name: '← 返回核对批次' })).toHaveAttribute('href', '/batches/batch-001')
})

test('uses database names for missing records, contents and counts', async () => {
  server.use(http.get('/api/tasks/8/20260920/1/D/differences', () => HttpResponse.json({ items: [
    { businessKey: 'only-uat', oldContent: null, newContent: 'uat value', lineResult: '2', reason: '生产少' },
    { businessKey: 'only-sit', oldContent: 'sit value', newContent: null, lineResult: '3', reason: '信创少' }
  ], page: 0, size: 20, total: 2 })))
  const user = userEvent.setup()
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D')
  expect(await screen.findByText('仅 uat 存在')).toBeVisible()
  expect(screen.getByText('sit 缺少记录')).toBeVisible()
  expect(screen.getByText('仅 sit 存在')).toBeVisible()
  expect(screen.getByText('uat 缺少记录')).toBeVisible()
  expect(screen.getByText('sit 总量')).toBeVisible()
  expect(screen.getByText('uat 缺失或值不一致')).toBeVisible()
  await user.click(screen.getAllByRole('button', { name: '查看两侧内容' })[0])
  expect(screen.getByText('sit 内容')).toBeVisible()
  expect(screen.getByText('uat 内容')).toBeVisible()
  expect(document.body.textContent).not.toMatch(/旧侧|新侧|生产少|信创少/)
})

test('keeps results readable when database names cannot be loaded', async () => {
  server.use(http.get('/api/tasks/8/20260920/1/D/database-context', () => HttpResponse.json({}, { status: 500 })))
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D')
  expect(await screen.findByText('数据库名称读取失败，暂以数据库 A / B 标识。')).toBeVisible()
  expect(screen.getByText('值不一致')).toBeVisible()
  expect(screen.getByText('数据库 A（名称不可用） 总量')).toBeVisible()
})
