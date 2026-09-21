import { render, screen } from '@testing-library/react'
import { StatusBadge } from './StatusBadge'

test.each([
  ['3', '2', '一致'],
  ['3', '1', '有差异'],
  ['4', '1', '执行失败']
])('maps task state %s and result %s to %s', (taskState, value, label) => {
  render(<StatusBadge kind="result" taskState={taskState} value={value} />)

  expect(screen.getByText(label)).toBeVisible()
})
