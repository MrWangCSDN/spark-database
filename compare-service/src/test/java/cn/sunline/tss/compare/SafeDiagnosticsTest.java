package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

class SafeDiagnosticsTest {
    @Test void preservesCauseTypesAndSafeContextWhileRedactingCredentials() {
        var cause = new SQLException("column CONFIGURATION_MODULE missing; password=db-secret; jdbc:postgresql://db.internal:5432/app?user=ccbs&password=query-secret");
        var error = new IllegalStateException("JASYPT_ENCRYPTOR_PASSWORD=ccbs", cause);

        String summary = SafeDiagnostics.summary(error);

        assertThat(summary).contains("IllegalStateException", "SQLException", "column CONFIGURATION_MODULE missing");
        assertThat(summary).contains("JASYPT_ENCRYPTOR_PASSWORD=[REDACTED]", "jdbc:[REDACTED]");
        assertThat(summary).doesNotContain("db-secret", "query-secret", "db.internal", "=ccbs");
    }

    @Test void emitsSanitizedStackFramesWithoutRevealingNullOrSecretMessages() {
        var cause = new RuntimeException((String) null);
        var error = new IllegalArgumentException("pwd: hidden-value", cause);

        String detail = SafeDiagnostics.logDetail(error);

        assertThat(detail).contains("IllegalArgumentException", "RuntimeException", "SafeDiagnosticsTest");
        assertThat(detail).contains("pwd:[REDACTED]").doesNotContain("hidden-value");
    }

    @Test void boundsPersistedAndLoggedDiagnostics() {
        var error = new IllegalStateException("x".repeat(6000));

        assertThat(SafeDiagnostics.summary(error)).hasSizeLessThanOrEqualTo(500);
        assertThat(SafeDiagnostics.logDetail(error)).hasSizeLessThanOrEqualTo(4000);
    }
}
