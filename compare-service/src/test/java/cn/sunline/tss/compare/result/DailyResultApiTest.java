package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DailyResultApiTest {
    JdbcTemplate jdbc;DailyResultRepository repository;MockMvc mvc;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);repository=new DailyResultRepository(ds,new ObjectMapper());
        mvc=MockMvcBuilders.standaloneSetup(new DailyResultController(repository)).setControllerAdvice(new ApiExceptionHandler()).build();
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW VALUES(1,'20260920','v1','20260921','b1','D','客户表','dept',1,'sit',2,'uat',1000005,2000005,3,1000000,2000000,2,1000,1,0,0,'20260922120000','0')");
        jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,RESULT_DATE,SAMPLE_NO,COMP_INDEX_STR,LINE_RESULT,DETAIL_JSON) VALUES(1,'20260920',1,'key','3','{\"fields\":[{\"name\":\"id\",\"originValue\":\"key\",\"destinationValue\":null}],\"truncated\":false}')");
    }
    @Test void separatesFullCountsFromSamplesAndUsesSavedNames() throws Exception {
        mvc.perform(get("/api/results").param("date","20260920")).andExpect(status().isOk())
            .andExpect(jsonPath("$.results.total").value(1)).andExpect(jsonPath("$.connections[0].objectCount").value(1))
            .andExpect(jsonPath("$.results.items[0].onlyOriginCount").value(1000000))
            .andExpect(jsonPath("$.results.items[0].configurationModule").value("dept"));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","3").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.summary.onlyOriginCount").value(1000000))
            .andExpect(jsonPath("$.items[0].detail.fields[0].originValue").value("key"));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","3").param("version","old"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESULT_REPLACED"));
    }
    @Test void expiresSamplesOnlyAndRetainsSummaryCounts() throws Exception {
        assertThat(repository.expire("20260923000000")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW",Long.class)).isZero();
        var summary=repository.exact(1,"20260920");assertThat(summary.onlyOriginCount()).isEqualTo(1000000);
        assertThat(summary.detailsExpired()).isEqualTo("1");assertThat(summary.originSamples()).isZero();
        assertThat(repository.expire("20260923000000")).isZero();
    }
    @Test void listsExactFieldPatternsAndPagesOnlySelectedSamples() throws Exception {
        String first="a".repeat(64),second="b".repeat(64);
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET DIFFERENT_SAMPLES=2 WHERE BCOMP_INDEX=1");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW VALUES(1,'20260920','v1',?,'[\"a\"]',1,1)",first);
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW VALUES(1,'20260920','v1',?,'[\"a\",\"b\"]',1,1)",second);
        for(String id:java.util.List.of(first,second)) jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,RESULT_DATE,SAMPLE_NO,COMP_INDEX_STR,LINE_RESULT,DETAIL_JSON,PATTERN_ID) VALUES(1,'20260920',?,'key','4','{\"fields\":[]}',?)",id.equals(first)?1:2,id);
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].fields[0]").value("a"))
            .andExpect(jsonPath("$[1].differentCount").value(1));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1").param("patternId",first))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","stale"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESULT_REPLACED"));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1").param("patternId","bad"))
            .andExpect(status().isBadRequest());
        assertThat(repository.expire("20260923000000")).isEqualTo(1);
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].differentCount").value(1)).andExpect(jsonPath("$[0].sampleCount").value(0));
    }
    @Test void rejectsInvalidDatesTypesPagesAndMissingResults() throws Exception {
        mvc.perform(get("/api/results").param("date","20260230")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/results").param("date","20260920").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/results/1/20260920/differences").param("type","9").param("version","v1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/results/99/20260920")).andExpect(status().isNotFound());
    }
}
