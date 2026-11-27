package cn.sunline.tss.compare.result;

import cn.sunline.tss.compare.api.ApiException;
import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.metadata.MetadataDbType;
import cn.sunline.tss.compare.metadata.MetadataSqlDialect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.util.*;

@Repository
public class DailyResultRepository {
    private static final Logger log=LoggerFactory.getLogger(DailyResultRepository.class);
    private final JdbcTemplate jdbc;
    private final JdbcTemplate maintenance;
    private final TransactionTemplate transaction;
    private final TransactionTemplate exportTransaction;
    private final ObjectMapper json;
    private final MetadataSqlDialect dialect;
    private static final RowMapper<DailyResultSummary> MAPPER=(rs,n)->new DailyResultSummary(
        rs.getLong("BCOMP_INDEX"),rs.getString("RESULT_DATE"),rs.getString("RUN_ID"),rs.getString("TASK_BUSI_DATE"),
        rs.getString("TASK_BATCH"),rs.getString("TIME_NODE"),rs.getString("CONFIGURATION_NAME"),rs.getString("CONFIGURATION_MODULE"),
        rs.getString("REVISER_NAME"),rs.getString("REVISER_USERNAME"),rs.getObject("ORIGIN_DATABASE_ID",Long.class),
        rs.getString("ORIGIN_DATABASE_NAME"),rs.getObject("DESTINATION_DATABASE_ID",Long.class),rs.getString("DESTINATION_DATABASE_NAME"),
        rs.getLong("ORIGIN_COUNT"),rs.getLong("DESTINATION_COUNT"),rs.getLong("MATCHED_COUNT"),rs.getLong("ONLY_ORIGIN_COUNT"),
        rs.getLong("ONLY_DESTINATION_COUNT"),rs.getLong("DIFFERENT_COUNT"),rs.getInt("DETAIL_LIMIT"),rs.getLong("ORIGIN_SAMPLES"),
        rs.getLong("DESTINATION_SAMPLES"),rs.getLong("DIFFERENT_SAMPLES"),rs.getString("PUBLISHED_AT"),rs.getString("DETAILS_EXPIRED"),rs.getString("SOURCE_TABLE_NAME"),rs.getString("CONFIGURATION_DISPLAY_NAME"),rs.getString("CONFIGURATION_SCOPE"));
    public DailyResultRepository(DataSource source,ObjectMapper json) {
        this(source, json, MetadataSqlDialect.forType(MetadataDbType.GAUSSDB));
    }
    @Autowired
    public DailyResultRepository(DataSource source,ObjectMapper json, MetadataSqlDialect dialect) {
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(30);
        maintenance=new JdbcTemplate(source);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));this.json=json;this.dialect=dialect;
        exportTransaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        exportTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        exportTransaction.setTimeout(30);
    }
    public record ConnectionGroup(Long originDatabaseId,String originDatabaseName,Long destinationDatabaseId,String destinationDatabaseName,long objectCount) {}
    public record Results(PageResult<DailyResultSummary> results,List<ConnectionGroup> connections) {}
    public record Sample(String businessKey,JsonNode detail) {}
    public record Samples(DailyResultSummary summary,List<Sample> items,long page,int size,long total) {}
    public Results list(String date,int page,int size) {
        return list(date,page,size,null,null,null);
    }
    public Results list(String date,int page,int size,String domain,String configurationName,String reviser) {
        var where=new StringBuilder(" WHERE RESULT_DATE=?");
        var args=new ArrayList<Object>();args.add(date);
        if(domain!=null&&!domain.isBlank()) {
            where.append(" AND (LOWER(COALESCE(CONFIGURATION_MODULE,'')) LIKE ? ESCAPE '!' OR LOWER(CASE LOWER(CONFIGURATION_MODULE) WHEN 'dept' THEN '存款组' WHEN 'loan' THEN '贷款组' WHEN 'comm' THEN '公共组' WHEN 'sett' THEN '结算组' WHEN 'platform' THEN '平台组' ELSE COALESCE(CONFIGURATION_MODULE,'') END) LIKE ? ESCAPE '!')");
            String value=contains(domain);args.add(value);args.add(value);
        }
        if(configurationName!=null&&!configurationName.isBlank()) {
            where.append(" AND (LOWER(COALESCE(CONFIGURATION_NAME,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(SOURCE_TABLE_NAME,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(CONFIGURATION_DISPLAY_NAME,'')) LIKE ? ESCAPE '!')");String value=contains(configurationName);args.add(value);args.add(value);args.add(value);
        }
        if(reviser!=null&&!reviser.isBlank()) {
            where.append(" AND (LOWER(COALESCE(REVISER_NAME,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(REVISER_USERNAME,'')) LIKE ? ESCAPE '!')");
            String value=contains(reviser);args.add(value);args.add(value);
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_RESULT_NEW"+where,Long.class,args.toArray());
        var pageSql=dialect.page("SELECT * FROM TSS_BCOMP_RESULT_NEW"+where+" ORDER BY BCOMP_INDEX",(long)page*size,size);
        var pageArgs=new ArrayList<>(args);pageArgs.addAll(pageSql.parameters());
        var items=jdbc.query(pageSql.sql(),MAPPER,pageArgs.toArray());
        var groups=jdbc.query("SELECT ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,COUNT(*) FROM TSS_BCOMP_RESULT_NEW"+where+" GROUP BY ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME ORDER BY ORIGIN_DATABASE_ID,DESTINATION_DATABASE_ID",
            (rs,n)->new ConnectionGroup(rs.getObject(1,Long.class),rs.getString(2),rs.getObject(3,Long.class),rs.getString(4),rs.getLong(5)),args.toArray());
        return new Results(new PageResult<>(items,page,size,total),groups);
    }
    private static String contains(String value) {
        return "%"+value.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    public DailyResultSummary exact(long index,String date) { return find(index,date,false); }
    private record ExportResult(DailyResultSummary summary,int version,int fieldCountSize) {}
    public DailyResultExportSnapshot exportSnapshot(String date) {
        try {return exportTransaction.execute(status->readExportSnapshot(date));}
        catch(ApiException error){throw error;}
        catch(RuntimeException error){throw ApiException.internal("RESULT_EXPORT_FAILED","读取每日核对字段统计失败，请根据日志检查结果结构及版本",error);}
    }
    private DailyResultExportSnapshot readExportSnapshot(String date) {
        var rows=jdbc.query("SELECT * FROM TSS_BCOMP_RESULT_NEW WHERE RESULT_DATE=? ORDER BY BCOMP_INDEX FOR UPDATE",
            (rs,n)->new ExportResult(MAPPER.mapRow(rs,n),rs.getInt("FIELD_COUNTS_VERSION"),rs.getInt("FIELD_COUNTS_SIZE")),date);
        if(rows.isEmpty()) return new DailyResultExportSnapshot(List.of());
        var locked=new LinkedHashMap<Long,ExportResult>();
        rows.forEach(row->locked.put(row.summary().bcompIndex(),row));
        var counts=new HashMap<Long,List<DailyResultFieldCounts.StoredFieldCount>>();
        jdbc.query("SELECT BCOMP_INDEX,RUN_ID,FIELD_NAME,DIFFERENT_COUNT FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE RESULT_DATE=?",
            (RowCallbackHandler)rs->{
                long index=rs.getLong(1);
                if(locked.containsKey(index)) counts.computeIfAbsent(index,ignored->new ArrayList<>())
                    .add(new DailyResultFieldCounts.StoredFieldCount(rs.getString(2),rs.getString(3),rs.getLong(4)));
            },date);
        var patterns=new HashMap<Long,List<FieldPatternResult>>();
        if(rows.stream().anyMatch(row->row.version()==0&&row.summary().differentCount()>0)) {
            jdbc.query("SELECT f.BCOMP_INDEX,f.RUN_ID,f.PATTERN_ID,f.FIELDS_JSON,f.DIFFERENT_COUNT,f.SAMPLE_COUNT,f.COLLAPSED_PATTERN_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW f JOIN TSS_BCOMP_RESULT_NEW r ON r.BCOMP_INDEX=f.BCOMP_INDEX AND r.RESULT_DATE=f.RESULT_DATE WHERE f.RESULT_DATE=? AND r.FIELD_COUNTS_VERSION=0 AND r.DIFFERENT_COUNT>0",
                (RowCallbackHandler)rs->{
                    long index=rs.getLong(1);var row=locked.get(index);
                    if(row==null) return;
                    if(!row.summary().runId().equals(rs.getString(2))) {
                        log.warn("Daily result export invalid: date={}, configurationId={}, reason=legacy_run_mismatch",date,index);
                        throw ApiException.internal("RESULT_EXPORT_FAILED","历史字段组合结果版本不一致",null);
                    }
                    try {
                        patterns.computeIfAbsent(index,ignored->new ArrayList<>()).add(new FieldPatternResult(
                            rs.getString(3),parseExportFields(rs.getString(4)),rs.getLong(5),rs.getLong(6),rs.getLong(7)));
                    } catch(java.sql.SQLException error) {
                        log.warn("Daily result export invalid: date={}, configurationId={}, reason=legacy_pattern_read_failed",date,index);
                        throw error;
                    }
                },date);
        }
        var results=new ArrayList<DailyResultExportSnapshot.TableResult>();
        var unavailable=new ArrayList<String>();
        int unavailableCount=0;
        for(var row:rows) {
            var summary=row.summary();long index=summary.bcompIndex();
            try {
                var storedCounts=counts.getOrDefault(index,List.of());
                if(row.version()==1 && row.fieldCountSize()!=storedCounts.size())
                    throw ApiException.internal("RESULT_EXPORT_FAILED","字段统计记录数与发布清单不一致",null);
                results.add(new DailyResultExportSnapshot.TableResult(summary,DailyResultFieldCounts.resolve(summary,row.version(),
                    storedCounts,patterns.getOrDefault(index,List.of()))));
            } catch(ApiException error) {
                // Resolver messages are fixed validation reasons, never business values or SQL.
                log.warn("Daily result export invalid: date={}, configurationId={}, code={}, reason={}",date,index,error.code(),error.getMessage());
                if(!"FIELD_COUNTS_UNAVAILABLE".equals(error.code())) throw error;
                unavailableCount++;
                if(unavailable.size()<10) unavailable.add(summary.configurationName()+"（配置"+index+"）");
            }
        }
        if(unavailableCount>0) throw ApiException.conflict("FIELD_COUNTS_UNAVAILABLE",date+" 的 "+unavailableCount+
            " 张核对表缺少完整字段统计："+String.join("、",unavailable)+
            (unavailableCount>10?"，其余"+(unavailableCount-10)+"张":"")+"。请重新核对后导出。");
        return new DailyResultExportSnapshot(results);
    }
    private DailyResultSummary find(long index,String date,boolean lock) {
        return find(jdbc,index,date,lock);
    }
    private DailyResultSummary find(JdbcTemplate queries,long index,String date,boolean lock) {
        var rows=queries.query("SELECT * FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=?"+(lock?" FOR UPDATE":""),MAPPER,index,date);
        if(rows.isEmpty()) throw ApiException.notFound("RESULT_NOT_FOUND","该核对日期尚无成功发布的结果");
        return rows.get(0);
    }
    public record FieldPatternResult(String patternId,List<String> fields,long differentCount,long sampleCount,long collapsedPatternCount) {}
    public List<FieldPatternResult> fieldPatterns(long index,String date,String version) {
        return transaction.execute(status->{
            var summary=find(index,date,true);
            requireVersion(summary,version);
            return jdbc.query("SELECT PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT FROM TSS_BCOMP_FIELD_DIFF_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=? AND RUN_ID=? ORDER BY CASE WHEN PATTERN_ID='__OTHER__' THEN 1 ELSE 0 END,DIFFERENT_COUNT DESC,PATTERN_ID ASC",
                (rs,n)->new FieldPatternResult(rs.getString(1),parseFields(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getLong(5)),index,date,version);
        });
    }
    private List<String> parseExportFields(String value) throws java.sql.SQLException {
        try {
            JsonNode root=json.readerFor(JsonNode.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value);
            if(root==null||!root.isArray()) throw new java.sql.SQLException("差异字段组合必须是字符串数组");
            var fields=new ArrayList<String>();
            for(JsonNode field:root) {
                if(!field.isTextual()) throw new java.sql.SQLException("差异字段组合成员必须是字符串");
                fields.add(field.textValue());
            }
            return fields;
        } catch(java.io.IOException error){throw new java.sql.SQLException("差异字段组合格式错误",error);}
    }
    private List<String> parseFields(String value) throws java.sql.SQLException {
        try {return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}
        catch(java.io.IOException error){throw new java.sql.SQLException("差异字段组合格式错误",error);}
    }
    private static void requireVersion(DailyResultSummary summary,String version) {
        if(!summary.runId().equals(version)) throw ApiException.conflict("RESULT_REPLACED","当天结果已更新，请刷新后查看");
    }
    public Samples samples(long index,String date,String type,String version,String patternId,long page,int size) {
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
                +(patternId==null?"":" AND PATTERN_ID=?")+" ORDER BY SAMPLE_NO";
            var pageSql=dialect.page(sql,Math.multiplyExact(page,size),size);
            RowMapper<Sample> mapper=(rs,n)->{
                try { return new Sample(rs.getString(1),json.readTree(rs.getString(2))); }
                catch(java.io.IOException e){ throw new java.sql.SQLException("差异样本格式错误",e); }
            };
            var pageParams=new ArrayList<Object>();pageParams.add(index);pageParams.add(date);pageParams.add(type);
            if(patternId!=null) pageParams.add(patternId);
            pageParams.addAll(pageSql.parameters());
            List<Sample> rows=jdbc.query(pageSql.sql(),mapper,pageParams.toArray());
            return new Samples(summary,rows,page,size,total);
        });
    }
    /** Only terminal or removed tasks release orphaned attempts; long-running jobs keep their staging rows. */
    public void removeFinishedStages() {
        String terminal=" NOT EXISTS (SELECT 1 FROM TSS_BCOMP_TASK_NEW t"
            +" WHERE t.BCOMP_INDEX=TSS_BCOMP_FULL_RUN_NEW.BCOMP_INDEX AND t.TASK_BUSI_DATE=TSS_BCOMP_FULL_RUN_NEW.TASK_BUSI_DATE"
            +" AND t.TASK_BATCH=TSS_BCOMP_FULL_RUN_NEW.TASK_BATCH AND t.BCOMP_TIME_NODE=TSS_BCOMP_FULL_RUN_NEW.TIME_NODE AND t.TASK_STATE='2')";
        var candidates=dialect.limit("SELECT RUN_ID FROM TSS_BCOMP_FULL_RUN_NEW WHERE"+terminal+" ORDER BY CREATED_AT",100);
        for(String run:maintenance.queryForList(candidates.sql(),String.class,candidates.parameters().toArray())) {
            maintenance.execute("DELETE FROM TSS_BCOMP_FULL_RUN_NEW WHERE RUN_ID=? AND"+terminal,
                (PreparedStatementCallback<Void>)ps->{ ps.setString(1,run);ps.execute();return null; });
        }
    }

    /** Lock/recheck each bounded publication, sharing publisher lock order on the summary row. */
    public int expire(String cutoff) {
        var candidateSql=dialect.limit("SELECT BCOMP_INDEX,RESULT_DATE FROM TSS_BCOMP_RESULT_NEW WHERE DETAILS_EXPIRED='0' AND PUBLISHED_AT<? ORDER BY PUBLISHED_AT",100);
        var candidateParams=new ArrayList<Object>();candidateParams.add(cutoff);candidateParams.addAll(candidateSql.parameters());
        var candidates=maintenance.query(candidateSql.sql(),
            (rs,n)->Map.entry(rs.getLong(1),rs.getString(2)),candidateParams.toArray());
        int expired=0;
        for(var candidate:candidates) {
            Boolean changed=transaction.execute(status->{
                var locked=maintenance.query("SELECT * FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=? FOR UPDATE",
                    MAPPER,candidate.getKey(),candidate.getValue());
                // Import cleanup may have removed a candidate after the outer selection.
                if(locked.isEmpty()) return false;
                var summary=locked.get(0);
                if("1".equals(summary.detailsExpired()) || summary.publishedAt().compareTo(cutoff)>=0) return false;
                maintenance.execute("DELETE FROM TSS_BCOMP_DTL_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=?",
                    (PreparedStatementCallback<Void>)ps->{
                        ps.setLong(1,candidate.getKey());ps.setString(2,candidate.getValue());
                        ps.execute();return null;
                    });
                maintenance.update("UPDATE TSS_BCOMP_FIELD_DIFF_NEW SET SAMPLE_COUNT=0 WHERE BCOMP_INDEX=? AND RESULT_DATE=?",candidate.getKey(),candidate.getValue());
                maintenance.update("UPDATE TSS_BCOMP_RESULT_NEW SET DETAILS_EXPIRED='1',ORIGIN_SAMPLES=0,DESTINATION_SAMPLES=0,DIFFERENT_SAMPLES=0 WHERE BCOMP_INDEX=? AND RESULT_DATE=?",candidate.getKey(),candidate.getValue());
                return true;
            });
            if(Boolean.TRUE.equals(changed)) expired++;
        }
        return expired;
    }
}
