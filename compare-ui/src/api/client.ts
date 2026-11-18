import type { ApiError } from './types'

export class RequestError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId?: string) { super(message) }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (!(init?.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...init, headers })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ code: 'HTTP_ERROR', message: '请求失败' })) as ApiError
    throw new RequestError(response.status, body.code, body.message, body.traceId)
  }
  return response.json() as Promise<T>
}

export async function download(path: string, fallbackFilename: string): Promise<void> {
  const response = await fetch(path)
  if (!response.ok) {
    const body = await response.json().catch(() => ({ code: 'HTTP_ERROR', message: '请求失败' })) as ApiError
    throw new RequestError(response.status, body.code, body.message, body.traceId)
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const encoded = disposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1]
  const quoted = disposition.match(/filename\s*=\s*"([^"]+)"/i)?.[1]
  let filename = quoted ?? fallbackFilename
  if (encoded) {
    try { filename = decodeURIComponent(encoded) } catch { filename = quoted ?? fallbackFilename }
  }
  const objectUrl = URL.createObjectURL(await response.blob())
  try {
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

export function query(path: string, values: Record<string, string | number | undefined | null>) {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') params.set(key, String(value)) })
  const text = params.toString()
  return text ? `${path}?${text}` : path
}
