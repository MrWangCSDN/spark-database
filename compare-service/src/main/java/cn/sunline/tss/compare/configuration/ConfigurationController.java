package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class ConfigurationController {
    private final ConfigurationService service;
    public ConfigurationController(ConfigurationService service){this.service=service;}
    @GetMapping("/api/configurations") PageResult<BasicConfiguration> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.list(page,size);}
    @GetMapping("/api/configurations/{index}") ConfigurationDetail find(@PathVariable long index){return service.find(index);}
    @PostMapping("/api/configurations") ResponseEntity<ConfigurationSaveResult> create(@RequestBody ConfigurationCommand command){var result=service.create(command);return ResponseEntity.created(URI.create("/api/configurations/"+result.configuration().basic().bcompIndex())).body(result);}
    @PutMapping("/api/configurations/{index}") ConfigurationSaveResult update(@PathVariable long index,@RequestBody ConfigurationCommand command){return service.update(index,command);}
    @PostMapping("/api/configurations/{index}/copy") ResponseEntity<ConfigurationSaveResult> copy(@PathVariable long index,@RequestBody CopyConfigurationRequest request){var result=service.copy(index,request);return ResponseEntity.created(URI.create("/api/configurations/"+result.configuration().basic().bcompIndex())).body(result);}
    @GetMapping("/api/configuration-options") ConfigurationOptions options(){return service.options();}
}
