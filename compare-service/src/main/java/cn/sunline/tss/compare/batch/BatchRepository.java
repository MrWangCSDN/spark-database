package cn.sunline.tss.compare.batch;

import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.task.TaskSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Repository
public class BatchRepository {
    private static final String ITEM_JOIN = """
         FROM TSS_BCOMP_BATCH_NEW b
         JOIN TSS_BCOMP_BATCH_ITEM_NEW i ON i.BATCH_ID=b.BATCH_ID
         LEFT JOIN TSS_BCOMP_TASK_NEW t ON t.TASK_BATCH=b.BATCH_ID
           AND t.TASK_BUSI_DATE=b.TASK_BUSI_DATE AND t.BCOMP_INDEX=i.BCOMP_INDEX
        """;
    private static final String KNOWN_STATE = "(t.TASK_STATE IN ('1','2','4') OR (t.TASK_STATE='3' AND t.TASK_RESULT IN ('1','2')))";
    private static final String SUMMARY_SELECT = """
        SELECT b.BATCH_ID,b.TASK_BUSI_DATE,b.VERIFY_BUSINESS_DATE,b.CREATED_AT,b.SOURCE_BATCH_ID,b.TOTAL_COUNT,
          SUM(CASE WHEN t.TASK_STATE='1' THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.TASK_STATE='2' THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.TASK_STATE='3' AND t.TASK_RESULT='2' THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.TASK_STATE='3' AND t.TASK_RESULT='1' THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.TASK_STATE='4' THEN 1 ELSE 0 END),
          SUM(CASE WHEN
        """ + KNOWN_STATE + " THEN 0 ELSE 1 END) " + ITEM_JOIN;
    private static final String SUMMARY_GROUP = " GROUP BY b.BATCH_ID,b.TASK_BUSI_DATE,b.VERIFY_BUSINESS_DATE,b.CREATED_AT,b.SOURCE_BATCH_ID,b.TOTAL_COUNT ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public BatchRepository(DataSource source, @Value("${compare.jdbc-query-timeout-seconds:30}") int timeout) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(timeout);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    <T> T inTransaction(Supplier<T> operation) { return transaction.execute(status -> operation.get()); }

    List<Long> allConfigurationIds() {
        return jdbc.query("SELECT BCOMP_INDEX FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_TYPE='2' ORDER BY BCOMP_INDEX FETCH FIRST 5001 ROWS ONLY",
            (rs, n) -> rs.getLong(1));
    }

    Map<Long, Configuration> configurations(List<Long> ids) {
        String placeholders = placeholders(ids.size());
        List<Map<String, String>> basics = jdbc.query("""
            SELECT c.*,s.ORIG_DATABASE_ID,s.DEST_DATABASE_ID,s.ORIG_SQL,s.DEST_SQL,
              o.DATABASE_ID AS O_ID,o.DATABASE_NAME AS O_NAME,o.DATABASE_URL AS O_URL,
              o.DATABASE_USER AS O_USER,o.DATABASE_PASSWORD AS O_PASSWORD,o.DATABASE_DRIVER AS O_DRIVER,
              o.DATABASE_ENCRYPTION AS O_ENCRYPTION,o.DATABASE_GENERATERKEY AS O_KEY,
              d.DATABASE_ID AS D_ID,d.DATABASE_NAME AS D_NAME,d.DATABASE_URL AS D_URL,
              d.DATABASE_USER AS D_USER,d.DATABASE_PASSWORD AS D_PASSWORD,d.DATABASE_DRIVER AS D_DRIVER,
              d.DATABASE_ENCRYPTION AS D_ENCRYPTION,d.DATABASE_GENERATERKEY AS D_KEY
            FROM TSS_BCOMP_CONF_NEW c
            LEFT JOIN TSS_BCOMP_TABLE_SQL_NEW s ON s.BCOMP_INDEX=c.BCOMP_INDEX
            LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW o ON o.DATABASE_ID=s.ORIG_DATABASE_ID
            LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d ON d.DATABASE_ID=s.DEST_DATABASE_ID
            WHERE c.BCOMP_TYPE='2' AND c.BCOMP_INDEX IN (
            """ + placeholders + ") ORDER BY c.BCOMP_INDEX", (rs, n) -> strings(rs), ids.toArray());
        var fields = new HashMap<Long, List<Map<String, String>>>();
        jdbc.query("SELECT * FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX IN (" + placeholders + ") ORDER BY BCOMP_INDEX,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_NAME",
            rs -> {
                fields.computeIfAbsent(rs.getLong("BCOMP_INDEX"), key -> new ArrayList<>()).add(strings(rs));
            }, ids.toArray());
        var result = new HashMap<Long, Configuration>();
        for (var row : basics) {
            long id = Long.parseLong(row.get("BCOMP_INDEX"));
            result.put(id, new Configuration(row, fields.getOrDefault(id, List.of())));
        }
        return result;
    }

