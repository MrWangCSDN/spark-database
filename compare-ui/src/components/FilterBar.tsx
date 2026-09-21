import type { FormEvent, ReactNode } from 'react'

export function FilterBar({ children, onSubmit, onReset }: { children: ReactNode; onSubmit: (event: FormEvent<HTMLFormElement>) => void; onReset: () => void }) {
  return <form className="filter-bar" aria-label="任务筛选" onSubmit={onSubmit}>
    <div className="filter-fields">{children}</div>
    <div className="filter-actions"><button type="button" className="button ghost" onClick={onReset}>重置</button><button className="button primary">查询</button></div>
  </form>
}
