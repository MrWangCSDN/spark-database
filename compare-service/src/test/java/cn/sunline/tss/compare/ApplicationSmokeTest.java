package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.core.env.Environment;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "tssveritask.executor.thread.enable=false", "spring.config.import="
})
class ApplicationSmokeTest {
    static final String URL="jdbc:h2:mem:boot_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
    static {
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql"))
            .execute(new DriverManagerDataSource(URL,"sa",""));
    }
    @DynamicPropertySource static void metadata(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("jasypt.encryptor.password", () -> "smoke-key");
        registry.add("jasypt.encryptor.algorithm", () -> "PBEWITHHMACSHA512ANDAES_256");
        registry.add("jasypt.encryptor.iv-generator-classname", () -> "org.jasypt.iv.RandomIvGenerator");
        registry.add("encrypted.metadata.password", () -> "ENC(" + MetadataPasswordCipher.encryptor("smoke-key").encrypt("runtime-password") + ")");
        registry.add("spark.datasource.password", () -> "${encrypted.metadata.password}");
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Test void jasyptDecryptsPropertyAndNestedSparkPassword() {
        assertThat(environment.getProperty("encrypted.metadata.password")).isEqualTo("runtime-password");
        assertThat(environment.getProperty("spark.datasource.password")).isEqualTo("runtime-password");
    }
    @Test void bootsStandaloneWithoutFileOrRedisTables() {
        assertThat(http.getForObject("/tss/runStatus",String.class)).isEqualTo("2");
        assertThat(http.postForObject("/api/cache/refresh",null,Map.class)).containsEntry("code",200);
    }

    @Test void servesTheManagementConsoleWithoutCapturingApiRoutes() {
        for (String path : new String[]{"/results", "/results/1/20260920", "/tasks", "/batches", "/batches/new", "/batches/202609220001"}) {
            ResponseEntity<String> page=http.getForEntity(path,String.class);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page.getBody()).contains("<div id=\"root\">");
        }
        assertThat(http.getForEntity("/api/missing",String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void wiresRestrictedConfigurationImportWithStableEmptyFileError() {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response=http.exchange("/api/configurations/import",HttpMethod.POST,new HttpEntity<>(new LinkedMultiValueMap<>(),headers),Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code","CONFIG_SCRIPT_REQUIRED");
    }

    @Test void servesDailySampleGridThroughRealResultEndpoints() {
        String patternId="a".repeat(64);
        jdbc.update("INSERT INTO TSS_BCOMP_RESULT_NEW VALUES(17,'20260923','browser-v1','20260923','batch-1','D','客户余额','dept',1,'sit',2,'uat',5,5,4,0,0,1,100,0,0,1,'20260923120000','0')");
        jdbc.update("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(17,'20260923','browser-v1',?,'[\"amount\"]',1,1,1)",patternId);
        jdbc.update("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,RESULT_DATE,SAMPLE_NO,COMP_INDEX_STR,LINE_RESULT,DETAIL_JSON,PATTERN_ID) VALUES(17,'20260923',1,'C001','4','{\"fields\":[{\"name\":\"amount\",\"label\":\"金额\",\"originValue\":\"120.00\",\"destinationValue\":\"125.00\"}],\"truncated\":false}',?)",patternId);

        assertThat(http.getForEntity("/results/17/20260923",String.class).getBody()).contains("<div id=\"root\">");
        JsonNode summary=http.getForObject("/api/results/17/20260923",JsonNode.class);
        assertThat(summary.path("detailLimit").asInt()).isEqualTo(100);
        assertThat(summary.path("differentCount").asInt()).isEqualTo(1);
        JsonNode patterns=http.getForObject("/api/results/17/20260923/field-patterns?version=browser-v1",JsonNode.class);
        assertThat(patterns.get(0).path("patternId").asText()).isEqualTo(patternId);
        assertThat(patterns.get(0).path("sampleCount").asInt()).isEqualTo(1);
        assertThat(patterns.get(0).path("collapsedPatternCount").asInt()).isEqualTo(1);
        JsonNode samples=http.getForObject("/api/results/17/20260923/differences?type=4&patternId="+patternId+"&version=browser-v1&page=0&size=100",JsonNode.class);
        assertThat(samples.path("total").asInt()).isEqualTo(1);
        assertThat(samples.path("items").get(0).path("detail").path("fields").get(0).path("label").asText()).isEqualTo("金额");
        assertThat(samples.path("items").get(0).path("detail").path("fields").get(0).path("destinationValue").asText()).isEqualTo("125.00");
    }
}
