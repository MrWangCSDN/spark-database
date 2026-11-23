package cn.sunline.tss.tssveri.spark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Chooses one shared primary-key HASH bucket count from this run's two source queries. */
final class AutoHashPartitionPlanner {
    static final int DEFAULT_MAX_PARTITION_NUM = 64;

    private AutoHashPartitionPlanner() { }

    static int plan(String[] args) throws Exception {
        return plan(args, DEFAULT_MAX_PARTITION_NUM);
    }

    static int plan(String[] args, int maxPartitionNum) throws Exception {
        PrimaryKeyHashPlanner.partitionCount(maxPartitionNum);
        long oldRows = count("数据库A", args[SparkHandlerTable.OLD_URL_INDEX],
                args[SparkHandlerTable.OLD_USER_INDEX], args[SparkHandlerTable.OLD_PASSWORD_INDEX],
                args[SparkHandlerTable.OLD_DRIVER_INDEX], args[SparkHandlerTable.OLD_TABLE_INDEX]);
        long newRows = count("数据库B", args[SparkHandlerTable.NEW_URL_INDEX],
                args[SparkHandlerTable.NEW_USER_INDEX], args[SparkHandlerTable.NEW_PASSWORD_INDEX],
                args[SparkHandlerTable.NEW_DRIVER_INDEX], args[SparkHandlerTable.NEW_TABLE_INDEX]);
        return partitions(Math.max(oldRows, newRows), maxPartitionNum);
    }

    static int partitions(long rows) {
        return partitions(rows, DEFAULT_MAX_PARTITION_NUM);
    }

    static int partitions(long rows, int maxPartitionNum) {
        PrimaryKeyHashPlanner.partitionCount(maxPartitionNum);
        if (rows < 0) throw new IllegalArgumentException("统计行数不能为负数");
        long required = Math.max(1, rows / 1_000_000 + (rows % 1_000_000 == 0 ? 0 : 1));
        int count = 1;
        while (count < required && count < maxPartitionNum) count *= 2;
        return Math.min(count, maxPartitionNum);
    }

    private static long count(String side, String url, String user, String password,
                              String driver, String source) throws Exception {
        Class.forName(driver);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + source)) {
            if (!result.next()) throw new SQLException(side + "统计未返回结果");
            long rows = result.getLong(1);
            if (result.wasNull() || rows < 0) throw new SQLException(side + "统计行数无效");
            return rows;
        } catch (SQLException error) {
            throw new SQLException(side + "运行前统计失败", error);
        }
    }
}
