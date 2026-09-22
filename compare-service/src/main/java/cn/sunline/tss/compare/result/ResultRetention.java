package cn.sunline.tss.compare.result;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
@EnableScheduling
public class ResultRetention {
    private final DailyResultRepository repository;
    private final int days;
    public ResultRetention(DailyResultRepository repository,@Value("${compare.result.retention-days:30}") int days) {
        if(days<1||days>3650) throw new IllegalArgumentException("样本保留天数必须在1..3650之间");
        this.repository=repository;this.days=days;
    }
    @Scheduled(initialDelay=60000,fixedDelay=3600000)
    public void clean() {
        String cutoff=LocalDateTime.now().minusDays(days).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        // Bound each scheduled pass; remaining expired groups are handled next hour.
        for(int i=0;i<10 && repository.expire(cutoff)==100;i++) { }
    }
}
