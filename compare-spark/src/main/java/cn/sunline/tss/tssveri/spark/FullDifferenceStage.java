package cn.sunline.tss.tssveri.spark;

import java.io.Serializable;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** A run owns private attempt rows until the driver atomically publishes Spark's winning partitions. */
final class FullDifferenceStage {
    static final class PartitionManifest implements Serializable {
        final int partition;
        final long attempt;
        final ResultAggregate totals = new ResultAggregate(0);

        PartitionManifest(int partition, long attempt) {
            this.partition = partition;
            this.attempt = attempt;
        }

        long rows() {
            return Math.addExact(totals.different, Math.addExact(totals.onlyOrigin, totals.onlyDestination));
        }
    }

    static void start(Connection c,String run,String[] args) throws SQLException {
        try (var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_FULL_RUN_NEW(RUN_ID,BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,TIME_NODE,CREATED_AT) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1,run);
            ps.setLong(2,Long.parseLong(args[6]));
            ps.setString(3,args[7]);
            ps.setString(4,args[8]);
            ps.setString(5,args[9]);
            ps.setString(6,LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            ps.executeUpdate();
        }
    }

    static void remove(Connection c,String run) throws SQLException {
        try (var ps=c.prepareStatement("DELETE FROM TSS_BCOMP_FULL_RUN_NEW WHERE RUN_ID=?")) {
            ps.setString(1,run);
            ps.executeUpdate();
        }
    }

    static final class Writer implements AutoCloseable {
        private final Connection connection;
        private final PreparedStatement insert;
        private long localNo;
        private int pending;

        Writer(Connection connection,String run,int partition,long attempt) throws SQLException {
            this.connection=connection;
            connection.setAutoCommit(false);
            insert=connection.prepareStatement("INSERT INTO TSS_BCOMP_FULL_STAGE_NEW(RUN_ID,PART_ID,ATTEMPT_ID,LOCAL_NO,LINE_RESULT,COMP_INDEX_STR,DETAIL_JSON,PATTERN_ID,FIELDS_JSON) VALUES(?,?,?,?,?,?,?,?,?)");
            insert.setString(1,run);
            insert.setInt(2,partition);
            insert.setLong(3,attempt);
        }

        void append(SampleRow row,FieldPattern pattern,long occurrences) throws Exception {
            String json=row.json();
            insert.setString(5,row.type);
            insert.setString(6,row.key);
            insert.setString(7,json);
            insert.setString(8,pattern==null?null:pattern.id);
            insert.setString(9,pattern==null?null:pattern.json);
            for(long n=0;n<occurrences;n++) {
                localNo=Math.incrementExact(localNo);
                insert.setLong(4,localNo);
                insert.addBatch();
                if(++pending==100) flush();
            }
        }

        void flush() throws SQLException {
            if(pending==0) return;
            insert.executeBatch();
            insert.clearBatch();
            connection.commit();
            pending=0;
        }

        public void close() throws SQLException {
            try (insert) {
                // Roll back any incomplete batch. Committed attempt rows are invisible until selected.
                connection.rollback();
            }
        }
    }

    private static final String WINNERS = " FROM TSS_BCOMP_FULL_STAGE_NEW s JOIN TSS_BCOMP_FULL_WIN_NEW w"
        +" ON w.RUN_ID=s.RUN_ID AND w.PART_ID=s.PART_ID AND w.ATTEMPT_ID=s.ATTEMPT_ID WHERE s.RUN_ID=?";

