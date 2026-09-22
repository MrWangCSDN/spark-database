import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DashboardPage } from './DashboardPage'
import { renderPage } from '../test/render'
import { server, batch } from '../test/server'

const dashboard = {
  serviceStatus: '运行中',
  runningCount: 0,
  todayCompletedCount: 1,
  todayDifferenceCount: 0,
  recentTasks: [],
  updatedAt: '20260920120000'
}

test('shows actual scheduler settings and batch results instead of an assumed healthy scheduler', async () => {
  server.use(http.get('/api/batches/runtime', () => HttpResponse.json({ tasksEnabled: false, concurrency: 2, maxBatchSize: 5000 })))
  renderPage(<DashboardPage />)
  expect(screen.getByRole('heading', { name: '核对工作台' })).toBeVisible()
  expect(await screen.findByText('未启用')).toBeVisible()
  expect(screen.getByLabelText('任务调度状态')).toHaveTextContent('执行并发2')
  expect(await screen.findByRole('link', { name: batch.batchId })).toHaveAttribute('href', `/batches/${batch.batchId}`)
  expect(screen.getByRole('region', { name: '最近批次' })).toHaveTextContent('执行失败')
  expect(screen.getByRole('link', { name: '发起全部比对' })).toHaveAttribute('href', '/batches/new')
})

test('shows a stable error state when scheduler settings cannot be read', async () => {
  server.use(http.get('/api/batches/runtime', () => HttpResponse.json({}, { status: 500 })))
  renderPage(<DashboardPage />)
  expect(await screen.findByRole('alert')).toHaveTextContent('调度状态读取失败')
})

test('refreshes the configuration cache once and disables the action while loading', async () => {
  let calls = 0
  let complete!: () => void
  const pending = new Promise<void>(resolve => { complete = resolve })
  server.use(
    http.get('/api/dashboard', () => HttpResponse.json(dashboard)),
    http.post('/api/cache/refresh', async () => {
      calls += 1
      await pending
      return HttpResponse.json({ code: 200, msg: '刷新成功' })
    })
  )
  const user = userEvent.setup()
  renderPage(<DashboardPage />)

  const button = await screen.findByRole('button', { name: '刷新配置缓存' })
  await user.click(button)
  expect(button).toBeDisabled()
  expect(button).toHaveTextContent('刷新中…')
  complete()

  expect(await screen.findByText('缓存已刷新')).toBeVisible()
  expect(calls).toBe(1)
})

test('shows the server message when cache refresh returns a business failure', async () => {
  server.use(
    http.get('/api/dashboard', () => HttpResponse.json(dashboard)),
    http.post('/api/cache/refresh', () => HttpResponse.json({ code: 500, msg: '当前存在运行中任务' }))
  )
  const user = userEvent.setup()
  renderPage(<DashboardPage />)

  await user.click(await screen.findByRole('button', { name: '刷新配置缓存' }))

  expect(await screen.findByText('当前存在运行中任务')).toBeVisible()
  expect(screen.queryByText('缓存已刷新')).not.toBeInTheDocument()
})

test('shows a retryable message when cache refresh has a network failure', async () => {
  server.use(
    http.get('/api/dashboard', () => HttpResponse.json(dashboard)),
    http.post('/api/cache/refresh', () => HttpResponse.error())
  )
  const user = userEvent.setup()
  renderPage(<DashboardPage />)

  await user.click(await screen.findByRole('button', { name: '刷新配置缓存' }))

  expect(await screen.findByText('网络异常，缓存刷新失败，请重试')).toBeVisible()
  expect(screen.getByRole('button', { name: '重试刷新配置缓存' })).toBeEnabled()
})
