import { FormEvent, useState } from 'react'
import type { DatabaseSaveRequest, DatabaseSummary } from '../api/types'

type DatabaseDrawerProps = {
  database?: DatabaseSummary
  isSaving: boolean
  onClose: () => void
  onSave: (value: DatabaseSaveRequest) => void
}

export function DatabaseDrawer({ database, isSaving, onClose, onSave }: DatabaseDrawerProps) {
  const [form, setForm] = useState<DatabaseSaveRequest>({
    databaseId: database?.databaseId ?? 0,
    databaseName: database?.databaseName ?? '',
    url: database?.url ?? '',
    user: database?.user ?? '',
    driver: database?.driver ?? 'org.postgresql.Driver',
    encryption: database?.encryption ?? '1',
    newPassword: null
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (isSaving) return
    onSave({ ...form, newPassword: form.newPassword?.trim() || null })
  }
  return <div className="drawer-layer">
    <button className="scrim" aria-label="关闭数据库连接" disabled={isSaving} onClick={onClose} />
    <section className="drawer" role="dialog" aria-modal="true" aria-busy={isSaving} aria-label={database ? '编辑数据库连接' : '新建数据库连接'}>
      <div className="drawer-heading"><div><span className="eyebrow">DATABASE</span><h2>{database ? '编辑数据库连接' : '新建数据库连接'}</h2></div><button className="icon-button" aria-label="关闭" disabled={isSaving} onClick={onClose}>×</button></div>
      <form className="form-stack" onSubmit={submit}>
        <label htmlFor="database-id">连接编号<input id="database-id" type="number" min="1" disabled={!!database || isSaving} value={form.databaseId || ''} onChange={event => setForm({ ...form, databaseId: Number(event.target.value) })} required /></label>
        <label htmlFor="database-name">连接名称<input id="database-name" disabled={isSaving} value={form.databaseName} onChange={event => setForm({ ...form, databaseName: event.target.value })} required /></label>
        <label htmlFor="database-url">JDBC URL<input id="database-url" disabled={isSaving} value={form.url} onChange={event => setForm({ ...form, url: event.target.value })} required /></label>
        <label htmlFor="database-user">用户名<input id="database-user" disabled={isSaving} value={form.user} onChange={event => setForm({ ...form, user: event.target.value })} required /></label>
        <label htmlFor="database-driver">驱动类<input id="database-driver" disabled={isSaving} value={form.driver} onChange={event => setForm({ ...form, driver: event.target.value })} required /></label>
        <label htmlFor="database-encryption">密码存储<select id="database-encryption" disabled={isSaving} value={form.encryption} onChange={event => setForm({ ...form, encryption: event.target.value })}><option value="1">AES 加密</option><option value="0">原格式</option></select></label>
        <label htmlFor="database-password">新密码<input id="database-password" aria-label="新密码" type="password" autoComplete="new-password" disabled={isSaving} value={form.newPassword ?? ''} onChange={event => setForm({ ...form, newPassword: event.target.value })} required={!database} /><small>{database ? '留空表示保留原密码' : '创建连接时必须填写'}</small></label>
        <div className="drawer-actions"><button type="button" className="button ghost" disabled={isSaving} onClick={onClose}>取消</button><button className="button primary" disabled={isSaving}>{isSaving ? '正在保存' : '保存连接'}</button></div>
      </form>
    </section>
  </div>
}
