package cn.sunline.tss.compare.configuration;

public record ConfigScriptImportResult(
    boolean saved,
    boolean cacheRefreshed,
    long configurationCount,
    long fieldCount,
    long tableSqlCount,
    String warning
) {}
