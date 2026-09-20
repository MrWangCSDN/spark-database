import { Fragment, useState } from 'react'
import { useLocation, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, query } from '../api/client'
import type { DifferenceSummary, PageResult, TaskSummary } from '../api/types'
import { StatusBadge, resultLabel } from '../components/StatusBadge'
import { Pagination } from '../components/Pagination'
import { formatTime } from './TasksPage'

export function TaskDetailPage() {
  const parts = useLocation().pathname.split('/').filter(Boolean), identity = parts.slice(1, 5).map(encodeURIComponent).join('/')
  const [page, setPage] = useState(0), [expanded, setExpanded] = useState<string | null>(null)
  const task = useQuery({ queryKey: ['task', identity], queryFn: () => api<TaskSummary>(`/api/tasks/${identity}`) })
  const differences = useQuery({ queryKey: ['differences', identity, page], queryFn: () => api<PageResult<DifferenceSummary>>(query(`/api/tasks/${identity}/differences`, { page, size: 20 })) })
  return <div className="page"><Link className="back-link" to="/tasks">← 返回任务列表</Link><div className="page-heading compact"><div><span className="eyebrow">TASK DETAIL</span><h1>{task.data?.configurationName ?? '任务详情'}</h1><p>{task.data ? `#${task.data.bcompIndex} · ${task.data.taskBusinessDate} · 批次 ${task.data.taskBatch}` : '正在读取任务…'}</p></div><div className="heading-status"><StatusBadge value={task.data?.taskState} /><StatusBadge value={task.data?.taskResult} kind="result" /></div></div>
    {task.data && <section className="facts"><div><span>创建时间</span><strong>{formatTime(task.data.createdAt)}</strong></div><div><span>开始时间</span><strong>{formatTime(task.data.startedAt)}</strong></div><div><span>结束时间</span><strong>{formatTime(task.data.endedAt)}</strong></div><div><span>旧 / 新侧总量</span><strong>{task.data.file1Count ?? '—'} / {task.data.file2Count ?? '—'}</strong></div></section>}
    <section className="section-block"><div className="section-heading"><div><h2>差异明细</h2><p>业务键、差异类型以及两侧原始拼接内容。</p></div><span>{differences.data?.total ?? 0} 条</span></div><div className="table-wrap"><table><thead><tr><th>业务键</th><th>差异类型</th><th>原因</th><th></th></tr></thead><tbody>{differences.data?.items.map(item => <Fragment key={item.businessKey}>
      <tr><td className="mono">{item.businessKey}</td><td><span className="result-code">{resultLabel(item.lineResult)}</span></td><td>{item.reason || '—'}</td><td><button className="text-button" aria-expanded={expanded === item.businessKey} onClick={() => setExpanded(expanded === item.businessKey ? null : item.businessKey)}>查看两侧内容</button></td></tr>
      {expanded === item.businessKey && <tr className="expanded-row"><td colSpan={4}><div className="diff-grid"><div><span>旧侧内容</span><pre>{item.oldContent || '（空）'}</pre></div><div><span>新侧内容</span><pre>{item.newContent || '（空）'}</pre></div></div></td></tr>}
    </Fragment>)}</tbody></table>{differences.data?.total === 0 && <div className="empty">没有差异数据</div>}</div>{differences.data && <Pagination page={page} size={20} total={differences.data.total} onChange={setPage} />}</section>
  </div>
}
