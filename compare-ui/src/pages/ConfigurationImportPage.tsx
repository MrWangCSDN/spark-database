import { FormEvent, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { ConfigScriptImportResult } from '../api/types'
import { SaveWarning } from '../components/SaveWarning'

const formatSize = (bytes: number) => bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`

export function ConfigurationImportPage() {
  const [file, setFile] = useState<File | null>(null)
  const [script, setScript] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [validation, setValidation] = useState('')
  const client = useQueryClient()
  const mutation = useMutation({
    mutationFn: async () => {
      const source = file ?? new File([script], 'pasted-axon-config.sql', { type: 'application/sql;charset=UTF-8' })
      const body = new FormData()
      body.append('file', source)
      return api<ConfigScriptImportResult>('/api/configurations/import', { method: 'POST', body })
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['configurations'] }),
    onError: (error: Error) => setValidation(error.message)
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.reset()
    if (!file && !script.trim()) { setValidation('请选择 SQL 文件或粘贴脚本'); return }
    if (!confirmed) { setValidation('请确认脚本会整体替换三张配置表'); return }
    setValidation('')
    mutation.mutate()
  }
  const chooseFile = (selected: File | null) => {
    setFile(selected)
    if (selected) setScript('')
    setValidation('')
    mutation.reset()
  }
  const paste = (value: string) => {
    setScript(value)
    if (value) setFile(null)
    setValidation('')
    mutation.reset()
  }
  const result = mutation.data
  return <form className="page import-page" onSubmit={submit}>
    <Link className="back-link" to="/configurations">← 返回核对配置</Link>
    <div className="page-heading"><div><span className="eyebrow">AXON CONFIG IMPORT</span><h1>导入 Axon 配置</h1><p>执行 Axon-Link 已生成的三表 SQL，完成后再到任务页创建 Spark 核对任务。</p></div><button className="button primary" disabled={mutation.isPending}>{mutation.isPending ? '导入中…' : '执行导入'}</button></div>

    <div className="notice warning" role="note"><div><strong>该操作会整体替换核对配置</strong><p>脚本会清空并重建 TSS_BCOMP_CONF_NEW、TSS_BCOMP_FIELD_NEW、TSS_BCOMP_TABLE_SQL_NEW。存在等待或运行任务时后端会拒绝执行。</p></div></div>
    <SaveWarning message={result?.warning} savedTitle="配置已导入" successMessage="新配置已加载到当前服务实例。" />
    {validation && <div className="notice error" role="alert">{validation}</div>}
    {result && <section className="import-result" aria-label="导入结果"><div><span>配置</span><strong>已导入 {result.configurationCount} 个配置</strong></div><div><span>字段</span><strong>{result.fieldCount} 个字段</strong></div><div><span>两侧 SQL</span><strong>{result.tableSqlCount} 条</strong></div></section>}

    <section className="section-block import-source" aria-label="脚本来源"><div className="section-heading"><div><h2>选择脚本来源</h2><p>上传 Axon 下载的 .sql 文件，或直接粘贴完整内容；两种方式只会提交其中一种。</p></div></div>
      <div className="import-source-grid">
        <label className="file-drop">选择 SQL 文件<input type="file" accept=".sql,application/sql,text/plain" onChange={event => chooseFile(event.target.files?.[0] ?? null)} /><span>{file ? file.name : '点击选择 Axon 生成的 SQL 文件'}</span>{file && <small>{formatSize(file.size)}</small>}</label>
        <label>粘贴 SQL<textarea aria-label="粘贴 SQL" rows={16} value={script} placeholder="START TRANSACTION; … COMMIT;" onChange={event => paste(event.target.value)} /></label>
      </div>
    </section>

    <section className="section-block import-check" aria-label="执行确认"><h2>执行前确认</h2><label className="confirm-line"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} /><span>确认执行后会整体替换三张配置表，并且当前没有等待或运行中的核对任务。</span></label><p>服务端会校验表名、列顺序、语句顺序和值格式；其他 SQL、函数和表达式都会被拒绝并回滚。</p></section>
  </form>
}
