import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { DatabaseSaveRequest, DatabaseSummary, PageResult } from '../api/types'
import { DatabaseDrawer } from '../components/DatabaseDrawer'

export function DatabasesPage() {
  const [selected, setSelected] = useState<DatabaseSummary | undefined>(), [open, setOpen] = useState(false), [error, setError] = useState('')
  const client = useQueryClient(), list = useQuery({ queryKey: ['databases'], queryFn: () => api<PageResult<DatabaseSummary>>('/api/databases?page=0&size=100') })
  const save = useMutation({ mutationFn: (value: DatabaseSaveRequest) => api<DatabaseSummary>(selected ? `/api/databases/${selected.databaseId}` : '/api/databases', { method: selected ? 'PUT' : 'POST', body: JSON.stringify(value) }), onSuccess: () => { setOpen(false); setSelected(undefined); client.invalidateQueries({ queryKey: ['databases'] }) }, onError: (value: Error) => setError(value.message) })
  return <div className="page"><div className="page-heading"><div><span className="eyebrow">DATABASES</span><h1>数据库连接</h1><p>管理 Spark 任务读取两侧数据所需的 JDBC 连接。</p></div><button className="button primary" onClick={() => { setSelected(undefined); setOpen(true) }}>新建连接</button></div>{error && <div className="notice error">{error}</div>}
    <div className="table-wrap"><table><thead><tr><th>编号 / 名称</th><th>JDBC URL</th><th>用户</th><th>驱动</th><th>密码</th><th></th></tr></thead><tbody>{list.data?.items.map(item => <tr key={item.databaseId}><td><strong>{item.databaseName}</strong><small>#{item.databaseId}</small></td><td className="mono truncate">{item.url}</td><td>{item.user}</td><td className="mono truncate">{item.driver}</td><td>{item.passwordConfigured ? '已配置 · 不回显' : '未配置'}</td><td><button className="row-link button-link" aria-label={`编辑 ${item.databaseName}`} onClick={() => { setSelected(item); setOpen(true) }}>编辑</button></td></tr>)}</tbody></table></div>
    {open && <DatabaseDrawer database={selected} onClose={() => setOpen(false)} onSave={value => save.mutate(value)} />}
  </div>
}
