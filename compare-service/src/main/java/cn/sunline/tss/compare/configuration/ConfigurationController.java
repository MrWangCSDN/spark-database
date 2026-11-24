package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.api.ApiError;
import cn.sunline.tss.compare.api.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ConfigurationController {
    private final ConfigurationService service;
    public ConfigurationController(ConfigurationService service){this.service=service;}
    PageResult<BasicConfiguration> list(int page,int size){return service.list(page,size);}
    @GetMapping("/api/configurations") PageResult<BasicConfiguration> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String keyword){return service.list(page,size,keyword);}
    @PostMapping(value="/api/configurations/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) ConfigScriptImportResult importScript(@RequestPart(value="file",required=false) MultipartFile file){return service.importScript(file);}
    @GetMapping("/api/configurations/{index}") ConfigurationDetail find(@PathVariable long index){return service.find(index);}
    @PostMapping("/api/configurations") ResponseEntity<ConfigurationSaveResult> create(@RequestBody ConfigurationCommand command){var result=service.create(command);return ResponseEntity.created(URI.create("/api/configurations/"+result.configuration().basic().bcompIndex())).body(result);}
    @PutMapping("/api/configurations/{index}") ConfigurationSaveResult update(@PathVariable long index,@RequestBody ConfigurationCommand command){return service.update(index,command);}
    @PostMapping("/api/configurations/{index}/copy") ResponseEntity<ConfigurationSaveResult> copy(@PathVariable long index,@RequestBody CopyConfigurationRequest request){var result=service.copy(index,request);return ResponseEntity.created(URI.create("/api/configurations/"+result.configuration().basic().bcompIndex())).body(result);}
    @GetMapping("/api/configuration-options") ConfigurationOptions options(){return service.options();}

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidConfigurationJson(HttpMessageNotReadableException exception) {
        for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
            if(cause instanceof ApiException error&&"INVALID_MAX_PARTITION_NUM".equals(error.code())) {
                return ResponseEntity.badRequest().body(new ApiError(error.code(),error.getMessage(),
                    UUID.randomUUID().toString().replace("-","").substring(0,16)));
            }
        }
        return ResponseEntity.badRequest().body(new ApiError("INVALID_CONFIGURATION","请求格式不合法，请检查核对配置",
            UUID.randomUUID().toString().replace("-","").substring(0,16)));
    }
}
