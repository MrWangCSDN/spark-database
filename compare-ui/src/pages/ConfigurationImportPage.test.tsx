import { http, HttpResponse } from 'msw'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ConfigurationImportPage } from './ConfigurationImportPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'

test('requires a script and replacement confirmation before importing', async () => {
  const user = userEvent.setup()
  renderPage(<ConfigurationImportPage />, '/configurations/new')

  await user.click(screen.getByRole('button', { name: '执行导入' }))
  expect(screen.getByRole('alert')).toHaveTextContent('请选择 SQL 文件或粘贴脚本')

  await user.type(screen.getByRole('textbox', { name: '粘贴 SQL' }), 'START TRANSACTION; COMMIT;')
  await user.click(screen.getByRole('button', { name: '执行导入' }))
  expect(screen.getByRole('alert')).toHaveTextContent('请确认脚本会整体替换三张配置表，并清理 7 天前的历史结果')
})

test('submits pasted SQL as multipart and shows imported row counts', async () => {
  const user = userEvent.setup()
  let contentType = ''
  server.use(http.post('/api/configurations/import', async ({ request }) => {
    contentType = request.headers.get('content-type') ?? ''
    return HttpResponse.json({ saved: true, cacheRefreshed: true, configurationCount: 2, fieldCount: 18, tableSqlCount: 2, warning: null })
  }))
  renderPage(<ConfigurationImportPage />, '/configurations/new')

  await user.type(screen.getByRole('textbox', { name: '粘贴 SQL' }), 'START TRANSACTION; COMMIT;')
  await user.click(screen.getByRole('checkbox', { name: /确认执行后会整体替换/ }))
  await user.click(screen.getByRole('button', { name: '执行导入' }))

  expect(await screen.findByText('已导入 2 个配置')).toBeVisible()
  expect(screen.getByText('18 个字段')).toBeVisible()
  expect(contentType).not.toContain('application/json')
  expect(screen.queryByRole('region', { name: '历史结果清理' })).not.toBeInTheDocument()
  expect(screen.queryByText('历史结果清理完成')).not.toBeInTheDocument()
})

test('shows committed-data warning when automatic cache refresh fails', async () => {
  const user = userEvent.setup()
  server.use(http.post('/api/configurations/import', () => HttpResponse.json({ saved: true, cacheRefreshed: false, configurationCount: 1, fieldCount: 3, tableSqlCount: 1, warning: '配置已导入，但缓存刷新失败' })))
  renderPage(<ConfigurationImportPage />, '/configurations/new')

  const file = new File(['START TRANSACTION; COMMIT;'], 'axon-config.sql', { type: 'application/sql' })
  await user.upload(screen.getByLabelText(/选择 SQL 文件/), file)
  expect(screen.getByText('axon-config.sql')).toBeVisible()
  await user.click(screen.getByRole('checkbox', { name: /确认执行后会整体替换/ }))
  await user.click(screen.getByRole('button', { name: '执行导入' }))

  expect(await screen.findByText('配置已导入，但缓存刷新失败')).toBeVisible()
})

test('only offers direct batch creation after a failed cache refresh has been successfully retried', async () => {
  server.use(http.post('/api/configurations/import', () => HttpResponse.json({ saved: true, cacheRefreshed: false, configurationCount: 200, fieldCount: 3000, tableSqlCount: 200, warning: null })))
  const user = userEvent.setup(); renderPage(<ConfigurationImportPage />, '/configurations/new')
  await user.type(screen.getByRole('textbox', { name: '粘贴 SQL' }), 'START TRANSACTION; COMMIT;')
  await user.click(screen.getByRole('checkbox', { name: /确认执行后会整体替换/ }))
  await user.click(screen.getByRole('button', { name: '执行导入' }))
  await screen.findByText('已导入 200 个配置')
  expect(screen.queryByRole('link', { name: '发起全部比对' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '重试刷新缓存' }))
  expect(await screen.findByRole('link', { name: '发起全部比对' })).toHaveAttribute('href', '/batches/new')
})

const cleanup = { retentionDays: 7, cutoff: '20261119100000', deletedResults: 4, deletedDetails: 120, deletedFieldPatterns: 8, deletedFieldCounts: 12, deletedLegacyDetails: 20 }
const imported = { saved: true, cacheRefreshed: true, configurationCount: 2, fieldCount: 18, tableSqlCount: 2, warning: null }

