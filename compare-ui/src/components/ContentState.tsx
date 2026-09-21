import type { ReactNode } from 'react'

interface ContentStateProps {
  state: 'loading' | 'empty' | 'error' | 'success'
  title: string
  description?: string
  action?: ReactNode
}

export function ContentState({ state, title, description, action }: ContentStateProps) {
  return <div className={`content-state content-state-${state}`} role={state === 'error' ? 'alert' : 'status'}>
    <span className="content-state-mark" aria-hidden>{state === 'error' ? '!' : state === 'success' ? '✓' : state === 'loading' ? '···' : '○'}</span>
    <div><strong>{title}</strong>{description && <p>{description}</p>}</div>
    {action && <div className="content-state-action">{action}</div>}
  </div>
}
