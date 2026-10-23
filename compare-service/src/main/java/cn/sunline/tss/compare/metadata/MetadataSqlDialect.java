package cn.sunline.tss.compare.metadata;

import java.util.List;
import java.util.Objects;

public final class MetadataSqlDialect {
    private final MetadataDbType type;

    private MetadataSqlDialect(MetadataDbType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public static MetadataSqlDialect forType(MetadataDbType type) {
        return new MetadataSqlDialect(type);
    }

    public MetadataDbType type() {
        return type;
    }

    public Page page(String baseSql, long offset, int limit) {
        requireSql(baseSql);
        if (offset < 0) throw new IllegalArgumentException("分页偏移量不能为负数");
        if (limit < 1) throw new IllegalArgumentException("分页大小必须大于0");
        return switch (type) {
            case GAUSSDB -> new Page(baseSql + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", List.of(offset, limit));
            case GDB -> new Page(baseSql + " LIMIT ? OFFSET ?", List.of(limit, offset));
        };
    }

    public Page limit(String baseSql, int limit) {
        requireSql(baseSql);
        if (limit < 1) throw new IllegalArgumentException("限制条数必须大于0");
        return switch (type) {
            case GAUSSDB -> new Page(baseSql + " FETCH FIRST ? ROWS ONLY", List.of(limit));
            case GDB -> new Page(baseSql + " LIMIT ?", List.of(limit));
        };
    }

    private static void requireSql(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("基础 SQL 不能为空");
    }

    public record Page(String sql, List<Object> parameters) {
        public Page {
            if (sql == null || sql.isBlank()) throw new IllegalArgumentException("分页 SQL 不能为空");
            parameters = List.copyOf(parameters);
        }
    }
}
