import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { ThemeSwitcher } from './ThemeSwitcher'

type NavigationIcon = 'overview' | 'tasks' | 'config' | 'database'

const navigation: Array<{ label: string; items: Array<{ to: string; label: string; icon: NavigationIcon }> }> = [
  { label: '管理视图', items: [{ to: '/', label: '核对工作台', icon: 'overview' }, { to: '/batches', label: '核对批次', icon: 'tasks' }, { to: '/results', label: '每日结果', icon: 'tasks' }] },
  { label: '基础配置', items: [{ to: '/configurations', label: '核对配置', icon: 'config' }, { to: '/databases', label: '数据库连接', icon: 'database' }] }
]

const paths: Record<NavigationIcon, string> = {
  overview: 'M4 13h6V4H4v9Zm0 7h6v-4H4v4Zm10 0h6v-9h-6v9Zm0-16v4h6V4h-6Z',
  tasks: 'M7 4h10M7 12h10M7 20h10M3.5 4h.01M3.5 12h.01M3.5 20h.01',
  config: 'M12 15.5A3.5 3.5 0 1 0 12 8a3.5 3.5 0 0 0 0 7.5ZM19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.86 2.86-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6l-.05.08H9.9L9.85 20a1.7 1.7 0 0 0-1-.6 1.7 1.7 0 0 0-1.88.34l-.06.06-2.86-2.86.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1l-.08-.05V9.9L4 9.85a1.7 1.7 0 0 0 .6-1 1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.86-2.86.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6l.05-.08h4.05l.05.08a1.7 1.7 0 0 0 1 .6 1.7 1.7 0 0 0 1.88-.34l.06-.06 2.86 2.86-.06.06A1.7 1.7 0 0 0 19.4 9c.08.39.3.74.6 1l.08.05v4.05L20 14.15c-.3.26-.52.61-.6 1Z',
  database: 'M20 6c0 1.1-3.58 2-8 2s-8-.9-8-2 3.58-2 8-2 8 .9 8 2Zm0 0v6c0 1.1-3.58 2-8 2s-8-.9-8-2V6m16 6v6c0 1.1-3.58 2-8 2s-8-.9-8-2v-6'
}

function Icon({ name }: { name: NavigationIcon }) {
  return <svg viewBox="0 0 24 24" aria-hidden><path d={paths[name]} fill={name === 'overview' ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>
}

export function AppShell() {
  const [open, setOpen] = useState(false)
  return <div className="app-shell">
    <aside className={open ? 'sidebar open' : 'sidebar'}>
      <div className="brand"><span className="brand-mark">T</span><div><strong>TSS Compare</strong><small>Spark 核对管理台</small></div></div>
      <nav className={open ? 'primary-navigation open' : 'primary-navigation'} aria-label="主导航">
        {navigation.map(group => <section className="nav-group" key={group.label} aria-labelledby={`nav-${group.label}`}>
          <h2 id={`nav-${group.label}`}>{group.label}</h2>
          {group.items.map(item => <NavLink key={item.to} to={item.to} end={item.to === '/'} onClick={() => setOpen(false)}><Icon name={item.icon} /><span>{item.label}</span></NavLink>)}
        </section>)}
      </nav>
      <div className="sidebar-footer"><div className="environment-mark"><span aria-hidden>●</span><div><strong>独立比对服务</strong><small>内部管理</small></div></div><ThemeSwitcher /></div>
    </aside>
    {open && <button className="scrim" aria-label="关闭导航" onClick={() => setOpen(false)} />}
    <main className="workspace">
      <header className="global-header"><div><span className="global-product">数据库比对中心</span><small>管理驾驶舱</small></div><span className="identity-mark"><i aria-hidden />内部管理</span></header>
      <header className="mobile-header"><button className="icon-button" aria-label="打开导航" onClick={() => setOpen(true)}>☰</button><div><strong>TSS Compare</strong><small>管理驾驶舱</small></div></header>
      <Outlet />
    </main>
  </div>
}
