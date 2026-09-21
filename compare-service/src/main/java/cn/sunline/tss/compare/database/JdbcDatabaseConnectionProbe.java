package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

@Component
final class JdbcDatabaseConnectionProbe implements DatabaseConnectionProbe {
    private static final String DRIVER_ERROR = "数据库驱动不可用，请检查驱动类和部署包";

    @Override
    public Metadata connect(DatabaseConnectionSpec spec) throws Exception {
        Driver driver = loadDriver(spec.driver());
        Properties properties = new Properties();
        properties.setProperty("user", spec.user());
        properties.setProperty("password", spec.password());

        try (Connection connection = driver.connect(spec.url(), properties)) {
            if (connection == null || !connection.isValid(5)) {
                throw new SQLException("JDBC connection validation failed");
            }
            DatabaseMetaData metadata = connection.getMetaData();
            return new Metadata(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());
        }
    }

    private Driver loadDriver(String driverClassName) {
        try {
            Class<?> type = Class.forName(
                driverClassName,
                true,
                Thread.currentThread().getContextClassLoader()
            );
            if (!Driver.class.isAssignableFrom(type)) {
                throw driverUnavailable();
            }
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ApiException exception) {
            throw exception;
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            throw driverUnavailable();
        }
    }

    private static ApiException driverUnavailable() {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "DATABASE_DRIVER_NOT_FOUND",
            DRIVER_ERROR
        );
    }
}
