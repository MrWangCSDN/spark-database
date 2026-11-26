package cn.sunline.tss.tssveri.spark;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Publish summary, bounded samples and task completion in one metadata transaction. */
final class DailyResultPublisher {
    record Context(String name,String module,String reviserName,String reviserUsername,Long originId,String originName,Long destinationId,String destinationName) {
        Context(String name,String module,Long originId,String originName,Long destinationId,String destinationName) {
            this(name,module,null,null,originId,originName,destinationId,destinationName);
        }
    }
    static Context context(Connection connection,long index) throws SQLException {
        try(var ps=connection.prepareStatement("SELECT c.BCOMP_NAME,c.BCOMP_MODULE,c.REVISER_NAME,c.REVISER_USERNAME,s.ORIG_DATABASE_ID,o.DATABASE_NAME,s.DEST_DATABASE_ID,d.DATABASE_NAME FROM TSS_BCOMP_CONF_NEW c JOIN TSS_BCOMP_TABLE_SQL_NEW s ON s.BCOMP_INDEX=c.BCOMP_INDEX LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW o ON o.DATABASE_ID=s.ORIG_DATABASE_ID LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d ON d.DATABASE_ID=s.DEST_DATABASE_ID WHERE c.BCOMP_INDEX=?")) {
            ps.setLong(1,index);
            try(var rs=ps.executeQuery()) {
                if(!rs.next()) throw new SQLException("任务结果配置不存在");
                return new Context(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5,Long.class),rs.getString(6),rs.getObject(7,Long.class),rs.getString(8));
            }
        }
    }
    private static void addSample(PreparedStatement ps,long index,String[] args,String date,String type,int number,SampleRow row,String patternId) throws Exception {
        ps.setLong(1,index);ps.setString(2,args[7]);ps.setString(3,args[8]);ps.setString(4,row.key);
        ps.setString(5,type);ps.setString(6,date);ps.setInt(7,number);ps.setString(8,row.json());ps.setString(9,patternId);ps.addBatch();
    }
    static String resultDate(String[] args) {
        String value=args[20]==null||args[20].isBlank()?args[7]:args[20];
        LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE);
        return value;
    }
    @FunctionalInterface
    private interface DetailWriter {
        void write(Connection c,long index,String date,String run) throws Exception;
    }

    static void publish(Connection c,String[] args,Context context,ResultAggregate result) throws Exception {
        if(result.patternCount()!=result.different) throw new IllegalArgumentException("字段组合计数与差异总数不一致");
        publish(c,args,context,result,UUID.randomUUID().toString(),result.limit,
            result.samples("3").size(),result.samples("2").size(),result.patternSampleCount(),
            (conn,index,date,run)->writeSamples(conn,index,date,run,args,result));
    }

    static void publishFull(Connection c,String[] args,Context context,String run,
            List<FullDifferenceStage.PartitionManifest> manifests,int partitions) throws Exception {
        if(manifests.size()!=partitions || partitions<1 || partitions>4) throw new SQLException("全量差异获胜分区不完整");
        var winners=new ArrayList<>(manifests);
        winners.sort(Comparator.comparingInt(manifest->manifest.partition));
        var totals=new ResultAggregate(0);
        for(int i=0;i<partitions;i++) {
            var manifest=winners.get(i);
            if(manifest.partition!=i) throw new SQLException("全量差异获胜分区重复或缺失");
            totals.merge(manifest.totals);
        }
        publish(c,args,context,totals,run,-1,totals.onlyOrigin,totals.onlyDestination,totals.different,
            (conn,index,date,version)->{
                FullDifferenceStage.copyWinners(conn,version,winners,args,date);
                FullDifferenceStage.publishPatterns(conn,version,index,date,totals.different);
            });
    }

    private static void publish(Connection c,String[] args,Context context,ResultAggregate result,String runId,int detailLimit,
            long originSaved,long destinationSaved,long differentSaved,DetailWriter details) throws Exception {
        if(!c.getAutoCommit()) throw new SQLException("结果发布需要独立连接事务");
        result.validateFieldCounts();
        var fieldCounts=result.fieldCounts();
        long index=Long.parseLong(args[6]);String date=resultDate(args);
        String now=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        c.setAutoCommit(false);
        Throwable failure=null;
        boolean transactionResolved=false;
        try {
            try(var ps=c.prepareStatement("SELECT TASK_STATE FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=? AND TASK_BUSI_DATE=? AND TASK_BATCH=? AND BCOMP_TIME_NODE=? FOR UPDATE")) {
                ps.setLong(1,index);ps.setString(2,args[7]);ps.setString(3,args[8]);ps.setString(4,args[9]);
                try(var rs=ps.executeQuery()) { if(!rs.next()||!"2".equals(rs.getString(1))) throw new SQLException("任务已结束或不再拥有发布资格"); }
            }
            try(var ps=c.prepareStatement("SELECT RUN_ID FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=? AND RESULT_DATE=? FOR UPDATE")) {
                ps.setLong(1,index);ps.setString(2,date);try(var rs=ps.executeQuery()){ while(rs.next()) { /* lock existing publication */ } }
            }
            for(String table:new String[]{"TSS_BCOMP_DTL_NEW","TSS_BCOMP_FIELD_DIFF_NEW","TSS_BCOMP_FIELD_COUNT_NEW","TSS_BCOMP_RESULT_NEW"}) {
                try(var ps=c.prepareStatement("DELETE FROM "+table+" WHERE BCOMP_INDEX=? AND RESULT_DATE=?")) { ps.setLong(1,index);ps.setString(2,date);ps.execute(); }
            }
            try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_RESULT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,CONFIGURATION_NAME,CONFIGURATION_MODULE,REVISER_NAME,REVISER_USERNAME,ORIGIN_DATABASE_ID,ORIGIN_DATABASE_NAME,DESTINATION_DATABASE_ID,DESTINATION_DATABASE_NAME,ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,DIFFERENT_COUNT,DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,PUBLISHED_AT,DETAILS_EXPIRED,FIELD_COUNTS_VERSION,FIELD_COUNTS_SIZE) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'0',1,?)")) {
                int i=1;ps.setLong(i++,index);ps.setString(i++,date);ps.setString(i++,runId);ps.setString(i++,args[7]);ps.setString(i++,args[8]);ps.setString(i++,args[9]);ps.setString(i++,context.name());ps.setString(i++,context.module());
                ps.setString(i++,context.reviserName());ps.setString(i++,context.reviserUsername());
                ps.setObject(i++,context.originId());ps.setString(i++,context.originName());ps.setObject(i++,context.destinationId());ps.setString(i++,context.destinationName());
                ps.setLong(i++,result.originCount());ps.setLong(i++,result.destinationCount());ps.setLong(i++,result.matched);ps.setLong(i++,result.onlyOrigin);ps.setLong(i++,result.onlyDestination);ps.setLong(i++,result.different);
                ps.setInt(i++,detailLimit);ps.setLong(i++,originSaved);ps.setLong(i++,destinationSaved);ps.setLong(i++,differentSaved);ps.setString(i++,now);ps.setInt(i,fieldCounts.size());ps.executeUpdate();
            }
            try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_FIELD_COUNT_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,FIELD_NAME,DIFFERENT_COUNT) VALUES(?,?,?,?,?)")) {
                for(var field:fieldCounts.entrySet()) {
                    ps.setLong(1,index);ps.setString(2,date);ps.setString(3,runId);
                    ps.setString(4,field.getKey());ps.setLong(5,field.getValue());ps.addBatch();
                }
                ps.executeBatch();
            }
            details.write(c,index,date,runId);
            try(var ps=c.prepareStatement("UPDATE TSS_BCOMP_TASK_NEW SET FILE1_ACOUNT=?,FILE2_ACOUNT=?,FILE1_SCOUNT=?,FILE2_SCOUNT=?,FILE1_FCOUNT=?,FILE2_FCOUNT=?,TASK_STATE='3',TASK_RESULT=?,FTASK_REASON=NULL,END_DATETIME=?,RESULT_DATE=? WHERE BCOMP_INDEX=? AND TASK_BUSI_DATE=? AND TASK_BATCH=? AND BCOMP_TIME_NODE=? AND TASK_STATE='2'")) {
                ps.setLong(1,result.originCount());ps.setLong(2,result.destinationCount());ps.setLong(3,result.matched);ps.setLong(4,result.matched);ps.setLong(5,Math.addExact(result.different,result.onlyDestination));ps.setLong(6,Math.addExact(result.different,result.onlyOrigin));
                ps.setString(7,result.onlyOrigin==0&&result.onlyDestination==0&&result.different==0?"2":"1");ps.setString(8,now);ps.setString(9,date);ps.setLong(10,index);ps.setString(11,args[7]);ps.setString(12,args[8]);ps.setString(13,args[9]);
                if(ps.executeUpdate()!=1) throw new SQLException("任务发布身份已变化");
            }
            c.commit();
            transactionResolved=true;
        } catch(Exception | Error error) {
            failure=error;
            try {
                c.rollback();
                transactionResolved=true;
            } catch(Exception | Error rollbackError) {
                if(rollbackError!=error) error.addSuppressed(rollbackError);
            }
            throw error;
        } finally {
            // Never reset after failed rollback: enabling autocommit could commit partial writes.
            // The caller owns this connection and closes it; publication is not blindly retried.
            if(transactionResolved) {
                try { c.setAutoCommit(true); }
                catch(Exception | Error resetError) {
                    if(failure==null) throw resetError;
                    if(resetError!=failure) failure.addSuppressed(resetError);
                }
            }
        }
    }
    private static void writeSamples(Connection c,long index,String date,String runId,String[] args,ResultAggregate result) throws Exception {
        try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(?,?,?,?,?,?,?,?)")) {
            for(var pattern:result.patterns()) {
                ps.setLong(1,index);ps.setString(2,date);ps.setString(3,runId);
                ps.setString(4,pattern.id);ps.setString(5,pattern.json);ps.setLong(6,pattern.count);ps.setInt(7,pattern.sampleCount());ps.setInt(8,pattern.collapsedPatternCount);ps.addBatch();
            }
            ps.executeBatch();
        }
        try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,COMP_INDEX_STR,LINE_RESULT,RESULT_DATE,SAMPLE_NO,DETAIL_JSON,PATTERN_ID) VALUES(?,?,?,?,?,?,?,?,?)")) {
            int batchCount=0;
            for(String type:new String[]{"2","3"}) {
                int n=0;
                for(SampleRow row:result.samples(type)) {
                    addSample(ps,index,args,date,type,++n,row,null);batchCount++;
                    if(batchCount%100==0) {ps.executeBatch();ps.clearBatch();}
                }
            }
            int n=0;
            for(var pattern:result.patterns()) if(!"__OTHER__".equals(pattern.id)) for(SampleRow row:pattern.sampleRows()) {
                addSample(ps,index,args,date,"4",++n,row,pattern.id);batchCount++;
                if(batchCount%100==0) {ps.executeBatch();ps.clearBatch();}
            }
            ps.executeBatch();ps.clearBatch();
        }
    }
}
