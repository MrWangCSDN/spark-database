import type { TaskDatabaseContext } from '../api/types'

export interface DatabaseLabels { origin: string; destination: string }

export function databaseLabels(context?: TaskDatabaseContext): DatabaseLabels {
  const { originDatabaseId: a, originDatabaseName: aName, destinationDatabaseId: b, destinationDatabaseName: bName } = context ?? {}
  let origin = aName?.trim() || (a != null ? `数据库 #${a}` : '数据库 A（名称不可用）')
  let destination = bName?.trim() || (b != null ? `数据库 #${b}` : '数据库 B（名称不可用）')
  if (origin === destination) {
    origin += a != null && a !== b ? `（#${a}）` : `（${a != null ? `#${a}，` : ''}查询 A）`
    destination += b != null && a !== b ? `（#${b}）` : `（${b != null ? `#${b}，` : ''}查询 B）`
  }
  return { origin, destination }
}

// SparkHandlerTable: 2 = origin missing, 3 = destination missing, 4 = differing values.
export function differenceLabel(code: string, names: DatabaseLabels): string {
  return ({ '2': `仅 ${names.destination} 存在`, '3': `仅 ${names.origin} 存在`, '4': '值不一致', '5': '可解释差异' } as Record<string, string>)[code] ?? `未知结果（${code}）`
}

export function differenceReason(code: string, reason: string | null, names: DatabaseLabels): string {
  const missing = code === '2' ? names.origin : code === '3' ? names.destination : null
  if (!missing) return reason || '—'
  const legacy = code === '2' ? '生产少' : '信创少'
  return `${missing} 缺少记录${reason?.trim() && reason.trim() !== legacy ? `；${reason}` : ''}`
}
