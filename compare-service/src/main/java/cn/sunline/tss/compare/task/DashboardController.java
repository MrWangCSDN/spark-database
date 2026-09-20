package cn.sunline.tss.compare.task;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
    private final TaskManagementService service;
    public DashboardController(TaskManagementService service) { this.service = service; }
    @GetMapping("/api/dashboard") DashboardSummary dashboard() { return service.dashboard(); }
}
