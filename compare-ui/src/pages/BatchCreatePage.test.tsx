import { http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import type { BatchPreview, BatchPreviewItem, BatchRequest } from '../api/types'
import { renderPage } from '../test/render'
import { batch, server } from '../test/server'
import { BatchCreatePage } from './BatchCreatePage'

const ready: BatchPreviewItem = { bcompIndex: 8, configurationName: '客户余额核对', module: 'CORE', timeNode: 'D', verifyBusinessDate: '20260922', ready: true, issueCode: null, issueMessage: null, configurationChanged: false }
function preview(items: BatchPreviewItem[] = [ready]): BatchPreview { return { fingerprint: 'preview-1', taskBusinessDate: '20260922', verifyBusinessDate: '20260922', totalCount: items.length, readyCount: items.filter(item => item.ready).length, blockedCount: items.filter(item => !item.ready).length, changedCount: items.filter(item => item.configurationChanged).length, items } }
function Destination() { return <div data-testid="destination">{useLocation().pathname}</div> }
function renderCreate(route: Parameters<typeof renderPage>[1] = '/batches/new') {
  return renderPage(<Routes><Route path="/batches/new" element={<BatchCreatePage />} /><Route path="/batches/:batchId" element={<Destination />} /></Routes>, route)
}

test('previews ALL on the server without loading a capped configuration list, then creates one batch', async () => {
  const bodies: BatchRequest[] = []
  server.use(
    http.post('/api/batches/preview', async ({ request }) => { bodies.push(await request.json() as BatchRequest); return HttpResponse.json(preview(Array.from({ length: 200 }, (_, index) => ({ ...ready, bcompIndex: index + 1, configurationName: `表 ${index + 1}` })))) }),
    http.post('/api/batches', async ({ request }) => { bodies.push(await request.json() as BatchRequest); return HttpResponse.json(batch, { status: 201 }) })
  )
  const user = userEvent.setup(); renderCreate()
  await user.clear(screen.getByLabelText('任务业务日期')); await user.type(screen.getByLabelText('任务业务日期'), '20260922')
  await user.click(screen.getByRole('button', { name: '预检配置' }))
  expect(await screen.findByText(/可创建 200 项/)).toBeVisible()
  await user.click(screen.getByRole('button', { name: '确认发起' }))
  expect(await screen.findByTestId('destination')).toHaveTextContent(`/batches/${batch.batchId}`)
  expect(bodies[0]).toEqual({ scope: 'ALL', taskBusinessDate: '20260922', verifyBusinessDate: '20260922' })
  expect(bodies[1]).toMatchObject({ ...bodies[0], previewFingerprint: 'preview-1' })
  expect(bodies[1].requestId).toMatch(/^[a-f\d-]{36}$/)
})

test('blocks creation until the user explicitly excludes problem entries and previews again', async () => {
  const bodies: BatchRequest[] = []
  const bad = { ...ready, bcompIndex: 9, configurationName: '无主键表', ready: false, issueCode: 'BUSINESS_KEY_REQUIRED', issueMessage: '请设置核对主键' }
  server.use(http.post('/api/batches/preview', async ({ request }) => { const body = await request.json() as BatchRequest; bodies.push(body); return HttpResponse.json(preview(body.excludedIds?.includes(9) ? [ready] : [ready, bad])) }))
  const user = userEvent.setup(); renderCreate()
  await user.click(screen.getByRole('button', { name: '预检配置' }))
  expect(await screen.findByText('请设置核对主键')).toBeVisible()
  expect(screen.getByRole('button', { name: '确认发起' })).toBeDisabled()
  await user.click(screen.getByRole('checkbox', { name: '排除 无主键表' }))
  await user.click(screen.getByRole('button', { name: '排除勾选的 1 项并重新预检' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '确认发起' })).toBeEnabled())
  expect(bodies).toHaveLength(2); expect(bodies[1].excludedIds).toEqual([9])
  expect(screen.getByText('已明确排除 1 项：#9')).toBeVisible()
})

