import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { DashboardSummary } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { formatTime } from './TasksPage'

export function DashboardPage() {
  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: () => api<DashboardSummary>('/api/dashboard'), refetchInterval: value => document.visibilityState === 'visible' && (value.state.data?.runningCount ?? 0) > 0 ? 10000 : false })
  const data = dashboard.data
  return <div className="page"><div className="page-heading"><div><span className="eyebrow">OVERVIEW</span><h1>运行总览</h1><p>当前服务状态、当日执行量和最近核对任务。</p></div><Link className="button primary" to="/tasks">查看全部任务</Link></div>
    <section className="metric-strip"><div><span>服务状态</span><strong className="service-state"><i />{data?.serviceStatus ?? '读取中'}</strong></div><div><span>运行中</span><strong>{data?.runningCount ?? '—'}</strong></div><div><span>今日完成</span><strong>{data?.todayCompletedCount ?? '—'}</strong></div><div><span>今日差异</span><strong>{data?.todayDifferenceCount ?? '—'}</strong></div></section>
    <section className="section-block"><div className="section-heading"><div><h2>最近任务</h2><p>按创建时间显示最近的数据库核对运行。</p></div><small>更新于 {formatTime(data?.updatedAt)}</small></div><div className="table-wrap"><table><thead><tr><th>配置</th><th>业务日期</th><th>状态</th><th>结果</th><th></th></tr></thead><tbody>{data?.recentTasks.map(item => <tr key={`${item.bcompIndex}-${item.taskBusinessDate}-${item.taskBatch}`}><td><strong>{item.configurationName}</strong><small>#{item.bcompIndex}</small></td><td>{item.taskBusinessDate}<small>批次 {item.taskBatch}</small></td><td><StatusBadge value={item.taskState} /></td><td><StatusBadge value={item.taskResult} kind="result" /></td><td><Link className="row-link" to={`/tasks/${item.bcompIndex}/${item.taskBusinessDate}/${item.taskBatch}/${item.timeNode}`}>查看</Link></td></tr>)}</tbody></table></div></section>
  </div>
}
