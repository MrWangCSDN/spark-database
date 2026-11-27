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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DailyResultApiTest {
    JdbcTemplate jdbc;DailyResultRepository repository;MockMvc mvc;
    @Test void exportsFrozenConfigurationMetadataAfterAllCurrentConfigurationHasChanged() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET CONFIGURATION_NAME='a_1',SOURCE_TABLE_NAME='a',CONFIGURATION_DISPLAY_NAME='配置乙',CONFIGURATION_SCOPE='b=2'");
        jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,SOURCE_TABLE_NAME,CONFIGURATION_NAME,CONFIGURATION_SCOPE) VALUES(1,'different','changed','新名称','changed=1')");
        mvc.perform(get("/api/results/1/20260920")).andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationName").value("a_1"))
            .andExpect(jsonPath("$.sourceTableName").value("a"))
            .andExpect(jsonPath("$.configurationDisplayName").value("配置乙"))
            .andExpect(jsonPath("$.configurationScope").value("b=2"));
        byte[] bytes=mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var tables=book.getSheet("表明细");var fields=book.getSheet("表字段明细");
            assertThat(tables.getRow(0).getCell(8).getStringCellValue()).isEqualTo("真实表名");
            assertThat(tables.getRow(0).getCell(9).getStringCellValue()).isEqualTo("配置名称");
            assertThat(tables.getRow(0).getCell(10).getStringCellValue()).isEqualTo("查询条件");
            assertThat(tables.getRow(1).getCell(1).getStringCellValue()).isEqualTo("a_1");
            assertThat(tables.getRow(1).getCell(8).getStringCellValue()).isEqualTo("a");
            assertThat(tables.getRow(1).getCell(9).getStringCellValue()).isEqualTo("配置乙");
            assertThat(tables.getRow(1).getCell(10).getStringCellValue()).isEqualTo("b=2");
            assertThat(fields.getRow(1).getCell(1).getStringCellValue()).isEqualTo("a_1");
            assertThat(fields.getRow(1).getCell(5).getStringCellValue()).isEqualTo("a");
            assertThat(fields.getRow(1).getCell(6).getStringCellValue()).isEqualTo("配置乙");
            assertThat(fields.getRow(1).getCell(7).getStringCellValue()).isEqualTo("b=2");
        }
    }
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);repository=new DailyResultRepository(ds,new ObjectMapper());
        var exportService=new DailyResultExportService(repository,new DailyResultWorkbookWriter());
        mvc=MockMvcBuilders.standaloneSetup(new DailyResultController(repository,exportService)).setControllerAdvice(new ApiExceptionHandler()).build();
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,CONFIGURATION_NAME,CONFIGURATION_MODULE,ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT,DETAILS_EXPIRED) VALUES(1,'20260920','v1','20260921','b1','D','客户表','dept',1,'sit',2,'uat',1000005,2000005,3,1000000,2000000,2,1000,1,0,0,'20260922120000','0')");
        jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,RESULT_DATE,SAMPLE_NO,COMP_INDEX_STR,LINE_RESULT,DETAIL_JSON) VALUES(1,'20260920',1,'key','3','{\"fields\":[{\"name\":\"id\",\"originValue\":\"key\",\"destinationValue\":null}],\"truncated\":false}')");
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET FIELD_COUNTS_VERSION=1,FIELD_COUNTS_SIZE=1 WHERE BCOMP_INDEX=1");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(1,'20260920','v1','A',2)");
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
    @Test void filtersDailyResultsBeforeCountingGroupingAndPaging() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET CONFIGURATION_NAME='客户_%表',REVISER_NAME='王山河',REVISER_USERNAME='c-wangsh8' WHERE BCOMP_INDEX=1");
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,CONFIGURATION_NAME,CONFIGURATION_MODULE,REVISER_NAME,REVISER_USERNAME,ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT) VALUES(2,'20260920','v2','20260921','b2','D','贷款账户','loan','李四','lisi',3,'other-sit',4,'other-uat',1,1,1,0,0,0,0,0,0,0,'20260922120100')");
        mvc.perform(get("/api/results").param("date","20260920").param("domain","存款").param("configurationName","_%").param("reviser","WANGSH8").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.total").value(1))
            .andExpect(jsonPath("$.results.items[0].reviserName").value("王山河"))
            .andExpect(jsonPath("$.results.items[0].reviserUsername").value("c-wangsh8"))
            .andExpect(jsonPath("$.connections.length()").value(1))
            .andExpect(jsonPath("$.connections[0].objectCount").value(1));
        mvc.perform(get("/api/results").param("date","20260920").param("domain","DEPT").param("reviser","王山"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.total").value(1));
        mvc.perform(get("/api/results").param("date","20260920").param("configurationName","_%").param("page","1").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.total").value(1))
            .andExpect(jsonPath("$.results.items.length()").value(0));
        mvc.perform(get("/api/results/1/20260920"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviserUsername").value("c-wangsh8"));
    }
    @Test void summaryMappingKeepsNullableDatabaseIdsAndLongCounts() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET ORIGIN_DATABASE_ID=NULL,DESTINATION_DATABASE_ID=NULL,ORIGIN_COUNT=3000000000,DESTINATION_COUNT=4000000000,ONLY_ORIGIN_COUNT=2999999995,ONLY_DESTINATION_COUNT=3999999995,REVISER_NAME='负责人',REVISER_USERNAME='owner' WHERE BCOMP_INDEX=1");
        var summary=repository.exact(1,"20260920");
        assertThat(summary.originDatabaseId()).isNull();
        assertThat(summary.destinationDatabaseId()).isNull();
        assertThat(summary.originCount()).isEqualTo(3_000_000_000L);
        assertThat(summary.destinationCount()).isEqualTo(4_000_000_000L);
        assertThat(summary.reviserName()).isEqualTo("负责人");
        assertThat(summary.reviserUsername()).isEqualTo("owner");
        mvc.perform(get("/api/results").param("date","20260920"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results.items[0].originCount").value(3_000_000_000L))
            .andExpect(jsonPath("$.results.items[0].destinationCount").value(4_000_000_000L));
        assertThat(repository.exportSnapshot("20260920").results().get(0).summary()).isEqualTo(summary);
    }

    @Test void fullResultsPageAcrossEveryPatternAndPreserveLongCounts() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET DETAIL_LIMIT=-1,ORIGIN_COUNT=3000000000,DESTINATION_COUNT=3000000000,MATCHED_COUNT=0,ONLY_ORIGIN_COUNT=0,ONLY_DESTINATION_COUNT=0,DIFFERENT_COUNT=3000000000,DIFFERENT_SAMPLES=3000000000");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW VALUES(1,'20260920','v1','__OTHER__','[]',3000000000,3000000000,2999999999)");
        jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,RESULT_DATE,LINE_RESULT,SAMPLE_NO,COMP_INDEX_STR,DETAIL_JSON,PATTERN_ID) VALUES(1,'20260920','4',2147483650,'hidden-combination','{}',?)","a".repeat(64));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3_000_000_000L))
            .andExpect(jsonPath("$.items[0].businessKey").value("hidden-combination"));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1").param("page","2147483648").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2_147_483_648L));
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].sampleCount").value(3_000_000_000L))
            .andExpect(jsonPath("$[0].collapsedPatternCount").value(2_999_999_999L));
    }

    @Test void stageMaintenanceOnlyReclaimsRunsWhoseTaskHasFinishedOrDisappeared() {
        for(String batch:java.util.List.of("active","finished","missing")) {
            jdbc.update("INSERT INTO TSS_BCOMP_FULL_RUN_NEW VALUES(?,1,'20260920',?,'D','20000101000000')",batch,batch);
            jdbc.update("INSERT INTO TSS_BCOMP_FULL_STAGE_NEW(RUN_ID,PART_ID,ATTEMPT_ID,LOCAL_NO,LINE_RESULT,COMP_INDEX_STR,DETAIL_JSON) VALUES(?,0,1,1,'2','key','{}')",batch);
        }
        jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20260920','active','D','2'),(1,'20260920','finished','D','4')");
        repository.removeFinishedStages();
        assertThat(jdbc.queryForList("SELECT RUN_ID FROM TSS_BCOMP_FULL_STAGE_NEW",String.class)).containsExactly("active");
    }

    @Test void allResultsAreNotLimitedByListPagination() {
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,CONFIGURATION_NAME,CONFIGURATION_MODULE,ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT,DETAILS_EXPIRED) VALUES(2,'20260920','v2','20260921','b1','D','账户表','dept',3,'aaa',4,'bbb',10,11,9,1,2,0,100,0,0,0,'20260922120100','0')");
        assertThat(repository.list("20260920",0,1).results().items()).hasSize(1);
        assertThat(repository.exportSnapshot("20260920").results())
            .extracting(result->result.summary().bcompIndex()).containsExactly(1L,2L);
    }
    @Test void exportsCompleteWorkbookWithUtf8FilenameAndDynamicSavedNames() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET ORIGIN_DATABASE_NAME='核心库',DESTINATION_DATABASE_NAME='新库' WHERE BCOMP_INDEX=1");
        byte[] body=mvc.perform(get("/api/results/export").param("date","20260920"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,allOf(
                containsString("filename=\"daily-results-20260920.xlsx\""),
                containsString("filename*=UTF-8''"))))
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).startsWith((byte)'P',(byte)'K');
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(body))) {
            assertThat(workbook.getSheet("领域汇总")).isNotNull();
            assertThat(workbook.getSheet("表明细")).isNotNull();
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheet("领域汇总").getRow(0).getCell(1).getStringCellValue()).isEqualTo("核心库总量");
            assertThat(workbook.getSheet("表明细").getRow(0).getCell(4).getStringCellValue()).isEqualTo("新库总量");
            for(String sheet:java.util.List.of("领域汇总","表明细","表字段明细"))
                assertThat(workbook.getSheet(sheet).getRow(1).getCell(0).getStringCellValue()).isEqualTo("存款组");
        }
    }
    @Test void exportsSavedReviserAfterTableInBothDetailSheets() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET REVISER_NAME='王山河',REVISER_USERNAME='c-wangsh8' WHERE BCOMP_INDEX=1");
        try(var workbook=exportWorkbook()) {
            var summary=workbook.getSheet("领域汇总");
            var tables=workbook.getSheet("表明细");
            var fields=workbook.getSheet("表字段明细");
            assertThat(summary.getRow(0).getLastCellNum()).isEqualTo((short)6);
            assertThat(tables.getRow(0).getCell(1).getStringCellValue()).isEqualTo("表");
            assertThat(tables.getRow(0).getCell(2).getStringCellValue()).isEqualTo("负责人");
            assertThat(tables.getRow(1).getCell(2).getStringCellValue()).isEqualTo("王山河(c-wangsh8)");
            assertThat(tables.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(1000005d);
            assertThat(tables.getTables().get(0).getCTTable().getAutoFilter().getRef()).isEqualTo("A1:K2");
            assertThat(fields.getRow(0).getCell(2).getStringCellValue()).isEqualTo("负责人");
            assertThat(fields.getRow(1).getCell(2).getStringCellValue()).isEqualTo("王山河(c-wangsh8)");
            assertThat(fields.getRow(1).getCell(3).getStringCellValue()).isEqualTo("A");
            assertThat(fields.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(2d);
            assertThat(fields.getTables().get(0).getCTTable().getAutoFilter().getRef()).isEqualTo("A1:H2");
        }
    }
    @Test void exportsPlaceholderForHistoricalResultWithoutReviser() throws Exception {
        try(var workbook=exportWorkbook()) {
            assertThat(workbook.getSheet("表明细").getRow(1).getCell(2).getStringCellValue()).isEqualTo("—");
            assertThat(workbook.getSheet("表字段明细").getRow(1).getCell(2).getStringCellValue()).isEqualTo("—");
        }
    }
    @Test void exportsNativeCountsIncludingFieldsOnlyInCollapsedPatterns() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET DIFFERENT_COUNT=30127,ORIGIN_COUNT=1030130,DESTINATION_COUNT=2030130,FIELD_COUNTS_SIZE=2");
        jdbc.update("DELETE FROM TSS_BCOMP_FIELD_COUNT_NEW");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(1,'20260920','v1','ALL',30127),(1,'20260920','v1','RARE',29873)");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW VALUES(1,'20260920','v1','__OTHER__','[]',29873,0,29873)");
        try(var workbook=exportWorkbook()) {
            var sheet=workbook.getSheet("表字段明细");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("ALL");
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(30127d);
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("RARE");
            assertThat(sheet.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(29873d);
        }
    }
    @Test void exportRequiresRerunForIncompleteLegacyResultsButAcceptsCompletePatterns() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET FIELD_COUNTS_VERSION=0");
        jdbc.update("DELETE FROM TSS_BCOMP_FIELD_COUNT_NEW");
        mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
            .andExpect(jsonPath("$.code").value("FIELD_COUNTS_UNAVAILABLE")).andExpect(jsonPath("$.message",containsString("重新核对")));
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW VALUES(1,'20260920','v1','legacy','[\"A\",\"B\"]',2,0,1)");
        try(var workbook=exportWorkbook()) {
            assertThat(workbook.getSheet("表字段明细").getRow(2).getCell(3).getStringCellValue()).isEqualTo("B");
            assertThat(workbook.getSheet("表字段明细").getRow(2).getCell(4).getNumericCellValue()).isEqualTo(2d);
        }
    }
    @Test void exportWrapsFieldReadFailuresAsJson() throws Exception {
        jdbc.update("DROP TABLE TSS_BCOMP_FIELD_COUNT_NEW");
        mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("RESULT_EXPORT_FAILED")).andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }
    @Test void logsFailedConfigurationAndSafeReasonWithoutBusinessValues() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_FIELD_COUNT_NEW SET RUN_ID='stale-private-value',FIELD_NAME='private-field'");
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET CONFIGURATION_NAME='private-table'");
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(DailyResultRepository.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();logger.addAppender(events);
        try {
            mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isInternalServerError());
            assertThat(events.list).anySatisfy(event->assertThat(event.getFormattedMessage())
                .contains("date=20260920","configurationId=1","字段统计结果版本不一致"));
            assertThat(events.list).allSatisfy(event->assertThat(event.getFormattedMessage())
                .doesNotContain("private-table","private-field","stale-private-value"));
        } finally {logger.detachAppender(events);events.stop();}
    }
    @Test void rejectsNonStringFieldsAndTrailingJsonInsteadOfExportingInventedFields() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET FIELD_COUNTS_VERSION=0");
        for(String invalid:java.util.List.of("[123,true]","[\"A\"] trailing garbage","[\"A\"] [\"B\"]","[\"A\",null]","{}")) {
            jdbc.update("DELETE FROM TSS_BCOMP_FIELD_DIFF_NEW");
            jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW VALUES(1,'20260920','v1','legacy',?,2,0,1)",invalid);
            mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RESULT_EXPORT_FAILED"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
        }
    }
    @Test void rejectsPartialFieldLossEvenWhenRemainingFieldCoversEveryDifference() throws Exception {
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET DIFFERENT_COUNT=260,ORIGIN_COUNT=1000263,DESTINATION_COUNT=2000263,FIELD_COUNTS_SIZE=2");
        jdbc.update("UPDATE TSS_BCOMP_FIELD_COUNT_NEW SET DIFFERENT_COUNT=260 WHERE FIELD_NAME='A'");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW VALUES(1,'20260920','v1','B',180)");
        try(var workbook=exportWorkbook()) {
            assertThat(workbook.getSheet("表字段明细").getRow(2).getCell(4).getNumericCellValue()).isEqualTo(180d);
        }
        jdbc.update("DELETE FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE FIELD_NAME='B'");
        mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("RESULT_EXPORT_FAILED"))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }
    private XSSFWorkbook exportWorkbook() throws Exception {
        byte[] bytes=mvc.perform(get("/api/results/export").param("date","20260920")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }
    @Test void exportRejectsInvalidOrEmptyDatesWithoutReturningAnXlsx() throws Exception {
        mvc.perform(get("/api/results/export"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_DATE"));
        mvc.perform(get("/api/results/export").param("date","20260230"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_DATE"));
        mvc.perform(get("/api/results/export").param("date","20260919"))
            .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("RESULT_NOT_FOUND"));
    }
    @Test void exportRejectsCountsThatExcelCannotRepresentExactly() throws Exception {
        long unsafe=9_007_199_254_740_993L,matched=unsafe-1_000_000L-2L;
        jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET ORIGIN_COUNT=?,DESTINATION_COUNT=?,MATCHED_COUNT=? WHERE BCOMP_INDEX=1",
            unsafe,matched+2_000_000L+2L,matched);
        mvc.perform(get("/api/results/export").param("date","20260920"))
            .andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("RESULT_EXPORT_FAILED"));
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
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(1,'20260920','v1',?,'[\"a\"]',2,1,1)",first);
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(1,'20260920','v1',?,'[\"a\",\"b\"]',1,1,1)",second);
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(1,'20260920','v1','__OTHER__','[]',99,0,3)");
        for(String id:java.util.List.of(first,second)) jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,RESULT_DATE,SAMPLE_NO,COMP_INDEX_STR,LINE_RESULT,DETAIL_JSON,PATTERN_ID) VALUES(1,'20260920',?,'key','4','{\"fields\":[]}',?)",id.equals(first)?1:2,id);
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].fields[0]").value("a"))
            .andExpect(jsonPath("$[0].collapsedPatternCount").value(1))
            .andExpect(jsonPath("$[2].patternId").value("__OTHER__"))
            .andExpect(jsonPath("$[2].fields.length()").value(0))
            .andExpect(jsonPath("$[2].sampleCount").value(0))
            .andExpect(jsonPath("$[2].collapsedPatternCount").value(3));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1").param("patternId",first))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","stale"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESULT_REPLACED"));
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1").param("patternId","bad"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/results/1/20260920/differences").param("type","4").param("version","v1").param("patternId","__OTHER__"))
            .andExpect(status().isBadRequest());
        assertThat(repository.expire("20260923000000")).isEqualTo(1);
        mvc.perform(get("/api/results/1/20260920/field-patterns").param("version","v1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].differentCount").value(2)).andExpect(jsonPath("$[0].sampleCount").value(0));
    }
    @Test void rejectsInvalidDatesTypesPagesAndMissingResults() throws Exception {
        mvc.perform(get("/api/results").param("date","20260230")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/results").param("date","20260920").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/results/1/20260920/differences").param("type","9").param("version","v1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/results/99/20260920")).andExpect(status().isNotFound());
    }
}
