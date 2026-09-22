package cn.sunline.tss.compare.task;

/** Current configuration labels, not an execution-time snapshot. No connection credentials. */
public record TaskDatabaseContext(Long originDatabaseId, String originDatabaseName,
                                  Long destinationDatabaseId, String destinationDatabaseName) {}
