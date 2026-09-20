import { http, HttpResponse } from 'msw'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SaveWarning } from './SaveWarning'
import { server } from '../test/server'

test('retries the server cache refresh without discarding the saved configuration', async () => {
  let calls = 0
  server.use(http.post('/api/cache/refresh', () => { calls += 1; return HttpResponse.json({ code: 200, msg: '刷新成功' }) }))
  const user = userEvent.setup()
  render(<SaveWarning message="配置已保存，但缓存刷新失败" />)
  await user.click(screen.getByRole('button', { name: '重试刷新缓存' }))
  expect(calls).toBe(1)
  expect(await screen.findByText('缓存已刷新')).toBeVisible()
})
