import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { ThemeSwitcher } from './ThemeSwitcher'

const links = [['/', '运行总览', '01'], ['/tasks', '核对任务', '02'], ['/configurations', '核对配置', '03'], ['/databases', '数据库连接', '04']]

export function AppShell() {
  const [open, setOpen] = useState(false)
  return <div className="app-shell">
    <aside className={open ? 'sidebar open' : 'sidebar'}>
      <div className="brand"><span className="brand-mark">T</span><div><strong>TSS Compare</strong><small>Spark 核对管理台</small></div></div>
      <nav>{links.map(([to, label, index]) => <NavLink key={to} to={to} end={to === '/'} onClick={() => setOpen(false)}><span>{index}</span>{label}</NavLink>)}</nav>
      <ThemeSwitcher />
    </aside>
    {open && <button className="scrim" aria-label="关闭导航" onClick={() => setOpen(false)} />}
    <main><header className="mobile-header"><button className="icon-button" aria-label="打开导航" onClick={() => setOpen(true)}>☰</button><strong>TSS Compare</strong></header><Outlet /></main>
  </div>
}
