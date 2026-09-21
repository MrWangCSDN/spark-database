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

test('uses database-specific saved and refreshed copy when supplied', async () => {
  server.use(http.post('/api/cache/refresh', () => HttpResponse.json({ code: 200, msg: '刷新成功' })))
  const user = userEvent.setup()
  render(<SaveWarning message="连接已保存，但缓存刷新失败" savedTitle="连接已保存" successMessage="新连接已加载到当前服务实例。" />)

  expect(screen.getByText('连接已保存')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '重试刷新缓存' }))
  expect(await screen.findByText('新连接已加载到当前服务实例。')).toBeVisible()
})

test('shows a new cache failure after an earlier retry succeeded', async () => {
  server.use(http.post('/api/cache/refresh', () => HttpResponse.json({ code: 200, msg: '刷新成功' })))
  const user = userEvent.setup()
  const view = render(<SaveWarning message="连接已保存，但缓存刷新失败" savedTitle="连接已保存" successMessage="新连接已加载到当前服务实例。" />)

  await user.click(screen.getByRole('button', { name: '重试刷新缓存' }))
  expect(await screen.findByText('新连接已加载到当前服务实例。')).toBeVisible()

  view.rerender(<SaveWarning message={null} savedTitle="连接已保存" successMessage="新连接已加载到当前服务实例。" />)
  view.rerender(<SaveWarning message="连接已保存，但缓存刷新失败" savedTitle="连接已保存" successMessage="新连接已加载到当前服务实例。" />)

  expect(screen.getByText('连接已保存，但缓存刷新失败')).toBeVisible()
  expect(screen.getByRole('button', { name: '重试刷新缓存' })).toBeVisible()
})
