import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { ConfigurationsPage } from './ConfigurationsPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'

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
