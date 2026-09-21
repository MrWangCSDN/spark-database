import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigurationEditorPage } from './ConfigurationEditorPage'
import { renderPage } from '../test/render'

test('shows cache refresh warning after configuration is saved', async () => {
  const user = userEvent.setup()
  renderPage(<ConfigurationEditorPage />, '/configurations/8')
  await user.click(await screen.findByRole('button', { name: '保存配置' }))
  expect(await screen.findByText('配置已保存，但缓存刷新失败')).toBeVisible()
})

test('labels configuration editor sections for direct navigation', async () => {
  renderPage(<ConfigurationEditorPage />, '/configurations/8')

  expect(await screen.findByRole('region', { name: '基本信息' })).toBeVisible()
  expect(screen.getByRole('region', { name: '两侧查询' })).toBeVisible()
  expect(screen.getByRole('region', { name: '字段规则' })).toBeVisible()
})
