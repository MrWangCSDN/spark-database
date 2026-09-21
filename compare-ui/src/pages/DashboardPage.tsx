import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { DashboardSummary } from '../api/types'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { formatTime } from './TasksPage'

export function DashboardPage() {
  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: () => api<DashboardSummary>('/api/dashboard'), refetchInterval: value => document.visibilityState === 'visible' && (value.state.data?.runningCount ?? 0) > 0 ? 10000 : false })
  const [cacheRefresh, setCacheRefresh] = useState<{ state: 'idle' | 'loading' | 'success' | 'failed', message?: string }>({ state: 'idle' })
  const data = dashboard.data
  const refreshCache = async () => {
    setCacheRefresh({ state: 'loading' })
    try {
      const result = await api<{ code: number, msg: string }>('/api/cache/refresh', { method: 'POST' })
      setCacheRefresh(result.code === 200
        ? { state: 'success', message: '新配置已加载到当前服务实例。' }
        : { state: 'failed', message: result.msg || '缓存刷新失败，请重试' })
    } catch {
      setCacheRefresh({ state: 'failed', message: '网络异常，缓存刷新失败，请重试' })
    }
  }
  const buttonLabel = cacheRefresh.state === 'loading'
    ? '刷新中…'
    : cacheRefresh.state === 'failed'
      ? '重试刷新配置缓存'
      : cacheRefresh.state === 'success' ? '再次刷新配置缓存' : '刷新配置缓存'
  const attentionTasks = data?.recentTasks.filter(item => item.taskState === '4' || (item.taskState === '3' && item.taskResult === '1')).slice(0, 5) ?? []
  return <div className="page"><PageHeader eyebrow="OVERVIEW" title="运行总览" description="查看服务健康、当日执行情况和需要关注的核对结果。" meta={data ? `数据更新于 ${formatTime(data.updatedAt)}` : undefined} actions={<><button className="button ghost" disabled={cacheRefresh.state === 'loading'} onClick={refreshCache}>{buttonLabel}</button><Link className="button primary" to="/tasks">查看全部任务</Link></>} />
    {cacheRefresh.state !== 'idle' && cacheRefresh.state !== 'loading' && <div className={`notice ${cacheRefresh.state === 'failed' ? 'error' : ''}`} role="status"><div><strong>{cacheRefresh.state === 'success' ? '缓存已刷新' : '缓存刷新失败'}</strong><p>{cacheRefresh.message}</p></div></div>}
    {dashboard.isLoading && <ContentState state="loading" title="正在加载运行总览" description="正在读取服务状态和最近任务。" />}
    {dashboard.isError && <ContentState state="error" title="总览加载失败" description="服务状态暂时不可用，请稍后刷新。" action={<button className="button ghost small" onClick={() => dashboard.refetch()}>重新加载</button>} />}
    {data && <><section className="metric-strip executive-metrics" aria-label="核心运行指标"><div className="metric-health"><span>服务状态</span><strong className="service-state"><i />{data.serviceStatus}</strong><small>独立 Spark 比对服务</small></div><div><span>运行中任务</span><strong>{data.runningCount}</strong><small>当前正在执行</small></div><div><span>今日完成</span><strong>{data.todayCompletedCount}</strong><small>业务日期当日完成</small></div><div className={data.todayDifferenceCount > 0 ? 'metric-attention' : ''}><span>今日差异</span><strong>{data.todayDifferenceCount}</strong><small>{data.todayDifferenceCount > 0 ? '需要关注' : '暂未发现差异'}</small></div></section>
    <section className="section-block attention-section" aria-label="需要关注"><div className="section-heading"><div><span className="section-kicker">ATTENTION</span><h2>需要关注</h2><p>最近任务中执行失败或核对存在差异的记录。</p></div><Link className="row-link" to="/tasks?taskResult=1">进入任务中心 →</Link></div>
      {attentionTasks.length === 0 ? <ContentState state="success" title="最近任务运行正常" description="当前没有失败或存在差异的最近任务。" /> : <div className="attention-list">{attentionTasks.map(item => <Link key={`${item.bcompIndex}-${item.taskBusinessDate}-${item.taskBatch}-${item.timeNode}`} to={`/tasks/${item.bcompIndex}/${item.taskBusinessDate}/${item.taskBatch}/${item.timeNode}`}><div><StatusBadge value={item.taskState === '4' ? '4' : item.taskResult} kind={item.taskState === '4' ? 'state' : 'result'} taskState={item.taskState} /><strong>{item.configurationName}</strong><span>#{item.bcompIndex} · {item.taskBusinessDate} · 批次 {item.taskBatch}</span></div><span aria-hidden>→</span></Link>)}</div>}
    </section>
    <section className="section-block" aria-label="最近任务"><div className="section-heading"><div><span className="section-kicker">RECENT RUNS</span><h2>最近任务</h2><p>按创建时间显示最近的数据库核对运行。</p></div><small>更新于 {formatTime(data.updatedAt)}</small></div>{data.recentTasks.length === 0 ? <ContentState state="empty" title="暂无最近任务" description="创建任务后，运行状态会显示在这里。" /> : <div className="table-wrap"><table><thead><tr><th>配置</th><th>业务日期</th><th>状态</th><th>结果</th><th></th></tr></thead><tbody>{data.recentTasks.map(item => <tr key={`${item.bcompIndex}-${item.taskBusinessDate}-${item.taskBatch}-${item.timeNode}`}><td><strong>{item.configurationName}</strong><small>#{item.bcompIndex} · 时点 {item.timeNode}</small></td><td>{item.taskBusinessDate}<small>批次 {item.taskBatch}</small></td><td><StatusBadge value={item.taskState} /></td><td><StatusBadge value={item.taskResult} kind="result" taskState={item.taskState} /></td><td><Link className="row-link" to={`/tasks/${item.bcompIndex}/${item.taskBusinessDate}/${item.taskBatch}/${item.timeNode}`}>查看</Link></td></tr>)}</tbody></table></div>}</section></>}
  </div>
}
