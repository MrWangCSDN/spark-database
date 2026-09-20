package cn.sunline.tss.compare.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    @Test
    void returnsStableCodeAndTraceIdWithoutLeakingCause() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        var response = mvc.perform(get("/test/conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TASK_IDENTITY_CONFLICT"))
            .andExpect(jsonPath("$.message").value("任务已经存在"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("database-password");
    }

    @Test
    void pageRejectsUnsafeBounds() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PageResult<>(java.util.List.of(), 0, 101, 0));
    }

    @RestController
    static class TestController {
        @GetMapping("/test/conflict")
        Object conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_IDENTITY_CONFLICT", "任务已经存在",
                new IllegalStateException("database-password"));
        }
    }
}
