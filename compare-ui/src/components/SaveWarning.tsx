import { useState } from 'react'
import { api } from '../api/client'

type SaveWarningProps = {
  message?: string | null
  savedTitle?: string
  successMessage?: string
}

export function SaveWarning({ message, savedTitle = '配置已保存', successMessage = '新配置已加载到当前服务实例。' }: SaveWarningProps) {
  const [state, setState] = useState<'idle' | 'loading' | 'success' | 'failed'>('idle')
  if (!message) return null
  const refresh = async () => {
    setState('loading')
    try {
      const result = await api<{ code: number }>('/api/cache/refresh', { method: 'POST' })
      setState(result.code === 200 ? 'success' : 'failed')
    } catch { setState('failed') }
  }
  return <div className="notice warning" role="status"><div><strong>{state === 'success' ? '缓存已刷新' : savedTitle}</strong><p>{state === 'failed' ? '缓存刷新仍未成功，请稍后重试' : state === 'success' ? successMessage : message}</p></div>{state !== 'success' && <button className="button ghost small" disabled={state === 'loading'} onClick={refresh}>{state === 'loading' ? '刷新中…' : '重试刷新缓存'}</button>}</div>
}
