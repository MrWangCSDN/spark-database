import type { FieldRule } from '../api/types'

export function FieldRulesEditor({ fields, onChange }: { fields: FieldRule[]; onChange: (fields: FieldRule[]) => void }) {
  const update = (index: number, patch: Partial<FieldRule>) => onChange(fields.map((field, current) => current === index ? { ...field, ...patch } : field))
  const add = () => onChange([...fields, { name: '', type: '1', memo: '', oldIndex: fields.length + 1, newIndex: fields.length + 1, indexFlag: '0', enumFlag: '0', enumIndex: null, partitionFlag: '0', ignoreValue: null, ignoreFlag: '0' }])
  return <section className="section-block"><div className="section-heading"><div><h2>字段规则</h2><p>字段位置从 1 开始，两侧位置不可重复。</p></div><button type="button" className="button ghost" onClick={add}>添加字段</button></div>
    <div className="table-wrap"><table><thead><tr><th>字段名</th><th>类型</th><th>旧侧位置</th><th>新侧位置</th><th>业务键</th><th>分区</th><th></th></tr></thead><tbody>{fields.map((field, index) => <tr key={index}>
      <td><input aria-label={`字段名 ${index + 1}`} value={field.name} onChange={e => update(index, { name: e.target.value })} /></td>
      <td><select aria-label={`字段类型 ${index + 1}`} value={field.type} onChange={e => update(index, { type: e.target.value })}><option value="1">字符</option><option value="2">数值</option><option value="3">日期</option><option value="4">时间</option></select></td>
      <td><input type="number" min="1" aria-label={`旧侧位置 ${index + 1}`} value={field.oldIndex} onChange={e => update(index, { oldIndex: Number(e.target.value) })} /></td>
      <td><input type="number" min="1" aria-label={`新侧位置 ${index + 1}`} value={field.newIndex} onChange={e => update(index, { newIndex: Number(e.target.value) })} /></td>
      <td><input type="checkbox" aria-label={`业务键 ${index + 1}`} checked={field.indexFlag === '1'} onChange={e => update(index, { indexFlag: e.target.checked ? '1' : '0' })} /></td>
      <td><input type="checkbox" aria-label={`分区字段 ${index + 1}`} checked={field.partitionFlag === '1'} onChange={e => update(index, { partitionFlag: e.target.checked ? '1' : '0' })} /></td>
      <td><button type="button" className="text-button danger" onClick={() => onChange(fields.filter((_, current) => current !== index))}>移除</button></td>
    </tr>)}</tbody></table></div>
  </section>
}
