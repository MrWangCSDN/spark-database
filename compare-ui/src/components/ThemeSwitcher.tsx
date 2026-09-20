import { useEffect, useState } from 'react'

type Theme = 'light' | 'dark' | 'system'

export function ThemeSwitcher() {
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('compare-theme') as Theme) || 'system')
  useEffect(() => {
    const dark = theme === 'dark' || (theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
    document.documentElement.dataset.theme = dark ? 'dark' : 'light'
    localStorage.setItem('compare-theme', theme)
  }, [theme])
  return <label className="theme-switcher"><span>主题</span><select aria-label="主题" value={theme} onChange={event => setTheme(event.target.value as Theme)}><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select></label>
}
