package cn.sunline.tss.compare.task;

import cn.sunline.tss.compare.api.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
public class TaskManagementRepository {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TASK_NAME = "COALESCE(bi.CONFIGURATION_NAME,c.BCOMP_NAME,CAST(t.BCOMP_INDEX AS VARCHAR))";
    private static final String TASK_FROM = " FROM TSS_BCOMP_TASK_NEW t"
        + " LEFT JOIN TSS_BCOMP_CONF_NEW c ON c.BCOMP_INDEX=t.BCOMP_INDEX"
        + " LEFT JOIN TSS_BCOMP_BATCH_NEW b ON b.BATCH_ID=t.TASK_BATCH AND b.TASK_BUSI_DATE=t.TASK_BUSI_DATE"
        + " LEFT JOIN TSS_BCOMP_BATCH_ITEM_NEW bi ON bi.BATCH_ID=b.BATCH_ID AND bi.BCOMP_INDEX=t.BCOMP_INDEX ";
    private static final String VISIBLE_TASK = "(c.BCOMP_TYPE='2' OR c.BCOMP_INDEX IS NULL OR bi.BATCH_ID IS NOT NULL)";
    private static final String SELECT_TASK = "SELECT t.BCOMP_INDEX," + TASK_NAME
        + ",t.TASK_BUSI_DATE,t.VERIFY_BUSINESS_DATE,t.TASK_BATCH,t.BCOMP_TIME_NODE,t.CREATE_DATETIME,t.BEGIN_DATETIME,t.END_DATETIME,t.TASK_STATE,t.TASK_RESULT,t.FTASK_REASON,t.FILE1_ACOUNT,t.FILE2_ACOUNT,t.FILE1_SCOUNT,t.FILE2_SCOUNT,t.FILE1_FCOUNT,t.FILE2_FCOUNT,t.BCOMP_READ_MODE,t.RESULT_DATE" + TASK_FROM;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TaskManagementRepository(DataSource source,
                                    @Value("${compare.jdbc-query-timeout-seconds:30}") int queryTimeout) {
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(queryTimeout);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    ConfigurationStatus configurationStatus(long index) {
        List<ConfigurationStatus> rows = jdbc.query("""
            SELECT c.BCOMP_NAME,c.BCOMP_DATE_MODE,
              (SELECT COUNT(*) FROM TSS_BCOMP_TABLE_SQL_NEW s WHERE s.BCOMP_INDEX=c.BCOMP_INDEX),
              (SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW f WHERE f.BCOMP_INDEX=c.BCOMP_INDEX),
              (SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW f WHERE f.BCOMP_INDEX=c.BCOMP_INDEX AND f.FIELD_INDEX_FLAG='1'),
              (SELECT COUNT(*) FROM TSS_BCOMP_TABLE_SQL_NEW s JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d1 ON d1.DATABASE_ID=s.ORIG_DATABASE_ID JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d2 ON d2.DATABASE_ID=s.DEST_DATABASE_ID WHERE s.BCOMP_INDEX=c.BCOMP_INDEX)
            FROM TSS_BCOMP_CONF_NEW c WHERE c.BCOMP_INDEX=? AND c.BCOMP_TYPE='2'
            """, (rs, n) -> new ConfigurationStatus(rs.getString(1), rs.getString(2),
            rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)), index);
        return rows.isEmpty() ? null : rows.get(0);
    }

