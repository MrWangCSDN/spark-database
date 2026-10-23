package cn.sunline.tss.compare.metadata;

import java.util.Locale;

public enum MetadataDbType {
    GAUSSDB,
    GDB;

    public static MetadataDbType parse(String raw) {
        if (raw == null || raw.isBlank()) return GAUSSDB;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("bd.metadata-db-type 只支持 GAUSSDB 或 GDB: " + raw, error);
        }
    }
}
