package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseConnectionTestService {
    private final DatabaseManagementRepository repository;
    private final DatabaseConnectionTester tester;

    public DatabaseConnectionTestService(
        DatabaseManagementRepository repository,
        DatabaseConnectionTester tester
    ) {
        this.repository = repository;
        this.tester = tester;
    }

    public DatabaseConnectionTestResult testDraft(DatabaseSaveRequest request) {
        validateDraft(request);
        JdbcCurrentSchemaPolicy.requireValid(request.url(), request.driver());
        return tester.test(new DatabaseConnectionSpec(
            request.url().trim(),
            request.user().trim(),
            request.newPassword(),
            request.driver().trim()
        ));
    }

    public DatabaseConnectionTestResult testSaved(long id, SavedDatabaseTestRequest request) {
        DatabaseManagementRepository.StoredDatabase stored = repository.find(id);
        if (stored == null) {
            throw ApiException.notFound("DATABASE_NOT_FOUND", "数据库连接不存在");
        }
        JdbcCurrentSchemaPolicy.requireValid(stored.url(), stored.driver());
        String temporaryPassword = request == null ? null : request.newPassword();
        String password = isBlank(temporaryPassword) ? plaintext(stored) : temporaryPassword;
        return tester.test(new DatabaseConnectionSpec(
            stored.url(),
            stored.user(),
            password,
            stored.driver()
        ));
    }

    private static void validateDraft(DatabaseSaveRequest request) {
        if (request == null
            || request.databaseId() <= 0
            || isBlank(request.databaseName())
            || isBlank(request.url())
            || isBlank(request.user())
            || isBlank(request.driver())
            || !("0".equals(request.encryption()) || "1".equals(request.encryption()))
            || isBlank(request.newPassword())) {
            throw ApiException.badRequest("INVALID_DATABASE", "数据库连接配置不完整");
        }
    }

    private static String plaintext(DatabaseManagementRepository.StoredDatabase stored) {
        if ("0".equals(stored.encryption())) {
            return stored.password();
        }
        if (!"1".equals(stored.encryption()) || isBlank(stored.password()) || isBlank(stored.key())) {
            throw new IllegalStateException("数据库密码解密失败");
        }
        try {
            return PasswordEncryption.decrypt(stored.password(), stored.key());
        } catch (Exception exception) {
            throw new IllegalStateException("数据库密码解密失败", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
