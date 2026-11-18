package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DailyResultExportService {
    private static final Logger log=LoggerFactory.getLogger(DailyResultExportService.class);
    private final DailyResultRepository repository;
    private final DailyResultWorkbookWriter writer;

    public DailyResultExportService(DailyResultRepository repository,DailyResultWorkbookWriter writer) {
        this.repository=repository;this.writer=writer;
    }

    public byte[] export(String date) {
        try {
            var snapshot=repository.exportSnapshot(date);
            if(snapshot.results().isEmpty()) throw ApiException.notFound("RESULT_NOT_FOUND","该日期没有可导出的核对结果");
            return writer.write(DailyResultExportProjection.build(snapshot));
        } catch(ApiException error) {
            log.warn("Daily result export rejected: date={}, code={}, causeType={}",date,error.code(),
                error.getCause()==null?error.getClass().getSimpleName():error.getCause().getClass().getSimpleName());
            throw error;
        } catch(RuntimeException error) {
            log.warn("Daily result export failed: date={}, causeType={}",date,error.getClass().getSimpleName());
            throw ApiException.internal("RESULT_EXPORT_FAILED","生成每日核对结果报表失败",error);
        }
    }
}
