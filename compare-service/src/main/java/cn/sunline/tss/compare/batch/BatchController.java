package cn.sunline.tss.compare.batch;

import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.api.ApiError;
import cn.sunline.tss.compare.task.TaskSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestController
@RequestMapping("/api/batches")
public class BatchController {
    private final BatchService service;

    public BatchController(BatchService service) { this.service = service; }

    @PostMapping("/preview")
    public BatchPreview preview(@RequestBody BatchRequest request) { return service.preview(request); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchSummary create(@RequestBody BatchRequest request) { return service.create(request); }

    @GetMapping
    public PageResult<BatchSummary> list(@RequestParam(defaultValue="0") int page,
                                         @RequestParam(defaultValue="20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/runtime")
    public BatchService.RuntimeSettings runtime() { return service.runtime(); }

    @GetMapping("/{batchId}")
    public BatchSummary find(@PathVariable String batchId) { return service.find(batchId); }

    @PostMapping("/{batchId}/cancel")
    public BatchSummary cancel(@PathVariable String batchId) { return service.cancel(batchId); }

    @GetMapping("/{batchId}/tasks")
    public PageResult<TaskSummary> tasks(@PathVariable String batchId,
                                         @RequestParam(defaultValue="ALL") String status,
                                         @RequestParam(defaultValue="0") int page,
                                         @RequestParam(defaultValue="20") int size) {
        return service.tasks(batchId, status, page, size);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_BATCH_REQUEST", "请求格式不合法，请检查配置编号、日期和分页参数",
            UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
    }
}
