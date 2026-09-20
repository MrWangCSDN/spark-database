package cn.sunline.tss.compare.task;

import cn.sunline.tss.compare.TaskWorker;
import cn.sunline.tss.compare.api.ApiException;
import cn.sunline.tss.compare.api.PageResult;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TaskManagementService {
    private static final Pattern DATE = Pattern.compile("\\d{8}");
    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z0-9_.-]{1,20}");
    private static final Map<String,String> SORTS = Map.of(
        "createdAt,desc", "t.CREATE_DATETIME DESC,t.BCOMP_INDEX ASC",
        "startedAt,desc", "t.BEGIN_DATETIME DESC,t.BCOMP_INDEX ASC",
        "endedAt,desc", "t.END_DATETIME DESC,t.BCOMP_INDEX ASC",
        "bcompIndex,asc", "t.BCOMP_INDEX ASC,t.CREATE_DATETIME DESC");

    private final TaskManagementRepository repository;
    private final TaskWorker worker;

    public TaskManagementService(TaskManagementRepository repository, TaskWorker worker) {
        this.repository = repository; this.worker = worker;
    }

    public TaskSummary create(TaskCreateRequest request) {
        validateRequest(request);
        var config = repository.configurationStatus(request.bcompIndex());
        if (config == null || !config.complete()) {
            throw ApiException.badRequest("CONFIGURATION_INCOMPLETE", "库表核对配置不存在或不完整");
        }
        if ("2".equals(config.dateMode()) && (request.verifyBusinessDate() == null || !DATE.matcher(request.verifyBusinessDate()).matches())) {
            throw ApiException.badRequest("VERIFY_DATE_REQUIRED", "指定日期模式必须填写8位核对日期");
        }
        TaskSummary created = repository.insertWaiting(request, config.name());
        if (created == null) throw ApiException.conflict("TASK_IDENTITY_CONFLICT", "同一核对编号、业务日期和批次的任务已存在");
        return created;
    }

    public PageResult<TaskSummary> findTasks(Long index, String state, String result, String from,
                                             String to, String keyword, int page, int size, String sort) {
        validatePage(page, size);
        String order = SORTS.get(sort);
        if (order == null) throw ApiException.badRequest("INVALID_SORT", "不支持的任务排序方式");
        return repository.findTasks(new TaskSearch(index, state, result, from, to, keyword, page, size, order));
    }

    public TaskSummary exact(long index, String date, String batch, String timeNode) {
        TaskSummary task = repository.findExact(index, date, batch, timeNode);
        if (task == null) throw ApiException.notFound("TASK_NOT_FOUND", "核对任务不存在");
        return task;
    }

    public PageResult<DifferenceSummary> differences(long index, String date, String batch,
                                                      String result, String key, int page, int size) {
        validatePage(page, size);
        return repository.findDifferences(index, date, batch, result, key, page, size);
    }

    public DashboardSummary dashboard() { return repository.dashboard(worker == null ? "2" : worker.status()); }

    private static void validateRequest(TaskCreateRequest request) {
        if (request == null || request.bcompIndex() <= 0 || !validDate(request.taskBusinessDate())
            || !SIMPLE.matcher(nullToEmpty(request.taskBatch())).matches()
            || !SIMPLE.matcher(nullToEmpty(request.timeNode())).matches()) {
            throw ApiException.badRequest("INVALID_TASK", "核对编号、业务日期、批次或时点不合法");
        }
        if (request.verifyBusinessDate() != null && !request.verifyBusinessDate().isBlank() && !validDate(request.verifyBusinessDate())) {
            throw ApiException.badRequest("INVALID_VERIFY_DATE", "核对日期必须是8位日期");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw ApiException.badRequest("INVALID_PAGE", "分页参数不合法");
    }
    private static boolean validDate(String value) { return value != null && DATE.matcher(value).matches(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
