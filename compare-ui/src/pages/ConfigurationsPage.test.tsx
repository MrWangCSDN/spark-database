import { http, HttpResponse } from 'msw'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigurationsPage } from './ConfigurationsPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'
import type { BasicConfiguration } from '../api/types'

test('shows configuration list loading and empty states without changing table semantics', async () => {
  server.use(http.get('/api/configurations', () => HttpResponse.json({ items: [], page: 0, size: 20, total: 0 })))
  renderPage(<ConfigurationsPage />, '/configurations')

  expect(await screen.findByText('还没有核对配置')).toBeVisible()
  expect(screen.getByRole('link', { name: '导入 Axon 配置' })).toHaveAttribute('href', '/configurations/new')
})

function SelectedDestination() { return <div data-testid="selected-state">{JSON.stringify(useLocation().state)}</div> }

test('keeps selections across configuration pages and passes every selected identifier to batch creation', async () => {
  server.use(http.get('/api/configurations', ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page'))
    return HttpResponse.json({ items: [{ bcompIndex: page ? 108 : 8, name: page ? '第二页配置' : '第一页配置', module: 'CORE', state: '1', dateMode: '1' }], page, size: 20, total: 21 })
  }))
  const user = userEvent.setup()
  renderPage(<Routes><Route path="/configurations" element={<ConfigurationsPage />} /><Route path="/batches/new" element={<SelectedDestination />} /></Routes>, '/configurations')
  await user.click(await screen.findByRole('checkbox', { name: '选择 第一页配置' }))
  await user.click(screen.getByRole('button', { name: '下一页' }))
  await user.click(await screen.findByRole('checkbox', { name: '选择 第二页配置' }))
  await user.click(screen.getByRole('button', { name: '上一页' }))
  expect(await screen.findByRole('checkbox', { name: '选择 第一页配置' })).toBeChecked()
  await user.click(screen.getByRole('button', { name: '发起所选比对' }))
  expect(await screen.findByTestId('selected-state')).toHaveTextContent('{"configurationIds":[8,108]}')
})

test('does not label an unspecified Axon date mode as cross-day', async () => {
  server.use(http.get('/api/configurations', () => HttpResponse.json({ items: [{ bcompIndex: 8, name: 'Axon 导入配置', dateMode: null }], page: 0, size: 20, total: 1 })))
  renderPage(<ConfigurationsPage />, '/configurations')
  expect(await screen.findByText('未指定')).toBeVisible()
  expect(screen.queryByText('跨日')).not.toBeInTheDocument()
})

function configuration(bcompIndex: number, name: string): BasicConfiguration {
  return { bcompIndex, name, module: 'CORE', memo: '', range: '', timeNode: 'D', state: '1', partitionNum: 1, maxPartitionNum: 64, dateMode: '1' }
}

function configurationResponses(resolve: (url: URL) => { items: BasicConfiguration[]; total: number }) {
  const requests: URL[] = []
  server.use(http.get('/api/configurations', ({ request }) => {
    const url = new URL(request.url)
    requests.push(url)
    return HttpResponse.json({ ...resolve(url), page: Number(url.searchParams.get('page')), size: 20 })
  }))
  return requests
}

function HistoryControls() {
  const navigate = useNavigate()
  return <><button onClick={() => navigate(-1)}>浏览器后退</button><button onClick={() => navigate(1)}>浏览器前进</button><button onClick={() => navigate('/configurations?keyword=cap&page=9')}>访问旧分页</button><output data-testid="location-search">{useLocation().search}</output></>
}

function currentSearch() { return new URLSearchParams(screen.getByTestId('location-search').textContent ?? '') }

test('submits a trimmed encoded keyword on Enter and finds configurations outside the loaded page', async () => {
  const requests = configurationResponses(url => url.searchParams.get('keyword') === '客户_%!&'
    ? { items: [configuration(445, '远处匹配配置')], total: 1 }
    : { items: [configuration(8, '初始配置')], total: 200 })
  const user = userEvent.setup()
  renderPage(<><HistoryControls /><ConfigurationsPage /></>, '/configurations?page=2')
  await screen.findByText('初始配置')
  const input = screen.getByRole('textbox', { name: '配置名称' })
  await user.type(input, '  客户_%!&  ')
  expect(requests).toHaveLength(1)
  await user.keyboard('{Enter}')

  expect(await screen.findByText('远处匹配配置')).toBeVisible()
  expect(screen.queryByText('初始配置')).not.toBeInTheDocument()
  expect(requests[requests.length - 1]?.searchParams.get('keyword')).toBe('客户_%!&')
  expect(requests[requests.length - 1]?.searchParams.get('page')).toBe('0')
  expect(requests[requests.length - 1]?.searchParams.get('size')).toBe('20')
  expect(currentSearch().get('keyword')).toBe('客户_%!&')
  expect(currentSearch().get('page')).toBeNull()
  expect(input).toHaveValue('客户_%!&')
})

