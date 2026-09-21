package cn.sunline.tss.compare.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> apiException(ApiException exception) {
        String traceId = traceId();
        log.warn("管理API请求失败 traceId={}, code={}, exceptionType={}",
            traceId, exception.code(), exception.getClass().getSimpleName());
        return ResponseEntity.status(exception.status())
            .body(new ApiError(exception.code(), exception.getMessage(), traceId));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missingResource(NoResourceFoundException exception) {
        String traceId = traceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiError("RESOURCE_NOT_FOUND", "请求的资源不存在", traceId));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException exception) {
        String traceId = traceId();
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new ApiError("CONFIG_SCRIPT_TOO_LARGE", "配置脚本超过允许的大小", traceId));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        String traceId = traceId();
        log.error("管理API内部异常 traceId={}, exceptionType={}", traceId, exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiError("INTERNAL_ERROR", "服务处理失败，请根据追踪编号检查日志", traceId));
    }

    private static String traceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
