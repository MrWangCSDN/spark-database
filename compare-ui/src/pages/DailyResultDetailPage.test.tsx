import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderPage } from '../test/render'
import { server } from '../test/server'
import { DailyResultDetailPage } from './DailyResultDetailPage'
import { DailyResultsPage } from './DailyResultsPage'

export const daily = { bcompIndex: 1, resultDate: '20260920', runId: 'v1', taskBusiDate: '20260921', taskBatch: 'b1', timeNode: 'D', configurationName: '客户表', originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat', originCount: 1000005, destinationCount: 2000005, matchedCount: 3, onlyOriginCount: 1000000, onlyDestinationCount: 2000000, differentCount: 2, detailLimit: 1000, originSamples: 1000, destinationSamples: 1000, differentSamples: 2, publishedAt: '20260922120000', detailsExpired: '0' }
function mock(expired = false, patterns: Array<{ patternId: string; fields: string[]; differentCount: number; sampleCount: number }> = [
  { patternId: 'balance', fields: ['balance'], differentCount: 1, sampleCount: 1 },
  { patternId: 'balance-status', fields: ['balance', 'status'], differentCount: 1, sampleCount: 1 }
]) {
  const summary = expired ? { ...daily, detailsExpired: '1', originSamples: 0 } : daily
  server.use(
    http.get('/api/results/1/20260920', () => HttpResponse.json(summary)),
    http.get('/api/results/1/20260920/field-patterns', ({ request }) => {
      expect(new URL(request.url).searchParams.get('version')).toBe('v1')
      return HttpResponse.json(patterns)
    }),
    http.get('/api/results/1/20260920/differences', ({ request }) => {
      const params = new URL(request.url).searchParams
      expect(params.get('version')).toBe('v1')
      if (params.get('type') === '4' && patterns.length) expect(params.get('patternId')).toBeTruthy()
      return HttpResponse.json({ summary, items: expired ? [] : [{ businessKey: 'key', detail: { fields: [{ name: 'balance', originValue: '100', destinationValue: '200' }], truncated: false } }], page: 0, size: 20, total: expired ? 0 : params.get('type') === '4' ? 1 : 1000 })
    }),
    http.get('/api/results', () => HttpResponse.json({ results: { items: [daily], total: 1, page: 0, size: 20 }, connections: [{ ...daily, objectCount: 1 }] }))
  )
}
test('distinguishes full counts and saved samples and expands different fields', async () => {
  mock(); const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  expect(await screen.findByText(/实际差异 1,000,000 条 · 已保存明细 1,000 条/)).toBeVisible()
  await user.click(screen.getByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText('[balance、status]')).toBeVisible()
  expect(screen.getAllByText('1 行')).toHaveLength(2)
  await user.click(screen.getAllByRole('button', { name: '查看样本' })[0])
  await user.click(await screen.findByRole('button', { name: '查看字段' }))
  expect(screen.getByText('sit 的值')).toBeVisible(); expect(screen.getByText('uat 的值')).toBeVisible()
  expect(screen.getByText('100', { exact: true })).toBeVisible(); expect(screen.getByText('200', { exact: true })).toBeVisible()
})
test('falls back to legacy samples when an old result has no field groups', async () => {
  mock(false, [])
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText(/请重新执行核对/)).toBeVisible()
  expect(await screen.findByRole('button', { name: '查看字段' })).toBeVisible()
})
test('paginates samples within the chosen group and resets on another group', async () => {
  mock(false, [
    { patternId: 'balance', fields: ['balance'], differentCount: 100, sampleCount: 25 },
    { patternId: 'status', fields: ['status'], differentCount: 40, sampleCount: 2 }
  ])
  const requests: string[] = []
  server.use(http.get('/api/results/1/20260920/differences', ({ request }) => {
    const params = new URL(request.url).searchParams
    requests.push(`${params.get('patternId')}:${params.get('page')}`)
    return HttpResponse.json({ summary: daily, items: [{ businessKey: 'key', detail: { fields: [], truncated: false } }], page: Number(params.get('page')), size: 20, total: params.get('patternId') === 'balance' ? 25 : 2 })
  }))
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  await user.click((await screen.findAllByRole('button', { name: '查看样本' }))[0])
  await user.click(await screen.findByRole('button', { name: '下一页' }))
  expect(await screen.findByText(/第 2 \/ 2 页/)).toBeVisible()
  await user.click(screen.getAllByRole('button', { name: '查看样本' })[1])
  expect(await screen.findByText(/第 1 \/ 1 页/)).toBeVisible()
  expect(requests).toContain('balance:1')
  expect(requests).toContain('status:0')
})
test('marks replaced task results and expired samples without erasing counts', async () => {
  mock(true)
  renderPage(<DailyResultDetailPage />, '/results/1/20260920?sourceBatch=old&sourceDate=20260920')
  expect(await screen.findByText(/原任务的明细已被/)).toBeVisible()
  expect(screen.getByText(/样本已按保留期限清理/)).toBeVisible()
  expect(screen.getByText(/实际差异 1,000,000 条 · 已保存明细 0 条/)).toBeVisible()
})
test('shows daily table totals with named connections', async () => {
  mock()
  renderPage(<DailyResultsPage />, '/results?date=20260920')
  expect(await screen.findByText('客户表')).toBeVisible()
  expect(screen.getByText('sit 总量')).toBeVisible()
  expect(screen.getByText('uat 总量')).toBeVisible()
  expect(screen.getByText('1,000,005')).toBeVisible()
  expect(screen.getByRole('link', { name: '查看样本' })).toHaveAttribute('href', '/results/1/20260920')
})
test('reports a publication replaced during sample loading', async () => {
  mock()
  server.use(http.get('/api/results/1/20260920/differences', () => HttpResponse.json({ code: 'RESULT_REPLACED', message: '当天结果已更新，请刷新后查看' }, { status: 409 })))
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  expect(await screen.findByText('当天结果已更新，请刷新后查看')).toBeVisible()
  expect(screen.getByRole('button', { name: '刷新结果' })).toBeVisible()
})