    ConfigurationConnections configurationConnections(long index) {
        List<ConfigurationConnections> rows = jdbc.query("""
            SELECT o.DATABASE_URL,o.DATABASE_DRIVER,d.DATABASE_URL,d.DATABASE_DRIVER
            FROM TSS_BCOMP_TABLE_SQL_NEW s
            JOIN TSS_BCOMP_DATABASE_CONFIG_NEW o ON o.DATABASE_ID=s.ORIG_DATABASE_ID
            JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d ON d.DATABASE_ID=s.DEST_DATABASE_ID
            WHERE s.BCOMP_INDEX=?
            """, (rs, n) -> new ConfigurationConnections(
                new DatabaseEndpoint(rs.getString(1), rs.getString(2)),
                new DatabaseEndpoint(rs.getString(3), rs.getString(4))
            ), index);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    TaskDatabaseContext databaseContext(long index) {
        List<TaskDatabaseContext> rows = jdbc.query("""
            SELECT s.ORIG_DATABASE_ID,o.DATABASE_NAME,s.DEST_DATABASE_ID,d.DATABASE_NAME
            FROM TSS_BCOMP_TABLE_SQL_NEW s
            LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW o ON o.DATABASE_ID=s.ORIG_DATABASE_ID
            LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d ON d.DATABASE_ID=s.DEST_DATABASE_ID
            WHERE s.BCOMP_INDEX=?
            """, (rs, n) -> new TaskDatabaseContext(nullableLong(rs, 1), rs.getString(2),
                nullableLong(rs, 3), rs.getString(4)), index);
        return rows.size() == 1 ? rows.get(0) : new TaskDatabaseContext(null, null, null, null);
    }

    boolean identityExists(long index, String date, String batch) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=? AND TASK_BUSI_DATE=? AND TASK_BATCH=?",
            Integer.class, index, date, batch);
        return count != null && count > 0;
    }

    TaskSummary insertWaiting(TaskCreateRequest request, String configurationName) {
        return transaction.execute(status -> {
            if (identityExists(request.bcompIndex(), request.taskBusinessDate(), request.taskBatch())) return null;
            String created = LocalDateTime.now().format(TIMESTAMP);
            jdbc.update("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,VERIFY_BUSINESS_DATE,TASK_BATCH,CREATE_DATETIME,BCOMP_TIME_NODE,TASK_STATE) VALUES(?,?,?,?,?,?, '1')",
                request.bcompIndex(), request.taskBusinessDate(), emptyToNull(request.verifyBusinessDate()),
                request.taskBatch(), created, request.timeNode());
            return new TaskSummary(request.bcompIndex(), configurationName, request.taskBusinessDate(),
                emptyToNull(request.verifyBusinessDate()), request.taskBatch(), request.timeNode(), created,
                null, null, "1", null, null, null, null, null, null, null, null, null, null);
        });
    }

    PageResult<TaskSummary> findTasks(TaskSearch search) {
        var where = new StringBuilder(" WHERE " + VISIBLE_TASK);
        var params = new ArrayList<Object>();
        add(where, params, "t.BCOMP_INDEX=?", search.bcompIndex());
        add(where, params, "t.TASK_STATE=?", blankToNull(search.taskState()));
        add(where, params, "t.TASK_RESULT=?", blankToNull(search.taskResult()));
        add(where, params, "t.TASK_BUSI_DATE>=?", blankToNull(search.dateFrom()));
        add(where, params, "t.TASK_BUSI_DATE<=?", blankToNull(search.dateTo()));
        if (blankToNull(search.keyword()) != null) {
            where.append(" AND (LOWER(" + TASK_NAME + ") LIKE ? OR LOWER(t.TASK_BATCH) LIKE ?)");
            String pattern = "%" + search.keyword().toLowerCase() + "%";
            params.add(pattern); params.add(pattern);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + TASK_FROM + where,
            Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(search.page() * search.size()); pageParams.add(search.size());
        List<TaskSummary> items = jdbc.query(SELECT_TASK + where + " ORDER BY " + search.orderBy() + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            TASK_MAPPER, pageParams.toArray());
        return new PageResult<>(items, search.page(), search.size(), total == null ? 0 : total);
    }

    TaskSummary findExact(long index, String date, String batch, String timeNode) {
        List<TaskSummary> rows = jdbc.query(SELECT_TASK + " WHERE t.BCOMP_INDEX=? AND t.TASK_BUSI_DATE=? AND t.TASK_BATCH=? AND t.BCOMP_TIME_NODE=?",
            TASK_MAPPER, index, date, batch, timeNode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    PageResult<DifferenceSummary> findDifferences(long index, String date, String batch,
                                                   String lineResult, String businessKey, int page, int size) {
        var where = new StringBuilder(" WHERE BCOMP_INDEX=? AND TASK_DATE=? AND TASK_BATCH=?");
        var params = new ArrayList<Object>(List.of(index, date, batch));
        add(where, params, "LINE_RESULT=?", blankToNull(lineResult));
        add(where, params, "COMP_INDEX_STR=?", blankToNull(businessKey));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_DTL_NEW" + where, Long.class, params.toArray());
        params.add(page * size); params.add(size);
        List<DifferenceSummary> items = jdbc.query("SELECT COMP_INDEX_STR,LINE_CONTENT1,LINE_CONTENT2,LINE_RESULT,FDTL_REASON FROM TSS_BCOMP_DTL_NEW" + where + " ORDER BY COMP_INDEX_STR OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            (rs, n) -> new DifferenceSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), params.toArray());
        return new PageResult<>(items, page, size, total == null ? 0 : total);
    }

    DashboardSummary dashboard(String serviceStatus) {
        String today = LocalDate.now().format(DATE);
        long running = count("TASK_STATE='2'", new Object[0]);
        long completed = count("TASK_BUSI_DATE=? AND TASK_STATE='3'", new Object[]{today});
        long differences = count("TASK_BUSI_DATE=? AND TASK_STATE='3' AND TASK_RESULT='1'", new Object[]{today});
        List<TaskSummary> recent = jdbc.query(SELECT_TASK + " WHERE " + VISIBLE_TASK + " ORDER BY t.CREATE_DATETIME DESC,t.BCOMP_INDEX ASC OFFSET 0 ROWS FETCH NEXT 8 ROWS ONLY", TASK_MAPPER);
        return new DashboardSummary(serviceStatus, running, completed, differences,
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), recent);
    }

    private long count(String condition, Object[] params) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW WHERE " + condition, Long.class, params);
        return value == null ? 0 : value;
    }

    private static void add(StringBuilder where, List<Object> params, String condition, Object value) {
        if (value != null) { where.append(" AND ").append(condition); params.add(value); }
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private static final RowMapper<TaskSummary> TASK_MAPPER = (rs, n) -> mapTask(rs);
    private static TaskSummary mapTask(ResultSet rs) throws SQLException {
        return new TaskSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getString(10), rs.getString(11), rs.getString(12), nullableLong(rs, 13), nullableLong(rs, 14),
            nullableLong(rs, 15), nullableLong(rs, 16), nullableLong(rs, 17), nullableLong(rs, 18), rs.getString(19), rs.getString(20));
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }

    record ConfigurationStatus(String name, String dateMode, int sqlCount, int fieldCount,
                               int businessKeyCount, int databaseReferenceCount) {
        boolean complete() { return sqlCount == 1 && fieldCount > 0 && businessKeyCount > 0 && databaseReferenceCount == 1; }
    }
    record DatabaseEndpoint(String url, String driver) {}
    record ConfigurationConnections(DatabaseEndpoint origin, DatabaseEndpoint destination) {}
}
