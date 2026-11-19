package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SparkFailureDiagnosticsTest {
    @Test void doesNotTreatAnOperatingSystemCodeInAFileNameAsTheFailureReason() {
        assertEquals("AccessDeniedException",SparkFailureDiagnostics.summary(new AccessDeniedException("/data/ENOSPC")));
        assertEquals("IOException",SparkFailureDiagnostics.summary(new IOException("/data/ENOSPC")));
        assertTrue(SparkFailureDiagnostics.summary(new IOException("write failed: ENOSPC (No space left on device)"))
            .contains("LOCAL_DISK_FULL"));
    }

    @Test void stopsAtEightCausesAndDoesNotClassifyAnUnvisitedCause() {
        Throwable failure=new IOException("No space left on device");
        for(int index=0;index<8;index++) failure=new IllegalStateException("sensitive-message",failure);

        assertEquals(String.join(" -> ",Collections.nCopies(8,"IllegalStateException")),SparkFailureDiagnostics.summary(failure));
    }

    @Test void stopsAtAnIdentityCycleWithoutRepeatingRawMessages() {
        var first=new IllegalStateException("sensitive-one");
        var second=new IllegalArgumentException("sensitive-two");
        first.initCause(second);
        second.initCause(first);

        assertEquals("IllegalStateException -> IllegalArgumentException",SparkFailureDiagnostics.summary(first));
    }

    @Test void preservesControlledFieldHintsAndBoundsTheTotalSummary() {
        var failure=new QueryFieldResolver.FieldResolutionException("数据库A字段[id]：SQL结果缺少配置字段\n"+"x".repeat(1000));

        String diagnostic=SparkFailureDiagnostics.summary(failure);

        assertTrue(diagnostic.startsWith("FieldResolutionException（数据库A字段[id]：SQL结果缺少配置字段"),diagnostic);
        assertTrue(diagnostic.length()<=500,diagnostic);
        assertFalse(diagnostic.contains("\n"),diagnostic);
    }
}