test('preserves the request identity on a network failure and prevents duplicate clicks during submission', async () => {
  const bodies: BatchRequest[] = []; let finish!: () => void
  server.use(
    http.post('/api/batches/preview', () => HttpResponse.json(preview())),
    http.post('/api/batches', async ({ request }) => { bodies.push(await request.json() as BatchRequest); if (bodies.length === 1) return HttpResponse.error(); await new Promise<void>(resolve => { finish = resolve }); return HttpResponse.json(batch) })
  )
  const user = userEvent.setup(); renderCreate()
  await user.click(screen.getByRole('button', { name: '预检配置' }))
  await user.click(await screen.findByRole('button', { name: '确认发起' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('本次请求标识已保留')
  await user.dblClick(screen.getByRole('button', { name: '确认发起' }))
  await waitFor(() => expect(bodies).toHaveLength(2))
  expect(screen.getByRole('button', { name: '正在创建批次…' })).toBeDisabled()
  expect(bodies[1]).toEqual(bodies[0]); finish()
  expect(await screen.findByTestId('destination')).toBeVisible()
})

test('requires a new preview after dates change or the server declares a stale preview', async () => {
  server.use(
    http.post('/api/batches/preview', () => HttpResponse.json(preview())),
    http.post('/api/batches', () => HttpResponse.json({ code: 'BATCH_PREVIEW_STALE', message: '配置已发生变化' }, { status: 409 }))
  )
  const user = userEvent.setup(); renderCreate()
  await user.click(screen.getByRole('button', { name: '预检配置' }))
  await user.click(await screen.findByRole('button', { name: '确认发起' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('请重新预检')
  expect(screen.getByRole('button', { name: '确认发起' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '重新预检' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '确认发起' })).toBeEnabled())
  await user.type(screen.getByLabelText('统一时点（可选）'), 'N')
  expect(screen.queryByRole('button', { name: '确认发起' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '预检配置' })).toBeVisible()
})

test('retries failed entries using server-retained dates and time nodes and warns about changed configuration', async () => {
  const bodies: BatchRequest[] = []
  server.use(
    http.post('/api/batches/preview', async ({ request }) => { bodies.push(await request.json() as BatchRequest); return HttpResponse.json(preview([{ ...ready, verifyBusinessDate: '20260919', timeNode: 'N', configurationChanged: true }])) }),
    http.post('/api/batches', async ({ request }) => { bodies.push(await request.json() as BatchRequest); return HttpResponse.json(batch) })
  )
  const user = userEvent.setup(); renderCreate('/batches/new?sourceBatchId=original-001')
  expect(screen.queryByLabelText('任务业务日期')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '预检配置' }))
  expect(await screen.findByText('1 项配置已发生变化')).toBeVisible()
  expect(screen.getByText('20260919')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '确认发起' }))
  await screen.findByTestId('destination')
  expect(bodies[0]).toEqual({ scope: 'FAILED', sourceBatchId: 'original-001' })
  expect(bodies[1]).not.toHaveProperty('taskBusinessDate'); expect(bodies[1]).not.toHaveProperty('verifyBusinessDate'); expect(bodies[1]).not.toHaveProperty('timeNode')
})

test('submits all selected identifiers without limiting the set to one configuration page', async () => {
  let submitted: BatchRequest | undefined
  server.use(http.post('/api/batches/preview', async ({ request }) => { submitted = await request.json() as BatchRequest; return HttpResponse.json(preview()) }))
  const user = userEvent.setup(); renderCreate({ pathname: '/batches/new', search: '?scope=SELECTED', state: { configurationIds: [108, 8, 108] } })
  expect(screen.getByText('已选 2 项配置')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '预检配置' }))
  await screen.findByRole('button', { name: '确认发起' })
  expect(submitted).toMatchObject({ scope: 'SELECTED', configurationIds: [8, 108] })
})
