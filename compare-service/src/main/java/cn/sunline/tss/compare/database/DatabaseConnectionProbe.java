package cn.sunline.tss.compare.database;

@FunctionalInterface
interface DatabaseConnectionProbe {
    Metadata connect(DatabaseConnectionSpec spec) throws Exception;

    record Metadata(String product, String version) {}
}
