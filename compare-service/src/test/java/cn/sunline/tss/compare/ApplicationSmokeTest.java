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
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.Map;
import java.util.UUID;
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
    }
    @Autowired TestRestTemplate http;
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
}