    Set<Long> activeConfigurationIds(List<Long> ids, String date) {
        var args = new ArrayList<Object>(ids);
        args.add(date);
        return new HashSet<>(jdbc.query("SELECT DISTINCT BCOMP_INDEX FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX IN (" + placeholders(ids.size())
            + ") AND TASK_BUSI_DATE=? AND TASK_STATE IN ('1','2')", (rs, n) -> rs.getLong(1), args.toArray()));
    }

    List<SourceItem> failedItems(String batchId) {
        return jdbc.query("SELECT i.BCOMP_INDEX,i.CONFIGURATION_NAME,i.CONFIGURATION_MODULE,i.TIME_NODE,i.VERIFY_BUSINESS_DATE,i.CONFIGURATION_HASH"
                + ITEM_JOIN + " WHERE b.BATCH_ID=? AND t.TASK_STATE='4' ORDER BY i.BCOMP_INDEX FETCH FIRST 5001 ROWS ONLY",
            (rs, n) -> new SourceItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), batchId);
    }

    ExistingRequest findRequest(String requestId) {
        var rows = jdbc.query("SELECT BATCH_ID,REQUEST_HASH FROM TSS_BCOMP_BATCH_NEW WHERE REQUEST_ID=?",
            (rs, n) -> new ExistingRequest(rs.getString(1), rs.getString(2)), requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    void insert(String batchId, String requestId, String requestHash, String date, String verifyDate,
                String sourceBatchId, String createdAt, List<PreparedItem> items) {
        jdbc.update("INSERT INTO TSS_BCOMP_BATCH_NEW(BATCH_ID,REQUEST_ID,REQUEST_HASH,TASK_BUSI_DATE,VERIFY_BUSINESS_DATE,SOURCE_BATCH_ID,CREATED_AT,TOTAL_COUNT) VALUES(?,?,?,?,?,?,?,?)",
            batchId, requestId, requestHash, date, verifyDate, sourceBatchId, createdAt, items.size());
        var itemRows = new ArrayList<Object[]>();
        var taskRows = new ArrayList<Object[]>();
        for (var prepared : items) {
            var item = prepared.preview();
            itemRows.add(new Object[]{batchId, item.bcompIndex(), item.configurationName(), item.module(), item.timeNode(), item.verifyBusinessDate(), prepared.configurationHash()});
            taskRows.add(new Object[]{item.bcompIndex(), date, batchId, item.timeNode(), item.verifyBusinessDate(), createdAt});
        }
        jdbc.batchUpdate("INSERT INTO TSS_BCOMP_BATCH_ITEM_NEW(BATCH_ID,BCOMP_INDEX,CONFIGURATION_NAME,CONFIGURATION_MODULE,TIME_NODE,VERIFY_BUSINESS_DATE,CONFIGURATION_HASH) VALUES(?,?,?,?,?,?,?)", itemRows);
        jdbc.batchUpdate("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,VERIFY_BUSINESS_DATE,CREATE_DATETIME,TASK_STATE) VALUES(?,?,?,?,?,?,'1')", taskRows);
    }

    BatchSummary find(String batchId) {
        var rows = jdbc.query(SUMMARY_SELECT + " WHERE b.BATCH_ID=? " + SUMMARY_GROUP, (rs, n) -> summary(rs), batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    PageResult<BatchSummary> list(int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_BATCH_NEW", Long.class);
        var items = jdbc.query(SUMMARY_SELECT + SUMMARY_GROUP + " ORDER BY b.CREATED_AT DESC,b.BATCH_ID DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            (rs, n) -> summary(rs), (long) page * size, size);
        return new PageResult<>(items, page, size, total == null ? 0 : total);
    }

    PageResult<TaskSummary> tasks(String batchId, String status, int page, int size) {
        String filter = switch (status) {
            case "WAITING" -> " AND t.TASK_STATE='1'";
            case "RUNNING" -> " AND t.TASK_STATE='2'";
            case "CONSISTENT" -> " AND t.TASK_STATE='3' AND t.TASK_RESULT='2'";
            case "DIFFERENCE" -> " AND t.TASK_STATE='3' AND t.TASK_RESULT='1'";
            case "FAILED" -> " AND t.TASK_STATE='4'";
            case "OTHER" -> " AND CASE WHEN " + KNOWN_STATE + " THEN 0 ELSE 1 END=1";
            default -> "";
        };
        String where = " WHERE b.BATCH_ID=?" + filter;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + ITEM_JOIN + where, Long.class, batchId);
        var items = jdbc.query("""
            SELECT i.BCOMP_INDEX,i.CONFIGURATION_NAME,b.TASK_BUSI_DATE,i.VERIFY_BUSINESS_DATE,b.BATCH_ID,i.TIME_NODE,
              COALESCE(t.CREATE_DATETIME,b.CREATED_AT),t.BEGIN_DATETIME,t.END_DATETIME,t.TASK_STATE,t.TASK_RESULT,
              t.FTASK_REASON,t.FILE1_ACOUNT,t.FILE2_ACOUNT,t.FILE1_SCOUNT,t.FILE2_SCOUNT,t.FILE1_FCOUNT,t.FILE2_FCOUNT,t.BCOMP_READ_MODE,t.RESULT_DATE
            """ + ITEM_JOIN + where + " ORDER BY i.BCOMP_INDEX OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            (rs, n) -> new TaskSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                nullableLong(rs, 13), nullableLong(rs, 14), nullableLong(rs, 15), nullableLong(rs, 16), nullableLong(rs, 17), nullableLong(rs, 18), rs.getString(19), rs.getString(20)),
            batchId, (long) page * size, size);
        return new PageResult<>(items, page, size, total == null ? 0 : total);
    }

    private static Map<String, String> strings(ResultSet rs) throws SQLException {
        var values = new TreeMap<String, String>();
        var metadata = rs.getMetaData();
        for (int i=1;i<=metadata.getColumnCount();i++) values.put(metadata.getColumnLabel(i).toUpperCase(Locale.ROOT), rs.getString(i));
        return values;
    }
    private static String placeholders(int count) { return Collections.nCopies(count, "?").stream().collect(Collectors.joining(",")); }
    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
    private static BatchSummary summary(ResultSet rs) throws SQLException {
        return new BatchSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(9)+rs.getInt(10)+rs.getInt(11));
    }

    record Configuration(Map<String, String> values, List<Map<String, String>> fields) {
        String value(String key) { return values.get(key); }
    }
    record SourceItem(long index, String name, String module, String timeNode, String verifyDate, String configurationHash) {}
    record ExistingRequest(String batchId, String requestHash) {}
    record PreparedItem(BatchPreviewItem preview, String configurationHash) {}
}
