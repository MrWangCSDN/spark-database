const states: Record<string, [string, string]> = {
  '1': ['等待', '○'], '2': ['运行中', '◌'], '3': ['完成', '✓'], '4': ['失败', '!']
}
const results: Record<string, [string, string]> = {
  '0': ['未生成', '○'], '1': ['有差异', '△'], '2': ['一致', '✓'], '3': ['执行失败', '!']
}

export function StatusBadge({ value, kind = 'state', taskState }: { value?: string | null; kind?: 'state' | 'result'; taskState?: string | null }) {
  const failedTaskResult: [string, string] = ['执行失败', '!']
  const [label, icon] = kind === 'result' && taskState === '4'
    ? failedTaskResult
    : (kind === 'state' ? states : results)[value ?? ''] ?? [value || '未知', '·']
  return <span className={`status status-${kind}-${taskState === '4' && kind === 'result' ? 'failed' : value ?? 'unknown'}`}><span aria-hidden>{icon}</span>{label}</span>
}
