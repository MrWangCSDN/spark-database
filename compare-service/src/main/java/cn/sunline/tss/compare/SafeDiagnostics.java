package cn.sunline.tss.compare;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces bounded diagnostics without exposing connection credentials or Spark arguments. */
final class SafeDiagnostics {
    private static final int SUMMARY_LIMIT = 500;
    private static final int LOG_LIMIT = 4000;
    private static final int CAUSE_LIMIT = 8;
    private static final int FRAMES_PER_CAUSE = 12;
    private static final Pattern JDBC = Pattern.compile("(?i)jdbc:[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URI_CREDENTIAL = Pattern.compile("(?i)(://[^\\s/:@]+:)[^\\s@]+@");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)([a-z0-9_.-]*(?:password|passwd|pwd|passphrase|secret|token)[a-z0-9_.-]*)\\s*([=:])\\s*(?:'[^']*'|\"[^\"]*\"|[^\\s,;]+)");

    private SafeDiagnostics() { }

    static String summary(Throwable error) {
        if (error == null) return "UnknownFailure";
        StringBuilder value = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        int causes = 0;
        while (current != null && causes++ < CAUSE_LIMIT && seen.add(current)) {
            if (!value.isEmpty()) value.append(" -> ");
            value.append(current.getClass().getSimpleName());
            String message = sanitize(current.getMessage());
            if (!message.isBlank()) value.append(": ").append(message);
            current = current.getCause();
        }
        return bound(value.toString(), SUMMARY_LIMIT);
    }

    static String logDetail(Throwable error) {
        if (error == null) return "UnknownFailure";
        StringBuilder value = new StringBuilder(summary(error));
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        int causes = 0;
        while (current != null && causes++ < CAUSE_LIMIT && seen.add(current)) {
            value.append("\n").append(current.getClass().getSimpleName()).append(" stack:");
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, FRAMES_PER_CAUSE); i++) {
                value.append("\n  at ").append(frames[i]);
            }
            current = current.getCause();
        }
        return bound(value.toString(), LOG_LIMIT);
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = JDBC.matcher(raw).replaceAll("jdbc:[REDACTED]");
        value = URI_CREDENTIAL.matcher(value).replaceAll("$1[REDACTED]@");
        Matcher matcher = SECRET_ASSIGNMENT.matcher(value);
        StringBuffer safe = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(safe, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + "[REDACTED]"));
        }
        matcher.appendTail(safe);
        return safe.toString().replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String bound(String value, int limit) {
        if (value.length() <= limit) return value;
        return value.substring(0, limit - 1) + "…";
    }
}
