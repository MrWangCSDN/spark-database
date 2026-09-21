import { render, screen } from '@testing-library/react'
import { ContentState } from './ContentState'
import { PageHeader } from './PageHeader'

test('renders title metadata and actions as one labelled page header', () => {
  render(<PageHeader eyebrow="OVERVIEW" title="运行总览" description="运行状态" meta="更新于 12:00" actions={<button>刷新</button>} />)

  expect(screen.getByRole('heading', { name: '运行总览' })).toBeVisible()
  expect(screen.getByText('更新于 12:00')).toBeVisible()
  expect(screen.getByRole('button', { name: '刷新' })).toBeVisible()
})

test('announces failed content state', () => {
  render(<ContentState state="error" title="任务加载失败" description="请稍后重试" />)

  expect(screen.getByRole('alert')).toHaveTextContent('任务加载失败')
})
