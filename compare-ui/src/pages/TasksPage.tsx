import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, query } from '../api/client'
import type { BasicConfiguration, PageResult, TaskSummary } from '../api/types'
import { Pagination } from '../components/Pagination'
import { StatusBadge } from '../components/StatusBadge'

export function TasksPage() {
  const [page, setPage] = useState(0), [open, setOpen] = useState(false), [error, setError] = useState('')
  const [form, setForm] = useState({ bcompIndex: '', taskBusinessDate: '', verifyBusinessDate: '', taskBatch: '', timeNode: '' })
  const client = useQueryClient()
  const tasks = useQuery({ queryKey: ['tasks', page], queryFn: () => api<PageResult<TaskSummary>>(query('/api/tasks', { page, size: 20 })), refetchInterval: result => document.visibilityState === 'visible' && result.state.data?.items.some(item => item.taskState === '1' || item.taskState === '2') ? 10000 : false })
  const configurations = useQuery({ queryKey: ['configuration-options-list'], queryFn: () => api<PageResult<BasicConfiguration>>('/api/configurations?page=0&size=100'), enabled: open })
  const create = useMutation({ mutationFn: (body: object) => api<TaskSummary>('/api/tasks', { method: 'POST', body: JSON.stringify(body) }), onSuccess: () => { setOpen(false); client.invalidateQueries({ queryKey: ['tasks'] }) }, onError: (value: Error) => setError(value.message) })
  const submit = (event: FormEvent) => {
    event.preventDefault(); setError('')
    if (!form.bcompIndex || !form.taskBusinessDate || !form.taskBatch || !form.timeNode) { setError('请完整填写任务信息'); return }
    create.mutate({ ...form, bcompIndex: Number(form.bcompIndex), verifyBusinessDate: form.verifyBusinessDate || form.taskBusinessDate })
  }
  return <div className="page"><div className="page-heading"><div><span className="eyebrow">TASKS</span><h1>核对任务</h1><p>创建任务并跟踪 Spark 核对运行状态与差异结果。</p></div><button className="button primary" onClick={() => setOpen(true)}>新建核对任务</button></div>
    <div className="toolbar"><span>{tasks.data?.total ?? 0} 个任务</span><button className="button ghost small" onClick={() => tasks.refetch()}>刷新</button></div>
    <div className="table-wrap"><table><thead><tr><th>任务</th><th>业务日期 / 批次</th><th>状态</th><th>结果</th><th>创建时间</th><th></th></tr></thead><tbody>{tasks.data?.items.map(item => <tr key={`${item.bcompIndex}-${item.taskBusinessDate}-${item.taskBatch}-${item.timeNode}`}><td><strong>{item.configurationName}</strong><small>#{item.bcompIndex} · 时点 {item.timeNode}</small></td><td>{item.taskBusinessDate}<small>批次 {item.taskBatch}</small></td><td><StatusBadge value={item.taskState} /></td><td><StatusBadge value={item.taskResult} kind="result" /></td><td className="mono">{formatTime(item.createdAt)}</td><td><Link className="row-link" to={`/tasks/${item.bcompIndex}/${item.taskBusinessDate}/${item.taskBatch}/${item.timeNode}`}>查看</Link></td></tr>)}</tbody></table>{tasks.isLoading && <div className="empty">正在加载任务…</div>}{tasks.isError && <div className="empty error">任务加载失败</div>}</div>
    {tasks.data && <Pagination page={page} size={20} total={tasks.data.total} onChange={setPage} />}
    {open && <div className="drawer-layer"><button className="scrim" aria-label="关闭新建任务" onClick={() => setOpen(false)} /><section className="drawer" role="dialog" aria-modal="true" aria-label="新建核对任务"><div className="drawer-heading"><div><span className="eyebrow">NEW TASK</span><h2>新建核对任务</h2></div><button className="icon-button" aria-label="关闭" onClick={() => setOpen(false)}>×</button></div><form onSubmit={submit} className="form-stack">
      <label>核对配置<select aria-label="核对配置" value={form.bcompIndex} onChange={e => setForm({ ...form, bcompIndex: e.target.value })}><option value="">请选择</option>{configurations.data?.items.map(item => <option key={item.bcompIndex} value={item.bcompIndex}>{item.bcompIndex} · {item.name}</option>)}</select></label>
      <div className="form-grid"><label>任务业务日期<input aria-label="任务业务日期" placeholder="YYYYMMDD" value={form.taskBusinessDate} onChange={e => setForm({ ...form, taskBusinessDate: e.target.value })} /></label><label>核验业务日期<input aria-label="核验业务日期" placeholder="默认同任务日期" value={form.verifyBusinessDate} onChange={e => setForm({ ...form, verifyBusinessDate: e.target.value })} /></label></div>
      <div className="form-grid"><label>批次<input aria-label="批次" value={form.taskBatch} onChange={e => setForm({ ...form, taskBatch: e.target.value })} /></label><label>时点<input aria-label="时点" value={form.timeNode} onChange={e => setForm({ ...form, timeNode: e.target.value })} /></label></div>
      {error && <p className="form-error" role="alert">{error}</p>}<div className="drawer-actions"><button type="button" className="button ghost" onClick={() => setOpen(false)}>取消</button><button className="button primary" disabled={create.isPending}>创建任务</button></div>
    </form></section></div>}
  </div>
}

export function formatTime(value?: string | null) { return value && value.length === 14 ? `${value.slice(0,4)}-${value.slice(4,6)}-${value.slice(6,8)} ${value.slice(8,10)}:${value.slice(10,12)}:${value.slice(12)}` : value || '—' }
