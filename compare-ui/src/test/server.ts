import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

export const task = {
  bcompIndex: 8, configurationName: '客户余额核对', taskBusinessDate: '20260920',
  verifyBusinessDate: '20260920', taskBatch: '1', timeNode: 'D', createdAt: '20260920120000',
  startedAt: null, endedAt: null, taskState: '1', taskResult: null, failureReason: null,
  file1Count: null, file2Count: null, file1SuccessCount: null, file2SuccessCount: null,
  file1FailureCount: null, file2FailureCount: null, readMode: '1'
}

export const batch = { batchId: '20260922-001', taskBusinessDate: '20260922', verifyBusinessDate: '20260922', createdAt: '20260922120000', sourceBatchId: null, totalCount: 200, waitingCount: 49, runningCount: 1, consistentCount: 142, differenceCount: 6, failedCount: 2, cancelledCount: 0, otherCount: 0, endedCount: 150 }
export const handlers = [
  http.get('/api/tasks/8/20260920/1/D/database-context', () => HttpResponse.json({ originDatabaseId: 1, originDatabaseName: 'sit', destinationDatabaseId: 2, destinationDatabaseName: 'uat' })),
  http.get('/api/batches/runtime', () => HttpResponse.json({ tasksEnabled: true, concurrency: 1, maxBatchSize: 5000 })),
  http.get('/api/batches', () => HttpResponse.json({ items: [batch], page: 0, size: 20, total: 1 })),
  http.get('/api/batches/:batchId/tasks', () => HttpResponse.json({ items: [task], page: 0, size: 20, total: 1 })),
  http.get('/api/batches/:batchId', () => HttpResponse.json(batch)),
  http.get('/api/tasks', () => HttpResponse.json({ items: [task], page: 0, size: 20, total: 1 })),
  http.post('/api/tasks', async () => HttpResponse.json(task, { status: 201 })),
  http.get('/api/configurations', () => HttpResponse.json({ items: [{ bcompIndex: 8, name: '客户余额核对', module: 'CORE', memo: '', range: '', timeNode: 'D', state: '1', partitionNum: 8, dateMode: '1' }], page: 0, size: 100, total: 1 })),
  http.get('/api/tasks/8/20260920/1/D', () => HttpResponse.json({ ...task, taskState: '3', taskResult: '2', endedAt: '20260920123000', file1Count: 20, file2Count: 20, file1FailureCount: 1, file2FailureCount: 1 })),
  http.get('/api/tasks/8/20260920/1/D/differences', () => HttpResponse.json({ items: [{ businessKey: 'C001', oldContent: '100.00|2026-09-20', newContent: '101.00|2026-09-20', lineResult: '4', reason: '字段值不一致' }], page: 0, size: 20, total: 1 })),
  http.get('/api/configurations/8', () => HttpResponse.json({ basic: { bcompIndex: 8, name: '客户余额核对', module: 'CORE', memo: '', range: '', timeNode: 'D', state: '1', partitionNum: 8, dateMode: '1' }, tableSql: { origDatabaseId: 1, destDatabaseId: 2, origSql: 'select * from old_customer', destSql: 'select * from new_customer' }, fields: [{ name: 'CUSTOMER_ID', type: '1', memo: '', oldIndex: 1, newIndex: 1, indexFlag: '1', enumFlag: '0', enumIndex: null, partitionFlag: '0', ignoreValue: null, ignoreFlag: '0' }] })),
  http.get('/api/configuration-options', () => HttpResponse.json({ states: ['1'], enums: [], ignoreValues: [] })),
  http.get('/api/databases', () => HttpResponse.json({ items: [{ databaseId: 1, databaseName: '核心旧库', url: 'jdbc:postgresql://old/core?currentSchema=business', user: 'reader', driver: 'org.postgresql.Driver', encryption: '1', passwordConfigured: true }], page: 0, size: 100, total: 1 })),
  http.post('/api/databases/test', () => HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 25 })),
  http.post('/api/databases/:id/test', () => HttpResponse.json({ success: true, databaseProduct: 'GaussDB', databaseVersion: 'V500', elapsedMs: 25 })),
  http.post('/api/cache/refresh', () => HttpResponse.json({ code: 200, msg: '刷新成功' })),
  http.put('/api/configurations/8', async () => HttpResponse.json({ configuration: {}, saved: true, cacheRefreshed: false, warning: '配置已保存，但缓存刷新失败' })),
  http.post('/api/configurations/import', async () => HttpResponse.json({ saved: true, cacheRefreshed: true, configurationCount: 1, fieldCount: 2, tableSqlCount: 1, warning: null })),
  http.put('/api/databases/1', async ({ request }) => HttpResponse.json({ ...(await request.json() as object), passwordConfigured: true }))
]

export const server = setupServer(...handlers)
