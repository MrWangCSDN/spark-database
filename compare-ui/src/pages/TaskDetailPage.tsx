import { Fragment, useState } from 'react'
import { useLocation, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, query } from '../api/client'
import type { DifferenceSummary, PageResult, TaskSummary } from '../api/types'
import { ContentState } from '../components/ContentState'
import { StatusBadge, resultLabel } from '../components/StatusBadge'
import { Pagination } from '../components/Pagination'
import { formatTime } from './TasksPage'

type DetailTab = 'differences' | 'execution'

export function TaskDetailPage() {
  const parts = useLocation().pathname.split('/').filter(Boolean), identity = parts.slice(1, 5).map(encodeURIComponent).join('/')
  const [page, setPage] = useState(0), [expanded, setExpanded] = useState<string | null>(null), [tab, setTab] = useState<DetailTab>('differences')
  const task = useQuery({ queryKey: ['task', identity], queryFn: () => api<TaskSummary>(`/api/tasks/${identity}`) })
  const differences = useQuery({ queryKey: ['differences', identity, page], queryFn: () => api<PageResult<DifferenceSummary>>(query(`/api/tasks/${identity}/differences`, { page, size: 20 })) })
  const item = task.data
  return <div className="page"><Link className="back-link" to="/tasks">← 返回任务列表</Link>
    <div className="page-heading compact"><div><span className="eyebrow">TASK DETAIL</span><h1>{item?.configurationName ?? '任务详情'}</h1><p>{item ? `#${item.bcompIndex} · ${item.taskBusinessDate} · 批次 ${item.taskBatch} · 时点 ${item.timeNode}` : '正在读取任务…'}</p></div>{item && <div className="heading-status"><StatusBadge value={item.taskState} /><StatusBadge value={item.taskResult} kind="result" taskState={item.taskState} /></div>}</div>
    {task.isLoading && <ContentState state="loading" title="正在加载任务详情" />}
    {task.isError && <ContentState state="error" title="任务详情加载失败" description="无法读取当前任务，请返回列表后重试。" />}
    {item && <><section className="facts result-facts" aria-label="任务结果摘要"><div><span>旧 / 新侧总量</span><strong>{item.file1Count ?? '—'} / {item.file2Count ?? '—'}</strong></div><div><span>旧侧差异</span><strong>{item.file1FailureCount ?? '—'}</strong></div><div><span>新侧差异</span><strong>{item.file2FailureCount ?? '—'}</strong></div><div><span>结束时间</span><strong>{formatTime(item.endedAt)}</strong></div></section>
      <div className="detail-tabs" role="tablist" aria-label="任务详情视图"><button role="tab" aria-label="差异明细" aria-selected={tab === 'differences'} aria-controls="differences-panel" onClick={() => setTab('differences')}>差异明细 <span>{differences.data?.total ?? 0}</span></button><button role="tab" aria-selected={tab === 'execution'} aria-controls="execution-panel" onClick={() => setTab('execution')}>执行信息</button></div>
      {tab === 'differences' ? <section id="differences-panel" className="detail-panel" role="tabpanel"><div className="section-heading"><div><h2>差异明细</h2><p>业务键、差异类型以及两侧原始拼接内容。</p></div><span>{differences.data?.total ?? 0} 条</span></div>
        {differences.isLoading && <ContentState state="loading" title="正在加载差异明细" />}{differences.isError && <ContentState state="error" title="差异明细加载失败" />}{differences.data?.total === 0 && <ContentState state="success" title="没有差异数据" description="当前任务结果一致。" />}
        {!!differences.data?.items.length && <div className="table-wrap"><table><thead><tr><th>业务键</th><th>差异类型</th><th>原因</th><th></th></tr></thead><tbody>{differences.data.items.map(row => <Fragment key={row.businessKey}><tr><td className="mono break-value">{row.businessKey}</td><td><span className="result-code">{resultLabel(row.lineResult)}</span></td><td className="break-value">{row.reason || '—'}</td><td><button className="text-button" aria-expanded={expanded === row.businessKey} onClick={() => setExpanded(expanded === row.businessKey ? null : row.businessKey)}>查看两侧内容</button></td></tr>{expanded === row.businessKey && <tr className="expanded-row"><td colSpan={4}><div className="diff-grid"><div><span>旧侧内容</span><pre>{row.oldContent || '（空）'}</pre></div><div><span>新侧内容</span><pre>{row.newContent || '（空）'}</pre></div></div></td></tr>}</Fragment>)}</tbody></table></div>}{differences.data && differences.data.total > 0 && <Pagination page={page} size={20} total={differences.data.total} onChange={setPage} />}
      </section> : <section id="execution-panel" className="detail-panel" role="tabpanel"><div className="section-heading"><div><h2>执行信息</h2><p>任务时间、读取策略、两侧统计和失败原因。</p></div></div><dl className="execution-grid"><div><dt>创建时间</dt><dd>{formatTime(item.createdAt)}</dd></div><div><dt>开始时间</dt><dd>{formatTime(item.startedAt)}</dd></div><div><dt>结束时间</dt><dd>{formatTime(item.endedAt)}</dd></div><div><dt>读取策略</dt><dd>{item.readMode || '—'}</dd></div><div><dt>旧侧成功 / 失败</dt><dd>{item.file1SuccessCount ?? '—'} / {item.file1FailureCount ?? '—'}</dd></div><div><dt>新侧成功 / 失败</dt><dd>{item.file2SuccessCount ?? '—'} / {item.file2FailureCount ?? '—'}</dd></div><div className="span-full"><dt>失败原因</dt><dd>{item.failureReason || '—'}</dd></div></dl></section>}
    </>}
  </div>
}
