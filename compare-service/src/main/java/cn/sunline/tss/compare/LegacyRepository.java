package cn.sunline.tss.compare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 仅访问既有表，不创建或修改配置表。 */
@Repository
public class LegacyRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final String IDENTITY = "BCOMP_INDEX=? AND TASK_BUSI_DATE=? AND TASK_BATCH=?";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public LegacyRepository(DataSource source, @Value("${compare.jdbc-query-timeout-seconds:30}") int queryTimeout) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(queryTimeout);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    public synchronized Task claim() {
        return transaction.execute(status -> {
            // 原Spark最终更新不区分时点，故不能领取三元身份有多行的任务。
            String sql = "SELECT t.BCOMP_INDEX,t.TASK_BUSI_DATE,t.TASK_BATCH,t.BCOMP_TIME_NODE,t.VERIFY_BUSINESS_DATE "
                + "FROM TSS_BCOMP_TASK_NEW t WHERE t.TASK_STATE='1' "
                + "AND NOT EXISTS(SELECT 1 FROM TSS_BCOMP_TASK_NEW r WHERE r.BCOMP_INDEX=t.BCOMP_INDEX AND r.TASK_STATE='2' AND COALESCE(NULLIF(r.VERIFY_BUSINESS_DATE,''),r.TASK_BUSI_DATE)=COALESCE(NULLIF(t.VERIFY_BUSINESS_DATE,''),t.TASK_BUSI_DATE)) "
                + "AND EXISTS (SELECT 1 FROM TSS_BCOMP_CONF_NEW c WHERE c.BCOMP_INDEX=t.BCOMP_INDEX AND c.BCOMP_TYPE='2') "
                + "AND (SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW d WHERE d.BCOMP_INDEX=t.BCOMP_INDEX "
                + "AND d.TASK_BUSI_DATE=t.TASK_BUSI_DATE AND d.TASK_BATCH=t.TASK_BATCH)=1 ORDER BY t.BCOMP_INDEX";
            List<Task> tasks = jdbc.query(connection -> {
                var ps = connection.prepareStatement(sql); ps.setMaxRows(1); ps.setFetchSize(1); return ps;
            }, (rs, n) -> task(rs));
            if (tasks.isEmpty()) return null;
            Task task = tasks.get(0);
            int changed = jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='2',RESULT_DATE=COALESCE(NULLIF(VERIFY_BUSINESS_DATE,''),TASK_BUSI_DATE),BEGIN_DATETIME=? WHERE " + IDENTITY + " AND TASK_STATE='1'",
                now(), task.index(), task.date(), task.batch());
            if (changed > 1) throw new IllegalStateException("任务身份不唯一，领取已回滚");
            return changed == 1 ? task : null;
        });
    }
    public void fail(Task task, String reason) {
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4',TASK_RESULT='1',FTASK_REASON=?,END_DATETIME=? WHERE " + IDENTITY + " AND TASK_STATE='2'",
            reason, now(), task.index(), task.date(), task.batch());
    }
    public boolean hasCompletedResult(Task task) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT TASK_STATE,FILE1_ACOUNT,FILE2_ACOUNT FROM TSS_BCOMP_TASK_NEW WHERE " + IDENTITY,
            (rs,n) -> "3".equals(rs.getString(1)) && rs.getObject(2) != null && rs.getObject(3) != null,
            task.index(), task.date(), task.batch()));
    }
    public void failIncompleteResult(Task task) {
        jdbc.update("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4',TASK_RESULT='1',FTASK_REASON=?,END_DATETIME=? WHERE " + IDENTITY
                + " AND (TASK_STATE='2' OR (TASK_STATE='3' AND (FILE1_ACOUNT IS NULL OR FILE2_ACOUNT IS NULL)))",
            "Spark结束但未写入完整结果，请检查Spark日志", now(), task.index(), task.date(), task.batch());
    }
    public SqlConfig sql(Task task) {
        Integer fields = jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=?", Integer.class, task.index());
        if (fields == null || fields == 0) throw new IllegalStateException("核对字段配置为空");
        return jdbc.queryForObject("SELECT ORIG_DATABASE_ID,DEST_DATABASE_ID,ORIG_SQL,DEST_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=?",
            (rs,n) -> new SqlConfig(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4)), task.index());
    }
    public ConfigStore.Snapshot loadConfiguration() {
        return transaction.execute(status -> {
            Map<Long,String> names = new HashMap<>();
            jdbc.query("SELECT BCOMP_INDEX,BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_TYPE='2'", (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.put(rs.getLong(1), rs.getString(2)));
            Map<Long,Database> databases = new HashMap<>();
            jdbc.query("SELECT DATABASE_ID,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION,DATABASE_GENERATERKEY FROM TSS_BCOMP_DATABASE_CONFIG_NEW",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> databases.put(rs.getLong(1),new Database(rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7))));
            return new ConfigStore.Snapshot(Map.copyOf(names),Map.copyOf(databases));
        });
    }
    private static Task task(ResultSet rs) throws SQLException {
        return new Task(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5));
    }
    private static String now() { return LocalDateTime.now().format(TIME); }
    public record SqlConfig(long oldId, long newId, String oldSql, String newSql) {}
}
