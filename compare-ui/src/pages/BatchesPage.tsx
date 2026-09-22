import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api, query } from '../api/client'
import type { BatchSummary, PageResult } from '../api/types'
import { BatchTable } from '../components/BatchTable'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'

export function BatchesPage() {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page')) || 0)
  const list = useQuery({ queryKey: ['batches', page], queryFn: () => api<PageResult<BatchSummary>>(query('/api/batches', { page, size: 20 })), refetchInterval: document.visibilityState === 'visible' ? 10000 : false })
  return <div className="page"><PageHeader eyebrow="BATCHES" title="核对批次" description="按每轮执行查看进度和结果，执行失败的任务可以单独重新发起。" actions={<><Link className="button ghost" to="/tasks">查看全部单表任务</Link><Link className="button primary" to="/batches/new">发起全部比对</Link></>} />
    <div className="toolbar"><span><strong>{list.data?.total ?? 0}</strong> 个批次</span><button className="button ghost small" onClick={() => list.refetch()}>刷新列表</button></div>
    {list.isLoading && <ContentState state="loading" title="正在加载核对批次" />}
    {list.isError && <ContentState state="error" title="批次加载失败" action={<button className="button ghost small" onClick={() => list.refetch()}>重新加载</button>} />}
    {list.data?.total === 0 && <ContentState state="empty" title="暂无核对批次" description="导入核对配置后，选择业务日期发起第一轮比对。历史单表任务仍可通过上方入口查看。" />}
    {!!list.data?.items.length && <BatchTable items={list.data.items} />}
    {!!list.data?.total && <Pagination page={page} size={20} total={list.data.total} onChange={next => setParams(next ? { page: String(next) } : {})} />}
  </div>
}
