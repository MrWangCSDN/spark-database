import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskDetailPage } from './TaskDetailPage'
import { renderPage } from '../test/render'

test('shows mapped difference result and expands original values', async () => {
  const user = userEvent.setup()
  renderPage(<TaskDetailPage />, '/tasks/8/20260920/1/D')
  expect(await screen.findByText('值不一致')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '查看两侧内容' }))
  expect(screen.getByText('100.00|2026-09-20')).toBeVisible()
  expect(screen.getByText('101.00|2026-09-20')).toBeVisible()
})
