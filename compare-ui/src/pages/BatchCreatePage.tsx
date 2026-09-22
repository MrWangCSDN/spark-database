import { FormEvent, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { api, RequestError } from '../api/client'
import type { BatchPreview, BatchRequest, BatchScope, BatchSummary } from '../api/types'
import { PageHeader } from '../components/PageHeader'

function today() { const date = new Date(); return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(date.getDate()).padStart(2, '0')}` }
// getRandomValues also works on internal HTTP pages where randomUUID is unavailable.
function requestIdentity() {
  const bytes = crypto.getRandomValues(new Uint8Array(16)); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function BatchCreatePage() {
  const [params] = useSearchParams(), location = useLocation(), navigate = useNavigate(), client = useQueryClient()
  const sourceBatchId = params.get('sourceBatchId') || undefined
  const selection = (location.state as { configurationIds?: number[] } | null)?.configurationIds
  const configurationIds = Array.isArray(selection) ? [...new Set(selection.filter(id => Number.isInteger(id) && id > 0))].sort((a, b) => a - b) : []
  const scope: BatchScope = sourceBatchId ? 'FAILED' : params.get('scope') === 'SELECTED' ? 'SELECTED' : 'ALL'
  const [dates, setDates] = useState({ taskBusinessDate: today(), verifyBusinessDate: '', timeNode: '' })
  const [excludedIds, setExcludedIds] = useState<number[]>([]), [excludeSelection, setExcludeSelection] = useState<number[]>([])
  const [preview, setPreview] = useState<BatchPreview | null>(null), [previewRequest, setPreviewRequest] = useState('')
  const [requestId, setRequestId] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState<'preview' | 'create' | null>(null)
  const inFlight = useRef(false)
  const body: BatchRequest = {
    scope, ...(scope === 'FAILED' ? { sourceBatchId } : { taskBusinessDate: dates.taskBusinessDate, verifyBusinessDate: dates.verifyBusinessDate || dates.taskBusinessDate, ...(dates.timeNode.trim() ? { timeNode: dates.timeNode.trim() } : {}) }),
    ...(scope === 'SELECTED' ? { configurationIds } : {}), ...(excludedIds.length ? { excludedIds } : {})
  }
  const validPreview = preview !== null && previewRequest === JSON.stringify(body)
  const canCreate = validPreview && preview.blockedCount === 0 && preview.readyCount > 0 && requestId !== ''
  const invalidate = () => { setPreview(null); setPreviewRequest(''); setRequestId(''); setExcludeSelection([]); setError('') }
  const update = (key: keyof typeof dates, value: string) => { setDates(current => ({ ...current, [key]: value })); invalidate() }
  const preflight = async (request = body) => {
    if (inFlight.current) return
    inFlight.current = true; setBusy('preview'); setError(''); setPreview(null); setRequestId('')
    try {
      const result = await api<BatchPreview>('/api/batches/preview', { method: 'POST', body: JSON.stringify(request) })
      setPreview(result); setPreviewRequest(JSON.stringify(request)); setExcludeSelection([])
      if (result.blockedCount === 0 && result.readyCount > 0) setRequestId(requestIdentity())
    } catch (value) { setError(value instanceof Error ? value.message : '预检失败，请重试') }
    finally { inFlight.current = false; setBusy(null) }
  }
  const submit = (event: FormEvent) => { event.preventDefault(); void preflight() }
  const exclude = () => {
    const next = [...new Set([...excludedIds, ...excludeSelection])].sort((a, b) => a - b)
    setExcludedIds(next)
    void preflight({ ...body, excludedIds: next })
  }
  const create = async () => {
    if (!canCreate || !preview || inFlight.current) return
    inFlight.current = true; setBusy('create'); setError('')
    try {
      const result = await api<BatchSummary>('/api/batches', { method: 'POST', body: JSON.stringify({ ...body, requestId, previewFingerprint: preview.fingerprint }) })
      client.invalidateQueries({ queryKey: ['batches'] }); client.invalidateQueries({ queryKey: ['tasks'] })
      navigate(`/batches/${encodeURIComponent(result.batchId)}`)
    } catch (value) {
      if (value instanceof RequestError && ['BATCH_PREVIEW_STALE', 'BATCH_NOT_READY', 'BATCH_REQUEST_CONFLICT'].includes(value.code)) {
        setPreviewRequest(''); setRequestId(''); setError(`${value.message} 请重新预检。`)
      } else { setError(`${value instanceof Error ? value.message : '提交失败'}。可再次点击“确认发起”，本次请求标识已保留，不会重复创建。`) }
    } finally { inFlight.current = false; setBusy(null) }
  }
  const blockedIds = preview?.items.filter(item => !item.ready).map(item => item.bcompIndex) ?? []
  return <div className="page"><Link className="back-link" to={sourceBatchId ? `/batches/${encodeURIComponent(sourceBatchId)}` : '/batches'}>← 返回核对批次</Link><PageHeader eyebrow="NEW BATCH" title={scope === 'FAILED' ? '重新发起失败项' : '发起核对批次'} description="先校验配置，再一次性创建整批任务。后台按配置并发执行，提交成功后无需保持页面打开。" />
    <form onSubmit={submit}><fieldset disabled={busy !== null} className="batch-form"><div className="batch-scope"><span>执行范围</span><strong>{scope === 'ALL' ? '全部核对配置（包含所有分页）' : scope === 'SELECTED' ? `已选 ${configurationIds.length} 项配置` : `来源批次 ${sourceBatchId} 的执行失败项`}</strong><Link className="row-link" to="/configurations">选择部分配置 →</Link></div>
      {scope === 'FAILED' ? <div className="notice" role="note"><div><strong>保留原任务的日期和时点</strong><p>只重新发起执行失败的任务；按当前配置核对，保留原批次及差异结果。参数在预检结果中展示。</p></div></div> : <div className="batch-fields"><label>任务业务日期<input aria-label="任务业务日期" placeholder="YYYYMMDD" pattern="[0-9]{8}" maxLength={8} value={dates.taskBusinessDate} onChange={event => update('taskBusinessDate', event.target.value)} required /></label><label>核验业务日期<input aria-label="核验业务日期" placeholder="默认同任务日期" pattern="[0-9]{8}" maxLength={8} value={dates.verifyBusinessDate} onChange={event => update('verifyBusinessDate', event.target.value)} /></label><label>统一时点（可选）<input aria-label="统一时点（可选）" placeholder="默认沿用各项配置" value={dates.timeNode} onChange={event => update('timeNode', event.target.value)} /></label></div>}
      {scope === 'SELECTED' && !configurationIds.length && <p className="form-error">尚未选择配置，请返回核对配置勾选后发起。</p>}
      {excludedIds.length > 0 && <div className="batch-excluded"><span>已明确排除 {excludedIds.length} 项：{excludedIds.map(id => `#${id}`).join('、')}</span><button type="button" className="text-button" onClick={() => { setExcludedIds([]); invalidate() }}>恢复所有排除项</button></div>}
      <div className="batch-form-actions"><small>批次号由系统生成。预检不会执行 SQL 或创建任务。</small><button className="button ghost" disabled={scope === 'SELECTED' && !configurationIds.length}>{busy === 'preview' ? '预检中…' : preview ? '重新预检' : '预检配置'}</button></div>
    </fieldset></form>
    {error && <div className="notice error" role="alert">{error}</div>}
    {preview && <section className="section-block" aria-label="预检结果"><div className="section-heading"><div><h2>预检结果</h2><p>任务业务日期 {preview.taskBusinessDate} · 选中 {preview.totalCount} 项 · 可创建 {preview.readyCount} 项 · 需处理 {preview.blockedCount} 项</p></div></div>
      {!validPreview && <div className="notice warning" role="status">预检已失效，请重新预检后提交。</div>}
      {preview.changedCount > 0 && <div className="notice warning" role="status"><div><strong>{preview.changedCount} 项配置已发生变化</strong><p>本次将使用当前配置执行，日期和时点仍保留原任务值。请核对下方标记后发起。</p></div></div>}
      {preview.blockedCount > 0 && <div className="batch-precheck-actions"><label className="confirm-line"><input type="checkbox" aria-label="勾选所有问题项" disabled={busy !== null} checked={blockedIds.length > 0 && blockedIds.every(id => excludeSelection.includes(id))} onChange={event => setExcludeSelection(event.target.checked ? blockedIds : [])} /><span>勾选要排除的问题项</span></label><button className="button ghost small" disabled={!excludeSelection.length || busy !== null} onClick={exclude}>排除勾选的 {excludeSelection.length} 项并重新预检</button></div>}
      <div className="table-wrap batch-preview-table"><table><thead><tr><th>排除</th><th>配置 / 模块</th><th>核验日期</th><th>时点</th><th>预检状态 / 原因</th></tr></thead><tbody>{preview.items.map(item => <tr key={item.bcompIndex}><td>{!item.ready ? <input className="table-checkbox" type="checkbox" aria-label={`排除 ${item.configurationName}`} disabled={busy !== null} checked={excludeSelection.includes(item.bcompIndex)} onChange={event => setExcludeSelection(current => event.target.checked ? [...current, item.bcompIndex] : current.filter(id => id !== item.bcompIndex))} /> : '—'}</td><td><strong>{item.configurationName}</strong><small>#{item.bcompIndex} · {item.module || '未分组'}{item.configurationChanged ? ' · 配置已变化' : ''}</small></td><td>{item.verifyBusinessDate || '—'}</td><td>{item.timeNode || '—'}</td><td>{item.ready ? <span className="status status-state-3">可创建</span> : <span className="error">{item.issueMessage || '配置需要处理'}</span>}</td></tr>)}</tbody></table></div>
      <div className="batch-submit"><p>{preview.blockedCount > 0 ? '存在问题项时不会创建任务。可先修复配置，或明确排除后重新预检。' : `确认后，${preview.readyCount} 个任务将进入同一批次的执行队列。`}</p><button type="button" className="button primary" disabled={!canCreate || busy !== null} onClick={create}>{busy === 'create' ? '正在创建批次…' : '确认发起'}</button></div>
    </section>}
  </div>
}
