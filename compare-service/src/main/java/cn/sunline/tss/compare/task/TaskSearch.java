package cn.sunline.tss.compare.task;

record TaskSearch(Long bcompIndex, String taskState, String taskResult, String dateFrom,
                  String dateTo, String keyword, int page, int size, String orderBy) {}