test('uses the filtered total and keeps the keyword during pagination and refresh, then resets page on query', async () => {
  const requests = configurationResponses(url => ({ items: [configuration(100 + Number(url.searchParams.get('page')), `匹配 ${url.searchParams.get('keyword')}`)], total: 41 }))
  const user = userEvent.setup()
  renderPage(<><HistoryControls /><ConfigurationsPage /></>, '/configurations?keyword=loan&page=1')
  expect(await screen.findByText('第 2 / 3 页 · 共 41 条')).toBeVisible()
  expect(screen.getByRole('textbox', { name: '配置名称' })).toHaveValue('loan')
  await user.click(screen.getByRole('button', { name: '下一页' }))
  expect(await screen.findByText('第 3 / 3 页 · 共 41 条')).toBeVisible()
  expect(requests[requests.length - 1]?.searchParams.get('keyword')).toBe('loan')
  expect(requests[requests.length - 1]?.searchParams.get('page')).toBe('2')
  const beforeRefresh = requests.length
  await user.click(screen.getByRole('button', { name: '刷新列表' }))
  await waitFor(() => expect(requests.length).toBe(beforeRefresh + 1))
  expect(requests[requests.length - 1]?.searchParams.get('keyword')).toBe('loan')
  expect(requests[requests.length - 1]?.searchParams.get('page')).toBe('2')

  const input = screen.getByRole('textbox', { name: '配置名称' })
  await user.clear(input)
  await user.type(input, 'cust')
  await user.click(screen.getByRole('button', { name: '查询' }))
  expect(await screen.findByText('匹配 cust')).toBeVisible()
  expect(currentSearch().get('keyword')).toBe('cust')
  expect(currentSearch().get('page')).toBeNull()
  expect(requests[requests.length - 1]?.searchParams.get('page')).toBe('0')
})

test('restores URL filters, draft and results on browser back and forward', async () => {
  configurationResponses(url => ({ items: [configuration(8, `结果 ${url.searchParams.get('keyword')}`)], total: 41 }))
  const user = userEvent.setup()
  renderPage(<><HistoryControls /><ConfigurationsPage /></>, '/configurations?keyword=old&page=1')
  await screen.findByText('结果 old')
  const input = screen.getByRole('textbox', { name: '配置名称' })
  await user.clear(input)
  await user.type(input, 'new{Enter}')
  expect(await screen.findByText('结果 new')).toBeVisible()
  await user.type(input, ' unsaved')
  await user.click(screen.getByRole('button', { name: '浏览器后退' }))
  expect(await screen.findByText('结果 old')).toBeVisible()
  expect(input).toHaveValue('old')
  expect(currentSearch().get('page')).toBe('1')
  await user.click(screen.getByRole('button', { name: '浏览器前进' }))
  expect(await screen.findByText('结果 new')).toBeVisible()
  expect(input).toHaveValue('new')
  expect(currentSearch().get('page')).toBeNull()
})

test('keeps an empty search recoverable through reset', async () => {
  configurationResponses(url => url.searchParams.has('keyword') ? { items: [], total: 0 } : { items: [configuration(8, '可恢复配置')], total: 1 })
  const user = userEvent.setup()
  renderPage(<><HistoryControls /><ConfigurationsPage /></>, '/configurations?keyword=missing')
  expect(await screen.findByText('没有符合条件的配置')).toBeVisible()
  expect(screen.queryByText('还没有核对配置')).not.toBeInTheDocument()
  expect(screen.getByRole('textbox', { name: '配置名称' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '重置' }))
  expect(await screen.findByText('可恢复配置')).toBeVisible()
  expect(screen.getByRole('textbox', { name: '配置名称' })).toHaveValue('')
  expect(currentSearch().get('keyword')).toBeNull()
  expect(currentSearch().get('page')).toBeNull()
})

test('keeps a failed search recoverable through reset', async () => {
  server.use(http.get('/api/configurations', ({ request }) => new URL(request.url).searchParams.has('keyword')
    ? HttpResponse.json({ code: 'INVALID_CONFIGURATION_QUERY', message: '查询失败' }, { status: 400 })
    : HttpResponse.json({ items: [configuration(8, '恢复后的配置')], page: 0, size: 20, total: 1 })))
  const user = userEvent.setup()
  renderPage(<ConfigurationsPage />, '/configurations?keyword=broken')
  expect(await screen.findByText('核对配置加载失败')).toBeVisible()
  expect(screen.getByRole('textbox', { name: '配置名称' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '重置' }))
  expect(await screen.findByText('恢复后的配置')).toBeVisible()
})

test.each([{ total: 41, expectedPage: '2' }, { total: 0, expectedPage: null }])('replaces an obsolete page with the last valid page for total $total', async ({ total, expectedPage }) => {
  const requests = configurationResponses(url => url.searchParams.get('keyword') === 'cap'
    ? { items: Number(url.searchParams.get('page')) > 2 || !total ? [] : [configuration(88, '最后有效页')], total }
    : { items: [configuration(8, '原始位置')], total: 1 })
  const user = userEvent.setup()
  renderPage(<><HistoryControls /><ConfigurationsPage /></>, '/configurations?keyword=before')
  await screen.findByText('原始位置')
  await user.click(screen.getByRole('button', { name: '访问旧分页' }))
  await waitFor(() => expect(currentSearch().get('page')).toBe(expectedPage))
  expect(requests[requests.length - 1]?.searchParams.get('page')).toBe(expectedPage ?? '0')
  if (total) expect(await screen.findByText('最后有效页')).toBeVisible()
  else expect(await screen.findByText('没有符合条件的配置')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '浏览器后退' }))
  expect(await screen.findByText('原始位置')).toBeVisible()
  expect(currentSearch().get('keyword')).toBe('before')
})

