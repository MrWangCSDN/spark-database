package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConnectionTesterTest {
    private static final DatabaseConnectionSpec SPEC =
        new DatabaseConnectionSpec("jdbc:test", "reader", "password", "test.Driver");

    @Test
    void returnsOnlyProductVersionAndElapsedTime() {
        var tester = new DatabaseConnectionTester(
            spec -> new DatabaseConnectionProbe.Metadata("GaussDB", "V500"),
            Duration.ofSeconds(1),
            1
        );

        var result = tester.test(new DatabaseConnectionSpec(
            "jdbc:secret", "reader", "password", "driver"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.databaseProduct()).isEqualTo("GaussDB");
        assertThat(result.databaseVersion()).isEqualTo("V500");
        assertThat(result.elapsedMs()).isNotNegative();
        tester.close();
    }

    @Test
    void timesOutWithoutGrowingPastTheConcurrencyLimit() {
        CountDownLatch release = new CountDownLatch(1);
        var tester = new DatabaseConnectionTester(spec -> {
            while (release.getCount() > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            return new DatabaseConnectionProbe.Metadata("db", "version");
        }, Duration.ofMillis(30), 1);

        assertThatThrownBy(() -> tester.test(SPEC))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("DATABASE_CONNECTION_TIMEOUT"));
        assertThatThrownBy(() -> tester.test(SPEC))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("DATABASE_TEST_BUSY"));

        release.countDown();
        tester.close();
    }

    @Test
    void hidesProbeFailureDetails() {
        var tester = new DatabaseConnectionTester(spec -> {
            throw new SQLException("password=secret jdbc:private-host");
        }, Duration.ofSeconds(1), 1);

        assertThatThrownBy(() -> tester.test(SPEC))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code()).isEqualTo("DATABASE_CONNECTION_FAILED");
                assertThat(error.getMessage()).doesNotContain("secret", "private-host");
            });

        tester.close();
    }

    @Test
    void mapsMissingDriverWithoutExposingItsName() {
        var probe = new JdbcDatabaseConnectionProbe();

        assertThatThrownBy(() -> probe.connect(new DatabaseConnectionSpec(
            "jdbc:test", "reader", "password", "private.missing.Driver"
        ))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo("DATABASE_DRIVER_NOT_FOUND");
            assertThat(error.getMessage()).doesNotContain("private.missing.Driver");
        });
    }

    @Test
    void rejectsClassesThatAreNotJdbcDrivers() {
        var probe = new JdbcDatabaseConnectionProbe();

        assertThatThrownBy(() -> probe.connect(new DatabaseConnectionSpec(
            "jdbc:test", "reader", "password", String.class.getName()
        ))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo("DATABASE_DRIVER_NOT_FOUND");
            assertThat(error.getMessage()).doesNotContain(String.class.getName());
        });
    }
}
