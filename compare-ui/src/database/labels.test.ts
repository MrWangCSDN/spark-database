import { databaseLabels, differenceLabel, differenceReason } from './labels'

const names = databaseLabels({ originDatabaseId: 10, originDatabaseName: 'sit', destinationDatabaseId: 20, destinationDatabaseName: 'uat' })
test('maps actual Spark codes and the database missing a record', () => {
  expect(differenceLabel('2', names)).toBe('仅 uat 存在')
  expect(differenceReason('2', '生产少', names)).toBe('sit 缺少记录')
  expect(differenceLabel('3', names)).toBe('仅 sit 存在')
  expect(differenceReason('3', '信创少', names)).toBe('uat 缺少记录')
  expect(differenceLabel('4', names)).toBe('值不一致')
  expect(differenceLabel('5', names)).toBe('可解释差异')
  expect(differenceLabel('1', names)).toBe('未知结果（1）')
  expect(differenceReason('4', '字段 AMOUNT 不一致', names)).toBe('字段 AMOUNT 不一致')
  expect(differenceReason('2', '额外诊断信息', names)).toBe('sit 缺少记录；额外诊断信息')
})
test('disambiguates duplicate and absent names without guessing environment names', () => {
  expect(databaseLabels()).toEqual({ origin: '数据库 A（名称不可用）', destination: '数据库 B（名称不可用）' })
  expect(databaseLabels({ originDatabaseId: 10, originDatabaseName: ' ', destinationDatabaseId: 20, destinationDatabaseName: 'uat' }).origin).toBe('数据库 #10')
  expect(databaseLabels({ originDatabaseId: 10, originDatabaseName: 'core', destinationDatabaseId: 20, destinationDatabaseName: 'core' })).toEqual({ origin: 'core（#10）', destination: 'core（#20）' })
  expect(databaseLabels({ originDatabaseId: 10, originDatabaseName: 'core', destinationDatabaseId: 10, destinationDatabaseName: 'core' })).toEqual({ origin: 'core（#10，查询 A）', destination: 'core（#10，查询 B）' })
})