async function submitPastedImport() {
  const user = userEvent.setup()
  await user.type(screen.getByRole('textbox', { name: '粘贴 SQL' }), 'START TRANSACTION; COMMIT;')
  await user.click(screen.getByRole('checkbox', { name: /确认执行后会整体替换/ }))
  await user.click(screen.getByRole('button', { name: '执行导入' }))
  return user
}

test('explains seven-day publication retention and retained task history before confirmation', () => {
  renderPage(<ConfigurationImportPage />, '/configurations/new')

  expect(screen.getByRole('note')).toHaveTextContent('按发布时间清理超过 7 天的历史结果与样本')
  expect(screen.getByRole('note')).toHaveTextContent('任务和批次历史保留')
  expect(screen.getByRole('checkbox', { name: /确认执行后会整体替换/ })).toHaveAccessibleName(/按发布时间清理超过 7 天的历史结果与样本，保留任务和批次历史/)
})

test.each([true, false])('shows committed cleanup counts and cutoff when cache refresh is %s', async cacheRefreshed => {
  server.use(http.post('/api/configurations/import', () => HttpResponse.json({ ...imported, cacheRefreshed, warning: cacheRefreshed ? null : '配置已导入，但缓存刷新失败', cleanup })))
  renderPage(<ConfigurationImportPage />, '/configurations/new')
  const user = await submitPastedImport()

  await screen.findByText('已导入 2 个配置')
  const result = screen.getByRole('region', { name: '历史结果清理' })
  expect(result).toHaveTextContent('历史结果清理完成')
  expect(result).toHaveTextContent('2026-11-19 10:00:00')
  expect(result).toHaveTextContent('已清理 4 条每日结果、120 条差异明细（其中旧版明细 20 条）')
  expect(result).toHaveTextContent('字段组合统计 8 条，字段差异计数 12 条')
  expect(result).toHaveTextContent('截止时刻及之后保留')
  if (!cacheRefreshed) {
    expect(screen.getByText('配置已导入，但缓存刷新失败')).toBeVisible()
    expect(screen.queryByRole('link', { name: '发起全部比对' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试刷新缓存' }))
    expect(await screen.findByRole('link', { name: '发起全部比对' })).toBeVisible()
    expect(result).toHaveTextContent('120 条差异明细')
  }
})

test('shows a real zero-deletion cleanup response', async () => {
  server.use(http.post('/api/configurations/import', () => HttpResponse.json({ ...imported, cleanup: { ...cleanup, deletedResults: 0, deletedDetails: 0, deletedFieldPatterns: 0, deletedFieldCounts: 0, deletedLegacyDetails: 0 } })))
  renderPage(<ConfigurationImportPage />, '/configurations/new')
  await submitPastedImport()

  await screen.findByText('已导入 2 个配置')
  expect(screen.getByRole('region', { name: '历史结果清理' })).toHaveTextContent('已清理 0 条每日结果、0 条差异明细（其中旧版明细 0 条）')
})

test('invalidates configuration and all result caches after committed import and cleanup', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const keys = [['configurations', 'loan', 0], ['daily-results', '20261101', '', '', '', 0], ['daily-result', '8/20261101'], ['daily-field-patterns', '8/20261101', 'old-run'], ['daily-samples', '8/20261101', 'old-run', '3', null, 0], ['differences', '8/20261101/b1/D', 0]]
  for (const key of keys) client.setQueryData(key, { cached: true })
  client.setQueryData(['databases'], { cached: true })
  server.use(http.post('/api/configurations/import', () => HttpResponse.json({ ...imported, cacheRefreshed: false, warning: '配置已导入，但缓存刷新失败', cleanup })))
  const view = render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/configurations/new']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><ConfigurationImportPage /></MemoryRouter></QueryClientProvider>)
  try {
    await submitPastedImport()
    await screen.findByText('已导入 2 个配置')
    for (const key of keys) expect(client.getQueryState(key)?.isInvalidated).toBe(true)
    expect(client.getQueryState(['databases'])?.isInvalidated).toBe(false)
  } finally {
    view.unmount()
    client.clear()
  }
})
