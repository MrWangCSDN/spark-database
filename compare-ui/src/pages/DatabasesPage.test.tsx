import { delay, http, HttpResponse } from 'msw'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DatabasesPage } from './DatabasesPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'

test('never prefills or submits a blank database password', async () => {
  let submitted: Record<string, unknown> = {}
  server.use(http.put('/api/databases/1', async ({ request }) => { submitted = await request.json() as Record<string, unknown>; return HttpResponse.json({ ...submitted, passwordConfigured: true }) }))
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '编辑 核心旧库' }))
  expect(screen.getByLabelText('新密码')).toHaveValue('')
  await user.click(screen.getByRole('button', { name: '保存连接' }))
  expect(submitted.newPassword).toBeNull()
})

test('disables database form actions while a save is pending', async () => {
  server.use(http.put('/api/databases/1', async ({ request }) => {
    await delay(100)
    return HttpResponse.json({ ...(await request.json() as object), passwordConfigured: true })
  }))
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '编辑 核心旧库' }))
  await user.click(screen.getByRole('button', { name: '保存连接' }))
  expect(screen.getByRole('button', { name: '正在保存' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '取消' })).toBeDisabled()
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})

test('tests a new database draft and shows product metadata without saving', async () => {
  let tested: Record<string, unknown> = {}, saveCalls = 0
  server.use(
    http.post('/api/databases/test', async ({ request }) => {
      tested = await request.json() as Record<string, unknown>
      return HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 138 })
    }),
    http.post('/api/databases', () => { saveCalls += 1; return HttpResponse.json({}, { status: 201 }) })
  )
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)

  await user.click(await screen.findByRole('button', { name: '新建连接' }))
  await user.type(screen.getByLabelText('连接编号'), '9')
  await user.type(screen.getByLabelText('连接名称'), 'GaussDB A')
  await user.type(screen.getByLabelText('JDBC URL'), 'jdbc:postgresql://db/core?currentSchema=business')
  await user.type(screen.getByLabelText('用户名'), 'reader')
  await user.type(screen.getByLabelText('新密码'), 'draft-secret')
  await user.click(screen.getByRole('button', { name: '测试连接' }))

  expect(await screen.findByText('连接成功 · GaussDB V500 · 138 ms')).toBeVisible()
  expect(tested).toMatchObject({ databaseId: 9, user: 'reader', newPassword: 'draft-secret' })
  expect(saveCalls).toBe(0)
})

test('uses the saved connection only while connection fields remain unchanged', async () => {
  let savedTests = 0, draftTests = 0
  server.use(
    http.post('/api/databases/1/test', () => {
      savedTests += 1
      return HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 22 })
    }),
    http.post('/api/databases/test', () => {
      draftTests += 1
      return HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 22 })
    })
  )
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)

  await user.click(await screen.findByRole('button', { name: '编辑 核心旧库' }))
  await user.click(screen.getByRole('button', { name: '测试连接' }))
  expect(await screen.findByText('连接成功 · GaussDB V500 · 22 ms')).toBeVisible()
  expect(savedTests).toBe(1)

  await user.type(screen.getByLabelText('JDBC URL'), '?ssl=true')
  expect(screen.queryByText('连接成功 · GaussDB V500 · 22 ms')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '测试连接' }))
  expect(await screen.findByText('测试当前连接参数时请输入密码')).toBeVisible()
  expect(draftTests).toBe(0)
})

test('clears a draft test result whenever a connection input changes', async () => {
  server.use(http.post('/api/databases/test', () => HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 18 })))
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '新建连接' }))
  await user.type(screen.getByLabelText('连接编号'), '9')
  await user.type(screen.getByLabelText('连接名称'), 'GaussDB A')
  await user.type(screen.getByLabelText('JDBC URL'), 'jdbc:postgresql://db/core?currentSchema=business')
  await user.type(screen.getByLabelText('用户名'), 'reader')
  await user.type(screen.getByLabelText('新密码'), 'secret')

  for (const [label, suffix] of [['JDBC URL', '?x=1'], ['用户名', '2'], ['驱动类', '2'], ['新密码', '2']] as const) {
    await user.click(screen.getByRole('button', { name: '测试连接' }))
    expect(await screen.findByText('连接成功 · GaussDB V500 · 18 ms')).toBeVisible()
    await user.type(screen.getByLabelText(label, { exact: false }), suffix)
    expect(screen.queryByText('连接成功 · GaussDB V500 · 18 ms')).not.toBeInTheDocument()
  }
})

