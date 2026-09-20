package cn.sunline.tss.compare;

import org.springframework.web.bind.annotation.*;

@RestController
public class StatusController {
    private final TaskWorker worker;
    public StatusController(TaskWorker worker) { this.worker=worker; }
    @GetMapping("/tss/runStatus") public String status() { return worker.status(); }
    @PostMapping("/api/cache/refresh") public TaskWorker.RefreshResult refresh() { return worker.refresh(); }
}
