import { FormEvent, useState } from 'react'
import type { DatabaseConnectionTestResult, DatabaseSaveRequest, DatabaseSummary } from '../api/types'
import { currentSchemaError } from '../database/currentSchema'

type DatabaseDrawerProps = {
  database?: DatabaseSummary
  isSaving: boolean
  onClose: () => void
  onSave: (value: DatabaseSaveRequest) => void
  onTestDraft: (value: DatabaseSaveRequest) => Promise<DatabaseConnectionTestResult>
  onTestSaved: (id: number, value: { newPassword: string | null }) => Promise<DatabaseConnectionTestResult>
}

export function DatabaseDrawer({ database, isSaving, onClose, onSave, onTestDraft, onTestSaved }: DatabaseDrawerProps) {
  const initial = {
    url: database?.url ?? '',
    user: database?.user ?? '',
    driver: database?.driver ?? 'org.postgresql.Driver'
  }
  const [form, setForm] = useState<DatabaseSaveRequest>({
    databaseId: database?.databaseId ?? 0,
    databaseName: database?.databaseName ?? '',
    url: initial.url,
    user: initial.user,
    driver: initial.driver,
    encryption: database?.encryption ?? '1',
    newPassword: null
  })
  const [isTesting, setIsTesting] = useState(false)
  const [testResult, setTestResult] = useState<DatabaseConnectionTestResult | null>(null)
  const [testError, setTestError] = useState('')
  const busy = isSaving || isTesting
  const schemaError = currentSchemaError(form.url, form.driver)

  const updateForm = (patch: Partial<DatabaseSaveRequest>) => {
    setTestResult(null)
    setTestError('')
    setForm(current => ({ ...current, ...patch }))
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    if (schemaError) { setTestError(schemaError); return }
    onSave({ ...form, newPassword: form.newPassword?.trim() || null })
  }
  const testConnection = async () => {
    if (busy) return
    setTestResult(null)
    setTestError('')
    if (schemaError) { setTestError(schemaError); return }
    const password = form.newPassword?.trim() || null
    const connectionChanged = form.url !== initial.url || form.user !== initial.user || form.driver !== initial.driver
    setIsTesting(true)
    try {
      const result = database && !connectionChanged && !password
        ? await onTestSaved(database.databaseId, { newPassword: null })
        : password
          ? await onTestDraft({ ...form, newPassword: password })
          : null
      if (!result) setTestError('测试当前连接参数时请输入密码')
      else setTestResult(result)
    } catch (value) {
      setTestError(value instanceof Error ? value.message : '数据库连接测试失败')
    } finally {
      setIsTesting(false)
    }
  }

  return <div className="drawer-layer">
    <button className="scrim" aria-label="关闭数据库连接" disabled={busy} onClick={onClose} />
    <section className="drawer" role="dialog" aria-modal="true" aria-busy={busy} aria-label={database ? '编辑数据库连接' : '新建数据库连接'}>
      <div className="drawer-heading"><div><span className="eyebrow">DATABASE</span><h2>{database ? '编辑数据库连接' : '新建数据库连接'}</h2></div><button className="icon-button" aria-label="关闭" disabled={busy} onClick={onClose}>×</button></div>
      <form className="form-stack" onSubmit={submit}>
        <label htmlFor="database-id">连接编号<input id="database-id" type="number" min="1" disabled={!!database || busy} value={form.databaseId || ''} onChange={event => updateForm({ databaseId: Number(event.target.value) })} required /></label>
        <label htmlFor="database-name">连接名称<input id="database-name" disabled={busy} value={form.databaseName} onChange={event => updateForm({ databaseName: event.target.value })} required /></label>
        <label htmlFor="database-url">JDBC URL<input id="database-url" disabled={busy} value={form.url} placeholder="jdbc:mysql://host:3306/database 或 jdbc:postgresql://host:5432/database?currentSchema=业务schema" aria-invalid={Boolean(schemaError)} aria-describedby={schemaError ? 'database-url-help database-url-error' : 'database-url-help'} onChange={event => updateForm({ url: event.target.value })} required /></label>
        <small id="database-url-help">GaussDB/PostgreSQL 兼容连接必须通过 currentSchema 指定 schema；GDB/MySQL 兼容连接不需要 currentSchema。</small>
        {schemaError && <small id="database-url-error" className="field-error" role="alert">{schemaError}</small>}
        <label htmlFor="database-user">用户名<input id="database-user" disabled={busy} value={form.user} onChange={event => updateForm({ user: event.target.value })} required /></label>
        <label htmlFor="database-driver">驱动类<input id="database-driver" list="database-driver-options" disabled={busy} value={form.driver} onChange={event => updateForm({ driver: event.target.value })} required />
          <datalist id="database-driver-options"><option value="org.postgresql.Driver" /><option value="oracle.jdbc.OracleDriver" /><option value="com.mysql.cj.jdbc.Driver" /><option value="com.mysql.jdbc.Driver" /><option value="com.huawei.gaussdb.jdbc.Driver" /><option value="org.opengauss.Driver" /></datalist>
          <small>GaussDB A 兼容模式优先使用 PostgreSQL 驱动；GDB 默认使用 com.mysql.jdbc.Driver。厂商驱动需要在服务与 Spark 节点部署对应 JAR。</small>
        </label>
        <label htmlFor="database-encryption">密码存储<select id="database-encryption" disabled={busy} value={form.encryption} onChange={event => updateForm({ encryption: event.target.value })}><option value="1">AES 加密</option><option value="0">原格式</option></select></label>
        <label htmlFor="database-password">新密码<input id="database-password" aria-label="新密码" type="password" autoComplete="new-password" disabled={busy} value={form.newPassword ?? ''} onChange={event => updateForm({ newPassword: event.target.value })} required={!database} /><small>{database ? '留空时可测试或保留原密码；修改连接参数后测试需重新输入密码' : '创建连接时必须填写'}</small></label>
        {testResult && <div className="connection-test-result success" role="status">连接成功 · {testResult.databaseProduct} {testResult.databaseVersion} · {testResult.elapsedMs} ms</div>}
        {testError && <div className="connection-test-result error" role="alert">{testError}</div>}
        <div className="drawer-actions"><button type="button" className="button ghost" disabled={busy} onClick={onClose}>取消</button><button type="button" className="button secondary" disabled={busy} onClick={testConnection}>{isTesting ? '正在测试' : '测试连接'}</button><button className="button primary" disabled={busy}>{isSaving ? '正在保存' : '保存连接'}</button></div>
      </form>
    </section>
  </div>
}
