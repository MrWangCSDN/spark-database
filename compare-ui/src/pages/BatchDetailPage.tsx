import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, query } from '../api/client'
import type { BatchSummary, BatchTaskStatus, PageResult, TaskSummary } from '../api/types'
import { BatchProgress } from '../components/BatchTable'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'
import { formatTime } from './TasksPage'

const filters: Array<{ value: BatchTaskStatus; label: string; count: keyof BatchSummary }> = [
  { value: 'ALL', label: '全部', count: 'totalCount' }, { value: 'WAITING', label: '等待', count: 'waitingCount' },
  { value: 'RUNNING', label: '运行中', count: 'runningCount' }, { value: 'CONSISTENT', label: '一致', count: 'consistentCount' },
  { value: 'DIFFERENCE', label: '有差异', count: 'differenceCount' }, { value: 'FAILED', label: '执行失败', count: 'failedCount' },
  { value: 'CANCELLED', label: '已取消', count: 'cancelledCount' }, { value: 'OTHER', label: '其他异常', count: 'otherCount' }
]
export function taskDetailUrl(task: TaskSummary) { return `/tasks/${[task.bcompIndex, task.taskBusinessDate, task.taskBatch, task.timeNode].map(value => encodeURIComponent(String(value))).join('/')}` }
export function BatchDetailPage() {
  const client = useQueryClient()
  const [confirmStop, setConfirmStop] = useState(false)
  const [stopRequested, setStopRequested] = useState(false)
  const { batchId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const status = filters.find(item => item.value === params.get('status'))?.value ?? 'ALL'
  const page = Math.max(0, Number(params.get('page')) || 0)
  const encodedId = encodeURIComponent(batchId)
  const summary = useQuery({ queryKey: ['batch', batchId], queryFn: () => api<BatchSummary>(`/api/batches/${encodedId}`), refetchInterval: result => document.visibilityState === 'visible' && (!result.state.data || result.state.data.waitingCount + result.state.data.runningCount > 0) ? 10000 : false })
  const tasks = useQuery({ queryKey: ['batch-tasks', batchId, status, page], queryFn: () => api<PageResult<TaskSummary>>(query(`/api/batches/${encodedId}/tasks`, { page, size: 20, status })), refetchInterval: document.visibilityState === 'visible' && (summary.data?.waitingCount ?? 0) + (summary.data?.runningCount ?? 0) > 0 ? 10000 : false })
  const batch = summary.data
  const refresh = () => { summary.refetch(); tasks.refetch() }
  const stop = useMutation({ mutationFn: () => api<BatchSummary>(`/api/batches/${encodedId}/cancel`, { method: 'POST' }), onSuccess: () => { setConfirmStop(false); setStopRequested(true); client.invalidateQueries({ queryKey: ['batch', batchId] }); client.invalidateQueries({ queryKey: ['batch-tasks', batchId] }); client.invalidateQueries({ queryKey: ['batches'] }) } })
  return <div className="page"><Link className="back-link" to="/batches">← 返回核对批次</Link><PageHeader eyebrow="BATCH DETAIL" title="批次详情" description={batchId} actions={<><button className="button ghost" onClick={refresh}>刷新进度</button>{!!batch && batch.waitingCount + batch.runningCount > 0 && <button className="button secondary" onClick={() => setConfirmStop(true)}>停止批次</button>}{!!batch?.failedCount && <Link className="button primary" to={`/batches/new?sourceBatchId=${encodedId}`}>重新发起失败项（{batch.failedCount}）</Link>}</>} />
    {confirmStop && <div className="notice warning" role="alert"><div><strong>停止当前批次？</strong><p>等待和运行中的任务将取消；已完成结果保留，其他批次继续执行。</p></div><button className="button ghost small" onClick={() => setConfirmStop(false)} disabled={stop.isPending}>返回</button><button className="button secondary small" onClick={() => stop.mutate()} disabled={stop.isPending}>{stop.isPending ? '停止中…' : '确认停止'}</button></div>}
    {stopRequested && <div className="notice" role="status">已取消待执行任务，并向运行中的 Spark 应用发送终止请求。请在 Spark 页面确认应用已结束。</div>}
    {stop.isError && <p className="form-error" role="alert">停止失败：{stop.error.message}</p>}
    {summary.isLoading && <ContentState state="loading" title="正在加载批次" />}{summary.isError && <ContentState state="error" title="批次加载失败" description={summary.error.message} action={<button className="button ghost small" onClick={refresh}>重新加载</button>} />}
    {batch && <><div className="facts"><div><span>任务业务日期</span><strong>{batch.taskBusinessDate}</strong></div><div><span>核验业务日期</span><strong>{batch.verifyBusinessDate || '按任务配置'}</strong></div><div><span>创建时间</span><strong>{formatTime(batch.createdAt)}</strong></div><div><span>来源批次</span><strong>{batch.sourceBatchId ? <Link className="row-link" to={`/batches/${encodeURIComponent(batch.sourceBatchId)}`}>{batch.sourceBatchId}</Link> : '首次发起'}</strong></div></div>
      <section className="batch-completion" aria-label="批次进度"><BatchProgress batch={batch} /><p>进度按已结束的任务数统计；有差异表示已完成比对，执行失败需要排查后重新发起。</p></section>
      <div className="batch-filters" role="group" aria-label="任务状态筛选">{filters.map(filter => <button key={filter.value} type="button" aria-pressed={status === filter.value} onClick={() => setParams(filter.value === 'ALL' ? {} : { status: filter.value })}>{filter.label}<strong>{batch[filter.count]}</strong></button>)}</div>
    </>}
    {tasks.isLoading && <ContentState state="loading" title="正在加载批次任务" />}{tasks.isError && <ContentState state="error" title="批次任务加载失败" action={<button className="button ghost small" onClick={() => tasks.refetch()}>重新加载任务</button>} />}
    {tasks.data?.total === 0 && <ContentState state="empty" title="当前状态下没有任务" />}
    {!!tasks.data?.items.length && <div className="table-wrap"><table><thead><tr><th>表 / 配置名称</th><th>核验日期 / 时点</th><th>执行状态</th><th>核对结果</th><th>结束时间</th><th></th></tr></thead><tbody>{tasks.data.items.map(task => <tr key={`${task.bcompIndex}-${task.timeNode}`}><td><strong>{task.configurationName}</strong><small>#{task.bcompIndex}</small></td><td>{task.verifyBusinessDate || task.taskBusinessDate}<small>时点 {task.timeNode}</small></td><td>{task.taskState ? <StatusBadge value={task.taskState} /> : <span className="status status-state-4">记录缺失</span>}</td><td>{task.taskState ? <StatusBadge value={task.taskResult} kind="result" taskState={task.taskState} /> : '—'}</td><td>{formatTime(task.endedAt)}</td><td>{task.taskState ? <Link className="row-link" to={`${taskDetailUrl(task)}?batchId=${encodedId}${task.taskState === '4' ? '&tab=execution' : ''}`}>{task.taskState === '4' ? '查看原因' : task.taskState === '3' && task.taskResult === '1' ? '查看差异' : '查看任务'}</Link> : <small>请核查任务记录</small>}</td></tr>)}</tbody></table></div>}
    {!!tasks.data?.total && <Pagination page={page} size={20} total={tasks.data.total} onChange={next => { const updated = new URLSearchParams(params); updated.set('page', String(next)); setParams(updated) }} />}
  </div>
}