test('disables test and save actions while a connection test is pending', async () => {
  server.use(http.post('/api/databases/1/test', async () => {
    await delay(100)
    return HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 100 })
  }))
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '编辑 核心旧库' }))
  await user.click(screen.getByRole('button', { name: '测试连接' }))

  expect(screen.getByRole('button', { name: '正在测试' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '保存连接' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '取消' })).toBeDisabled()
  expect(await screen.findByText('连接成功 · GaussDB V500 · 100 ms')).toBeVisible()
})

test('refreshes cache after save and preserves a refresh warning', async () => {
  let refreshCalls = 0
  server.use(
    http.put('/api/databases/1', async ({ request }) => HttpResponse.json({ ...(await request.json() as object), passwordConfigured: true })),
    http.post('/api/cache/refresh', () => {
      refreshCalls += 1
      return HttpResponse.json({ code: 500, msg: 'failed' })
    })
  )
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '编辑 核心旧库' }))
  await user.click(screen.getByRole('button', { name: '保存连接' }))

  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(refreshCalls).toBe(1)
  expect(screen.getByText('连接已保存，但缓存刷新失败')).toBeVisible()
  expect(screen.getByRole('button', { name: '重试刷新缓存' })).toBeVisible()
})

test('requires currentSchema before testing or saving a PostgreSQL-compatible connection', async () => {
  let testCalls = 0, saveCalls = 0
  server.use(
    http.post('/api/databases/test', () => { testCalls += 1; return HttpResponse.json({}) }),
    http.post('/api/databases', () => { saveCalls += 1; return HttpResponse.json({}, { status: 201 }) })
  )
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '新建连接' }))
  expect(screen.getByLabelText('JDBC URL')).toHaveAttribute('placeholder', 'jdbc:mysql://host:3306/database 或 jdbc:postgresql://host:5432/database?currentSchema=业务schema')
  await user.type(screen.getByLabelText('连接编号'), '9')
  await user.type(screen.getByLabelText('连接名称'), 'GaussDB A')
  await user.type(screen.getByLabelText('JDBC URL'), 'jdbc:postgresql://db/core')
  await user.type(screen.getByLabelText('用户名'), 'reader')
  await user.type(screen.getByLabelText('新密码'), 'secret')

  expect(screen.getByLabelText('JDBC URL')).toHaveAttribute('aria-invalid', 'true')
  expect(screen.getByRole('alert')).toHaveTextContent('currentSchema')
  await user.click(screen.getByRole('button', { name: '测试连接' }))
  await user.click(screen.getByRole('button', { name: '保存连接' }))
  expect(testCalls).toBe(0)
  expect(saveCalls).toBe(0)
})

test('does not require currentSchema for an Oracle connection', async () => {
  let saveCalls = 0
  server.use(http.post('/api/databases', () => { saveCalls += 1; return HttpResponse.json({}, { status: 201 }) }))
  const user = userEvent.setup()
  renderPage(<DatabasesPage />)
  await user.click(await screen.findByRole('button', { name: '新建连接' }))
  await user.type(screen.getByLabelText('连接编号'), '9')
  await user.type(screen.getByLabelText('连接名称'), 'Oracle')
  await user.type(screen.getByLabelText('JDBC URL'), 'jdbc:oracle:thin:@db:1521/core')
  await user.clear(screen.getByLabelText('驱动类', { exact: false }))
  await user.type(screen.getByLabelText('驱动类', { exact: false }), 'oracle.jdbc.OracleDriver')
  await user.type(screen.getByLabelText('用户名'), 'reader')
  await user.type(screen.getByLabelText('新密码'), 'secret')

  expect(screen.getByLabelText('JDBC URL')).toHaveAttribute('aria-invalid', 'false')
  await user.click(screen.getByRole('button', { name: '保存连接' }))
  await waitFor(() => expect(saveCalls).toBe(1))
})
