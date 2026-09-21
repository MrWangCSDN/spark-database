import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { DatabaseConnectionTestResult, DatabaseSaveRequest, DatabaseSummary, PageResult } from '../api/types'
import { ContentState } from '../components/ContentState'
import { DatabaseDrawer } from '../components/DatabaseDrawer'
import { PageHeader } from '../components/PageHeader'
import { SaveWarning } from '../components/SaveWarning'

export function DatabasesPage() {
  const [selected, setSelected] = useState<DatabaseSummary | undefined>()
  const [open, setOpen] = useState(false)
  const [error, setError] = useState('')
  const [warning, setWarning] = useState<string | null>(null)
  const client = useQueryClient()
  const list = useQuery({ queryKey: ['databases'], queryFn: () => api<PageResult<DatabaseSummary>>('/api/databases?page=0&size=100') })
  const save = useMutation({
    mutationFn: (value: DatabaseSaveRequest) => api<DatabaseSummary>(selected ? `/api/databases/${selected.databaseId}` : '/api/databases', { method: selected ? 'PUT' : 'POST', body: JSON.stringify(value) }),
    onSuccess: async () => {
      setOpen(false)
      setSelected(undefined)
      client.invalidateQueries({ queryKey: ['databases'] })
      try {
        const refresh = await api<{ code: number; msg: string }>('/api/cache/refresh', { method: 'POST' })
        setWarning(refresh.code === 200 ? null : '连接已保存，但缓存刷新失败')
      } catch {
        setWarning('连接已保存，但缓存刷新失败')
      }
    },
    onError: (value: Error) => setError(value.message)
  })
  const testDraft = (value: DatabaseSaveRequest) => api<DatabaseConnectionTestResult>('/api/databases/test', { method: 'POST', body: JSON.stringify(value) })
  const testSaved = (id: number, value: { newPassword: string | null }) => api<DatabaseConnectionTestResult>(`/api/databases/${id}/test`, { method: 'POST', body: JSON.stringify(value) })
  const openDrawer = (database?: DatabaseSummary) => {
    setError('')
    setWarning(null)
    setSelected(database)
    setOpen(true)
  }

  return <div className="page"><PageHeader eyebrow="DATABASES" title="数据库连接" description="管理 Spark 任务读取两侧数据所需的 JDBC 连接。" actions={<button className="button primary" onClick={() => openDrawer()}>新建连接</button>} />{error && <div className="notice error" role="alert">{error}</div>}
    <SaveWarning message={warning} savedTitle="连接已保存" successMessage="新连接已加载到当前服务实例。" />
    <div className="toolbar"><span><strong>{list.data?.total ?? 0}</strong> 个连接</span><button className="button ghost small" onClick={() => list.refetch()}>刷新列表</button></div>{list.isLoading && <ContentState state="loading" title="正在加载数据库连接" />}{list.isError && <ContentState state="error" title="数据库连接加载失败" />}{list.data?.total === 0 && <ContentState state="empty" title="还没有数据库连接" description="新建连接后才能配置两侧查询。" />}
    {!!list.data?.items.length && <div className="table-wrap"><table><thead><tr><th>编号 / 名称</th><th>JDBC URL</th><th>用户</th><th className="hide-narrow">驱动</th><th>密码</th><th></th></tr></thead><tbody>{list.data.items.map(item => <tr key={item.databaseId}><td><strong>{item.databaseName}</strong><small>#{item.databaseId}</small></td><td className="mono truncate">{item.url}</td><td>{item.user}</td><td className="mono truncate hide-narrow">{item.driver}</td><td>{item.passwordConfigured ? '已配置 · 密码不回显' : '未配置'}</td><td><button className="row-link button-link" aria-label={`编辑 ${item.databaseName}`} onClick={() => openDrawer(item)}>编辑</button></td></tr>)}</tbody></table></div>}
    {open && <DatabaseDrawer database={selected} isSaving={save.isPending} onClose={() => setOpen(false)} onSave={value => save.mutate(value)} onTestDraft={testDraft} onTestSaved={testSaved} />}
  </div>
}
