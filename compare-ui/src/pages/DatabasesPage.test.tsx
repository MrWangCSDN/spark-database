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
