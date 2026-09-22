import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, query } from '../api/client'
import type { DailyResults, TaskDatabaseContext } from '../api/types'
import { databaseLabels } from '../database/labels'
import { Pagination } from '../components/Pagination'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'

const groupKey = (c: TaskDatabaseContext) => JSON.stringify([c.originDatabaseId, c.originDatabaseName, c.destinationDatabaseId, c.destinationDatabaseName])
export function DailyResultsPage() {
  const [params, setParams] = useSearchParams()
  const today = new Date(), defaultDate = `${today.getFullYear()}${String(today.getMonth()+1).padStart(2,'0')}${String(today.getDate()).padStart(2,'0')}`
  const date = params.get('date') || defaultDate
  const [draft, setDraft] = useState(date), [page, setPage] = useState(0)
  const result = useQuery({ queryKey: ['daily-results', date, page], queryFn: () => api<DailyResults>(query('/api/results', { date, page, size: 20 })), refetchInterval: 15000 })
  return <div className="page daily-results-page"><PageHeader eyebrow="DAILY RESULTS" title="每日核对结果" description="按核对业务日期查看每张表最近一次成功结果；同日重跑成功后替换。数量为全量统计，明细仅保存限额内的样本。" actions={<Link className="button ghost" to="/batches">查看执行批次</Link>} />
    <form className="daily-date-form" onSubmit={e => { e.preventDefault(); setPage(0); setParams({ date: draft }) }}><label>核对业务日期<input value={draft} onChange={e => setDraft(e.target.value)} placeholder="yyyyMMdd" pattern="[0-9]{8}" required /></label><button className="button primary">查询结果</button></form>
    {result.isLoading && <ContentState state="loading" title="正在加载每日结果" />}
    {result.isError && <ContentState state="error" title="每日结果读取失败" description={result.error.message} />}
    {result.data && <>
      <div className="section-heading"><div><h2>{date} · 已完成 {result.data.results.total} 个核对对象</h2><p>对象数按核对配置统计，不推测联表 SQL 涉及的实际数据库表数。等待、运行和失败情况请查看执行批次。</p></div></div>
      {result.data.results.total === 0 && <ContentState state="empty" title="该日期尚无成功发布的结果" />}
      {result.data.connections.map(group => {
        const names = databaseLabels(group), items = result.data.results.items.filter(item => groupKey(item) === groupKey(group))
        return <section className="section-block" key={groupKey(group)}><div className="section-heading"><div><h2>{names.origin} / {names.destination}</h2><p>{names.origin}：{group.objectCount} 个核对对象 · {names.destination}：{group.objectCount} 个核对对象</p></div></div>
          {items.length > 0 ? <div className="table-wrap"><table><thead><tr><th>表 / 核对对象</th><th>{names.origin} 总量</th><th>{names.destination} 总量</th><th>仅 {names.origin} 存在</th><th>仅 {names.destination} 存在</th><th>主键相同、字段不同</th><th></th></tr></thead><tbody>{items.map(item => <tr key={item.bcompIndex}><td>{item.configurationName}</td><td>{item.originCount.toLocaleString()}</td><td>{item.destinationCount.toLocaleString()}</td><td>{item.onlyOriginCount.toLocaleString()}</td><td>{item.onlyDestinationCount.toLocaleString()}</td><td>{item.differentCount.toLocaleString()}</td><td><Link className="row-link" to={`/results/${item.bcompIndex}/${date}`}>查看样本</Link></td></tr>)}</tbody></table></div> : <p className="muted">该连接对的核对对象位于其他分页。</p>}
        </section>
      })}
      <Pagination page={page} size={20} total={result.data.results.total} onChange={setPage} />
    </>}
  </div>
}
