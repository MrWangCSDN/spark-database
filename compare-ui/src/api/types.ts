export interface PageResult<T> { items: T[]; page: number; size: number; total: number }
export interface TaskSummary {
  bcompIndex: number; configurationName: string; taskBusinessDate: string; verifyBusinessDate?: string | null;
  taskBatch: string; timeNode: string; createdAt: string; startedAt?: string | null; endedAt?: string | null;
  taskState: string | null; taskResult?: string | null; failureReason?: string | null; file1Count?: number | null;
  file2Count?: number | null; file1SuccessCount?: number | null; file2SuccessCount?: number | null;
  file1FailureCount?: number | null; file2FailureCount?: number | null; readMode?: string | null; resultDate?: string | null
}
export interface DifferenceSummary { businessKey: string; oldContent: string; newContent: string; lineResult: string; reason: string }
export interface DashboardSummary { serviceStatus: string; runningCount: number; todayCompletedCount: number; todayDifferenceCount: number; updatedAt: string; recentTasks: TaskSummary[] }
export interface BasicConfiguration { bcompIndex: number; name: string; module: string; memo: string; range: string; timeNode: string; state: string; partitionNum: number | null; dateMode: string | null }
export interface TableSqlConfiguration { origDatabaseId: number; destDatabaseId: number; origSql: string; destSql: string }
export interface FieldRule { name: string; type: string; memo: string; oldIndex: number; newIndex: number; indexFlag: string; enumFlag: string; enumIndex: number | null; partitionFlag: string; ignoreValue: string | null; ignoreFlag: string }
export interface ConfigurationDetail { basic: BasicConfiguration; tableSql: TableSqlConfiguration; fields: FieldRule[] }
export interface ConfigurationSaveResult { configuration: ConfigurationDetail; saved: boolean; cacheRefreshed: boolean; warning?: string | null }
export interface ConfigScriptImportResult { saved: boolean; cacheRefreshed: boolean; configurationCount: number; fieldCount: number; tableSqlCount: number; warning?: string | null }
export interface ConfigurationOptions { states: string[]; enums: Array<{ index: number; oldValue: string; newValue: string }>; ignoreValues: Array<{ id: number; value: string }> }
export interface DatabaseSummary { databaseId: number; databaseName: string; url: string; user: string; driver: string; encryption: string; passwordConfigured: boolean }
export interface DatabaseSaveRequest { databaseId: number; databaseName: string; url: string; user: string; driver: string; encryption: string; newPassword: string | null }
export interface DatabaseConnectionTestResult { success: boolean; databaseProduct: string; databaseVersion: string; elapsedMs: number }
export interface ApiError { code: string; message: string; traceId: string }

export type BatchScope = 'ALL' | 'SELECTED' | 'FAILED'
export type BatchTaskStatus = 'ALL' | 'WAITING' | 'RUNNING' | 'CONSISTENT' | 'DIFFERENCE' | 'FAILED' | 'OTHER'
export interface BatchRequest {
  scope: BatchScope; configurationIds?: number[]; excludedIds?: number[]; taskBusinessDate?: string;
  verifyBusinessDate?: string; timeNode?: string; sourceBatchId?: string; requestId?: string; previewFingerprint?: string
}
export interface BatchPreviewItem {
  bcompIndex: number; configurationName: string; module: string | null; timeNode: string | null;
  verifyBusinessDate: string | null; ready: boolean; issueCode: string | null; issueMessage: string | null; configurationChanged: boolean
}
export interface BatchPreview {
  fingerprint: string; taskBusinessDate: string; verifyBusinessDate: string | null; totalCount: number;
  readyCount: number; blockedCount: number; changedCount: number; items: BatchPreviewItem[]
}
export interface BatchSummary {
  batchId: string; taskBusinessDate: string; verifyBusinessDate: string | null; createdAt: string; sourceBatchId: string | null;
  totalCount: number; waitingCount: number; runningCount: number; consistentCount: number; differenceCount: number;
  failedCount: number; otherCount: number; endedCount: number
}
export interface BatchRuntime { tasksEnabled: boolean; concurrency: number; maxBatchSize: number }

export interface TaskDatabaseContext {
  originDatabaseId: number | null; originDatabaseName: string | null;
  destinationDatabaseId: number | null; destinationDatabaseName: string | null
}

export interface DailyResult extends TaskDatabaseContext {
  bcompIndex: number; resultDate: string; runId: string; taskBusiDate: string; taskBatch: string; timeNode: string;
  configurationName: string; originCount: number; destinationCount: number; matchedCount: number;
  onlyOriginCount: number; onlyDestinationCount: number; differentCount: number; detailLimit: number;
  originSamples: number; destinationSamples: number; differentSamples: number; publishedAt: string; detailsExpired: string
}
export interface DailyResults {
  results: PageResult<DailyResult>;
  connections: Array<TaskDatabaseContext & { objectCount: number }>
}
export interface DailySamples {
  summary: DailyResult; items: Array<{ businessKey: string; detail: { fields: Array<{ name: string; originValue: string | null; destinationValue: string | null }>; truncated: boolean } }>;
  page: number; size: number; total: number
}
export interface FieldPattern {
  patternId: string; fields: string[]; differentCount: number; sampleCount: number
}