    static void copyWinners(Connection c,String run,List<PartitionManifest> winners,String[] args,String date) throws SQLException {
        long offset=0;
        try(var ps=c.prepareStatement("INSERT INTO TSS_BCOMP_FULL_WIN_NEW(RUN_ID,PART_ID,ATTEMPT_ID,ROW_OFFSET) VALUES(?,?,?,?)")) {
            for(var winner:winners) {
                ps.setString(1,run);ps.setInt(2,winner.partition);ps.setLong(3,winner.attempt);ps.setLong(4,offset);
                ps.addBatch();
                offset=Math.addExact(offset,winner.rows());
            }
            ps.executeBatch();
        }
        long[][] counts=new long[winners.size()][3];
        try(var ps=c.prepareStatement("SELECT s.PART_ID,s.LINE_RESULT,COUNT(*)"+WINNERS+" GROUP BY s.PART_ID,s.LINE_RESULT")) {
            ps.setString(1,run);
            try(var rs=ps.executeQuery()) {
                while(rs.next()) {
                    int part=rs.getInt(1);
                    int category=switch(rs.getString(2)) {
                        case "2" -> 0; case "3" -> 1; case "4" -> 2; default -> throw new SQLException("未知暂存差异类型");
                    };
                    counts[part][category]=rs.getLong(3);
                }
            }
        }
        for(var winner:winners) {
            long[] stored=counts[winner.partition];
            if(stored[0]!=winner.totals.onlyDestination || stored[1]!=winner.totals.onlyOrigin || stored[2]!=winner.totals.different)
                throw new SQLException("全量差异暂存数量与获胜分区不一致");
        }
        String sql="INSERT INTO TSS_BCOMP_DTL_NEW(BCOMP_INDEX,TASK_DATE,TASK_BATCH,COMP_INDEX_STR,LINE_RESULT,RESULT_DATE,SAMPLE_NO,DETAIL_JSON,PATTERN_ID)"
            +" SELECT ?,?,?,s.COMP_INDEX_STR,s.LINE_RESULT,?,w.ROW_OFFSET+s.LOCAL_NO,s.DETAIL_JSON,s.PATTERN_ID"+WINNERS;
        try(var ps=c.prepareStatement(sql)) {
            ps.setLong(1,Long.parseLong(args[6]));ps.setString(2,args[7]);ps.setString(3,args[8]);ps.setString(4,date);ps.setString(5,run);
            // The affected-row count may exceed the 32-bit JDBC executeUpdate return type.
            ps.execute();
        }
    }

    static void publishPatterns(Connection c,String run,long index,String date,long different) throws SQLException {
        if(different==0) return;
        long distinct;
        try(var ps=c.prepareStatement("SELECT COUNT(DISTINCT s.PATTERN_ID),COUNT(s.PATTERN_ID)"+WINNERS+" AND s.LINE_RESULT='4'")) {
            ps.setString(1,run);
            try(var rs=ps.executeQuery()) {
                rs.next();distinct=rs.getLong(1);
                if(rs.getLong(2)!=different) throw new SQLException("字段差异组合暂存不完整");
            }
        }
        var metadata=c.getMetaData();
        boolean mysql=PrimaryKeyHashPlanner.isMysqlFamily(metadata.getDatabaseProductName(),metadata.getURL(),metadata.getDriverName());
        String topLimit=mysql?" LIMIT 127":" FETCH FIRST 127 ROWS ONLY";
        String first=mysql?" LIMIT 1":" FETCH FIRST 1 ROW ONLY";
        var top=new LinkedHashMap<String,Long>();
        try(var ps=c.prepareStatement("SELECT s.PATTERN_ID,COUNT(*) AS ROWS_IN_PATTERN"+WINNERS
                +" AND s.LINE_RESULT='4' GROUP BY s.PATTERN_ID ORDER BY ROWS_IN_PATTERN DESC,s.PATTERN_ID"+topLimit)) {
            ps.setString(1,run);
            try(var rs=ps.executeQuery()) { while(rs.next()) top.put(rs.getString(1),rs.getLong(2)); }
        }
        long remaining=different;
        try(var fields=c.prepareStatement("SELECT s.FIELDS_JSON"+WINNERS+" AND s.PATTERN_ID=?"+first);
            var insert=c.prepareStatement("INSERT INTO TSS_BCOMP_FIELD_DIFF_NEW(BCOMP_INDEX,RESULT_DATE,RUN_ID,PATTERN_ID,FIELDS_JSON,DIFFERENT_COUNT,SAMPLE_COUNT,COLLAPSED_PATTERN_COUNT) VALUES(?,?,?,?,?,?,?,?)")) {
            fields.setString(1,run);
            insert.setLong(1,index);insert.setString(2,date);insert.setString(3,run);
            insert.setLong(8,1);
            for(var entry:top.entrySet()) {
                fields.setString(2,entry.getKey());
                try(var rs=fields.executeQuery()) {
                    if(!rs.next()) throw new SQLException("字段差异组合缺少字段快照");
                    insert.setString(4,entry.getKey());insert.setString(5,rs.getString(1));
                }
                insert.setLong(6,entry.getValue());insert.setLong(7,entry.getValue());insert.addBatch();
                remaining=Math.subtractExact(remaining,entry.getValue());
            }
            if(distinct>top.size()) {
                insert.setString(4,"__OTHER__");insert.setString(5,"[]");insert.setLong(6,remaining);
                insert.setLong(7,remaining);insert.setLong(8,distinct-top.size());insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
