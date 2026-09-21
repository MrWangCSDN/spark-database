export interface PageResult<T> { items: T[]; page: number; size: number; total: number }
export interface TaskSummary {
  bcompIndex: number; configurationName: string; taskBusinessDate: string; verifyBusinessDate?: string | null;
  taskBatch: string; timeNode: string; createdAt: string; startedAt?: string | null; endedAt?: string | null;
  taskState: string; taskResult?: string | null; failureReason?: string | null; file1Count?: number | null;
  file2Count?: number | null; file1SuccessCount?: number | null; file2SuccessCount?: number | null;
  file1FailureCount?: number | null; file2FailureCount?: number | null; readMode?: string | null
}
export interface DifferenceSummary { businessKey: string; oldContent: string; newContent: string; lineResult: string; reason: string }
export interface DashboardSummary { serviceStatus: string; runningCount: number; todayCompletedCount: number; todayDifferenceCount: number; updatedAt: string; recentTasks: TaskSummary[] }
export interface BasicConfiguration { bcompIndex: number; name: string; module: string; memo: string; range: string; timeNode: string; state: string; partitionNum: number | null; dateMode: string }
export interface TableSqlConfiguration { origDatabaseId: number; destDatabaseId: number; origSql: string; destSql: string }
export interface FieldRule { name: string; type: string; memo: string; oldIndex: number; newIndex: number; indexFlag: string; enumFlag: string; enumIndex: number | null; partitionFlag: string; ignoreValue: string | null; ignoreFlag: string }
export interface ConfigurationDetail { basic: BasicConfiguration; tableSql: TableSqlConfiguration; fields: FieldRule[] }
export interface ConfigurationSaveResult { configuration: ConfigurationDetail; saved: boolean; cacheRefreshed: boolean; warning?: string | null }
export interface ConfigurationOptions { states: string[]; enums: Array<{ index: number; oldValue: string; newValue: string }>; ignoreValues: Array<{ id: number; value: string }> }
export interface DatabaseSummary { databaseId: number; databaseName: string; url: string; user: string; driver: string; encryption: string; passwordConfigured: boolean }
export interface DatabaseSaveRequest { databaseId: number; databaseName: string; url: string; user: string; driver: string; encryption: string; newPassword: string | null }
export interface DatabaseConnectionTestResult { success: boolean; databaseProduct: string; databaseVersion: string; elapsedMs: number }
export interface ApiError { code: string; message: string; traceId: string }
