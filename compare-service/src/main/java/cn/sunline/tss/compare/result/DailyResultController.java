package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/results")
public class DailyResultController {
    private final DailyResultRepository repository;
    public DailyResultController(DailyResultRepository repository) { this.repository=repository; }
    @GetMapping
    DailyResultRepository.Results list(@RequestParam String date,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        validate(date,page,size);return repository.list(date,page,size);
    }
    @GetMapping("/{index}/{date}")
    DailyResultSummary exact(@PathVariable long index,@PathVariable String date) { validate(date,0,20);return repository.exact(index,date); }
    @GetMapping("/{index}/{date}/field-patterns")
    java.util.List<DailyResultRepository.FieldPatternResult> fieldPatterns(@PathVariable long index,@PathVariable String date,@RequestParam String version) {
        validate(date,0,20);return repository.fieldPatterns(index,date,version);
    }
    @GetMapping("/{index}/{date}/differences")
    DailyResultRepository.Samples samples(@PathVariable long index,@PathVariable String date,@RequestParam String type,@RequestParam String version,@RequestParam(required=false) String patternId,
                                        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        validate(date,page,size);
        if(!java.util.Set.of("2","3","4").contains(type)) throw ApiException.badRequest("INVALID_DIFFERENCE_TYPE","请选择有效差异类型");
        if(patternId!=null&&!patternId.matches("[a-f0-9]{64}")) throw ApiException.badRequest("INVALID_PATTERN","字段组合编号不合法");
        return repository.samples(index,date,type,version,patternId,page,size);
    }
    private static void validate(String date,int page,int size) {
        try { if(!date.matches("[0-9]{8}")) throw new IllegalArgumentException();LocalDate.parse(date,DateTimeFormatter.BASIC_ISO_DATE); }
        catch(RuntimeException e) { throw ApiException.badRequest("INVALID_DATE","核对日期必须是有效的8位日期"); }
        if(page<0||size<1||size>100) throw ApiException.badRequest("INVALID_PAGE","分页参数不合法");
    }
}
