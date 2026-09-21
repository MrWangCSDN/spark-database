import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api, query } from '../api/client'
import type { BasicConfiguration, PageResult, TaskSummary } from '../api/types'
import { ContentState } from '../components/ContentState'
import { FilterBar } from '../components/FilterBar'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'

type TaskFilters = { bcompIndex: string; taskState: string; taskResult: string; dateFrom: string; dateTo: string; keyword: string; sort: string }
const emptyFilters: TaskFilters = { bcompIndex: '', taskState: '', taskResult: '', dateFrom: '', dateTo: '', keyword: '', sort: 'createdAt,desc' }
const filterKeys = Object.keys(emptyFilters) as Array<keyof TaskFilters>

function readFilters(params: URLSearchParams): TaskFilters {
  return filterKeys.reduce((value, key) => ({ ...value, [key]: params.get(key) ?? emptyFilters[key] }), emptyFilters)
}

export function TasksPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const committed = readFilters(searchParams)
  const page = Math.max(0, Number(searchParams.get('page')) || 0)
  const [draft, setDraft] = useState<TaskFilters>(committed), [open, setOpen] = useState(false), [error, setError] = useState('')
  const [form, setForm] = useState({ bcompIndex: '', taskBusinessDate: '', verifyBusinessDate: '', taskBatch: '', timeNode: '' })
  const client = useQueryClient()
  const tasks = useQuery({ queryKey: ['tasks', page, committed], queryFn: () => api<PageResult<TaskSummary>>(query('/api/tasks', { ...committed, page, size: 20 })), refetchInterval: result => document.visibilityState === 'visible' && result.state.data?.items.some(item => item.taskState === '1' || item.taskState === '2') ? 10000 : false })
  const configurations = useQuery({ queryKey: ['configuration-options-list'], queryFn: () => api<PageResult<BasicConfiguration>>('/api/configurations?page=0&size=100'), enabled: open })
  const create = useMutation({ mutationFn: (body: object) => api<TaskSummary>('/api/tasks', { method: 'POST', body: JSON.stringify(body) }), onSuccess: () => { setOpen(false); client.invalidateQueries({ queryKey: ['tasks'] }) }, onError: (value: Error) => setError(value.message) })
  const submit = (event: FormEvent) => {
    event.preventDefault(); setError('')
    if (!form.bcompIndex || !form.taskBusinessDate || !form.taskBatch || !form.timeNode) { setError('请完整填写任务信息'); return }
    create.mutate({ ...form, bcompIndex: Number(form.bcompIndex), verifyBusinessDate: form.verifyBusinessDate || form.taskBusinessDate })
  }
  const applyFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const next = new URLSearchParams()
    filterKeys.forEach(key => { if (draft[key] && !(key === 'sort' && draft[key] === emptyFilters.sort)) next.set(key, draft[key]) })
    setSearchParams(next)
  }
  const resetFilters = () => { setDraft(emptyFilters); setSearchParams(new URLSearchParams()) }
  const changePage = (nextPage: number) => { const next = new URLSearchParams(searchParams); if (nextPage > 0) next.set('page', String(nextPage)); else next.delete('page'); setSearchParams(next) }
  const update = (key: keyof TaskFilters, value: string) => setDraft(current => ({ ...current, [key]: value }))

  return <div className="page"><PageHeader eyebrow="TASKS" title="核对任务" description="筛选并跟踪 Spark 核对运行状态、结果和差异。" actions={<button className="button primary" onClick={() => setOpen(true)}>新建核对任务</button>} />
    <FilterBar onSubmit={applyFilters} onReset={resetFilters}>
      <label>关键词<input aria-label="关键词" placeholder="配置名称或批次" value={draft.keyword} onChange={event => update('keyword', event.target.value)} /></label>
      <label>配置编号<input aria-label="配置编号" inputMode="numeric" value={draft.bcompIndex} onChange={event => update('bcompIndex', event.target.value)} /></label>
      <label>任务状态<select aria-label="任务状态" value={draft.taskState} onChange={event => update('taskState', event.target.value)}><option value="">全部状态</option><option value="1">等待</option><option value="2">运行中</option><option value="3">完成</option><option value="4">失败</option></select></label>
      <label>核对结果<select aria-label="核对结果" value={draft.taskResult} onChange={event => update('taskResult', event.target.value)}><option value="">全部结果</option><option value="2">一致</option><option value="1">有差异 / 失败</option></select></label>
      <label>开始日期<input aria-label="开始日期" placeholder="YYYYMMDD" value={draft.dateFrom} onChange={event => update('dateFrom', event.target.value)} /></label>
      <label>结束日期<input aria-label="结束日期" placeholder="YYYYMMDD" value={draft.dateTo} onChange={event => update('dateTo', event.target.value)} /></label>
      <label>排序<select aria-label="排序" value={draft.sort} onChange={event => update('sort', event.target.value)}><option value="createdAt,desc">创建时间倒序</option><option value="startedAt,desc">开始时间倒序</option><option value="endedAt,desc">结束时间倒序</option><option value="bcompIndex,asc">配置编号正序</option></select></label>
    </FilterBar>
    <output className="sr-only" data-testid="task-search" aria-label="当前任务筛选参数">{searchParams.toString()}</output>
    <div className="toolbar"><span><strong>{tasks.data?.total ?? 0}</strong> 个任务</span><button className="button ghost small" onClick={() => tasks.refetch()}>刷新列表</button></div>
    {tasks.isLoading && <ContentState state="loading" title="正在加载任务" />}
    {tasks.isError && <ContentState state="error" title="任务加载失败" description="请检查服务状态后重试。" action={<button className="button ghost small" onClick={() => tasks.refetch()}>重新加载</button>} />}
    {tasks.data?.total === 0 && <ContentState state="empty" title="没有符合条件的任务" description="调整筛选条件，或创建一个新的核对任务。" />}
    {!!tasks.data?.items.length && <div className="table-wrap"><table><thead><tr><th>任务</th><th>业务日期 / 批次</th><th>状态</th><th>结果</th><th className="hide-narrow">创建时间</th><th></th></tr></thead><tbody>{tasks.data.items.map(item => <tr key={`${item.bcompIndex}-${item.taskBusinessDate}-${item.taskBatch}-${item.timeNode}`}><td><strong>{item.configurationName}</strong><small>#{item.bcompIndex} · 时点 {item.timeNode}</small></td><td>{item.taskBusinessDate}<small>批次 {item.taskBatch}</small></td><td><StatusBadge value={item.taskState} /></td><td><StatusBadge value={item.taskResult} kind="result" taskState={item.taskState} /></td><td className="mono hide-narrow">{formatTime(item.createdAt)}</td><td><Link className="row-link" to={`/tasks/${item.bcompIndex}/${item.taskBusinessDate}/${item.taskBatch}/${item.timeNode}`}>查看</Link></td></tr>)}</tbody></table></div>}
    {tasks.data && tasks.data.total > 0 && <Pagination page={page} size={20} total={tasks.data.total} onChange={changePage} />}
    {open && <div className="drawer-layer"><button className="scrim" aria-label="关闭新建任务" onClick={() => setOpen(false)} /><section className="drawer" role="dialog" aria-modal="true" aria-label="新建核对任务"><div className="drawer-heading"><div><span className="eyebrow">NEW TASK</span><h2>新建核对任务</h2><p>任务将进入等待状态，由调度线程领取。</p></div><button className="icon-button" aria-label="关闭" onClick={() => setOpen(false)}>×</button></div><form onSubmit={submit} className="form-stack">
      <label>核对配置<select aria-label="核对配置" value={form.bcompIndex} onChange={e => setForm({ ...form, bcompIndex: e.target.value })}><option value="">请选择</option>{configurations.data?.items.map(item => <option key={item.bcompIndex} value={item.bcompIndex}>{item.bcompIndex} · {item.name}</option>)}</select></label>
      <div className="form-grid"><label>任务业务日期<input aria-label="任务业务日期" placeholder="YYYYMMDD" value={form.taskBusinessDate} onChange={e => setForm({ ...form, taskBusinessDate: e.target.value })} /></label><label>核验业务日期<input aria-label="核验业务日期" placeholder="默认同任务日期" value={form.verifyBusinessDate} onChange={e => setForm({ ...form, verifyBusinessDate: e.target.value })} /></label></div>
      <div className="form-grid"><label>批次<input aria-label="批次" value={form.taskBatch} onChange={e => setForm({ ...form, taskBatch: e.target.value })} /></label><label>时点<input aria-label="时点" value={form.timeNode} onChange={e => setForm({ ...form, timeNode: e.target.value })} /></label></div>
      {error && <p className="form-error" role="alert">{error}</p>}<div className="drawer-actions"><button type="button" className="button ghost" onClick={() => setOpen(false)}>取消</button><button className="button primary" disabled={create.isPending}>创建任务</button></div>
    </form></section></div>}
  </div>
}

export function formatTime(value?: string | null) { return value && value.length === 14 ? `${value.slice(0,4)}-${value.slice(4,6)}-${value.slice(6,8)} ${value.slice(8,10)}:${value.slice(10,12)}:${value.slice(12)}` : value || '—' }
