import type { ApiError } from './types'

export class RequestError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId?: string) { super(message) }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ code: 'HTTP_ERROR', message: '请求失败' })) as ApiError
    throw new RequestError(response.status, body.code, body.message, body.traceId)
  }
  return response.json() as Promise<T>
}

export function query(path: string, values: Record<string, string | number | undefined | null>) {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== null && value !== '') params.set(key, String(value)) })
  const text = params.toString()
  return text ? `${path}?${text}` : path
}
