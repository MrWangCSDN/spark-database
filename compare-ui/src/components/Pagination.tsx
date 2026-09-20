export function Pagination({ page, size, total, onChange }: { page: number; size: number; total: number; onChange: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  return <div className="pagination" aria-label="分页"><span>第 {page + 1} / {pages} 页 · 共 {total} 条</span><div><button className="button ghost small" disabled={page === 0} onClick={() => onChange(page - 1)}>上一页</button><button className="button ghost small" disabled={page + 1 >= pages} onClick={() => onChange(page + 1)}>下一页</button></div></div>
}
