import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { BasicConfiguration, ConfigurationSaveResult, PageResult } from '../api/types'
import { ContentState } from '../components/ContentState'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'

export function ConfigurationsPage() {
  const [page, setPage] = useState(0), [copy, setCopy] = useState<BasicConfiguration | null>(null), [newIndex, setNewIndex] = useState(''), [newName, setNewName] = useState('')
  const client = useQueryClient(), navigate = useNavigate()
  const list = useQuery({ queryKey: ['configurations', page], queryFn: () => api<PageResult<BasicConfiguration>>(`/api/configurations?page=${page}&size=20`) })
  const copyMutation = useMutation({ mutationFn: () => api<ConfigurationSaveResult>(`/api/configurations/${copy?.bcompIndex}/copy`, { method: 'POST', body: JSON.stringify({ newIndex: Number(newIndex), newName }) }), onSuccess: () => { client.invalidateQueries({ queryKey: ['configurations'] }); navigate(`/configurations/${newIndex}`) } })
  const submit = (event: FormEvent) => { event.preventDefault(); copyMutation.mutate() }
  return <div className="page"><PageHeader eyebrow="CONFIGURATION" title="核对配置" description="导入 Axon 生成的三表脚本，并维护已导入配置的基本信息、两侧 SQL 与字段规则。" actions={<Link className="button primary" to="/configurations/new">导入 Axon 配置</Link>} />
    <div className="toolbar"><span><strong>{list.data?.total ?? 0}</strong> 个配置</span><button className="button ghost small" onClick={() => list.refetch()}>刷新列表</button></div>
    {list.isLoading && <ContentState state="loading" title="正在加载核对配置" />}{list.isError && <ContentState state="error" title="核对配置加载失败" />}{list.data?.total === 0 && <ContentState state="empty" title="还没有核对配置" description="导入 Axon 配置后才能创建数据库核对任务。" />}
    {!!list.data?.items.length && <div className="table-wrap"><table><thead><tr><th>编号 / 名称</th><th>模块</th><th>状态</th><th>日期模式</th><th>分区数</th><th></th></tr></thead><tbody>{list.data.items.map(item => <tr key={item.bcompIndex}><td><strong>{item.name}</strong><small>#{item.bcompIndex}</small></td><td>{item.module || '—'}</td><td>{item.state}</td><td>{item.dateMode === '1' ? '同日' : '跨日'}</td><td>{item.partitionNum ?? '—'}</td><td className="row-actions"><Link className="row-link" to={`/configurations/${item.bcompIndex}`}>编辑</Link><button className="text-button" onClick={() => { setCopy(item); setNewName(`${item.name} - 副本`) }}>复制</button></td></tr>)}</tbody></table></div>}{list.data && list.data.total > 0 && <Pagination page={page} size={20} total={list.data.total} onChange={setPage} />}
    {copy && <div className="modal-layer"><button className="scrim" aria-label="关闭复制" onClick={() => setCopy(null)} /><form className="modal" role="dialog" aria-label="复制核对配置" onSubmit={submit}><div className="drawer-heading"><h2>复制核对配置</h2><button type="button" className="icon-button" aria-label="关闭" onClick={() => setCopy(null)}>×</button></div><p>从 #{copy.bcompIndex} {copy.name} 复制配置，不复制历史任务。</p><label>新配置编号<input type="number" min="1" value={newIndex} onChange={e => setNewIndex(e.target.value)} required /></label><label>新配置名称<input value={newName} onChange={e => setNewName(e.target.value)} required /></label><div className="drawer-actions"><button className="button primary">创建副本</button></div></form></div>}
  </div>
}
