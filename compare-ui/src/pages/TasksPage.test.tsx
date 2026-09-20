import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TasksPage } from './TasksPage'
import { renderPage } from '../test/render'

test('opens the task drawer and validates required values', async () => {
  const user = userEvent.setup()
  renderPage(<TasksPage />)
  await user.click(await screen.findByRole('button', { name: '新建核对任务' }))
  expect(screen.getByRole('dialog', { name: '新建核对任务' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '创建任务' }))
  expect(screen.getByText('请完整填写任务信息')).toBeVisible()
})
