import type { DailySamples } from '../api/types'

type Field = DailySamples['items'][number]['detail']['fields'][number]
type Column = { name: string; label: string }

function displayValue(value: string | null) {
  return value === null ? 'NULL' : value === '' ? '（空字符串）' : value
}

function columnsFor(samples: DailySamples, patternFields?: string[]): Column[] {
  const labels = new Map<string, string>()
  for (const row of samples.items) for (const field of row.detail.fields) {
    if (!labels.has(field.name)) labels.set(field.name, field.label?.trim() || field.name)
  }
  const names = patternFields ?? [...labels.keys()]
  return names.map(name => ({ name, label: labels.get(name) || name }))
}

export function DailySampleGrid({ samples, type, originName, destinationName, patternFields }: {
  samples: DailySamples; type: string; originName: string; destinationName: string; patternFields?: string[]
}) {
  const columns = columnsFor(samples, patternFields)
  return <div className="table-wrap daily-sample-grid" role="region" aria-label={samples.summary.detailLimit === -1 ? '差异明细宽表' : '差异样本宽表'} tabIndex={0}>
    {type === '4' && <div className="sample-compare-legend"><span>{originName} → {destinationName}</span><small>底色标记不同字段</small></div>}
    <table><thead><tr><th>核对主键</th>{columns.map(column => <th key={column.name} title={column.name}>
      <span>{column.label}</span>{column.label !== column.name && <small>{column.name}</small>}
    </th>)}</tr></thead><tbody>{samples.items.map((row, index) => {
      const fields = new Map<string, Field>(row.detail.fields.map(field => [field.name, field]))
      return <tr key={`${row.businessKey}-${index}`}><td className="mono break-value">{row.businessKey}
        {row.detail.truncated && <small className="sample-truncated">展示已截断</small>}
      </td>{columns.map(column => {
        const field = fields.get(column.name)
        return <td key={column.name} className={`break-value${type === '4' && field ? ' sample-diff-cell' : ''}`}>{!field ? '未保存' : type === '4'
          ? <div className="sample-value-pair"><span className="sample-origin-value"><span className="sr-only">{originName} </span>{displayValue(field.originValue)}</span><span className="sample-compare-arrow" aria-hidden="true">→</span><span className="sample-destination-value"><span className="sr-only">{destinationName} </span>{displayValue(field.destinationValue)}</span></div>
          : displayValue(type === '3' ? field.originValue : field.destinationValue)}</td>
      })}</tr>
    })}</tbody></table>
  </div>
}
