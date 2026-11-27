import { http, HttpResponse } from 'msw'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderPage } from '../test/render'
import { server } from '../test/server'
import { DailyResultDetailPage } from './DailyResultDetailPage'
import { DailyResultsPage } from './DailyResultsPage'
import { DailySampleGrid } from './DailySampleGrid'

export const daily = { bcompIndex: 1, resultDate: '20260920', runId: 'v1', taskBusiDate: '20260921', taskBatch: 'b1', timeNode: 'D', configurationName: '客户表', configurationModule: 'dept', reviserName: null, reviserUsername: null, originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat', originCount: 1000005, destinationCount: 2000005, matchedCount: 3, onlyOriginCount: 1000000, onlyDestinationCount: 2000000, differentCount: 2, detailLimit: 1000, originSamples: 1000, destinationSamples: 1000, differentSamples: 2, publishedAt: '20260922120000', detailsExpired: '0' }
function mock(expired = false, patterns: Array<{ patternId: string; fields: string[]; differentCount: number; sampleCount: number; collapsedPatternCount: number }> = [
  { patternId: 'balance', fields: ['balance'], differentCount: 1, sampleCount: 1, collapsedPatternCount: 1 },
  { patternId: 'balance-status', fields: ['balance', 'status'], differentCount: 1, sampleCount: 1, collapsedPatternCount: 1 }
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
      if (params.get('type') !== '4') expect(params.get('patternId')).toBeNull()
      if (params.get('type') === '4' && patterns.length) expect(params.get('patternId')).toBeTruthy()
      expect(params.get('size')).toBe('100')
      const fields = params.get('type') === '4'
        ? [{ name: 'balance', label: '余额', originValue: '100', destinationValue: '200' }]
        : [{ name: 'id', label: 'ID', originValue: params.get('type') === '3' ? '1' : null, destinationValue: params.get('type') === '2' ? '1' : null }, { name: 'customer_no', label: '客户号', originValue: params.get('type') === '3' ? 'C001' : null, destinationValue: params.get('type') === '2' ? 'C002' : null }, { name: 'balance', label: '余额', originValue: params.get('type') === '3' ? '100' : null, destinationValue: params.get('type') === '2' ? '200' : null }]
      return HttpResponse.json({ summary, items: expired ? [] : [{ businessKey: 'key', detail: { fields, truncated: false } }], page: 0, size: 100, total: expired ? 0 : params.get('type') === '4' ? 1 : 1000 })
    }),
    http.get('/api/results', () => HttpResponse.json({ results: { items: [daily], total: 1, page: 0, size: 20 }, connections: [{ ...daily, objectCount: 1 }] }))
  )
}
test('shows one-sided rows and the largest field pattern without per-record clicks', async () => {
  mock(); const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  expect(await screen.findByText(/实际差异 1,000,000 条 · 已保存明细 1,000 条/)).toBeVisible()
  expect(await screen.findByRole('columnheader', { name: /客户号/ })).toBeVisible()
  expect(screen.getByText('C001')).toBeVisible()
  expect(screen.queryByRole('button', { name: '查看字段' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('tab', { name: /仅 uat 存在/ }))
  expect(await screen.findByText('C002')).toBeVisible()
  await user.click(screen.getByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText('[balance、status]')).toBeVisible()
  const patternPicker = screen.getByRole('group', { name: '差异字段组合' })
  expect(within(patternPicker).getAllByText('1 行')).toHaveLength(2)
  expect(within(patternPicker).getAllByText('1 条')).toHaveLength(2)
  expect(within(patternPicker).getByRole('button', { name: /\[balance\]/ })).toHaveAttribute('aria-pressed', 'true')
  await screen.findByRole('columnheader', { name: /余额/ })
  expect(screen.getByText('sit → uat')).toBeVisible()
  const balanceCell = screen.getByRole('cell', { name: /sit 100.*uat 200/ })
  expect(within(balanceCell).getByText('100')).toBeVisible()
  expect(within(balanceCell).getByText('200')).toBeVisible()
  expect(screen.queryByRole('button', { name: '查看字段' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('tab', { name: /仅 sit 存在/ }))
  expect(await screen.findByRole('columnheader', { name: /客户号/ })).toBeVisible()
})
test('falls back to legacy samples when an old result has no field groups', async () => {
  mock(false, [])
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText(/请重新执行核对/)).toBeVisible()
  await screen.findByRole('columnheader', { name: /余额/ })
  expect(screen.getByText('sit → uat')).toBeVisible()
  expect(screen.getByRole('cell', { name: /sit 100.*uat 200/ })).toBeVisible()
})
test('paginates samples within the chosen group and resets on another group', async () => {
  mock(false, [
    { patternId: 'balance', fields: ['balance'], differentCount: 100, sampleCount: 25, collapsedPatternCount: 1 },
    { patternId: 'status', fields: ['status'], differentCount: 40, sampleCount: 2, collapsedPatternCount: 1 }
  ])
  const requests: string[] = []
  server.use(http.get('/api/results/1/20260920/differences', ({ request }) => {
    const params = new URL(request.url).searchParams
    requests.push(`${params.get('patternId')}:${params.get('page')}`)
    return HttpResponse.json({ summary: daily, items: [{ businessKey: 'key', detail: { fields: [], truncated: false } }], page: Number(params.get('page')), size: 100, total: params.get('patternId') === 'balance' ? 125 : 2 })
  }))
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  await user.click(await screen.findByRole('button', { name: '下一页' }))
  expect(await screen.findByText(/第 2 \/ 2 页/)).toBeVisible()
  await user.click(screen.getByRole('button', { name: /\[status\]/ }))
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
  expect(screen.getAllByRole('columnheader').slice(0, 2).map(header => header.textContent)).toEqual(['领域', '表 / 核对对象'])
  expect(screen.getByText('存款组')).toBeVisible()
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
test('shows collapsed patterns last without requesting their samples', async () => {
  const requested: Array<string | null> = []
  mock(false, [
    { patternId: 'balance', fields: ['balance'], differentCount: 90, sampleCount: 3, collapsedPatternCount: 1 },
    { patternId: '__OTHER__', fields: [], differentCount: 40, sampleCount: 0, collapsedPatternCount: 12 }
  ])
  server.use(http.get('/api/results/1/20260920/differences', ({ request }) => {
    requested.push(new URL(request.url).searchParams.get('patternId'))
    return HttpResponse.json({ summary: daily, items: [], page: 0, size: 100, total: 3 })
  }))
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText('其余 12 种组合，共 40 条差异，未保存样本')).toBeVisible()
  expect(screen.queryByRole('button', { name: /其余 12 种组合/ })).not.toBeInTheDocument()
  expect(requested).not.toContain('__OTHER__')
})
test('does not fall back to legacy samples when only collapsed patterns remain', async () => {
  const requested: Array<string | null> = []
  mock(false, [{ patternId: '__OTHER__', fields: [], differentCount: 40, sampleCount: 0, collapsedPatternCount: 12 }])
  server.use(http.get('/api/results/1/20260920/differences', ({ request }) => {
    requested.push(new URL(request.url).searchParams.get('patternId'))
    return HttpResponse.json({ summary: daily, items: [], page: 0, size: 100, total: 0 })
  }))
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await user.click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByText('其余 12 种组合，共 40 条差异，未保存样本')).toBeVisible()
  expect(screen.queryByText(/请重新执行核对/)).not.toBeInTheDocument()
  expect(requested).toEqual([null])
})

test('distinguishes unchanged fields from truncated values across mixed full-mode patterns', () => {
  const summary = { ...daily, detailLimit: -1 }
  renderPage(<DailySampleGrid type="4" originName="sit" destinationName="uat" samples={{
    summary, page: 0, size: 100, total: 3,
    items: [
      { businessKey: 'only-a', detail: { fields: [{ name: 'a', originValue: '1', destinationValue: '2' }], truncated: false } },
      { businessKey: 'only-b', detail: { fields: [{ name: 'b', originValue: '3', destinationValue: '4' }], truncated: false } },
      { businessKey: 'clipped', detail: { fields: [], truncated: true } },
    ],
  }} />, '/results/1/20260920')
  const first = screen.getByRole('row', { name: /only-a/ })
  const second = screen.getByRole('row', { name: /only-b/ })
  expect(within(first).getByRole('cell', { name: '无差异' })).toBeVisible()
  expect(within(second).getByRole('cell', { name: '无差异' })).toBeVisible()
  expect(within(screen.getByRole('row', { name: /clipped/ })).getAllByRole('cell', { name: '未展示（已截断）' })).toHaveLength(2)
  expect(screen.queryByText('未保存')).not.toBeInTheDocument()
})

test('browses all full-mode field differences including overflow and keeps group shortcuts', async () => {
  const full = { ...daily, detailLimit: -1, onlyOriginCount: 1, originSamples: 1, onlyDestinationCount: 1, destinationSamples: 1, differentCount: 20101, differentSamples: 20101 }
  const requests: string[] = []
  server.use(
    http.get('/api/results/1/20260920', () => HttpResponse.json(full)),
    http.get('/api/results/1/20260920/field-patterns', () => HttpResponse.json([
      { patternId: 'balance', fields: ['balance'], differentCount: 20001, sampleCount: 20001, collapsedPatternCount: 1 },
      { patternId: '__OTHER__', fields: [], differentCount: 100, sampleCount: 100, collapsedPatternCount: 130 }
    ])),
    http.get('/api/results/1/20260920/differences', ({ request }) => {
      const params = new URL(request.url).searchParams
      expect(params.get('size')).toBe('100')
      const page = Number(params.get('page')), pattern = params.get('patternId'), type = params.get('type')
      requests.push(`${type}:${pattern}:${page}`)
      return HttpResponse.json({ summary: full, items: [{ businessKey: `${type}-${pattern ?? 'all'}-${page}`, detail: { fields: [{ name: pattern ? 'balance' : 'overflow_field', label: pattern ? '余额' : '折叠组合字段', originValue: '100', destinationValue: '200' }], truncated: false } }], page, size: 100, total: type === '4' ? pattern ? 20001 : 20101 : 1 })
    })
  )
  const user = userEvent.setup()
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  expect(await screen.findByText('全量')).toBeVisible()
  expect(screen.queryByText('-1 条')).not.toBeInTheDocument()
  expect(screen.getByText(/过长字段值或过多字段仍可能截断展示/)).toBeVisible()
  await user.click(screen.getByRole('tab', { name: /主键相同、字段不同/ }))
  const all = await screen.findByRole('button', { name: /全部字段差异/ })
  expect(all).toHaveAttribute('aria-pressed', 'true')
  expect(await screen.findByRole('columnheader', { name: /折叠组合字段/ })).toBeVisible()
  expect(screen.getByRole('region', { name: '差异明细宽表' })).toBeVisible()
  expect(screen.getByText(/其余 130 种组合，共 100 条差异/)).toHaveTextContent('全部字段差异')
  expect(screen.queryByText(/未保存样本/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '下一页' }))
  expect(await screen.findByText(/第 2 \/ 202 页/)).toBeVisible()
  expect(await screen.findByText('4-all-1')).toBeVisible()
  expect(requests).toContain('4:null:0')
  expect(requests).toContain('4:null:1')
  await user.click(screen.getByRole('button', { name: /\[balance\]/ }))
  expect(await screen.findByRole('columnheader', { name: /余额/ })).toBeVisible()
  expect(await screen.findByText(/第 1 \/ 201 页/)).toBeVisible()
  expect(requests).toContain('4:balance:0')
  await user.click(screen.getByRole('button', { name: /全部字段差异/ }))
  expect(await screen.findByRole('columnheader', { name: /折叠组合字段/ })).toBeVisible()
  expect(await screen.findByText(/第 1 \/ 202 页/)).toBeVisible()
  expect(requests).not.toContain('4:__OTHER__:0')
})

test('keeps full-mode rows visible when only the overflow pattern remains', async () => {
  const full = { ...daily, detailLimit: -1, differentCount: 40, differentSamples: 40 }
  const requestedTypes: string[] = []
  server.use(
    http.get('/api/results/1/20260920', () => HttpResponse.json(full)),
    http.get('/api/results/1/20260920/field-patterns', () => HttpResponse.json([
      { patternId: '__OTHER__', fields: [], differentCount: 40, sampleCount: 40, collapsedPatternCount: 140 }
    ])),
    http.get('/api/results/1/20260920/differences', ({ request }) => {
      const params = new URL(request.url).searchParams
      requestedTypes.push(params.get('type') ?? '')
      expect(params.get('patternId')).toBeNull()
      return HttpResponse.json({ summary: full, items: [{ businessKey: 'overflow-only-key', detail: { fields: [], truncated: false } }], page: 0, size: 100, total: 40 })
    })
  )
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await userEvent.setup().click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  expect(await screen.findByRole('button', { name: /全部字段差异/ })).toHaveAttribute('aria-pressed', 'true')
  expect(await screen.findByText('overflow-only-key')).toBeVisible()
  expect(requestedTypes).toContain('4')
  expect(screen.queryByText(/请重新执行核对/)).not.toBeInTheDocument()
})

test('preserves zero-limit summary wording', async () => {
  mock()
  server.use(http.get('/api/results/1/20260920', () => HttpResponse.json({ ...daily, detailLimit: 0, originSamples: 0 })))
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  expect(await screen.findByText('每组样本上限')).toBeVisible()
  expect(screen.getByText('0 条')).toBeVisible()
  expect(screen.getByText(/其余明细未保存，汇总已包含全部差异/)).toBeVisible()
  expect(screen.queryByText(/过长字段值或过多字段仍可能截断展示/)).not.toBeInTheDocument()
})

test('uses detail wording for full-mode entries on the daily list', async () => {
  mock()
  server.use(http.get('/api/results', () => HttpResponse.json({ results: { items: [{ ...daily, detailLimit: -1 }], total: 1, page: 0, size: 20 }, connections: [{ ...daily, objectCount: 1 }] })))
  renderPage(<DailyResultsPage />, '/results?date=20260920')
  expect(await screen.findByRole('link', { name: '查看明细' })).toHaveAttribute('href', '/results/1/20260920')
  expect(screen.getByText(/明细按配置保存为限额样本或全量差异/)).toBeVisible()
})


test('describes expired full details without claiming the rows are still saved', async () => {
  const expired = { ...daily, detailLimit: -1, detailsExpired: '1', originSamples: 0, destinationSamples: 0, differentSamples: 0 }
  server.use(
    http.get('/api/results/1/20260920', () => HttpResponse.json(expired)),
    http.get('/api/results/1/20260920/differences', () => HttpResponse.json({ summary: expired, items: [], page: 0, size: 100, total: 0 }))
  )
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  expect(await screen.findByText('明细已按保留期限清理，全量汇总仍保留。')).toBeVisible()
  expect(screen.queryByText(/已保存全部差异明细/)).not.toBeInTheDocument()
  expect(screen.queryByText(/样本已按保留期限清理/)).not.toBeInTheDocument()
})

test('keeps overflow counts after full details expire without directing users to deleted rows', async () => {
  const expired = { ...daily, detailLimit: -1, detailsExpired: '1', originSamples: 0, destinationSamples: 0,
    differentSamples: 0, differentCount: 140, originCount: 1000143, destinationCount: 2000143 }
  server.use(
    http.get('/api/results/1/20260920', () => HttpResponse.json(expired)),
    http.get('/api/results/1/20260920/field-patterns', () => HttpResponse.json([
      { patternId: '__OTHER__', fields: [], differentCount: 140, sampleCount: 0, collapsedPatternCount: 140 },
    ])),
    http.get('/api/results/1/20260920/differences', () => HttpResponse.json({ summary: expired, items: [], page: 0, size: 100, total: 0 }))
  )
  renderPage(<DailyResultDetailPage />, '/results/1/20260920')
  await userEvent.setup().click(await screen.findByRole('tab', { name: /主键相同、字段不同/ }))
  const overflow = await screen.findByRole('note')
  expect(overflow).toHaveTextContent('其余 140 种组合，共 140 条差异')
  expect(overflow).toHaveTextContent('明细已按保留期限清理')
  expect(overflow).not.toHaveTextContent('请在')
})
