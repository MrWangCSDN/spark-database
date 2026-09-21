package cn.sunline.tss.compare.database;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GaussDbConnectionIntegrationTest {
    @Test
    void connectsToConfiguredGaussDb() throws Exception {
        String url = System.getenv("GAUSSDB_TEST_URL");
        String user = System.getenv("GAUSSDB_TEST_USER");
        String password = System.getenv("GAUSSDB_TEST_PASSWORD");
        Assumptions.assumeTrue(
            nonBlank(url) && nonBlank(user) && nonBlank(password),
            "设置 GAUSSDB_TEST_URL/USER/PASSWORD 后执行真实联调"
        );
        String driver = System.getenv().getOrDefault("GAUSSDB_TEST_DRIVER", "org.postgresql.Driver");

        var metadata = new JdbcDatabaseConnectionProbe().connect(
            new DatabaseConnectionSpec(url, user, password, driver)
        );

        assertThat(metadata.product()).isNotBlank();
        assertThat(metadata.version()).isNotBlank();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
