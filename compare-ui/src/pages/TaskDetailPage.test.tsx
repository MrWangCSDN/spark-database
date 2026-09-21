import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskDetailPage } from './TaskDetailPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'

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
