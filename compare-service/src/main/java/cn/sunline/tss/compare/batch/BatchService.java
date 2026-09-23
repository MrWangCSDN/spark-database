package cn.sunline.tss.compare.batch;

import cn.sunline.tss.compare.ControlPlaneLock;
import cn.sunline.tss.compare.JobLauncher;
import cn.sunline.tss.compare.api.ApiException;
import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.configuration.CacheReloader;
import cn.sunline.tss.compare.database.JdbcCurrentSchemaPolicy;
import cn.sunline.tss.compare.task.TaskSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class BatchService {
    private static final int MAX_BATCH_SIZE = 5000;
    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z0-9_.-]{1,20}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> STATUSES = Set.of("ALL", "WAITING", "RUNNING", "CONSISTENT", "DIFFERENCE", "FAILED", "CANCELLED", "OTHER");
    private static final Set<String> FIELD_TYPES = Set.of("1", "2", "3", "4");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final BatchRepository repository;
    private final CacheReloader cache;
    private final Environment environment;
    private final JobLauncher launcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public BatchService(BatchRepository repository, CacheReloader cache, Environment environment, JobLauncher launcher) {
        this.repository = repository;
        this.cache = cache;
        this.environment = environment;
        this.launcher = launcher;
    }

    public BatchPreview preview(BatchRequest request) {
        return ControlPlaneLock.call(() -> prepare(normalize(request)).preview());
    }

    public BatchSummary create(BatchRequest request) {
        return ControlPlaneLock.call(() -> {
            NormalizedRequest normalized = normalize(request);
            if (request.requestId() == null || !REQUEST_ID.matcher(request.requestId()).matches()
                || request.previewFingerprint() == null || !FINGERPRINT.matcher(request.previewFingerprint()).matches()) {
                throw invalid("提交必须包含有效的请求标识和预检指纹，请重新预检");
            }
            // The preview belongs to the submission: a changed preview cannot reuse a previous request ID.
            String requestHash = hash(List.of(normalized, request.previewFingerprint()));
            var previous = repository.findRequest(request.requestId());
            if (previous != null) {
                if (!previous.requestHash().equals(requestHash)) {
                    throw ApiException.conflict("BATCH_REQUEST_CONFLICT", "该请求标识已用于其他批次内容，请重新发起");
                }
                return find(previous.batchId());
            }
            return repository.inTransaction(() -> {
                PreparedBatch prepared;
                try {
                    prepared = prepare(normalized);
                } catch (ApiException exception) {
                    // A previously reviewed set can disappear or outgrow its limit before submission.
                    if (Set.of("EMPTY_BATCH", "BATCH_TOO_LARGE", "BATCH_NOT_FOUND", "INVALID_BATCH_REQUEST").contains(exception.code())) {
                        throw ApiException.conflict("BATCH_PREVIEW_STALE", "执行范围已变化，请重新预检");
                    }
                    throw exception;
                }
                if (!prepared.preview().fingerprint().equals(request.previewFingerprint())) {
                    throw ApiException.conflict("BATCH_PREVIEW_STALE", "配置、任务状态或执行范围已变化，请重新预检");
                }
                if (prepared.preview().blockedCount() > 0) {
                    throw ApiException.conflict("BATCH_NOT_READY", "存在不可执行项，请修复或明确排除后重新预检");
                }
                try {
                    cache.reload();
                } catch (RuntimeException exception) {
                    throw ApiException.conflict("BATCH_CACHE_REFRESH_FAILED", "配置缓存刷新失败，未创建任务，请检查配置后重试");
                }
                String id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                repository.insert(id, request.requestId(), requestHash, prepared.preview().taskBusinessDate(),
                    prepared.preview().verifyBusinessDate(), normalized.sourceBatchId(), LocalDateTime.now().format(TIMESTAMP), prepared.items());
                return find(id);
            });
        });
    }

    public BatchSummary find(String id) {
        if (id == null || !SIMPLE.matcher(id).matches()) throw ApiException.notFound("BATCH_NOT_FOUND", "核对批次不存在");
        var batch = repository.find(id);
        if (batch == null) throw ApiException.notFound("BATCH_NOT_FOUND", "核对批次不存在");
        return batch;
    }

    public BatchSummary cancel(String id) {
        find(id);
        BatchSummary result=repository.inTransaction(() -> {
            repository.cancel(id);
            return repository.find(id);
        });
        launcher.cancelBatch(id);
        return result;
    }

    public PageResult<BatchSummary> list(int page, int size) {
        validatePage(page, size);
        return repository.list(page, size);
    }

    public PageResult<TaskSummary> tasks(String id, String status, int page, int size) {
        validatePage(page, size);
        if (status == null || !STATUSES.contains(status)) throw invalid("不支持的任务状态筛选");
        find(id);
        return repository.tasks(id, status, page, size);
    }

    public RuntimeSettings runtime() {
        return new RuntimeSettings(environment.getProperty("tssveritask.executor.thread.enable", Boolean.class, true),
            environment.getProperty("tssveritask.executor.thread.maxPoolSize", Integer.class, 1), MAX_BATCH_SIZE);
    }

    private PreparedBatch prepare(NormalizedRequest request) {
        String date = request.taskBusinessDate();
        String verifyDate = request.verifyBusinessDate();
        var sourceItems = new HashMap<Long, BatchRepository.SourceItem>();
        List<Long> ids;
        if ("FAILED".equals(request.scope())) {
            BatchSummary source = find(request.sourceBatchId());
            date = source.taskBusinessDate();
            verifyDate = source.verifyBusinessDate();
            var failed = repository.failedItems(source.batchId());
            failed.forEach(item -> sourceItems.put(item.index(), item));
            ids = failed.stream().map(BatchRepository.SourceItem::index).toList();
        } else {
            ids = "ALL".equals(request.scope()) ? repository.allConfigurationIds() : request.configurationIds();
        }
        if (ids.size() > MAX_BATCH_SIZE) throw tooLarge();
        var selected = new TreeSet<>(ids);
        if (!selected.containsAll(request.excludedIds())) throw invalid("排除项已不在执行范围，请重新选择执行范围");
        selected.removeAll(request.excludedIds());
        if (selected.isEmpty()) throw ApiException.badRequest("EMPTY_BATCH", "没有可发起的配置或失败项，请调整执行范围");
        ids = new ArrayList<>(selected);
        var configurations = repository.configurations(ids);
        var active = repository.activeConfigurationIds(ids, date);
        var prepared = new ArrayList<BatchRepository.PreparedItem>();
        int blocked = 0;
        int changed = 0;
        for (long id : ids) {
            var configuration = configurations.get(id);
            var source = sourceItems.get(id);
            String time = source == null ? request.timeNode() : source.timeNode();
            if (time == null && configuration != null) time = configuration.value("BCOMP_TIME_NODE");
            String itemDate = source == null ? verifyDate : source.verifyDate();
            String configurationHash = hash(configuration == null ? "missing:" + id : configuration);
            boolean configurationChanged = source != null && !source.configurationHash().equals(configurationHash);
            Issue issue = validateConfiguration(configuration, time, itemDate);
            if (issue == null && active.contains(id)) issue = new Issue("TASK_ALREADY_ACTIVE", "相同配置和业务日期已有等待或运行任务，请等待结束");
            String name = configuration == null ? (source == null ? "配置 #" + id : source.name()) : configuration.value("BCOMP_NAME");
            if (blank(name)) name = "配置 #" + id;
            String module = configuration == null ? (source == null ? null : source.module()) : configuration.value("BCOMP_MODULE");
            var item = new BatchPreviewItem(id, name, module, time, itemDate, issue == null,
                issue == null ? null : issue.code(), issue == null ? null : issue.message(), configurationChanged);
            prepared.add(new BatchRepository.PreparedItem(item, configurationHash));
            if (issue != null) blocked++;
            if (configurationChanged) changed++;
        }
        String fingerprint = hash(Arrays.asList(request, date, verifyDate, prepared));
        var preview = new BatchPreview(fingerprint, date, verifyDate, prepared.size(), prepared.size()-blocked, blocked, changed,
            prepared.stream().map(BatchRepository.PreparedItem::preview).toList());
        return new PreparedBatch(preview, prepared);
    }

    private Issue validateConfiguration(BatchRepository.Configuration configuration, String timeNode, String verifyDate) {
        if (configuration == null) return new Issue("CONFIGURATION_NOT_FOUND", "当前核对配置已不存在，请恢复配置或明确排除此项");
        if (blank(configuration.value("BCOMP_NAME")) || blank(configuration.value("BCOMP_STATE"))
            || (!blank(configuration.value("BCOMP_DATE_MODE")) && !Set.of("1", "2").contains(configuration.value("BCOMP_DATE_MODE")))
            || blank(configuration.value("ORIG_SQL")) || blank(configuration.value("DEST_SQL"))
            || configuration.fields().isEmpty()) return incomplete();
        if (timeNode == null || !SIMPLE.matcher(timeNode).matches()) return new Issue("INVALID_TIME_NODE", "缺少有效时点，请填写统一时点或修正配置（1～20位字母、数字、点、下划线或横线）");
        if ("2".equals(configuration.value("BCOMP_DATE_MODE")) && !validDate(verifyDate)) return incomplete();
        for (String side : List.of("O", "D")) {
            if (blank(configuration.value(side + "_ID")) || blank(configuration.value(side + "_URL"))
                || blank(configuration.value(side + "_USER")) || blank(configuration.value(side + "_DRIVER"))) return incomplete();
            try {
                JdbcCurrentSchemaPolicy.requireValid(configuration.value(side + "_URL"), configuration.value(side + "_DRIVER"));
            } catch (ApiException exception) {
                return new Issue("CURRENT_SCHEMA_REQUIRED", "两侧 GaussDB/PostgreSQL 连接都需要填写非空 currentSchema");
            }
        }
        var oldPositions = new HashSet<Long>();
        var newPositions = new HashSet<Long>();
        int keys = 0;
        for (var field : configuration.fields()) {
            long oldPosition = positiveNumber(field.get("FIELD_OLD_INDEX"));
            long newPosition = positiveNumber(field.get("FIELD_NEW_INDEX"));
            if (blank(field.get("FIELD_NAME")) || !FIELD_TYPES.contains(valueOrEmpty(field.get("FIELD_TYPE")))
                || oldPosition <= 0 || newPosition <= 0 || !oldPositions.add(oldPosition) || !newPositions.add(newPosition)) {
                return new Issue("INVALID_FIELD", "字段名称、类型或位置不合法，两侧字段位置必须各自唯一");
            }
            if ("1".equals(field.get("FIELD_INDEX_FLAG"))) keys++;
        }
        if (keys == 0) return new Issue("BUSINESS_KEY_REQUIRED", "至少需要配置一个主键或业务核对键字段");
        return null;
    }

    private NormalizedRequest normalize(BatchRequest request) {
        if (request == null || request.scope() == null || !Set.of("ALL", "SELECTED", "FAILED").contains(request.scope())) throw invalid("请选择全部配置、指定配置或失败项");
        List<Long> ids = normalizeIds(request.configurationIds());
        List<Long> excluded = normalizeIds(request.excludedIds());
        String date = trimToNull(request.taskBusinessDate());
        String verifyDate = trimToNull(request.verifyBusinessDate());
        String time = trimToNull(request.timeNode());
        String source = trimToNull(request.sourceBatchId());
        if ("FAILED".equals(request.scope())) {
            if (source == null || !SIMPLE.matcher(source).matches() || date != null || verifyDate != null || time != null || !ids.isEmpty()) {
                throw invalid("失败项重发必须指定原批次，并沿用原日期、核验日期和每项时点");
            }
        } else {
            if (!validDate(date) || (verifyDate != null && !validDate(verifyDate))) throw invalid("请填写有效的8位业务日期和核验日期（YYYYMMDD）");
            if (verifyDate == null) verifyDate = date;
            if (source != null) throw invalid("只有失败项重发可以指定原批次");
            if ("ALL".equals(request.scope()) && !ids.isEmpty()) throw invalid("全部范围无需指定配置编号");
            if ("SELECTED".equals(request.scope()) && ids.isEmpty()) throw ApiException.badRequest("EMPTY_BATCH", "请至少选择一项核对配置");
            if (time != null && !SIMPLE.matcher(time).matches()) throw invalid("统一时点必须为1～20位字母、数字、点、下划线或横线");
        }
        return new NormalizedRequest(request.scope(), ids, excluded, date, verifyDate, time, source);
    }

    private static List<Long> normalizeIds(List<Long> values) {
        if (values == null) return List.of();
        if (values.size() > MAX_BATCH_SIZE) throw tooLarge();
        var unique = new TreeSet<Long>();
        for (Long value : values) {
            if (value == null || value <= 0) throw invalid("配置编号必须为正整数");
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算批次指纹", exception);
        }
    }
    private static boolean validDate(String value) {
        if (value == null || !value.matches("[0-9]{8}")) return false;
        try { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE); return true; }
        catch (DateTimeParseException exception) { return false; }
    }
    private static long positiveNumber(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { return -1; }
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw ApiException.badRequest("INVALID_PAGE", "分页参数不合法");
    }
    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String valueOrEmpty(String value) { return value == null ? "" : value; }
    private static Issue incomplete() { return new Issue("CONFIGURATION_INCOMPLETE", "核对基本信息、查询SQL、字段或两侧数据库连接不完整"); }
    private static ApiException invalid(String message) { return ApiException.badRequest("INVALID_BATCH_REQUEST", message); }
    private static ApiException tooLarge() { return ApiException.badRequest("BATCH_TOO_LARGE", "单个批次最多允许5000项，请分批发起"); }

    public record RuntimeSettings(boolean tasksEnabled, int concurrency, int maxBatchSize) {}
    private record NormalizedRequest(String scope, List<Long> configurationIds, List<Long> excludedIds,
                                     String taskBusinessDate, String verifyBusinessDate, String timeNode, String sourceBatchId) {}
    private record PreparedBatch(BatchPreview preview, List<BatchRepository.PreparedItem> items) {}
    private record Issue(String code, String message) {}
}
