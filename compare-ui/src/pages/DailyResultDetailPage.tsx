import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, query } from '../api/client'
import type { DailyResult, DailySamples, FieldPattern } from '../api/types'
import { databaseLabels } from '../database/labels'
import { Pagination } from '../components/Pagination'
import { ContentState } from '../components/ContentState'
import { formatTime } from './TasksPage'
import { DailySampleGrid } from './DailySampleGrid'

export function DailyResultDetailPage() {
  const parts = useLocation().pathname.split('/'), identity = parts.slice(2, 4).map(p => encodeURIComponent(decodeURIComponent(p))).join('/')
  const [params] = useSearchParams(), [type, setType] = useState('3'), [page, setPage] = useState(0)
  const [patternId, setPatternId] = useState<string | null>(null)
  const result = useQuery({ queryKey: ['daily-result', identity], queryFn: () => api<DailyResult>(`/api/results/${identity}`), refetchInterval: 15000 })
  const fullDetails = result.data?.detailLimit === -1
  const patterns = useQuery({ queryKey: ['daily-field-patterns', identity, result.data?.runId], queryFn: () => api<FieldPattern[]>(query(`/api/results/${identity}/field-patterns`, { version: result.data?.runId })), enabled: !!result.data && type === '4', refetchInterval: 15000 })
  const selectablePatterns = patterns.data?.filter(pattern => pattern.patternId !== '__OTHER__') ?? []
  const collapsedPattern = patterns.data?.find(pattern => pattern.patternId === '__OTHER__')
  const selectedPattern = type === '4' ? selectablePatterns.find(pattern => pattern.patternId === (patternId ?? (fullDetails ? undefined : selectablePatterns[0]?.patternId))) : undefined
  const legacyFields = !fullDetails && type === '4' && patterns.isSuccess && patterns.data.length === 0 && !!result.data?.differentCount
  const showSamples = fullDetails || type !== '4' || legacyFields || !!selectedPattern
  const samples = useQuery({ queryKey: ['daily-samples', identity, result.data?.runId, type, selectedPattern?.patternId, page], queryFn: () => api<DailySamples>(query(`/api/results/${identity}/differences`, { type, patternId: selectedPattern?.patternId, version: result.data?.runId, page, size: 100 })), enabled: !!result.data && showSamples, refetchInterval: 15000 })
  useEffect(() => { setPage(0); setPatternId(null) }, [result.data?.runId, type])
  const item = samples.data?.summary.runId === result.data?.runId && samples.data?.summary.detailsExpired === '1' ? samples.data.summary : result.data
  if (result.isLoading) return <div className="page daily-result-page"><ContentState state="loading" title="正在加载核对结果" /></div>
  if (!item || result.isError) return <div className="page daily-result-page"><Link to="/results">返回每日结果</Link><ContentState state="error" title="暂无可展示的每日结果" description={result.error?.message} /></div>
  const names = databaseLabels(item), total = type === '3' ? item.onlyOriginCount : type === '2' ? item.onlyDestinationCount : item.differentCount
  const saved = type === '3' ? item.originSamples : type === '2' ? item.destinationSamples : item.differentSamples
  const replaced = params.has('sourceBatch') && (params.get('sourceBatch') !== item.taskBatch || params.get('sourceDate') !== item.taskBusiDate)
  const detailDescription = fullDetails
    ? item.detailsExpired === '1' ? '明细已按保留期限清理，汇总保留。'
      : total === saved ? '已保存全部差异明细。' : '已保存明细与实际差异数量不一致，请核查执行结果。'
    : total > saved ? '其余明细未保存，汇总已包含全部差异。' : '计数来自全量核对。'
  const emptyDescription = total === 0 ? '该类别没有差异。' : fullDetails
    ? '请核查明细是否已按保留期限清理。' : '请以全量差异数量为准；样本可能未启用或已清理。'
  return <div className="page daily-result-page"><Link className="back-link" to={`/results?date=${item.resultDate}`}>← 返回每日结果</Link>
    <div className="page-heading compact"><div><span className="eyebrow">DAILY RESULT</span><h1>{item.configurationName}</h1><p>核对日期 {item.resultDate} · 最近成功批次 {item.taskBatch} · 完成于 {formatTime(item.publishedAt)}</p></div></div>
    {replaced && <div className="notice" role="status">原任务的明细已被同一核对日期后续成功执行替换，当前展示当天最新成功结果。</div>}
    <section className="facts"><div><span>{names.origin} 总量</span><strong>{item.originCount.toLocaleString()}</strong></div><div><span>{names.destination} 总量</span><strong>{item.destinationCount.toLocaleString()}</strong></div><div><span>主键相同且核对一致</span><strong>{item.matchedCount.toLocaleString()}</strong></div><div><span>{fullDetails ? '明细保存方式' : '每组样本上限'}</span><strong>{fullDetails ? '全量' : `${item.detailLimit.toLocaleString()} 条`}</strong></div></section>
    <div className="detail-tabs" role="tablist" aria-label="差异分类">{[{ value: '3', label: `仅 ${names.origin} 存在`, count: item.onlyOriginCount }, { value: '2', label: `仅 ${names.destination} 存在`, count: item.onlyDestinationCount }, { value: '4', label: '主键相同、字段不同', count: item.differentCount }].map(tab => <button key={tab.value} role="tab" aria-selected={type === tab.value} onClick={() => { setType(tab.value); setPage(0); setPatternId(null) }}>{tab.label} <span>{tab.count.toLocaleString()}</span></button>)}</div>
    <section className="detail-panel"><div className="section-heading"><div><h2>{fullDetails ? '差异明细' : type === '4' && !legacyFields ? '字段差异组合' : '差异样本'}</h2><p>实际差异 {total.toLocaleString()} {type === '4' ? '行' : '条'} · 已保存明细 {saved.toLocaleString()} 条。{detailDescription}</p>{fullDetails && <p>全量保存差异行；过长字段值或过多字段仍可能截断展示，具体行标记“展示已截断”。</p>}</div></div>
      {item.detailsExpired === '1' && <div className="notice" role="status">{fullDetails ? '明细' : '样本'}已按保留期限清理，全量汇总仍保留。</div>}
      {type === '4' && patterns.isLoading && <ContentState state="loading" title="正在读取字段组合" />}
      {type === '4' && patterns.isError && <ContentState state="error" title="字段组合读取失败" description={patterns.error.message} action={<button className="button ghost" onClick={() => { void result.refetch(); void patterns.refetch() }}>刷新结果</button>} />}
      {type === '4' && legacyFields && <div className="notice" role="status">此结果没有字段组合汇总，请重新执行核对以查看各组合的精确数量。下方仍可查看旧版差异样本。</div>}
      {type === '4' && (fullDetails || !!patterns.data?.length) && <div className="daily-pattern-picker" role="group" aria-label="差异字段组合">
        {fullDetails && <button type="button" className="pattern-switch" aria-pressed={!patternId} onClick={() => { setPatternId(null); setPage(0) }}><strong>全部字段差异</strong><span>{item.differentCount.toLocaleString()} 行</span><small>已存明细 <span>{item.differentSamples.toLocaleString()} 条</span></small></button>}
        {selectablePatterns.map(pattern => <button key={pattern.patternId} type="button" className="pattern-switch" aria-pressed={selectedPattern?.patternId === pattern.patternId} onClick={() => { setPatternId(pattern.patternId); setPage(0) }}><strong>[{pattern.fields.join('、')}]</strong><span>{pattern.differentCount.toLocaleString()} 行</span><small>{fullDetails ? '已存明细' : '已存样本'} <span>{pattern.sampleCount.toLocaleString()} 条</span></small></button>)}
        {collapsedPattern && <div className="pattern-switch collapsed-pattern-summary" role="note">其余 {collapsedPattern.collapsedPatternCount.toLocaleString()} 种组合，共 {collapsedPattern.differentCount.toLocaleString()} 条差异{fullDetails ? '；请在“全部字段差异”中查看明细。' : '，未保存样本'}</div>}
      </div>}
      {showSamples && samples.isLoading && <ContentState state="loading" title={fullDetails ? '正在读取明细' : '正在读取样本'} />}
      {showSamples && samples.isError && <ContentState state="error" title={fullDetails ? '明细读取失败' : '样本读取失败'} description={samples.error.message} action={<button className="button ghost" onClick={() => { void result.refetch(); void samples.refetch() }}>刷新结果</button>} />}
      {showSamples && samples.data && !samples.data.items.length && <ContentState state="empty" title={fullDetails ? '没有已保存的明细' : '没有已保存的样本'} description={emptyDescription} />}
      {showSamples && item.detailsExpired !== '1' && !!samples.data?.items.length && <DailySampleGrid samples={samples.data} type={type} originName={names.origin} destinationName={names.destination} patternFields={selectedPattern?.fields} />}
      {showSamples && samples.data && <Pagination page={page} size={100} total={samples.data.total} onChange={setPage} />}
    </section>
  </div>
}
