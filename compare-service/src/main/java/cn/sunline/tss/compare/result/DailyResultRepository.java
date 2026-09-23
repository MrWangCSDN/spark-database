package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import cn.sunline.tss.compare.api.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

@Repository
public class DailyResultRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private static final RowMapper<DailyResultSummary> MAPPER=DataClassRowMapper.newInstance(DailyResultSummary.class);
    public DailyResultRepository(DataSource source,ObjectMapper json) {
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(30);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));this.json=json;
    }
    public record ConnectionGroup(Long originDatabaseId,String originDatabaseName,Long destinationDatabaseId,String destinationDatabaseName,long objectCount) {}
    public record Results(PageResult<DailyResultSummary> results,List<ConnectionGroup> connections) {}
    public record Sample(String businessKey,JsonNode detail) {}
    public record Samples(DailyResultSummary summary,List<Sample> items,int page,int size,long total) {}
    public Results list(String date,int page,int size) {
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_RESULT_NEW WHERE RESULT_DATE=?",Long.class,date);
        var items=jdbc.query("SELECT * FROM TSS_BCOMP_RESULT_NEW WHERE RESULT_DATE=? ORDER BY BCOMP_INDEX OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",MAPPER,date,(long)page*size,size);
        var groups=jdbc.query("SELECT ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,COUNT(*) FROM TSS_BCOMP_RESULT_NEW WHERE RESULT_DATE=? GROUP BY ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME ORDER BY ORIGIN_DATABASE_ID,DESTINATION_DATABASE_ID",
            (rs,n)->new ConnectionGroup(rs.getObject(1,Long.class),rs.getString(2),rs.getObject(3,Long.class),rs.getString(4),rs.getLong(5)),date);
        return new Results(new PageResult<>(items,page,size,total),groups);
    }
    public DailyResultSummary exact(long index,String date) { return find(index,date,false); }
    private DailyResultSummary find(long index,String date,boolean lock) {
        var rows=jdbc.query("SELECT * FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=?"+(lock?" FOR UPDATE":""),MAPPER,index,date);
        if(rows.isEmpty()) throw ApiException.notFound("RESULT_NOT_FOUND","该核对日期尚无成功发布的结果");
        return rows.get(0);
    }
    public record FieldPatternResult(String patternId,List<String> fields,long differentCount,int sampleCount) {}
    public List<FieldPatternResult> fieldPatterns(long index,String date,String version) {
        return transaction.execute(status->{
            var summary=find(index,date,true);
            requireVersion(summary,version);
            return jdbc.query("SELECT PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=? AND RUN_ID=? ORDER BY DIFFERENT_COUNT DESC,FIELDS_JSON",
                (rs,n)->new FieldPatternResult(rs.getString(1),parseFields(rs.getString(2)),rs.getLong(3),rs.getInt(4)),index,date,version);
        });
    }
    private List<String> parseFields(String value) throws java.sql.SQLException {
        try {return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}
        catch(java.io.IOException error){throw new java.sql.SQLException("差异字段组合格式错误",error);}
    }
    private static void requireVersion(DailyResultSummary summary,String version) {
        if(!summary.runId().equals(version)) throw ApiException.conflict("RESULT_REPLACED","当天结果已更新，请刷新后查看");
    }
    public Samples samples(long index,String date,String type,String version,String patternId,int page,int size) {
        return transaction.execute(status->{
            var summary=find(index,date,true);
            requireVersion(summary,version);
            if(patternId!=null && !"4".equals(type)) throw ApiException.badRequest("INVALID_PATTERN","只有字段差异支持组合筛选");
            long total;
            if(patternId!=null) {
                var counts=jdbc.query("SELECT SAMPLE_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=? AND RUN_ID=? AND PATTERN_ID=?",
                    (rs,n)->rs.getLong(1),index,date,version,patternId);
                if(counts.isEmpty()) throw ApiException.notFound("PATTERN_NOT_FOUND","该字段组合不存在或结果已更新");
                total=counts.get(0);
            } else total=switch(type) { case "2" -> summary.destinationSamples();case "3" -> summary.originSamples();default -> summary.differentSamples(); };
            String sql="SELECT COMP_INDEX_STR,DETAIL_JSON FROM TSS_BCOMP_DTL_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=? AND LINE_RESULT=?"
                +(patternId==null?"":" AND PATTERN_ID=?")+" ORDER BY SAMPLE_NO OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            RowMapper<Sample> mapper=(rs,n)->{
                try { return new Sample(rs.getString(1),json.readTree(rs.getString(2))); }
                catch(java.io.IOException e){ throw new java.sql.SQLException("差异样本格式错误",e); }
            };
            List<Sample> rows=patternId==null
                ?jdbc.query(sql,mapper,index,date,type,(long)page*size,size)
                :jdbc.query(sql,mapper,index,date,type,patternId,(long)page*size,size);
            return new Samples(summary,rows,page,size,total);
        });
    }
    /** Lock/recheck each bounded publication, sharing publisher lock order on the summary row. */
    public int expire(String cutoff) {
        var candidates=jdbc.query("SELECT BCOMP_INDEX,RESULT_DATE FROM TSS_BCOMP_RESULT_NEW WHERE DETAILS_EXPIRED='0' AND PUBLISHED_AT<? ORDER BY PUBLISHED_AT OFFSET 0 ROWS FETCH NEXT 100 ROWS ONLY",
            (rs,n)->Map.entry(rs.getLong(1),rs.getString(2)),cutoff);
        int expired=0;
        for(var candidate:candidates) {
            Boolean changed=transaction.execute(status->{
                var summary=find(candidate.getKey(),candidate.getValue(),true);
                if("1".equals(summary.detailsExpired()) || summary.publishedAt().compareTo(cutoff)>=0) return false;
                jdbc.update("DELETE FROM TSS_BCOMP_DTL_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=?",candidate.getKey(),candidate.getValue());
                jdbc.update("UPDATE TSS_BCOMP_FIELD_DIFF_NEW SET SAMPLE_COUNT=0 WHERE BCOMP_INDEX=? AND RESULT_DATE=?",candidate.getKey(),candidate.getValue());
                jdbc.update("UPDATE TSS_BCOMP_RESULT_NEW SET DETAILS_EXPIRED='1',ORIGIN_SAMPLES=0,DESTINATION_SAMPLES=0,DIFFERENT_SAMPLES=0 WHERE BCOMP_INDEX=? AND RESULT_DATE=?",candidate.getKey(),candidate.getValue());
                return true;
            });
            if(Boolean.TRUE.equals(changed)) expired++;
        }
        return expired;
    }
}
