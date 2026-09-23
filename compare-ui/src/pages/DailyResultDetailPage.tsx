import { Fragment, useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, query } from '../api/client'
import type { DailyResult, DailySamples, FieldPattern } from '../api/types'
import { databaseLabels } from '../database/labels'
import { Pagination } from '../components/Pagination'
import { ContentState } from '../components/ContentState'
import { formatTime } from './TasksPage'

export function DailyResultDetailPage() {
  const parts = useLocation().pathname.split('/'), identity = parts.slice(2, 4).map(p => encodeURIComponent(decodeURIComponent(p))).join('/')
  const [params] = useSearchParams(), [type, setType] = useState('3'), [page, setPage] = useState(0), [expanded, setExpanded] = useState<string | null>(null)
  const [patternId, setPatternId] = useState<string | null>(null)
  const result = useQuery({ queryKey: ['daily-result', identity], queryFn: () => api<DailyResult>(`/api/results/${identity}`), refetchInterval: 15000 })
  const patterns = useQuery({ queryKey: ['daily-field-patterns', identity, result.data?.runId], queryFn: () => api<FieldPattern[]>(query(`/api/results/${identity}/field-patterns`, { version: result.data?.runId })), enabled: !!result.data && type === '4', refetchInterval: 15000 })
  const selectedPattern = patterns.data?.find(pattern => pattern.patternId === patternId)
  const legacyFields = type === '4' && patterns.isSuccess && patterns.data.length === 0 && !!result.data?.differentCount
  const showSamples = type !== '4' || legacyFields || !!selectedPattern
  const samples = useQuery({ queryKey: ['daily-samples', identity, result.data?.runId, type, selectedPattern?.patternId, page], queryFn: () => api<DailySamples>(query(`/api/results/${identity}/differences`, { type, patternId: selectedPattern?.patternId, version: result.data?.runId, page, size: 20 })), enabled: !!result.data && showSamples, refetchInterval: 15000 })
  useEffect(() => { setPage(0); setExpanded(null); setPatternId(null) }, [result.data?.runId, type])
  const item = samples.data?.summary.runId === result.data?.runId && samples.data?.summary.detailsExpired === '1' ? samples.data.summary : result.data
  if (result.isLoading) return <div className="page daily-result-page"><ContentState state="loading" title="正在加载核对结果" /></div>
  if (!item || result.isError) return <div className="page daily-result-page"><Link to="/results">返回每日结果</Link><ContentState state="error" title="暂无可展示的每日结果" description={result.error?.message} /></div>
  const names = databaseLabels(item), total = type === '3' ? item.onlyOriginCount : type === '2' ? item.onlyDestinationCount : item.differentCount
  const saved = type === '3' ? item.originSamples : type === '2' ? item.destinationSamples : item.differentSamples
  const replaced = params.has('sourceBatch') && (params.get('sourceBatch') !== item.taskBatch || params.get('sourceDate') !== item.taskBusiDate)
  return <div className="page daily-result-page"><Link className="back-link" to={`/results?date=${item.resultDate}`}>← 返回每日结果</Link>
    <div className="page-heading compact"><div><span className="eyebrow">DAILY RESULT</span><h1>{item.configurationName}</h1><p>核对日期 {item.resultDate} · 最近成功批次 {item.taskBatch} · 完成于 {formatTime(item.publishedAt)}</p></div></div>
    {replaced && <div className="notice" role="status">原任务的明细已被同一核对日期后续成功执行替换，当前展示当天最新成功结果。</div>}
    <section className="facts"><div><span>{names.origin} 总量</span><strong>{item.originCount.toLocaleString()}</strong></div><div><span>{names.destination} 总量</span><strong>{item.destinationCount.toLocaleString()}</strong></div><div><span>主键相同且核对一致</span><strong>{item.matchedCount.toLocaleString()}</strong></div><div><span>每组样本上限</span><strong>{item.detailLimit.toLocaleString()} 条</strong></div></section>
    <div className="detail-tabs" role="tablist" aria-label="差异分类">{[{ value: '3', label: `仅 ${names.origin} 存在`, count: item.onlyOriginCount }, { value: '2', label: `仅 ${names.destination} 存在`, count: item.onlyDestinationCount }, { value: '4', label: '主键相同、字段不同', count: item.differentCount }].map(tab => <button key={tab.value} role="tab" aria-selected={type === tab.value} onClick={() => { setType(tab.value); setPage(0); setExpanded(null) }}>{tab.label} <span>{tab.count.toLocaleString()}</span></button>)}</div>
    <section className="detail-panel"><div className="section-heading"><div><h2>{type === '4' && !legacyFields ? '字段差异组合' : '差异样本'}</h2><p>实际差异 {total.toLocaleString()} {type === '4' ? '行' : '条'} · 已保存明细 {saved.toLocaleString()} 条。{total > saved ? '其余明细未保存，汇总已包含全部差异。' : '计数来自全量核对。'}</p></div></div>
      {item.detailsExpired === '1' && <div className="notice" role="status">样本已按保留期限清理，全量汇总仍保留。</div>}
      {type === '4' && patterns.isLoading && <ContentState state="loading" title="正在读取字段组合" />}
      {type === '4' && patterns.isError && <ContentState state="error" title="字段组合读取失败" description={patterns.error.message} action={<button className="button ghost" onClick={() => { void result.refetch(); void patterns.refetch() }}>刷新结果</button>} />}
      {type === '4' && legacyFields && <div className="notice" role="status">此结果没有字段组合汇总，请重新执行核对以查看各组合的精确数量。下方仍可查看旧版差异样本。</div>}
      {type === '4' && !!patterns.data?.length && <><div className="table-wrap"><table><thead><tr><th>不同字段组合</th><th>实际差异</th><th>已保存样本</th><th></th></tr></thead><tbody>{patterns.data.map(pattern => <tr key={pattern.patternId}><td className="break-value">[{pattern.fields.join('、')}]</td><td>{pattern.differentCount.toLocaleString()} 行</td><td>{pattern.sampleCount.toLocaleString()} 条</td><td><button className="text-button" aria-pressed={selectedPattern?.patternId === pattern.patternId} onClick={() => { setPatternId(pattern.patternId); setPage(0); setExpanded(null) }}>查看样本</button></td></tr>)}</tbody></table></div>{selectedPattern && <h3>[{selectedPattern.fields.join('、')}] 的样本</h3>}</>}
      {showSamples && samples.isLoading && <ContentState state="loading" title="正在读取样本" />}
      {showSamples && samples.isError && <ContentState state="error" title="样本读取失败" description={samples.error.message} action={<button className="button ghost" onClick={() => { void result.refetch(); void samples.refetch() }}>刷新结果</button>} />}
      {showSamples && samples.data && !samples.data.items.length && <ContentState state="empty" title="没有已保存的样本" description={total > 0 ? '请以全量差异数量为准；样本可能未启用或已清理。' : '该类别没有差异。'} />}
      {showSamples && item.detailsExpired !== '1' && !!samples.data?.items.length && <div className="table-wrap"><table><thead><tr><th>核对主键</th><th>{type === '4' ? '不同字段' : '记录所在数据库'}</th><th></th></tr></thead><tbody>{samples.data.items.map(row => <Fragment key={row.businessKey}><tr><td className="mono break-value">{row.businessKey}</td><td>{type === '4' ? row.detail.fields.map(f => f.name).join('、') : type === '3' ? names.origin : names.destination}</td><td><button className="text-button" onClick={() => setExpanded(expanded === row.businessKey ? null : row.businessKey)}>查看字段</button></td></tr>{expanded === row.businessKey && <tr className="expanded-row"><td colSpan={3}>{row.detail.truncated && <p role="status">该记录过宽，部分展示内容已截断；核对使用的是完整字段值。</p>}<table><thead><tr><th>字段</th><th>{names.origin} 的值</th><th>{names.destination} 的值</th></tr></thead><tbody>{row.detail.fields.map((field, i) => <tr key={i}><td>{field.name}</td><td className="break-value">{type === '2' ? '（无记录）' : field.originValue === null ? 'NULL' : field.originValue === '' ? '（空字符串）' : field.originValue}</td><td className="break-value">{type === '3' ? '（无记录）' : field.destinationValue === null ? 'NULL' : field.destinationValue === '' ? '（空字符串）' : field.destinationValue}</td></tr>)}</tbody></table></td></tr>}</Fragment>)}</tbody></table></div>}
      {showSamples && samples.data && <Pagination page={page} size={20} total={samples.data.total} onChange={setPage} />}
    </section>
  </div>
}
