package cn.sunline.tss.compare.task;

import cn.sunline.tss.compare.api.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskManagementService service;
    public TaskController(TaskManagementService service) { this.service = service; }

    @PostMapping
    ResponseEntity<TaskSummary> create(@RequestBody TaskCreateRequest request) {
        TaskSummary created = service.create(request);
        String location = "/api/tasks/%d/%s/%s/%s".formatted(created.bcompIndex(), created.taskBusinessDate(), created.taskBatch(), created.timeNode());
        return ResponseEntity.created(URI.create(location)).body(created);
    }

    @GetMapping
    PageResult<TaskSummary> list(@RequestParam(required=false) Long bcompIndex,
                                 @RequestParam(required=false) String taskState,
                                 @RequestParam(required=false) String taskResult,
                                 @RequestParam(required=false) String dateFrom,
                                 @RequestParam(required=false) String dateTo,
                                 @RequestParam(required=false) String keyword,
                                 @RequestParam(defaultValue="0") int page,
                                 @RequestParam(defaultValue="20") int size,
                                 @RequestParam(defaultValue="createdAt,desc") String sort) {
        return service.findTasks(bcompIndex, taskState, taskResult, dateFrom, dateTo, keyword, page, size, sort);
    }

    @GetMapping("/{index}/{date}/{batch}/{timeNode}")
    TaskSummary exact(@PathVariable long index, @PathVariable String date,
                      @PathVariable String batch, @PathVariable String timeNode) {
        return service.exact(index, date, batch, timeNode);
    }

    @GetMapping("/{index}/{date}/{batch}/{timeNode}/differences")
    PageResult<DifferenceSummary> differences(@PathVariable long index, @PathVariable String date,
                                               @PathVariable String batch, @PathVariable String timeNode,
                                               @RequestParam(required=false) String lineResult,
                                               @RequestParam(required=false) String businessKey,
                                               @RequestParam(defaultValue="0") int page,
                                               @RequestParam(defaultValue="20") int size) {
        service.exact(index, date, batch, timeNode);
        return service.differences(index, date, batch, lineResult, businessKey, page, size);
    }
}
