import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderPage } from '../test/render'
import { AppShell } from './AppShell'

function renderShell() {
  return renderPage(<Routes><Route element={<AppShell />}><Route index element={<div>内容</div>} /></Route></Routes>)
}

test('groups management and configuration navigation', () => {
  renderShell()

  expect(screen.getByText('管理视图')).toBeVisible()
  expect(screen.getByText('基础配置')).toBeVisible()
  expect(screen.getByRole('link', { name: /运行总览/ })).toHaveAttribute('href', '/')
  expect(screen.getByRole('link', { name: /数据库连接/ })).toHaveAttribute('href', '/databases')
})

test('opens and closes mobile navigation with accessible labels', async () => {
  const user = userEvent.setup()
  renderShell()

  await user.click(screen.getByRole('button', { name: '打开导航' }))
  expect(screen.getByRole('navigation', { name: '主导航' })).toHaveClass('open')
  await user.click(screen.getByRole('button', { name: '关闭导航' }))
  expect(screen.getByRole('navigation', { name: '主导航' })).not.toHaveClass('open')
})
