import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { BatchRuntime, BatchSummary, PageResult } from '../api/types'
import { BatchTable } from '../components/BatchTable'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'

export function DashboardPage() {
  const runtime = useQuery({ queryKey: ['batch-runtime'], queryFn: () => api<BatchRuntime>('/api/batches/runtime'), refetchInterval: 30000 })
  const batches = useQuery({ queryKey: ['batches', 'recent'], queryFn: () => api<PageResult<BatchSummary>>('/api/batches?page=0&size=5'), refetchInterval: 10000 })
  const [cacheRefresh, setCacheRefresh] = useState<{ state: 'idle' | 'loading' | 'success' | 'failed', message?: string }>({ state: 'idle' })
  const refreshCache = async () => {
    setCacheRefresh({ state: 'loading' })
    try {
      const result = await api<{ code: number, msg: string }>('/api/cache/refresh', { method: 'POST' })
      setCacheRefresh(result.code === 200 ? { state: 'success', message: '新配置已加载到当前服务实例。' } : { state: 'failed', message: result.msg || '缓存刷新失败，请重试' })
    } catch { setCacheRefresh({ state: 'failed', message: '网络异常，缓存刷新失败，请重试' }) }
  }
  const buttonLabel = cacheRefresh.state === 'loading' ? '刷新中…' : cacheRefresh.state === 'failed' ? '重试刷新配置缓存' : cacheRefresh.state === 'success' ? '再次刷新配置缓存' : '刷新配置缓存'
  return <div className="page"><PageHeader eyebrow="WORKSPACE" title="核对工作台" description="导入配置、发起整批比对，按批次跟踪本轮进度与结果。" actions={<><Link className="button ghost" to="/configurations/new">导入 Axon 配置</Link><Link className="button primary" to="/batches/new">发起全部比对</Link></>} />
    {runtime.isLoading && <ContentState state="loading" title="正在读取调度状态" />}
    {runtime.isError && <ContentState state="error" title="调度状态读取失败" action={<button className="button ghost small" onClick={() => runtime.refetch()}>重新加载</button>} />}
    {runtime.data && <section className="runtime-summary" aria-label="任务调度状态"><div><span>后台调度</span><strong className={runtime.data.tasksEnabled ? 'runtime-enabled' : ''}>{runtime.data.tasksEnabled ? '已启用' : '未启用'}</strong></div><div><span>执行并发</span><strong>{runtime.data.concurrency}<small>个任务同时运行</small></strong></div><p>{runtime.data.tasksEnabled ? '全部发起后任务统一入队，后台完成一个任务后自动领取下一个。' : '当前后台调度未启用。可创建等待任务，需由运维启用调度后才会执行。'}<small>单批最多 {runtime.data.maxBatchSize} 项；运行资源由后台配置控制。</small></p></section>}
    <section className="section-block" aria-label="最近批次"><div className="section-heading"><div><span className="section-kicker">RECENT BATCHES</span><h2>最近批次</h2><p>一致、有差异和执行失败分别统计，进入批次查看单表明细。</p></div><Link className="row-link" to="/batches">查看全部批次 →</Link></div>
      {batches.isLoading && <ContentState state="loading" title="正在加载最近批次" />}
      {batches.isError && <ContentState state="error" title="最近批次加载失败" action={<button className="button ghost small" onClick={() => batches.refetch()}>重新加载批次</button>} />}
      {batches.data?.total === 0 && <ContentState state="empty" title="尚未发起核对批次" description="已有配置可直接发起全部比对，也可在核对配置中勾选部分表。" action={<Link className="button ghost small" to="/configurations">查看核对配置</Link>} />}
      {!!batches.data?.items.length && <BatchTable items={batches.data.items} />}
    </section>
    <section className="workspace-maintenance"><Link className="row-link" to="/results">查看每日全量汇总与差异样本 →</Link><Link className="row-link" to="/tasks">查看全部单表任务（含历史任务） →</Link><button className="button ghost small" disabled={cacheRefresh.state === 'loading'} onClick={refreshCache}>{buttonLabel}</button></section>
    {cacheRefresh.state !== 'idle' && cacheRefresh.state !== 'loading' && <div className={`notice ${cacheRefresh.state === 'failed' ? 'error' : ''}`} role="status"><div><strong>{cacheRefresh.state === 'success' ? '缓存已刷新' : '缓存刷新失败'}</strong><p>{cacheRefresh.message}</p></div></div>}
  </div>
}
