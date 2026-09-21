package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class JdbcCurrentSchemaPolicy {
    private JdbcCurrentSchemaPolicy() {}

    public static void requireValid(String url, String driver) {
        if (isCompatible(url, driver) && !hasCurrentSchema(url)) {
            throw ApiException.badRequest(
                "CURRENT_SCHEMA_REQUIRED",
                "GaussDB/PostgreSQL 兼容连接的 JDBC URL 必须包含非空 currentSchema 参数"
            );
        }
    }

    private static boolean isCompatible(String url, String driver) {
        String normalizedUrl = lower(url);
        String normalizedDriver = lower(driver);
        return normalizedUrl.startsWith("jdbc:postgresql:")
            || normalizedUrl.startsWith("jdbc:gaussdb:")
            || normalizedUrl.startsWith("jdbc:opengauss:")
            || normalizedDriver.contains("postgresql")
            || normalizedDriver.contains("gaussdb")
            || normalizedDriver.contains("opengauss");
    }

    private static boolean hasCurrentSchema(String url) {
        if (url == null) return false;
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) return false;
        for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
            int separator = parameter.indexOf('=');
            String encodedKey = separator < 0 ? parameter : parameter.substring(0, separator);
            if (!"currentSchema".equalsIgnoreCase(decode(encodedKey))) continue;
            String encodedValue = separator < 0 ? "" : parameter.substring(separator + 1);
            if (!decode(encodedValue).isBlank()) return true;
        }
        return false;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
