import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
  expect(screen.getByRole('alert')).toHaveTextContent('请确认脚本会整体替换三张配置表')
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
