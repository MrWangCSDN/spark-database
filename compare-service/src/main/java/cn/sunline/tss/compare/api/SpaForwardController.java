package cn.sunline.tss.compare.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {
    @GetMapping({
        "/", "/tasks", "/tasks/{index}/{date}/{batch}/{timeNode}",
        "/batches", "/batches/{batchId}",
        "/configurations", "/configurations/{index}", "/databases"
    })
    String forwardManagementRoutes() {
        return "forward:/index.html";
    }
}
