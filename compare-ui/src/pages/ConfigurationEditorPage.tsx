import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ConfigurationDetail, ConfigurationOptions, ConfigurationSaveResult, DatabaseSummary, PageResult } from '../api/types'
import { FieldRulesEditor } from '../components/FieldRulesEditor'
import { SaveWarning } from '../components/SaveWarning'

const empty: ConfigurationDetail = { basic: { bcompIndex: 0, name: '', module: '', memo: '', range: '', timeNode: '', state: '', partitionNum: 1, dateMode: '1' }, tableSql: { origDatabaseId: 0, destDatabaseId: 0, origSql: '', destSql: '' }, fields: [{ name: '', type: '1', memo: '', oldIndex: 1, newIndex: 1, indexFlag: '1', enumFlag: '0', enumIndex: null, partitionFlag: '0', ignoreValue: null, ignoreFlag: '0' }] }

export function ConfigurationEditorPage() {
  const raw = useLocation().pathname.split('/').filter(Boolean)[1], creating = raw === 'new', index = Number(raw)
  const navigate = useNavigate(), [model, setModel] = useState<ConfigurationDetail>(empty), [warning, setWarning] = useState<string | null>(null), [validation, setValidation] = useState('')
  const detail = useQuery({ queryKey: ['configuration', index], queryFn: () => api<ConfigurationDetail>(`/api/configurations/${index}`), enabled: !creating && Number.isFinite(index) })
  const options = useQuery({ queryKey: ['configuration-options'], queryFn: () => api<ConfigurationOptions>('/api/configuration-options') })
  const databases = useQuery({ queryKey: ['databases-all'], queryFn: () => api<PageResult<DatabaseSummary>>('/api/databases?page=0&size=100') })
  useEffect(() => { if (detail.data) setModel(detail.data) }, [detail.data])
  const save = useMutation({ mutationFn: () => api<ConfigurationSaveResult>(creating ? '/api/configurations' : `/api/configurations/${index}`, { method: creating ? 'POST' : 'PUT', body: JSON.stringify(model) }), onSuccess: result => { setWarning(result.warning ?? null); if (creating) navigate(`/configurations/${result.configuration.basic.bcompIndex}`, { replace: true }) }, onError: (error: Error) => setValidation(error.message) })
  const localError = useMemo(() => {
    const old = model.fields.map(field => field.oldIndex), next = model.fields.map(field => field.newIndex)
    if (!model.basic.bcompIndex || !model.basic.name || !model.basic.state || !model.tableSql.origDatabaseId || !model.tableSql.destDatabaseId || !model.tableSql.origSql || !model.tableSql.destSql || !model.fields.length) return '请完整填写基本信息、数据库、SQL 和字段规则'
    if (new Set(old).size !== old.length || new Set(next).size !== next.length) return '两侧字段位置必须各自唯一'
    if (!model.fields.some(field => field.indexFlag === '1')) return '至少配置一个业务键字段'
    if (model.fields.filter(field => field.partitionFlag === '1').length > 1) return '最多配置一个分区字段'
    return ''
  }, [model])
  const submit = (event: FormEvent) => { event.preventDefault(); setValidation(localError); if (!localError) save.mutate() }
  const basic = (patch: Partial<ConfigurationDetail['basic']>) => setModel(value => ({ ...value, basic: { ...value.basic, ...patch } }))
  const sql = (patch: Partial<ConfigurationDetail['tableSql']>) => setModel(value => ({ ...value, tableSql: { ...value.tableSql, ...patch } }))
  return <form className="page" onSubmit={submit}><div className="page-heading"><div><span className="eyebrow">CONFIG EDITOR</span><h1>{creating ? '新建核对配置' : `编辑配置 #${index}`}</h1><p>一次保存完整配置聚合；运行中的配置不可修改。</p></div><button className="button primary" disabled={save.isPending}>保存配置</button></div><SaveWarning message={warning} />{validation && <div className="notice error" role="alert">{validation}</div>}
    <section className="section-block" aria-label="基本信息"><div className="section-heading"><div><h2>基本信息</h2><p>继续使用原表字段和编码。</p></div></div><div className="editor-grid"><label>配置编号<input type="number" min="1" disabled={!creating} value={model.basic.bcompIndex || ''} onChange={e => basic({ bcompIndex: Number(e.target.value) })} /></label><label>配置名称<input value={model.basic.name} onChange={e => basic({ name: e.target.value })} /></label><label>所属模块<input value={model.basic.module} onChange={e => basic({ module: e.target.value })} /></label><label>状态<select value={model.basic.state} onChange={e => basic({ state: e.target.value })}><option value="">请选择</option>{options.data?.states.map(value => <option key={value}>{value}</option>)}</select></label><label>时点<input value={model.basic.timeNode} onChange={e => basic({ timeNode: e.target.value })} /></label><label>分区数<input type="number" min="1" value={model.basic.partitionNum ?? ''} onChange={e => basic({ partitionNum: e.target.value ? Number(e.target.value) : null })} /></label><label>日期模式<select value={model.basic.dateMode} onChange={e => basic({ dateMode: e.target.value })}><option value="1">1 · 同日</option><option value="2">2 · 跨日</option></select></label><label className="span-2">备注<input value={model.basic.memo} onChange={e => basic({ memo: e.target.value })} /></label></div></section>
    <section className="section-block" aria-label="两侧查询"><div className="section-heading"><div><h2>两侧查询</h2><p>SQL 原样保存，不在页面执行或格式化。</p></div></div><div className="sql-grid"><div><label>旧侧数据库<select value={model.tableSql.origDatabaseId || ''} onChange={e => sql({ origDatabaseId: Number(e.target.value) })}><option value="">请选择</option>{databases.data?.items.map(db => <option key={db.databaseId} value={db.databaseId}>{db.databaseId} · {db.databaseName}</option>)}</select></label><label>旧侧 SQL<textarea rows={10} value={model.tableSql.origSql} onChange={e => sql({ origSql: e.target.value })} /></label></div><div><label>新侧数据库<select value={model.tableSql.destDatabaseId || ''} onChange={e => sql({ destDatabaseId: Number(e.target.value) })}><option value="">请选择</option>{databases.data?.items.map(db => <option key={db.databaseId} value={db.databaseId}>{db.databaseId} · {db.databaseName}</option>)}</select></label><label>新侧 SQL<textarea rows={10} value={model.tableSql.destSql} onChange={e => sql({ destSql: e.target.value })} /></label></div></div></section>
    <FieldRulesEditor fields={model.fields} onChange={fields => setModel(value => ({ ...value, fields }))} />
  </form>
}
