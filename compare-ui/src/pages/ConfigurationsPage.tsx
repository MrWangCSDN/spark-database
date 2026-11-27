import { FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, query } from '../api/client'
import type { BasicConfiguration, ConfigurationSaveResult, PageResult } from '../api/types'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { FilterBar } from '../components/FilterBar'

export function ConfigurationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const keyword = (searchParams.get('keyword') ?? '').trim()
  const rawPage = Number(searchParams.get('page') ?? 0), page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  const [draft, setDraft] = useState(keyword), [copy, setCopy] = useState<BasicConfiguration | null>(null), [newIndex, setNewIndex] = useState(''), [newName, setNewName] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const client = useQueryClient(), navigate = useNavigate()
  const list = useQuery({ queryKey: ['configurations', keyword, page], queryFn: () => api<PageResult<BasicConfiguration>>(query('/api/configurations', { keyword, page, size: 20 })) })
  useEffect(() => { setDraft(keyword) }, [searchParams, keyword])
  useEffect(() => {
    if (!list.isSuccess || list.isFetching || !list.data) return
    const lastPage = Math.max(0, Math.ceil(list.data.total / 20) - 1)
    if (page <= lastPage) return
    const next = new URLSearchParams(searchParams)
    if (lastPage > 0) next.set('page', String(lastPage)); else next.delete('page')
    setSearchParams(next, { replace: true })
  }, [list.data, list.isSuccess, list.isFetching, page, searchParams, setSearchParams])
  const copyMutation = useMutation({ mutationFn: () => api<ConfigurationSaveResult>(`/api/configurations/${copy?.bcompIndex}/copy`, { method: 'POST', body: JSON.stringify({ newIndex: Number(newIndex), newName }) }), onSuccess: () => { client.invalidateQueries({ queryKey: ['configurations'] }); navigate(`/configurations/${newIndex}`) } })
  const submit = (event: FormEvent) => { event.preventDefault(); copyMutation.mutate() }
  const updateSearch = (nextKeyword: string, nextPage: number) => {
    const next = new URLSearchParams(searchParams)
    if (nextKeyword) next.set('keyword', nextKeyword); else next.delete('keyword')
    if (nextPage > 0) next.set('page', String(nextPage)); else next.delete('page')
    setSearchParams(next)
  }
  const applyFilters = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); const next = draft.trim(); setDraft(next); updateSearch(next, 0) }
  const resetFilters = () => { setDraft(''); updateSearch('', 0) }
  return <div className="page configurations-page"><PageHeader eyebrow="CONFIGURATION" title="核对配置" description="导入 Axon 生成的三表脚本，并维护已导入配置的基本信息、两侧 SQL 与字段规则。" actions={<><Link className="button ghost" to="/configurations/new">导入 Axon 配置</Link><Link className="button primary" to="/batches/new">发起全部比对</Link></>} />
    <FilterBar label="配置筛选" onSubmit={applyFilters} onReset={resetFilters}><label>配置名称<input maxLength={100} placeholder="配置名称、标识或真实表名" value={draft} onChange={event => setDraft(event.target.value)} /></label></FilterBar>
    <div className="toolbar"><span><strong>{list.data?.total ?? 0}</strong> 个配置</span><button className="button ghost small" onClick={() => list.refetch()}>刷新列表</button></div>
    {list.isLoading && <ContentState state="loading" title="正在加载核对配置" />}{list.isError && <ContentState state="error" title="核对配置加载失败" />}{list.data?.total === 0 && <ContentState state="empty" title={keyword ? '没有符合条件的配置' : '还没有核对配置'} description={keyword ? '调整配置名称关键词，或重置筛选。' : '导入 Axon 配置后才能创建数据库核对任务。'} />}
    <div className="selection-toolbar"><span>跨页已选 <strong>{selected.length}</strong> 项</span><button className="text-button" disabled={!selected.length} onClick={() => setSelected([])}>清空选择</button><button className="button ghost small" disabled={!selected.length} onClick={() => navigate('/batches/new?scope=SELECTED', { state: { configurationIds: selected } })}>发起所选比对</button></div>
    {!!list.data?.items.length && <div className="table-wrap"><table><thead><tr><th><input className="table-checkbox" type="checkbox" aria-label="选择当前页所有配置" checked={!!list.data.items.length && list.data.items.every(item => selected.includes(item.bcompIndex))} onChange={event => { const pageIds = list.data!.items.map(item => item.bcompIndex); setSelected(current => event.target.checked ? [...new Set([...current, ...pageIds])] : current.filter(id => !pageIds.includes(id))) }} /></th><th>配置标识 / 名称</th><th>真实表名</th><th>查询条件</th><th>模块</th><th>状态</th><th>日期模式</th><th></th></tr></thead><tbody>{list.data.items.map(item => <tr key={item.bcompIndex}><td><input className="table-checkbox" type="checkbox" aria-label={`选择 ${item.name}`} checked={selected.includes(item.bcompIndex)} onChange={event => setSelected(current => event.target.checked ? [...current, item.bcompIndex] : current.filter(id => id !== item.bcompIndex))} /></td><td><strong>{item.configurationName || item.name}</strong><small>{item.configurationName && item.configurationName !== item.name && <><span>{item.name}</span> · </>}#{item.bcompIndex}</small></td><td>{item.sourceTableName || '—'}</td><td className="multiline">{item.configurationScope || '—'}</td><td>{item.module || '—'}</td><td>{item.state}</td><td>{item.dateMode === '1' ? '同日' : item.dateMode === '2' ? '指定核验日期' : '未指定'}</td><td className="row-actions"><Link className="row-link" to={`/configurations/${item.bcompIndex}`}>编辑</Link><button className="text-button" onClick={() => { setCopy(item); setNewName(`${item.name}_copy`) }}>复制</button></td></tr>)}</tbody></table></div>}{list.data && list.data.total > 0 && <Pagination page={page} size={20} total={list.data.total} onChange={nextPage => updateSearch(keyword, nextPage)} />}
    {copy && <div className="modal-layer"><button className="scrim" aria-label="关闭复制" onClick={() => setCopy(null)} /><form className="modal" role="dialog" aria-label="复制核对配置" onSubmit={submit}><div className="drawer-heading"><h2>复制核对配置</h2><button type="button" className="icon-button" aria-label="关闭" onClick={() => setCopy(null)}>×</button></div><p>从 #{copy.bcompIndex} {copy.name} 复制配置，不复制历史任务。</p><label>新配置编号<input type="number" min="1" value={newIndex} onChange={e => setNewIndex(e.target.value)} required /></label><label>新配置标识<input maxLength={100} value={newName} onChange={e => setNewName(e.target.value)} required /></label><div className="drawer-actions"><button className="button primary">创建副本</button></div></form></div>}
  </div>
}
