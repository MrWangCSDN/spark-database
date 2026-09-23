import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderPage } from '../test/render'
import { batch, server, task } from '../test/server'
import { BatchDetailPage } from './BatchDetailPage'

function renderDetail() { renderPage(<Routes><Route path="/batches/:batchId" element={<BatchDetailPage />} /></Routes>, `/batches/${batch.batchId}`) }

test('uses ended-task progress and separates differences from failed execution with status filters', async () => {
  const requests: URL[] = []
  server.use(http.get('/api/batches/:batchId/tasks', ({ request }) => { const url = new URL(request.url); requests.push(url); return HttpResponse.json({ items: [{ ...task, taskState: '4', taskResult: '1' }], page: Number(url.searchParams.get('page')), size: 20, total: 21 }) }))
  const user = userEvent.setup(); renderDetail()
  expect(await screen.findByRole('progressbar')).toHaveAttribute('value', '150')
  expect(screen.getByRole('progressbar')).toHaveAttribute('max', '200')
  expect(screen.getByRole('button', { name: '有差异 6' })).toBeVisible()
  expect(screen.getByRole('link', { name: '重新发起失败项（2）' })).toHaveAttribute('href', `/batches/new?sourceBatchId=${batch.batchId}`)
  await user.click(screen.getByRole('button', { name: '下一页' }))
  await waitFor(() => expect(requests.at(-1)?.searchParams.get('page')).toBe('1'))
  await user.click(screen.getByRole('button', { name: '执行失败 2' }))
  await waitFor(() => expect(requests.at(-1)?.searchParams.get('status')).toBe('FAILED'))
  expect(requests.at(-1)?.searchParams.get('page')).toBe('0')
  expect(screen.getByRole('link', { name: '查看原因' })).toHaveAttribute('href', `/tasks/8/20260920/1/D?batchId=${batch.batchId}&tab=execution`)
})

test('does not offer retry when a batch contains differences but no failed tasks', async () => {
  server.use(http.get('/api/batches/:batchId', () => HttpResponse.json({ ...batch, failedCount: 0 })))
  renderDetail(); await screen.findByRole('progressbar')
  expect(screen.queryByRole('link', { name: /重新发起失败项/ })).not.toBeInTheDocument()
})

test('stops only after confirmation and shows cancelled tasks', async () => {
  let stopped = false
  server.use(
    http.get('/api/batches/:batchId', () => HttpResponse.json(stopped ? { ...batch, waitingCount: 0, runningCount: 0, cancelledCount: 50, endedCount: 200 } : batch)),
    http.post('/api/batches/:batchId/cancel', () => { stopped = true; return HttpResponse.json({ ...batch, waitingCount: 0, runningCount: 0, cancelledCount: 50, endedCount: 200 }) })
  )
  const user = userEvent.setup(); renderDetail()
  await user.click(await screen.findByRole('button', { name: '停止批次' }))
  expect(stopped).toBe(false)
  expect(screen.getByText('等待和运行中的任务将取消；已完成结果保留，其他批次继续执行。')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '确认停止' }))
  await waitFor(() => expect(stopped).toBe(true))
  await waitFor(() => expect(screen.getByRole('button', { name: '已取消 50' })).toBeVisible())
  expect(screen.queryByRole('button', { name: '停止批次' })).not.toBeInTheDocument()
})

test('keeps missing historical entries visible without linking to a nonexistent task', async () => {
  server.use(http.get('/api/batches/:batchId/tasks', () => HttpResponse.json({ items: [{ ...task, taskState: null }], page: 0, size: 20, total: 1 })))
  renderDetail()
  expect(await screen.findByText('记录缺失')).toBeVisible()
  expect(screen.getByText('请核查任务记录')).toBeVisible()
  expect(screen.queryByRole('link', { name: '查看任务' })).not.toBeInTheDocument()
})
