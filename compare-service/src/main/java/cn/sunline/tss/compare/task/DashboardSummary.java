package cn.sunline.tss.compare.task;

import java.util.List;

public record DashboardSummary(String serviceStatus, long runningCount, long todayCompletedCount,
                               long todayDifferenceCount, String updatedAt, List<TaskSummary> recentTasks) {}
