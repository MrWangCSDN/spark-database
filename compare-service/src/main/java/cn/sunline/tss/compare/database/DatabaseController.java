package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/databases")
public class DatabaseController {
    private final DatabaseManagementService service;
    private final DatabaseConnectionTestService connectionTestService;
    public DatabaseController(DatabaseManagementService service, DatabaseConnectionTestService connectionTestService){
        this.service=service;
        this.connectionTestService=connectionTestService;
    }
    @GetMapping PageResult<DatabaseSummary> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(page,size);}
    @PostMapping("/test") DatabaseConnectionTestResult testDraft(@RequestBody DatabaseSaveRequest request){
        return connectionTestService.testDraft(request);
    }
    @PostMapping("/{id}/test") DatabaseConnectionTestResult testSaved(@PathVariable long id,@RequestBody(required=false) SavedDatabaseTestRequest request){
        return connectionTestService.testSaved(id,request);
    }
    @PostMapping ResponseEntity<DatabaseSummary> create(@RequestBody DatabaseSaveRequest request){
        var created=service.create(request);return ResponseEntity.created(URI.create("/api/databases/"+created.databaseId())).body(created);
    }
    @PutMapping("/{id}") DatabaseSummary update(@PathVariable long id,@RequestBody DatabaseSaveRequest request){return service.update(id,request);}
}
