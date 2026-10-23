package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCurrentSchemaPolicyTest {
    @Test void rejectsCompatibleUrlsWithoutANonBlankCurrentSchema() {
        assertRequired("jdbc:postgresql://db/core", "org.postgresql.Driver");
        assertRequired("jdbc:gaussdb://db/core?currentSchema=", "com.huawei.Driver");
        assertRequired("jdbc:opengauss://db/core?currentSchema=%20%20", "custom.Driver");
        assertRequired("jdbc:custom://db/core?ssl=true", "org.opengauss.Driver");
    }

    @Test void acceptsCaseInsensitiveSchemaAmongOtherParameters() {
        assertThatCode(() -> JdbcCurrentSchemaPolicy.requireValid(
            "jdbc:postgresql://db/core?ssl=true&CURRENTSCHEMA=business&applicationName=compare",
            "org.postgresql.Driver"
        )).doesNotThrowAnyException();
    }

    @Test void doesNotApplyPostgresSchemaRuleToOracleOrMysql() {
        assertThatCode(() -> JdbcCurrentSchemaPolicy.requireValid(
            "jdbc:oracle:thin:@db:1521/core", "oracle.jdbc.OracleDriver"
        )).doesNotThrowAnyException();
        assertThatCode(() -> JdbcCurrentSchemaPolicy.requireValid(
            "jdbc:mysql://db/core", "com.mysql.cj.jdbc.Driver"
        )).doesNotThrowAnyException();
    }

    @Test void doesNotApplyPostgresSchemaRuleToGdbMysqlCompatibleConnections() {
        assertThatCode(() -> JdbcCurrentSchemaPolicy.requireValid(
            "jdbc:gdb://db/core", "com.mysql.jdbc.Driver"
        )).doesNotThrowAnyException();
        assertThatCode(() -> JdbcCurrentSchemaPolicy.requireValid(
            "jdbc:mysql://db/core", "vendor.gdb.Driver"
        )).doesNotThrowAnyException();
    }

    private static void assertRequired(String url, String driver) {
        assertThatThrownBy(() -> JdbcCurrentSchemaPolicy.requireValid(url, driver))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("CURRENT_SCHEMA_REQUIRED");
                org.assertj.core.api.Assertions.assertThat(error.status().value()).isEqualTo(400);
            });
    }
}
