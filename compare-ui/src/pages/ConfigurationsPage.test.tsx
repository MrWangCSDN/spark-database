import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import { ConfigurationsPage } from './ConfigurationsPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'

test('shows configuration list loading and empty states without changing table semantics', async () => {
  server.use(http.get('/api/configurations', () => HttpResponse.json({ items: [], page: 0, size: 20, total: 0 })))
  renderPage(<ConfigurationsPage />, '/configurations')

  expect(await screen.findByText('还没有核对配置')).toBeVisible()
  expect(screen.getByRole('link', { name: '导入 Axon 配置' })).toHaveAttribute('href', '/configurations/new')
})
