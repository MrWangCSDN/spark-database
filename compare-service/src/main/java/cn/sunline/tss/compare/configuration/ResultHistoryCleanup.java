package cn.sunline.tss.compare.configuration;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ResultHistoryCleanup {
    static final int RETENTION_DAYS=7;
    private static final int MAX_INVALID_TIMESTAMPS=4096;
    private static final Pattern TIMESTAMP=Pattern.compile("[0-9]{14}");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuuMMddHHmmss",Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
    private static final String LEGACY_IDENTITY="t.BCOMP_INDEX=TSS_BCOMP_DTL_NEW.BCOMP_INDEX"
        +" AND t.TASK_BUSI_DATE=TSS_BCOMP_DTL_NEW.TASK_DATE"
        +" AND t.TASK_BATCH=TSS_BCOMP_DTL_NEW.TASK_BATCH";

    private ResultHistoryCleanup() {}

    static ResultCleanupSummary clean(JdbcTemplate jdbc,String cutoff) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || jdbc.getDataSource()==null
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("历史结果清理必须在调用方数据源事务内执行");
        }
        if(!validTimestamp(cutoff))throw new IllegalArgumentException("历史结果清理截止时间必须是有效的14位日期时间");

        // Consume every header before touching children, sharing the publisher/export lock order.
        stream(jdbc,"SELECT BCOMP_INDEX,RESULT_DATE FROM TSS_BCOMP_RESULT_NEW WHERE PUBLISHED_AT<?"
            +" ORDER BY BCOMP_INDEX,RESULT_DATE FOR UPDATE",cutoff,rs->{});
        long modernDetails=deleteChildren(jdbc,"TSS_BCOMP_DTL_NEW",cutoff);
        long patterns=deleteChildren(jdbc,"TSS_BCOMP_FIELD_DIFF_NEW",cutoff);
        long fields=deleteChildren(jdbc,"TSS_BCOMP_FIELD_COUNT_NEW",cutoff);
        long results=delete(jdbc,"DELETE FROM TSS_BCOMP_RESULT_NEW WHERE PUBLISHED_AT<?",cutoff);

        Set<String> invalid=invalidLegacyTimes(jdbc,cutoff);
        var parameters=new ArrayList<Object>();parameters.add(cutoff);
        String reliableAge="t.END_DATETIME<?";
        if(!invalid.isEmpty()) {
            reliableAge+=" AND t.END_DATETIME NOT IN ("+String.join(",",Collections.nCopies(invalid.size(),"?"))+")";
            parameters.addAll(invalid);
        }
        // Any uncertain duplicate task protects this entire three-part legacy identity.
        String legacySql="DELETE FROM TSS_BCOMP_DTL_NEW WHERE RESULT_DATE IS NULL"
            +" AND EXISTS (SELECT 1 FROM TSS_BCOMP_TASK_NEW t WHERE "+LEGACY_IDENTITY+")"
            +" AND NOT EXISTS (SELECT 1 FROM TSS_BCOMP_TASK_NEW t WHERE "+LEGACY_IDENTITY
            +" AND (t.TASK_STATE IS NULL OR t.TASK_STATE NOT IN ('3','4','5')"
            +" OR t.END_DATETIME IS NULL OR CHAR_LENGTH(t.END_DATETIME)<>14"
            +" OR NOT ("+reliableAge+")))";
        long legacyDetails=delete(jdbc,legacySql,parameters.toArray());
        return new ResultCleanupSummary(RETENTION_DAYS,cutoff,results,Math.addExact(modernDetails,legacyDetails),
            patterns,fields,legacyDetails);
    }

    private static long deleteChildren(JdbcTemplate jdbc,String table,String cutoff) {
        return delete(jdbc,"DELETE FROM "+table+" WHERE EXISTS (SELECT 1 FROM TSS_BCOMP_RESULT_NEW r"
            +" WHERE r.BCOMP_INDEX="+table+".BCOMP_INDEX AND r.RESULT_DATE="+table+".RESULT_DATE"
            +" AND r.PUBLISHED_AT<?)",cutoff);
    }

    private static Set<String> invalidLegacyTimes(JdbcTemplate jdbc,String cutoff) {
        var invalid=new LinkedHashSet<String>();
        String sql="SELECT DISTINCT t.END_DATETIME FROM TSS_BCOMP_TASK_NEW t"
            +" WHERE t.TASK_STATE IN ('3','4','5') AND t.END_DATETIME IS NOT NULL"
            +" AND CHAR_LENGTH(t.END_DATETIME)=14 AND t.END_DATETIME<?"
            +" AND EXISTS (SELECT 1 FROM TSS_BCOMP_DTL_NEW WHERE RESULT_DATE IS NULL AND "+LEGACY_IDENTITY+")";
        stream(jdbc,sql,cutoff,rs->{
            String value=rs.getString(1);
            if(value==null || value.length()!=14)
                throw new DataIntegrityViolationException("无法可靠读取历史任务结束时间");
            if(!validTimestamp(value) && !invalid.contains(value)) {
                if(invalid.size()==MAX_INVALID_TIMESTAMPS)
                    throw new DataIntegrityViolationException("无法可靠判定的历史任务结束时间超过安全上限");
                invalid.add(value);
            }
        });
        return invalid;
    }

    private static boolean validTimestamp(String value) {
        if(value==null || !TIMESTAMP.matcher(value).matches())return false;
        try {return LocalDateTime.parse(value,TIME).getYear()>0;}
        catch(DateTimeParseException exception){return false;}
    }

    private static void stream(JdbcTemplate jdbc,String sql,String cutoff,RowCallbackHandler handler) {
        jdbc.query(connection->{
            var statement=connection.prepareStatement(sql);
            statement.setFetchSize(256);
            statement.setString(1,cutoff);
            return statement;
        },handler);
    }

    private static long delete(JdbcTemplate jdbc,String sql,Object... parameters) {
        int affected=jdbc.update(sql,parameters);
        if(affected<0)throw new DataIntegrityViolationException("数据库未返回可靠的历史结果删除数量");
        return affected;
    }
}
