import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
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
