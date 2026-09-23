import { Link } from 'react-router-dom'
import type { BatchSummary } from '../api/types'
import { formatTime } from '../pages/TasksPage'

export function BatchProgress({ batch }: { batch: BatchSummary }) {
  const percent = batch.totalCount ? Math.round(batch.endedCount / batch.totalCount * 100) : 0
  return <div className="batch-progress"><span>已结束 {batch.endedCount} / {batch.totalCount}</span><progress aria-label="已结束任务进度" max={Math.max(batch.totalCount, 1)} value={batch.endedCount} /><small>{percent}%</small></div>
}

export function BatchTable({ items }: { items: BatchSummary[] }) {
  return <div className="table-wrap"><table><thead><tr><th>批次 / 创建时间</th><th>业务日期</th><th>执行进度</th><th>一致</th><th>有差异</th><th>执行失败</th><th>已取消</th><th></th></tr></thead><tbody>{items.map(batch => <tr key={batch.batchId}>
    <td><Link className="row-link" to={`/batches/${encodeURIComponent(batch.batchId)}`}>{batch.batchId}</Link><small>{formatTime(batch.createdAt)}{batch.sourceBatchId ? ' · 失败项重跑' : ''}</small></td><td>{batch.taskBusinessDate}<small>共 {batch.totalCount} 项任务</small></td><td><BatchProgress batch={batch} /><small>等待 {batch.waitingCount} · 运行中 {batch.runningCount}</small>{batch.otherCount > 0 && <small className="error">其他异常 {batch.otherCount}</small>}</td><td>{batch.consistentCount}</td><td className={batch.differenceCount ? 'batch-difference' : ''}>{batch.differenceCount}</td><td className={batch.failedCount ? 'error' : ''}>{batch.failedCount}</td><td>{batch.cancelledCount}</td><td><Link className="row-link" to={`/batches/${encodeURIComponent(batch.batchId)}`}>查看批次</Link></td>
  </tr>)}</tbody></table></div>
}
