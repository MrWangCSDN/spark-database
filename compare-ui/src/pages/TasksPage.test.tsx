import { http, HttpResponse } from 'msw'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TasksPage } from './TasksPage'
import { renderPage } from '../test/render'
import { server } from '../test/server'

test('opens the task drawer and validates required values', async () => {
  const user = userEvent.setup()
  renderPage(<TasksPage />)
  await user.click(await screen.findByRole('button', { name: '新建核对任务' }))
  expect(screen.getByRole('dialog', { name: '新建核对任务' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '创建任务' }))
  expect(screen.getByText('请完整填写任务信息')).toBeVisible()
})

test('loads active URL filters and sends them to the task API', async () => {
  let requested = ''
  server.use(http.get('/api/tasks', ({ request }) => { requested = request.url; return HttpResponse.json({ items: [], page: 0, size: 20, total: 0 }) }))
  renderPage(<TasksPage />, '/tasks?taskState=4&keyword=A%26B&sort=endedAt%2Cdesc')

  await screen.findByText('没有符合条件的任务')
  expect(requested).toContain('taskState=4')
  expect(requested).toContain('keyword=A%26B')
  expect(requested).toContain('sort=endedAt%2Cdesc')
})

test('applying a filter resets the page and keeps it in the URL', async () => {
  const user = userEvent.setup()
  renderPage(<TasksPage />, '/tasks?page=3')

  await user.selectOptions(await screen.findByLabelText('任务状态'), '2')
  await user.click(screen.getByRole('button', { name: '查询' }))
  expect(screen.getByTestId('task-search')).toHaveTextContent('taskState=2')
  expect(screen.getByTestId('task-search')).not.toHaveTextContent('page=3')
})
