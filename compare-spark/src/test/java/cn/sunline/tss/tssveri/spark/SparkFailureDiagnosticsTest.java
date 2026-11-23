package cn.sunline.tss.tssveri.spark;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.sql.SQLException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SparkFailureDiagnosticsTest {
    @Test void includesOnlyValidatedSqlStatesAcrossCausesAndSuppressedFailures() {
        var failure=new SQLException("SELECT sensitive-password FROM jdbc:secret", "08006",new IOException("sensitive-data"));
        failure.addSuppressed(new SQLException("sensitive-rollback", "08003"));
        var nested=new IllegalStateException("sensitive-wrapper",failure);

        assertEquals("IllegalStateException -> SQLException [SQLSTATE=08006] -> IOException；附加异常 SQLException [SQLSTATE=08003]",
            SparkFailureDiagnostics.summary(nested));
    }

    @Test void omitsMalformedSqlStatesWithoutCopyingTheirContents() {
        for(String state:new String[]{null,"","0800","080060","08a06"," 08006","08006\n","密码123","08-06"}) {
            assertEquals("SQLException",SparkFailureDiagnostics.summary(new SQLException("sensitive-message",state)),String.valueOf(state));
        }
        assertEquals("SQLException [SQLSTATE=57P01]",SparkFailureDiagnostics.summary(new SQLException("secret","57P01")));
    }

    @Test void boundsSqlStateDiagnosticsAndStillLimitsCauseAndSuppressedTraversal() {
        Throwable failure=new SQLException("unvisited-secret","99999");
        for(int index=0;index<8;index++) failure=new SQLException("sensitive-message","08006",failure);
        for(int index=0;index<4;index++) failure.addSuppressed(new SQLException("sensitive-suppressed",index==3?"88888":"08003"));

        String diagnostic=SparkFailureDiagnostics.summary(failure);

        assertTrue(diagnostic.contains("SQLSTATE=08006"),diagnostic);
        assertFalse(diagnostic.contains("99999"),diagnostic);
        assertFalse(diagnostic.contains("88888"),diagnostic);
        assertFalse(diagnostic.contains("sensitive"),diagnostic);
        assertTrue(diagnostic.length()<=500,diagnostic);
    }

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