test.each(['success', 'failure'])('does not redirect a cached empty page before a fresh request finishes with %s', async outcome => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['configurations', 'cap', 9], { items: [], page: 9, size: 20, total: 0 })
  let releaseResponse!: () => void
  const responseReady = new Promise<void>(resolve => { releaseResponse = resolve })
  let requested = false
  server.use(http.get('/api/configurations', async ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page'))
    if (page === 9) {
      requested = true
      await responseReady
      if (outcome === 'failure') return HttpResponse.json({ code: 'QUERY_FAILED', message: '查询失败' }, { status: 500 })
    }
    return HttpResponse.json({ items: [configuration(199, '新数据第十页')], page, size: 20, total: 200 })
  }))
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/configurations?keyword=cap&page=9']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><HistoryControls /><ConfigurationsPage /></MemoryRouter></QueryClientProvider>)
  try {
    await waitFor(() => expect(requested).toBe(true))
    expect(currentSearch().get('page')).toBe('9')
    releaseResponse()
    if (outcome === 'success') expect(await screen.findByText('新数据第十页')).toBeVisible()
    else expect(await screen.findByText('核对配置加载失败')).toBeVisible()
    expect(currentSearch().get('page')).toBe('9')
  } finally {
    releaseResponse()
    view.unmount()
    client.clear()
  }
})

test('preserves hidden selections across filters and reset, selecting only current-page items', async () => {
  configurationResponses(url => url.searchParams.get('keyword') === 'loan'
    ? { items: [configuration(101, 'loan_a'), configuration(102, 'loan_b')], total: 21 }
    : { items: [configuration(8, '初始已选')], total: 1 })
  const user = userEvent.setup()
  renderPage(<Routes><Route path="/configurations" element={<ConfigurationsPage />} /><Route path="/batches/new" element={<SelectedDestination />} /></Routes>, '/configurations')
  await user.click(await screen.findByRole('checkbox', { name: '选择 初始已选' }))
  await user.type(screen.getByRole('textbox', { name: '配置名称' }), 'loan{Enter}')
  await user.click(await screen.findByRole('checkbox', { name: '选择当前页所有配置' }))
  expect(screen.getByText(/跨页已选/)).toHaveTextContent('跨页已选 3 项')
  await user.click(screen.getByRole('checkbox', { name: '选择当前页所有配置' }))
  expect(screen.getByText(/跨页已选/)).toHaveTextContent('跨页已选 1 项')
  await user.click(screen.getByRole('checkbox', { name: '选择当前页所有配置' }))
  expect(screen.getByRole('link', { name: '发起全部比对' })).toHaveAttribute('href', '/batches/new')
  await user.click(screen.getByRole('button', { name: '重置' }))
  expect(await screen.findByRole('checkbox', { name: '选择 初始已选' })).toBeChecked()
  await user.click(screen.getByRole('button', { name: '发起所选比对' }))
  expect(await screen.findByTestId('selected-state')).toHaveTextContent('{"configurationIds":[8,101,102]}')
})

test('limits typed search keywords to 100 characters before requesting the server', async () => {
  const requests = configurationResponses(() => ({ items: [], total: 0 }))
  const user = userEvent.setup()
  renderPage(<ConfigurationsPage />, '/configurations')
  const input = await screen.findByRole('textbox', { name: '配置名称' })
  await user.type(input, 'x'.repeat(101))
  expect(input).toHaveValue('x'.repeat(100))
  await user.click(screen.getByRole('button', { name: '查询' }))
  await waitFor(() => expect(requests[requests.length - 1]?.searchParams.get('keyword')).toBe('x'.repeat(100)))
})

test('shows each configuration code beside its shared physical source and independent scope', async () => {
  server.use(http.get('/api/configurations', () => HttpResponse.json({ items: [
    { ...configuration(1, 'a'), sourceTableName: 'a', configurationName: '配置甲', configurationScope: 'a=1' },
    { ...configuration(2, 'a_1'), sourceTableName: 'a', configurationName: '配置乙', configurationScope: 'b=2' }
  ], page: 0, size: 20, total: 2 })))
  renderPage(<ConfigurationsPage />, '/configurations')
  expect(await screen.findByText('配置甲')).toBeVisible()
  expect(screen.getByText('配置乙')).toBeVisible()
  expect(screen.getByText('a_1')).toBeVisible()
  expect(screen.getByText('a=1')).toBeVisible()
  expect(screen.getByText('b=2')).toBeVisible()
  expect(screen.getAllByRole('columnheader').map(node => node.textContent)).toContain('真实表名')
})
