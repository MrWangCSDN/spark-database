package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class DatabaseConnectionTester implements AutoCloseable {
    private static final String CONNECTION_FAILED = "数据库连接失败，请检查地址、网络和凭据";

    private final DatabaseConnectionProbe probe;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;

    @Autowired
    public DatabaseConnectionTester(
        DatabaseConnectionProbe probe,
        @Value("${compare.database-test-timeout-seconds:10}") long timeoutSeconds,
        @Value("${compare.database-test-max-concurrency:4}") int maxConcurrency
    ) {
        this(probe, Duration.ofSeconds(timeoutSeconds), maxConcurrency);
    }

    DatabaseConnectionTester(
        DatabaseConnectionProbe probe,
        Duration timeout,
        int maxConcurrency
    ) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("database connection test timeout must be positive");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("database connection test concurrency must be positive");
        }
        this.probe = probe;
        this.timeout = timeout;
        this.executor = new ThreadPoolExecutor(
            maxConcurrency,
            maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public DatabaseConnectionTestResult test(DatabaseConnectionSpec spec) {
        long startedAt = System.nanoTime();
        Future<DatabaseConnectionProbe.Metadata> future;
        try {
            future = executor.submit(() -> probe.connect(spec));
        } catch (RejectedExecutionException exception) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "DATABASE_TEST_BUSY",
                "连接测试任务繁忙，请稍后重试"
            );
        }

        try {
            DatabaseConnectionProbe.Metadata metadata = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new DatabaseConnectionTestResult(true, metadata.product(), metadata.version(), elapsedMs);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ApiException(
                HttpStatus.GATEWAY_TIMEOUT,
                "DATABASE_CONNECTION_TIMEOUT",
                "数据库连接超时，请检查地址和网络"
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw connectionFailed();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            throw connectionFailed();
        }
    }

    private static ApiException connectionFailed() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "DATABASE_CONNECTION_FAILED",
            CONNECTION_FAILED
        );
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "database-connection-test-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
