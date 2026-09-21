package cn.sunline.tss.tssveri.spark;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.*;
import org.apache.spark.util.LongAccumulator;
import scala.Tuple2;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.time.DateUtils.parseDate;


/**
 * @param
 * @author Tyc
 * @description Spark 数据库表对比业务逻辑
 * @date 2025/12/22 15:49
 * @return
 */
@Slf4j
public class SparkHandlerTable {

    public static final String Old = "ORA";
    public static final String New = "YJJ";

    public static final String stringFieldType = "1";
    public static final String doubleFieldType = "2";
    public static final String enumFieldType = "3";
    public static final String dateFieldType = "4";

    private static final String filedSplit = "|+|";
    //同主键多行时行内容哈希与主键的分隔符：拼进COMP_INDEX_STR使结果页按主键查询时每条记录各自独立
    private static final String HASH_SEP = "#";
    //生产索引字段
    public static final String FIELD_OLD_INDEX = "FIELD_OLD_INDEX";
    //信创索引字段
    public static final String FIELD_NEW_INDEX = "FIELD_NEW_INDEX";
    //生产没有信创有
    public static final String OLD_NOT_NEW_EXISTS = "2";
    //生产有信创没有
    public static final String OLD_EXISTS_NEW_NOT = "3";
    //差异
    public static final String FAILED = "4";
    //数据可解释
    public static final String DATA_EXPLAINABLE = "5";

    public static final String TASK_STATE_COMPLETE = "3";
    public static final String TASK_STATE_FAILED = "4";

    public static final String TASK_RESULT_SUCCESS = "2";
    public static final String TASK_RESULT_FAILED = "1";

    // 下标常量定义
    static final int taskName = 0;
    static final int tssDataSourceUrl = 1;
    static final int tssDataSourceUser = 2;
    static final int tssDataSourcePassword = 3;
    static final int tssDataSourceDriver = 4;
    static final int bcompComit = 5;
    static final int bcompIndex = 6;
    static final int taskBusiDate = 7;
    static final int taskBusiBatch = 8;
    static final int bcompTimeNode = 9;

    static final int OLD_URL_INDEX = 10;
    static final int OLD_USER_INDEX = 11;
    static final int OLD_PASSWORD_INDEX = 12;
    static final int OLD_DRIVER_INDEX = 13;
    static final int OLD_TABLE_INDEX = 14;

    static final int NEW_URL_INDEX = 15;
    static final int NEW_USER_INDEX = 16;
    static final int NEW_PASSWORD_INDEX = 17;
    static final int NEW_DRIVER_INDEX = 18;
    static final int NEW_TABLE_INDEX = 19;
    static final int compDateIndex = 20;

    /**
     * 默认分区数量
     */
    private static final int DEFAULT_PARTITION_NUM = 8;

    /** 自适应分区数上限 */
    private static final int MAX_PARTITION_NUM = 64;
    /** 分片策略：按MIN/MAX等分（Spark JDBC bounds 模式） */
    private static final String SHARD_RANGE = "RANGE";
    /** 分片策略：按NTILE分位数生成范围谓词（Spark JDBC predicates 模式，仅数值列） */
    private static final String SHARD_NTILE = "NTILE";
    /** 分区列类型：不支持 */
    private static final int COL_TYPE_UNSUPPORTED = 0;
    /** 分区列类型：数值 */
    private static final int COL_TYPE_NUMERIC = 1;
    /** 分区列类型：DATE */
    private static final int COL_TYPE_DATE = 2;
    /** 分区列类型：TIMESTAMP */
    private static final int COL_TYPE_TIMESTAMP = 3;


    public static void main(String[] args) throws Exception {

        if (args == null || args.length != 21) {
            throw new IllegalArgumentException("库表比对需要完整的21个参数");
        }
        log.info("库表比对作业启动，参数数量：{}", args.length);
        SparkRun(args);
    }

    public static void SparkRun(String[] args) throws Exception {
        log.info("spark任务开始执行");

        SparkSession sc = SparkSession.builder()
                .appName(args[0])
                // 基础内存优化：启用Kryo序列化（比默认Java序列化更省内存）
                // 注：曾怀疑Kryo在shuffle反序列化时把同键多行压成雷同内容，经分组摘要与源表快照比对已排除——
                // 雷同来自源表本身存在重复行（无唯一键的表被客户端工具按非唯一条件UPDATE，一改就改多行）
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                // 启用堆外内存，避免堆内OOM（16C32G机器下调小）
                .config("spark.memory.offHeap.enabled", "true")
                .config("spark.memory.offHeap.size", "1g")
                // 降低存储缓存占比，把更多内存留给shuffle计算（默认0.5，大表cogroup场景调低）
                .config("spark.memory.storageFraction", "0.3")
                // 自适应执行：自动优化分片和Join策略（大数据量必备）
                .config("spark.sql.adaptive.enabled", "true")
                // AQE优化：自动处理数据倾斜的join
                .config("spark.sql.adaptive.skewJoin.enabled", "true")
                // AQE优化：自动合并小分区，减少任务调度开销
                .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
                // AQE优化：建议分区大小为64MB，适合有限资源场景
                .config("spark.sql.adaptive.advisoryPartitionSizeInBytes", "64m")
                .getOrCreate();

        //定义累加器，记录文件失败笔数
        LongAccumulator FAILED_COUNT= sc.sparkContext().longAccumulator();
        LongAccumulator OLD_NOT_NEW_EXISTS_COUNT= sc.sparkContext().longAccumulator();
        LongAccumulator OLD_EXISTS_NEW_NOT_COUNT= sc.sparkContext().longAccumulator();
        LongAccumulator SUCCESS_COUNT= sc.sparkContext().longAccumulator();
        //统计源表中同主键(索引字段)存在多行的分组数：这类分组走排序位置配对逐对出明细，
        //结束后在Driver端汇总打印一次，避免大表场景逐组刷日志
        LongAccumulator DUP_KEY_GROUP_COUNT = sc.sparkContext().longAccumulator();
        //两侧读取行数：在比对分区内累加（cogroup不丢行，各分组行数之和=源表行数），
        //替代单独count()行动——省去第二遍遍历，也就省去了为它而做的落盘缓存
        LongAccumulator READ_OLD_COUNT = sc.sparkContext().longAccumulator();
        LongAccumulator READ_NEW_COUNT = sc.sparkContext().longAccumulator();


        Connection connectionOut = null;
        PreparedStatement finalPs2 = null;
        PreparedStatement finalPs3 = null;
        PreparedStatement finalPs4 = null;
        Class.forName(args[tssDataSourceDriver]).newInstance();
        connectionOut = DriverManager.getConnection(args[tssDataSourceUrl], args[tssDataSourceUser], args[tssDataSourcePassword]);
        finalPs2 = connectionOut.prepareStatement("update tss_bcomp_task_new set FILE1_ACOUNT = ? , FILE2_ACOUNT = ?  " +
                "where BCOMP_INDEX = ? and TASK_BUSI_DATE = ? and TASK_BATCH = ? and BCOMP_TIME_NODE = ?");
        finalPs3 = connectionOut.prepareStatement("update tss_bcomp_task_new set TASK_STATE = ? , FTASK_REASON = ?,TASK_RESULT = ?  " +
                "where BCOMP_INDEX = ? and TASK_BUSI_DATE = ? and TASK_BATCH = ? and BCOMP_TIME_NODE = ?");
        finalPs4 = connectionOut.prepareStatement("update tss_bcomp_task_new set FILE1_SCOUNT = ?,FILE1_FCOUNT = ?,FILE2_SCOUNT = ?, " +
                "FILE2_FCOUNT = ?,TASK_STATE = ?,FTASK_REASON = ?,TASK_RESULT = ?,END_DATETIME = ? " +
                "WHERE BCOMP_INDEX = ? AND TASK_BUSI_DATE = ? AND TASK_BATCH = ?");

        try {
            //获取tssBcompField字段信息（需在获取数据源配置之前，因为分区列从field表中读取）
            List<Row> tssBcompFieldDataRows = getTssBcompFieldData(sc,args);

            //从field配置中获取分区列名，从config表获取分区数与分片策略
            int defaultParallelism = sc.sparkContext().defaultParallelism();
            ShardConf shardConf = readShardConf(args, defaultParallelism);

            //替换SQL中的${HDRQ}占位符（使用核对日期args[compDateIndex]）
            String sqlDateParam = args[compDateIndex];
            if (sqlDateParam != null && !sqlDateParam.isEmpty()) {
                args[OLD_TABLE_INDEX] = replaceSqlPlaceholders(args[OLD_TABLE_INDEX], sqlDateParam);
                args[NEW_TABLE_INDEX] = replaceSqlPlaceholders(args[NEW_TABLE_INDEX], sqlDateParam);
                log.info("SQL占位符替换完成，核对日期: {}", sqlDateParam);
            }

            //解析新老两侧的读取计划（HASH/RANGE/NTILE/普通读取，含legacy降级原因）
            ReadPlan readPlan = resolveReadPlan(args, tssBcompFieldDataRows, shardConf);
            //回写实际读取策略到TSS_BCOMP_TASK_NEW.BCOMP_READ_MODE，运维可见降级事实（列不存在时只告警）
            writeReadMode(connectionOut, readPlan, args);

            //枚举/忽略值改为executor分区内自加载：这两张表可能是数据级规模(如按账号/流水号配置忽略值)，
            //Driver端collect+广播会直接撑爆Driver堆；这里只提取配置中用到的索引(几个整数)传给executor
            String enumIndexCsv = tssBcompFieldDataRows.stream()
                    .filter(row -> enumFieldType.equals((String) row.getAs("FIELD_TYPE")))
                    .map(row -> row.getAs("FIELD_ENUM_INDEX"))
                    .filter(Objects::nonNull)
                    .map(v -> String.valueOf(((Number) v).intValue()))
                    .distinct()
                    .collect(Collectors.joining(","));
            String ignoreIdCsv = tssBcompFieldDataRows.stream()
                    .map(row -> row.getAs("FIELD_IGNORE_VALUE"))
                    .filter(v -> StringUtils.isNotEmpty((String) v))
                    .map(v -> ((String) v).trim())
                    .distinct()
                    .collect(Collectors.joining(","));

            //字段配置改为广播变量（仅几十行定义，不构成Driver内存压力）
            JavaSparkContext jsc = new JavaSparkContext(sc.sparkContext());
            Broadcast<List<Row>> bcField = jsc.broadcast(tssBcompFieldDataRows);

            //分别查询新老数据库表信息
            //不persist、不单独count()：40M级数据下DISK_ONLY缓存会把整份Row(每行带schema)序列化落盘，
            //与shuffle文件叠加撑爆executor本地盘(spark.local.dir未配置时默认/tmp)，报KryoException:"设备上没有空间"；
            //行数改由比对分区内累加器统计，全链只消费一次，磁盘上只留shuffle一份
            JavaRDD<Row> rowJavaRDDOld = readSide(sc, readPlan.oldSide);
            JavaRDD<Row> rowJavaRDDNew = readSide(sc, readPlan.newSide);
            //转换为pairRDD
            JavaPairRDD<String, String[]> pairsOra = RddToPair(FIELD_OLD_INDEX,rowJavaRDDOld,bcField);
            JavaPairRDD<String, String[]> pairsYjj = RddToPair(FIELD_NEW_INDEX,rowJavaRDDNew,bcField);
            //采用cogroup进行join
            JavaPairRDD<String, Tuple2<Iterable<String[]>, Iterable<String[]>>> rsCogroup = pairsOra.cogroup(pairsYjj);

            rsCogroup.foreachPartition(tuple -> {
                Connection connectionInner = null;
                PreparedStatement finalPs1 = null;
                PreparedStatement finalPs5 = null;
                Class.forName(args[tssDataSourceDriver]).newInstance();
                connectionInner = DriverManager.getConnection(args[tssDataSourceUrl], args[tssDataSourceUser], args[tssDataSourcePassword]);
                finalPs1 = connectionInner.prepareStatement("insert into TSS_BCOMP_DTL_NEW(bcomp_index,task_date,task_batch," +
                        "comp_index_str,LINE_CONTENT1,LINE_CONTENT2,LINE_RESULT,FDTL_REASON) " +
                        "values (?,?,?,?,?,?,?,?)");
                finalPs5 = connectionInner.prepareStatement("SELECT * FROM TSS_BCOMP_DIFFFIELD WHERE bcomp_index = ?");
                finalPs5.setString(1,args[bcompIndex]);

                int count = 0;
                try {
                    //从广播变量取字段配置；枚举/忽略值在本分区内用自有连接加载，
                    //Driver不持有任何数据级配置内容，避免collect撑爆Driver堆
                    List<Row> fieldRows = bcField.value();
                    HashMap<Integer, HashMap<String, String>> enumMap = loadEnumMap(connectionInner, enumIndexCsv);
                    Map<Integer, Set<String>> ignoreData = loadIgnoreMap(connectionInner, ignoreIdCsv);
                    //批量执行
                    int batchExecute = parseBatchSize(args[bcompComit]);

                    while (tuple.hasNext()) {
                        Tuple2<String, Tuple2<Iterable<String[]>, Iterable<String[]>>> next = tuple.next();
                        String key = next._1;
                        //同一主键(索引字段)下可能有重复记录，全部取出后统一配对，避免只比对首条而漏掉其余记录
                        List<String[]> oldRows = new ArrayList<>();
                        next._2()._1().iterator().forEachRemaining(oldRows::add);
                        List<String[]> newRows = new ArrayList<>();
                        next._2()._2().iterator().forEachRemaining(newRows::add);
                        //读取行数分区内累加：替代单独count()，避免为第二遍遍历而落盘缓存
                        READ_OLD_COUNT.add(oldRows.size());
                        READ_NEW_COUNT.add(newRows.size());
                        //源表同主键存在重复记录时计数，逐组明细只在debug级别输出（大表场景避免刷屏）
                        if (oldRows.size() > 1 || newRows.size() > 1) {
                            DUP_KEY_GROUP_COUNT.add(1);
                            log.debug("主键{}存在重复记录：老侧{}条，新侧{}条，按排序位置配对逐条比对", key, oldRows.size(), newRows.size());
                        }

                        //新系统无、老系统有：全部记为信创少
                        if (newRows.isEmpty()) {
                            for (String[] o : oldRows) {
                                insertRowToDtl(OLD_EXISTS_NEW_NOT, finalPs1, args, key, o, null, "信创少");
                                OLD_EXISTS_NEW_NOT_COUNT.add(1);
                                if (++count >= batchExecute) { flushBatch(finalPs1); count = 0; }
                            }
                            continue;
                        }
                        //老系统无、新系统有：全部记为生产少
                        if (oldRows.isEmpty()) {
                            for (String[] n : newRows) {
                                insertRowToDtl(OLD_NOT_NEW_EXISTS, finalPs1, args, key, null, n, "生产少");
                                OLD_NOT_NEW_EXISTS_COUNT.add(1);
                                if (++count >= batchExecute) { flushBatch(finalPs1); count = 0; }
                            }
                            continue;
                        }

                        //两侧都有记录
                        if (oldRows.size() == 1 && newRows.size() == 1) {
                            //单行对单行：直接逐字段比对，有差异记FAILED
                            ArrayList<String> diffNameList = new ArrayList<>();
                            boolean diff = compareRows(fieldRows, ignoreData, enumMap, oldRows.get(0), newRows.get(0), diffNameList);
                            if (diff) {
                                insertRowToDtl(FAILED, finalPs1, args, key, oldRows.get(0), newRows.get(0), String.join(",", diffNameList));
                                FAILED_COUNT.add(1);
                                if (++count >= batchExecute) { flushBatch(finalPs1); count = 0; }
                            } else {
                                SUCCESS_COUNT.add(1);
                            }
                        } else {
                            //同主键多行：按行内容哈希做多重集抵消——哈希相同即同一条记录，视为一致(计入成功)不出明细；
                            //哈希对不上的不再互配成差异，老侧记信创少、新侧记生产少；
                            //同时把哈希拼进COMP_INDEX_STR，结果页按(主键)查询时每条记录各自独立、互不串显
                            String[] oldHashes = new String[oldRows.size()];
                            for (int i = 0; i < oldRows.size(); i++) {
                                oldHashes[i] = rowHash(fieldRows, oldRows.get(i));
                            }
                            String[] newHashes = new String[newRows.size()];
                            Map<String, Integer> newHashCount = new HashMap<>();
                            for (int i = 0; i < newRows.size(); i++) {
                                newHashes[i] = rowHash(fieldRows, newRows.get(i));
                                newHashCount.merge(newHashes[i], 1, Integer::sum);
                            }
                            List<String[]> leftOld = new ArrayList<>();
                            List<String> leftOldHash = new ArrayList<>();
                            for (int i = 0; i < oldRows.size(); i++) {
                                Integer c = newHashCount.get(oldHashes[i]);
                                if (c != null && c > 0) {
                                    newHashCount.put(oldHashes[i], c - 1);
                                    SUCCESS_COUNT.add(1);
                                } else {
                                    leftOld.add(oldRows.get(i));
                                    leftOldHash.add(oldHashes[i]);
                                }
                            }
                            Map<String, Integer> oldHashCount = new HashMap<>();
                            for (String h : oldHashes) {
                                oldHashCount.merge(h, 1, Integer::sum);
                            }
                            List<String[]> leftNew = new ArrayList<>();
                            List<String> leftNewHash = new ArrayList<>();
                            for (int i = 0; i < newRows.size(); i++) {
                                Integer c = oldHashCount.get(newHashes[i]);
                                if (c != null && c > 0) {
                                    oldHashCount.put(newHashes[i], c - 1);
                                } else {
                                    leftNew.add(newRows.get(i));
                                    leftNewHash.add(newHashes[i]);
                                }
                            }
                            //老系统多出的记录：信创少
                            for (int i = 0; i < leftOld.size(); i++) {
                                insertRowToDtl(OLD_EXISTS_NEW_NOT, finalPs1, args, key + HASH_SEP + leftOldHash.get(i), leftOld.get(i), null, "信创少");
                                OLD_EXISTS_NEW_NOT_COUNT.add(1);
                                if (++count >= batchExecute) { flushBatch(finalPs1); count = 0; }
                            }
                            //新系统多出的记录：生产少
                            for (int i = 0; i < leftNew.size(); i++) {
                                insertRowToDtl(OLD_NOT_NEW_EXISTS, finalPs1, args, key + HASH_SEP + leftNewHash.get(i), null, leftNew.get(i), "生产少");
                                OLD_NOT_NEW_EXISTS_COUNT.add(1);
                                if (++count >= batchExecute) { flushBatch(finalPs1); count = 0; }
                            }
                        }
                    }
                } finally {
                    if (finalPs1 != null) {
                        finalPs1.executeBatch();
                        finalPs1.clearBatch();
                        finalPs1.close();
                    }
                    finalPs5.close();
                    connectionInner.close();
                }
            });

            //比对结束：打印两侧实际读取行数并回写任务表。若与源表行数不符（被取数SQL放大成倍数、或少行），
            //可直接据此判定问题在取数SQL而非比对/写入环节
            long countOld = READ_OLD_COUNT.value();
            long countNew = READ_NEW_COUNT.value();
            log.info("读取行数：老侧={}行，新侧={}行", countOld, countNew);
            insertLineCount(countOld, countNew, finalPs2, args);
            //源表同主键多行的分组数：这类分组按排序位置配对逐对出明细，
            //是结果页出现多条相似明细的正常来源（源表本身有重复记录），不是重复插入
            log.info("源表同主键重复记录分组数：{}", DUP_KEY_GROUP_COUNT.value());

            Long oldFaileCount = FAILED_COUNT.value() + OLD_NOT_NEW_EXISTS_COUNT.value();
            Long newFaileCount = FAILED_COUNT.value() + OLD_EXISTS_NEW_NOT_COUNT.value();
            Long successCount = SUCCESS_COUNT.value();
            insertToBcompTask(oldFaileCount,newFaileCount,successCount,finalPs4,args);


        } catch (Exception e) {
            log.error("Spark运行失败，错误：" + e);
            finalPs3.setString(1, "4");
            String message = e.getMessage().length() > 3950 ? e.getMessage().substring(0, 3950) : e.getMessage();
            finalPs3.setString(2, message);
            finalPs3.setString(3, "1");
            finalPs3.setInt(4, Integer.valueOf(args[bcompIndex]));
            finalPs3.setString(5, args[taskBusiDate]);
            finalPs3.setString(6, args[taskBusiBatch]);
            finalPs3.setString(7, args[bcompTimeNode]);
            finalPs3.executeUpdate();
            throw new RuntimeException("Spark运行失败，错误:" + e);
        } finally {
            if (finalPs3 != null) {
                finalPs3.close();
            }
            if (finalPs2 != null) {
                finalPs2.close();
            }
            if (finalPs4 != null) {
                finalPs4.close();
            }
            connectionOut.close();
        }

        sc.close();
    }

    /**
     * 分区内加载枚举映射：只取配置中用到的CONV_ENUM_INDEX。
     * 枚举表可能是数据级规模，Driver端collect会撑爆Driver堆，故改为每个task用自有连接加载。
     * @param conn 回放库连接
     * @param enumIndexCsv 配置中用到的枚举索引逗号串，为空表示未配置枚举字段
     * @return 枚举映射，未配置时返回空map(compareEnumType按原值比对)
     */
    private static HashMap<Integer, HashMap<String, String>> loadEnumMap(Connection conn, String enumIndexCsv) throws SQLException {
        HashMap<Integer, HashMap<String, String>> map = new HashMap<>();
        if (enumIndexCsv == null || enumIndexCsv.isEmpty()) {
            return map;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "select CONV_ENUM_INDEX, ORIG_ENUM_VALUE, DEST_ENUM_VALUE from tss_bcomp_enum where CONV_ENUM_INDEX in (" + enumIndexCsv + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                //同一CONV_ENUM_INDEX下的多行映射必须合并，逐行new单对map会互相覆盖
                map.computeIfAbsent(rs.getInt(1), k -> new HashMap<>())
                        .put(rs.getString(2), rs.getString(3));
            }
        }
        return map;
    }

    /**
     * 分区内加载忽略值：只取配置中用到的IGNORE_ID，值集合用Set保证contains为O(1)。
     * @param conn 回放库连接
     * @param ignoreIdCsv 配置中用到的忽略值ID逗号串，为空表示未配置忽略值
     */
    private static Map<Integer, Set<String>> loadIgnoreMap(Connection conn, String ignoreIdCsv) throws SQLException {
        Map<Integer, Set<String>> map = new HashMap<>();
        if (ignoreIdCsv == null || ignoreIdCsv.isEmpty()) {
            return map;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "select IGNORE_ID, IGNORE_VALUE from tss_ignore_value where IGNORE_ID in (" + ignoreIdCsv + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getInt(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        return map;
    }

    /**
     * 获取tss_bcomp_field_new数据
     * @param sc  spark
     * @param args 参数数组
     * @return List<Row> tss_bcomp_field_new数据
     * @throws Exception 获取数据异常
     */
    private static List<Row> getTssBcompFieldData(SparkSession sc, String[] args) throws Exception {

        //回放数据库信息
        String tssbcompfieldSql = "(select * from tss_bcomp_field_new where bcomp_index = ? order by FIELD_OLD_INDEX)  tss_bcomp_field_new"  ;
        String finalSql = tssbcompfieldSql.replaceFirst("\\?", String.valueOf(args[bcompIndex]));

        Map<String, String> tssDataSouceProperties = buildJdbcConfig(args[tssDataSourceUrl], args[tssDataSourceUser], args[tssDataSourcePassword], args[tssDataSourceDriver], finalSql);
        Dataset<Row> tssBcompFieldData = sc.read().format("jdbc").options(tssDataSouceProperties).load();

        Dataset<Row> rowDataset = normalizeColumnNames(tssBcompFieldData);
        //获取tssBcompField集合
        List<Row> tssBcompFieldDataRows = rowDataset.collectAsList();
        if (tssBcompFieldDataRows == null || tssBcompFieldDataRows.isEmpty()){
            log.error("tss_bcomp_field_new表中bcomp_index ： {} 配置为空，请检查!",args[bcompIndex]);
            throw new Exception("tss_bcomp_field_new表中bcomp_index ： " + args[bcompIndex] + " 配置为空，请检查!");
        }
        return tssBcompFieldDataRows;
    }


    /**
     * @author Tyc
     * @description
     * @date 2025/12/19 16:54
     * @param oldFaileCount 旧数据失败数
     * @param newFaileCount 新数据失败数
     * @param successCount 成功数据数
     * @param finalPs4 执行语句
     * @param args 数组
     * @return void
     */
    private static void insertToBcompTask(Long oldFaileCount, Long newFaileCount, Long successCount, PreparedStatement finalPs4, String[] args) {

        try {
            finalPs4.setLong(1,successCount);
            finalPs4.setLong(2,oldFaileCount);
            finalPs4.setLong(3,successCount);
            finalPs4.setLong(4,newFaileCount);
            finalPs4.setString(5,TASK_STATE_COMPLETE);
            finalPs4.setString(6,"");
            finalPs4.setString(7,(oldFaileCount == 0 && newFaileCount == 0) ? TASK_RESULT_SUCCESS : TASK_RESULT_FAILED);
            finalPs4.setString(8,getDateTime());
            finalPs4.setInt(9,Integer.parseInt(args[bcompIndex]));
            finalPs4.setString(10, args[taskBusiDate]);
            finalPs4.setString(11, args[taskBusiBatch]);

            finalPs4.executeUpdate();
        } catch (SQLException e) {
            log.error("更新任务状态出错,请检查");
            throw new RuntimeException(e);
        }

    }

    /**
     * @author Tyc
     * @description 比对数值字段
     * @date 2025/12/10 10:46
     * @param row 行数据
     * @param oldString 旧字段内容
     * @param newString 新字段内容
     * @param diffNameList 差异字段名称
     * @param bcompEnumMap  枚举值
     * @return boolean 是否差异
     */
    private static boolean compareEnumType(Row row, String oldString, String newString,
                                           ArrayList<String> diffNameList, HashMap<Integer, HashMap<String, String>> bcompEnumMap) {
        String fieldName = row.getAs("FIELD_NAME");
        Number fieldEnumIndex = row.getAs("FIELD_ENUM_INDEX");
        String AA  = oldString == null ? "" : oldString.trim();
        String BB  = newString == null ? "" : newString.trim();

        if (bcompEnumMap == null || bcompEnumMap.isEmpty()){
            log.error("枚举映射表bcompEnumMap为空，请检查配置！！现按原值进行比对");
            return !AA.equals(BB);
        }

        try {
            //老核心列内容根据枚举配置转换为新核心内容
            String toEnumAA = bcompEnumMap.get(fieldEnumIndex.intValue()).get(AA);
            if (!toEnumAA.equals(BB)){
                log.debug("枚举比对不同");
                log.debug("toEnumAA:{}", toEnumAA);
                log.debug("BB:{}", BB);
                diffNameList.add(fieldName);
                return true;
            }
        }catch (Exception e){
            log.error("老核心列枚举转换错误，列名：{},内容：{}" ,fieldName,AA);
            log.error("列内容枚举转换错误：" , e);
            throw new RuntimeException("枚举转换错误：" , e);
        }

        return false;
    }
    /**
     * @author Tyc
     * @description 比对数值字段
     * @date 2025/12/10 10:46
     * @param row 行数据
     * @param oldString 旧字段内容
     * @param newString 新字段内容
     * @param diffNameList 差异字段名称
     * @return boolean 是否差异
     */
    private static boolean compareDateType(Row row, String oldString, String newString, ArrayList<String> diffNameList) {
        String fieldName = row.getAs("FIELD_NAME");
        try {

            // 定义可能的日期格式
            String[] datePatterns = {
                    "yyyy-MM-dd",
                    "yyyy/MM/dd",
                    "yyyyMMdd",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy/MM/dd HH:mm:ss",
                    "yyyyMMddHHmmss"
            };

            SimpleDateFormat targetFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            // 如果两个字段都为空，则认为一致
            if (oldString == null && newString == null){
                return false;
            }

            Date oldDate = parseDate(oldString, datePatterns);
            Date newDate = parseDate(newString, datePatterns);
            //oldDate和newDate一定有值，否则解析转换失败进入异常
            // 比较日期
            if (!targetFormat.format(oldDate).equals(targetFormat.format(newDate))) {
                diffNameList.add(fieldName);
                return true;
            }
        } catch (Exception e) {
            // 解析异常也认为是不同的日期
            diffNameList.add(fieldName);
            return true;
        }
        return false;

    }


    /**
     * @author Tyc
     * @description 比对数值字段
     * @date 2025/12/10 10:46
     * @param row 行数据
     * @param oldString 旧字段内容
     * @param newString 新字段内容
     * @param diffNameList 差异字段名称
     * @return boolean 是否差异
     */
    private static boolean compareDouble(Row row, String oldString, String newString, ArrayList<String> diffNameList) {
        String fieldName = row.getAs("FIELD_NAME");
        if ((StringUtils.isEmpty(oldString)&&StringUtils.isNotEmpty(newString)) ||
                (StringUtils.isNotEmpty(oldString)&&StringUtils.isEmpty(newString))){
            //差异
            diffNameList.add(fieldName);
            return true;
        }

        if (StringUtils.isNotEmpty(oldString) && StringUtils.isNotEmpty(newString)){
            try {
                BigDecimal oldDecimal = new BigDecimal(oldString);
                BigDecimal newDecimal = new BigDecimal(newString);

                if (oldDecimal.compareTo(newDecimal) != 0) {
                    log.debug("数值比对不同");
                    log.debug("oldDecimal:{}", oldDecimal);
                    log.debug("newDecimal:{}", newDecimal);
                    //差异
                    diffNameList.add(fieldName);
                    return true;
                }
            }catch (Exception e){
                //解析异常也认为是不同的日期
                diffNameList.add(fieldName);
                return true;
            }

        }
        return false;
    }


    /**
     * @author Tyc
     * @description 日期类型字段比较
     * @date 2025/12/10 10:22
     * @param row 字段配置信息行
     * @param oldString 老系统字段值
     * @param newString 新系统字段值
     * @param diffNameList 差异字段名称列表
     * @return boolean 是否存在差异
     */
    private static boolean compareString(Row row, String oldString, String newString, ArrayList<String> diffNameList) {
        String fieldName = row.getAs("FIELD_NAME");
        String aA = oldString == null ? "" : oldString.trim();
        String bB = newString == null ? "" : newString.trim();
        if (!aA.equals(bB)) {
            log.debug("字符串比对不同");
            log.debug("AA:{}", aA);
            log.debug("BB:{}", bB);
            diffNameList.add(fieldName);
            return true;
        }
        return false;
    }

    /**
     * 从 tss_bcomp_field_new 数据中获取分区列名（FIELD_PARTITION_FLAG='1'的字段）
     * @param tssBcompFieldDataRows tss_bcomp_field_new表数据
     * @return String 分区列名，未配置时返回null
     */
    private static String getPartitionColumn(List<Row> tssBcompFieldDataRows) {
        for (Row row : tssBcompFieldDataRows) {
            String partitionFlag = row.getAs("FIELD_PARTITION_FLAG");
            if ("1".equals(partitionFlag)) {
                String fieldName = row.getAs("FIELD_NAME");
                log.info("从tss_bcomp_field_new获取分区列: {}", fieldName);
                return fieldName;
            }
        }
        log.info("tss_bcomp_field_new中未配置分区列(FIELD_PARTITION_FLAG='1')");
        return null;
    }

    /**
     * 读取分片配置：分区数(BCOMP_PARTITION_NUM) + 分片策略(BCOMP_SHARD_STRATEGY)。
     * BCOMP_SHARD_STRATEGY 列可能不存在（DDL 未执行），此时捕获 SQLException 降级为 RANGE，只读分区数。
     * @param args 参数数组
     * @param defaultParallelism 集群默认并行度，用于分区数自适应
     * @return ShardConf 分片配置
     */
    static ShardConf readShardConf(String[] args, int defaultParallelism) {
        Object configuredNum = null;
        String strategy = SHARD_RANGE;
        boolean strategyRead = false;
        try {
            Class.forName(args[tssDataSourceDriver]);
            try (Connection conn = DriverManager.getConnection(args[tssDataSourceUrl], args[tssDataSourceUser], args[tssDataSourcePassword])) {
                // Read strategy first: once HASH is known, neither invalid counts nor query failures may fall back.
                try (PreparedStatement ps = conn.prepareStatement("SELECT BCOMP_SHARD_STRATEGY FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX = ?")) {
                    ps.setString(1, args[bcompIndex]);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String value = rs.getString(1);
                            if (value != null && !value.trim().isEmpty()) strategy = value.trim().toUpperCase(Locale.ROOT);
                        }
                    }
                } catch (SQLException e) {
                    if (!isMissingShardStrategyColumn(e)) throw e;
                    log.info("TSS_BCOMP_CONF_NEW缺少BCOMP_SHARD_STRATEGY，使用legacy RANGE: {}", e.getMessage());
                }
                strategyRead = true;
                try (PreparedStatement ps = conn.prepareStatement("SELECT BCOMP_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX = ?")) {
                    ps.setString(1, args[bcompIndex]);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) configuredNum = "HASH".equals(strategy) ? rs.getObject(1) : rs.getInt(1);
                    }
                }
            }
        } catch (Exception e) {
            if (!strategyRead || "HASH".equals(strategy)) throw new IllegalArgumentException("分片策略或HASH配置读取失败", e);
            log.warn("读取分片配置失败，使用默认分区数: {}", e.getMessage());
        }
        if (!SHARD_RANGE.equals(strategy) && !SHARD_NTILE.equals(strategy) && !"HASH".equals(strategy))
            throw new IllegalArgumentException("不支持的分片策略: " + strategy);
        int partitionNum;
        if ("HASH".equals(strategy)) {
            partitionNum = PrimaryKeyHashPlanner.partitionCount(configuredNum);
        } else {
            int legacyNum = 0;
            try { if (configuredNum != null) legacyNum = new BigDecimal(configuredNum.toString()).intValue(); }
            catch (NumberFormatException ignored) { }
            partitionNum = resolvePartitionNum(legacyNum, defaultParallelism);
        }
        log.info("分片配置: 策略={}, 分区数={}（配置值={}, 集群并行度={}）", strategy, partitionNum, configuredNum, defaultParallelism);
        return new ShardConf(partitionNum, strategy);
    }

    /** Only a vendor-reported missing strategy column identifies the supported pre-migration schema. */
    private static boolean isMissingShardStrategyColumn(SQLException error) {
        String state = error.getSQLState();
        boolean missingColumn = "42703".equals(state) || "42S22".equals(state)
                || error.getErrorCode() == 904 || error.getErrorCode() == 42122;
        // H2 appends the entire SQL on later lines: do not mistake a missing BCOMP_INDEX for this column.
        String detail = Objects.toString(error.getMessage(), "").split("\n", 2)[0].toUpperCase(Locale.ROOT);
        return missingColumn && detail.contains("BCOMP_SHARD_STRATEGY");
    }

    /**
     * 计算最终分区数：配置了正值则用配置值；否则按集群默认并行度自适应，上限 MAX_PARTITION_NUM。
     */
    private static int resolvePartitionNum(int configuredNum, int defaultParallelism) {
        if (configuredNum > 0) {
            return configuredNum;
        }
        int base = defaultParallelism > 0 ? defaultParallelism : DEFAULT_PARTITION_NUM;
        return Math.min(MAX_PARTITION_NUM, Math.max(1, base));
    }



    /**
     * 替换SQL中的占位符${BCOMP_BUSI_DATE}为实际日期值
     * @param sql 原始SQL
     * @param dateVal 日期值
     * @return String 替换后的SQL
     */
    private static String replaceSqlPlaceholders(String sql, String dateVal) {
        if (dateVal != null && !dateVal.isEmpty()) {
            return sql.replace("${HDRQ}", dateVal);
        }
        return sql;
    }

    /**
     * 解析新老两侧的读取计划。HASH必须成功构建；legacy策略保留按侧降级行为。
     */
    static ReadPlan resolveReadPlan(String[] args, List<Row> fields, ShardConf conf) throws Exception {
        if ("HASH".equalsIgnoreCase(conf.strategy)) {
            PrimaryKeyHashPlanner.partitionCount(conf.partitionNum);
            // This branch is deliberately outside legacy fallback catches and does not read FIELD_PARTITION_FLAG.
            List<Integer> oldKeys = PrimaryKeyHashPlanner.keyPositions(fields, FIELD_OLD_INDEX);
            List<Integer> newKeys = PrimaryKeyHashPlanner.keyPositions(fields, FIELD_NEW_INDEX);
            SidePlan oldSide = hashSidePlan("老系统", args[OLD_URL_INDEX], args[OLD_USER_INDEX],
                    args[OLD_PASSWORD_INDEX], args[OLD_DRIVER_INDEX], args[OLD_TABLE_INDEX], oldKeys, conf.partitionNum);
            SidePlan newSide = hashSidePlan("新系统", args[NEW_URL_INDEX], args[NEW_USER_INDEX],
                    args[NEW_PASSWORD_INDEX], args[NEW_DRIVER_INDEX], args[NEW_TABLE_INDEX], newKeys, conf.partitionNum);
            return new ReadPlan(oldSide, newSide);
        }
        String partitionColumn = getPartitionColumn(fields);
        SidePlan oldSide = resolveSidePlan("老系统",
                args[OLD_URL_INDEX], args[OLD_USER_INDEX], args[OLD_PASSWORD_INDEX],
                args[OLD_DRIVER_INDEX], args[OLD_TABLE_INDEX], partitionColumn, conf);
        SidePlan newSide = resolveSidePlan("新系统",
                args[NEW_URL_INDEX], args[NEW_USER_INDEX], args[NEW_PASSWORD_INDEX],
                args[NEW_DRIVER_INDEX], args[NEW_TABLE_INDEX], partitionColumn, conf);
        return new ReadPlan(oldSide, newSide);
    }

    private static SidePlan hashSidePlan(String side, String url, String user, String password,
                                         String driver, String source, List<Integer> keys, int count) throws Exception {
        Class.forName(driver);
        String[] predicates;
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            predicates = PrimaryKeyHashPlanner.predicates(connection, source, keys, count);
        }
        String mode = "HASH主键读取(" + count + "分区)";
        log.info("{}表使用{}", side, mode);
        if (predicates == null) return SidePlan.ofOptions(side, mode, buildJdbcConfig(url, user, password, driver, source));
        return SidePlan.ofPredicates(side, mode, url, source, buildConnProps(user, password, driver), predicates);
    }

    /**
     * 为单侧解析读取计划：依次完成分区列存在性、类型探测、NTILE/RANGE 分片、边界校验、
     * 日期字面量方言探针，任一不通过即生成普通JDBC读取的 SidePlan 并记录降级原因（配置期一次性硬回退，
     * 不做运行时去参重试，避免日志显示分区成功、实际退化为单连接全量扫描的静默性能故障）。
     */
    private static SidePlan resolveSidePlan(String sideDesc, String url, String user, String password,
                                            String driver, String dbTable, String partitionColumn, ShardConf conf) {
        if (partitionColumn == null || partitionColumn.isEmpty()) {
            return normalSidePlan(sideDesc, url, user, password, driver, dbTable, "未配置分区列");
        }
        try {
            if (!columnExists(url, user, password, driver, dbTable, partitionColumn)) {
                return normalSidePlan(sideDesc, url, user, password, driver, dbTable, "分区列" + partitionColumn + "不存在");
            }
            int colType = resolvePartitionColumnType(url, user, password, driver, dbTable, partitionColumn);
            if (colType == COL_TYPE_UNSUPPORTED) {
                return normalSidePlan(sideDesc, url, user, password, driver, dbTable,
                        "分区列" + partitionColumn + "类型不支持(仅数值/DATE/TIMESTAMP)");
            }
            // NTILE 仅对数值列生效
            if (SHARD_NTILE.equalsIgnoreCase(conf.strategy) && colType == COL_TYPE_NUMERIC) {
                String[] predicates = buildNtilePredicates(url, user, password, driver, dbTable, partitionColumn, conf.partitionNum);
                if (predicates != null && predicates.length > 0) {
                    Properties props = buildConnProps(user, password, driver);
                    String mode = "NTILE谓词分片(列=" + partitionColumn + "," + predicates.length + "片)";
                    log.info("{}表使用{}", sideDesc, mode);
                    return SidePlan.ofPredicates(sideDesc, mode, url, dbTable, props, predicates);
                }
                log.info("{}表NTILE分片点不足，降级为RANGE等分", sideDesc);
            }
            // RANGE bounds
            Bounds bounds = getPartitionBounds(url, user, password, driver, dbTable, partitionColumn, colType);
            if (bounds == null || !bounds.ascending) {
                String reason = bounds == null ? "边界获取失败"
                        : "边界不升序(lower=" + bounds.lower + ",upper=" + bounds.upper + ")";
                return normalSidePlan(sideDesc, url, user, password, driver, dbTable, reason);
            }
            // 日期/时间戳列需实测字面量方言兼容性（Spark把边界渲染成裸单引号字面量下推，不做方言包装）
            if (colType == COL_TYPE_DATE || colType == COL_TYPE_TIMESTAMP) {
                if (!probeBoundLiteral(url, user, password, driver, dbTable, partitionColumn, bounds.lower)) {
                    return normalSidePlan(sideDesc, url, user, password, driver, dbTable, "日期字面量不被数据库接受(NLS格式?)");
                }
                if (bounds.span <= 1) {
                    log.warn("{}分区列{}为日期类型且值域跨度仅{}（如只核对单日），RANGE分区数会被Spark截断为1，等价单分区读取；建议改用数值列或TIMESTAMP列分区",
                            sideDesc, partitionColumn, bounds.span);
                }
            }
            int num = (int) Math.min(conf.partitionNum, Math.max(1L, bounds.span));
            Map<String, String> options = buildPartitionedJdbcConfig(url, user, password, driver, dbTable,
                    partitionColumn, num, bounds);
            String mode = "RANGE分区(列=" + partitionColumn + "," + num + "分区,[" + bounds.lower + "," + bounds.upper + "))";
            log.info("{}表使用{}", sideDesc, mode);
            return SidePlan.ofOptions(sideDesc, mode, options);
        } catch (Exception e) {
            log.warn("{}分区读取配置构建异常，回退为普通JDBC读取: {}", sideDesc, e.getMessage(), e);
            return normalSidePlan(sideDesc, url, user, password, driver, dbTable, "构建异常:" + e.getMessage());
        }
    }

    /**
     * 构建普通（单连接）JDBC读取的 SidePlan，modeDesc 记录降级原因。
     */
    private static SidePlan normalSidePlan(String sideDesc, String url, String user, String password,
                                           String driver, String dbTable, String reason) {
        String mode = "普通JDBC读取(" + reason + ")";
        log.info("{}表{}", sideDesc, mode);
        return SidePlan.ofOptions(sideDesc, mode, buildJdbcConfig(url, user, password, driver, dbTable));
    }

    /**
     * 按 SidePlan 实际读取一侧数据：predicates 走 spark.read().jdbc(url,table,predicates,props)，
     * 其余（普通/RANGE）走 options map 加载。
     */
    private static JavaRDD<Row> readSide(SparkSession sc, SidePlan plan) {
        Dataset<Row> df;
        if (plan.usePredicates) {
            df = sc.read().jdbc(plan.url, plan.dbTable, plan.predicates, plan.connProps);
        } else {
            df = sc.read().format("jdbc").options(plan.options).load();
        }
        return df.toJavaRDD();
    }

    /**
     * 构建 predicates 读取所需的 JDBC 连接属性。
     */
    private static Properties buildConnProps(String user, String password, String driver) {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("driver", driver);
        props.setProperty("fetchsize", "1000");
        props.setProperty("connectTimeout", "60000");
        props.setProperty("socketTimeout", "300000");
        // MySQL Connector/J and OceanBase need cursor fetching for a positive fetch size to stream rows.
        if (requiresCursorFetch(driver)) {
            props.setProperty("useCursorFetch", "true");
        }
        return props;
    }

    /** Positive fetch size requires server-side cursors for MySQL Connector/J and OceanBase. */
    private static boolean requiresCursorFetch(String driver) {
        String name = Objects.toString(driver, "").toLowerCase(Locale.ROOT);
        return name.contains("oceanbase") || name.equals("com.mysql.cj.jdbc.driver") || name.equals("com.mysql.jdbc.driver");
    }

    /**
     * 把本次核对两侧「实际采用」的读取策略回写 TSS_BCOMP_TASK_NEW.BCOMP_READ_MODE。
     * 该列可能不存在（DDL 未执行），此时只告警不影响核对，保证生产环境无 Spark 日志重定向时运维仍可见降级事实。
     */
    private static void writeReadMode(Connection conn, ReadPlan plan, String[] args) {
        String desc = plan.describe();
        try (PreparedStatement ps = conn.prepareStatement("update tss_bcomp_task_new set BCOMP_READ_MODE = ? " +
                "where BCOMP_INDEX = ? and TASK_BUSI_DATE = ? and TASK_BATCH = ? and BCOMP_TIME_NODE = ?")) {
            ps.setString(1, desc);
            ps.setString(2, args[bcompIndex]);
            ps.setString(3, args[taskBusiDate]);
            ps.setString(4, args[taskBusiBatch]);
            ps.setString(5, args[bcompTimeNode]);
            ps.executeUpdate();
            log.info("回写读取策略BCOMP_READ_MODE: {}", desc);
        } catch (SQLException e) {
            log.warn("回写BCOMP_READ_MODE失败（列可能不存在，DDL未执行?）: {}", e.getMessage());
        }
    }

    /** 分片配置：分区数 + 策略（RANGE/NTILE/HASH）。 */
    static class ShardConf {
        final int partitionNum;
        final String strategy;
        ShardConf(int partitionNum, String strategy) {
            this.partitionNum = partitionNum;
            this.strategy = strategy;
        }
    }

    /** 分区列值域边界。lower/upper 为渲染用字面量（数值为数字，日期为ISO字符串），span 为值域跨度。 */
    static class Bounds {
        String lower;
        String upper;
        long span;
        boolean ascending;
    }

    /** 单侧读取计划：options 路径（普通/RANGE）或 predicates 路径（NTILE/HASH）二选一。 */
    static class SidePlan {
        final String sideDesc;
        final String modeDesc;
        final boolean usePredicates;
        final Map<String, String> options;
        final String url;
        final String dbTable;
        final Properties connProps;
        final String[] predicates;

        private SidePlan(String sideDesc, String modeDesc, boolean usePredicates, Map<String, String> options,
                         String url, String dbTable, Properties connProps, String[] predicates) {
            this.sideDesc = sideDesc;
            this.modeDesc = modeDesc;
            this.usePredicates = usePredicates;
            this.options = options;
            this.url = url;
            this.dbTable = dbTable;
            this.connProps = connProps;
            this.predicates = predicates;
        }

        static SidePlan ofOptions(String sideDesc, String modeDesc, Map<String, String> options) {
            return new SidePlan(sideDesc, modeDesc, false, options, null, null, null, null);
        }

        static SidePlan ofPredicates(String sideDesc, String modeDesc, String url, String dbTable,
                                     Properties connProps, String[] predicates) {
            return new SidePlan(sideDesc, modeDesc, true, null, url, dbTable, connProps, predicates);
        }
    }

    /** 两侧读取计划，describe() 用于回写 BCOMP_READ_MODE（截断到 490 字符）。 */
    static class ReadPlan {
        final SidePlan oldSide;
        final SidePlan newSide;
        ReadPlan(SidePlan oldSide, SidePlan newSide) {
            this.oldSide = oldSide;
            this.newSide = newSide;
        }
        String describe() {
            String d = "老系统=" + oldSide.modeDesc + "; 新系统=" + newSide.modeDesc;
            return d.length() > 490 ? d.substring(0, 490) : d;
        }
    }

    /**
     * 探测分区列类型：数值 / DATE / TIMESTAMP / 不支持。
     * Spark 3.4+ 的 JDBC partitionColumn 原生支持 numeric/date/timestamp 三类，其余类型会抛 invalidPartitionColumnTypeError，
     * 故这里提前拦截并降级。
     * @return COL_TYPE_NUMERIC / COL_TYPE_DATE / COL_TYPE_TIMESTAMP / COL_TYPE_UNSUPPORTED
     */
    private static int resolvePartitionColumnType(String url, String user, String password,
                                                  String driver, String dbTable, String columnName) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName(driver);
            conn = openReadOnlyConnection(url, user, password);
            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT " + columnName + " FROM " + dbTable + " WHERE 1=0");
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (meta.getColumnName(i).equalsIgnoreCase(columnName)) {
                    int t = meta.getColumnType(i);
                    int result;
                    if (t == Types.INTEGER || t == Types.BIGINT || t == Types.SMALLINT || t == Types.TINYINT
                            || t == Types.NUMERIC || t == Types.DECIMAL || t == Types.FLOAT || t == Types.DOUBLE || t == Types.REAL) {
                        result = COL_TYPE_NUMERIC;
                    } else if (t == Types.DATE) {
                        result = COL_TYPE_DATE;
                    } else if (t == Types.TIMESTAMP || t == Types.TIMESTAMP_WITH_TIMEZONE) {
                        result = COL_TYPE_TIMESTAMP;
                    } else {
                        result = COL_TYPE_UNSUPPORTED;
                    }
                    log.info("分区列{}的SQL类型:{}(Types={}), 判定结果:{}", columnName, meta.getColumnTypeName(i), t, result);
                    return result;
                }
            }
            log.warn("未找到分区列{}的元数据，按不支持处理", columnName);
            return COL_TYPE_UNSUPPORTED;
        } catch (Exception e) {
            log.warn("检测分区列{}类型失败，按不支持处理: {}", columnName, e.getMessage());
            return COL_TYPE_UNSUPPORTED;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
            try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * 日期字面量方言探针：实连库试跑一条 WHERE col >= '字面量'（maxRows=1），
     * 验证数据库会话能否接受 Spark 下推的 ISO 字面量（取决于 NLS_DATE_FORMAT/NLS_TIMESTAMP_FORMAT），
     * 不匹配时报 ORA-01861，必须实测而不能假设。
     */
    private static boolean probeBoundLiteral(String url, String user, String password,
                                             String driver, String dbTable, String columnName, String literal) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName(driver);
            conn = openReadOnlyConnection(url, user, password);
            stmt = conn.createStatement();
            stmt.setMaxRows(1);
            String probeSql = "SELECT " + columnName + " FROM " + dbTable + " WHERE " + columnName + " >= '" + literal + "'";
            rs = stmt.executeQuery(probeSql);
            return true;
        } catch (Exception e) {
            log.warn("日期字面量探针失败(列={}, 字面量={}): {}", columnName, literal, e.getMessage());
            return false;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
            try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * 用 NTILE(N) OVER (ORDER BY col) 取分位数做分片点，生成行数天然均衡、能命中普通索引的范围谓词。
     * 仅对数值列适用；分片点不足或查询失败时返回 null，由调用方降级为 RANGE。
     * 谓词互斥且完备：各范围两两无交集，并额外补一片 col IS NULL 兑住空值（predicates 模式不会自动处理 NULL）。
     */
    private static String[] buildNtilePredicates(String url, String user, String password,
                                                 String driver, String dbTable, String columnName, int numPartitions) {
        if (numPartitions < 2) {
            return null;
        }
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName(driver);
            conn = openReadOnlyConnection(url, user, password);
            stmt = conn.createStatement();
            String sql = "SELECT nt, MIN(" + columnName + ") AS mn FROM ("
                    + "SELECT " + columnName + ", NTILE(" + numPartitions + ") OVER (ORDER BY " + columnName + ") AS nt FROM " + dbTable
                    + ") GROUP BY nt ORDER BY nt";
            log.info("NTILE分片采样SQL: {}", sql);
            rs = stmt.executeQuery(sql);
            List<BigDecimal> splitPoints = new ArrayList<>();
            boolean first = true;
            while (rs.next()) {
                BigDecimal mn = rs.getBigDecimal(2);
                if (mn == null) {
                    continue;
                }
                if (first) {
                    // 第一个tile的MIN是全局最小值，不作为切分点
                    first = false;
                    continue;
                }
                splitPoints.add(mn);
            }
            if (splitPoints.isEmpty()) {
                return null;
            }
            List<String> predicates = new ArrayList<>();
            predicates.add(columnName + " < " + splitPoints.get(0).toPlainString());
            for (int i = 0; i < splitPoints.size() - 1; i++) {
                predicates.add(columnName + " >= " + splitPoints.get(i).toPlainString()
                        + " AND " + columnName + " < " + splitPoints.get(i + 1).toPlainString());
            }
            predicates.add(columnName + " >= " + splitPoints.get(splitPoints.size() - 1).toPlainString());
            predicates.add(columnName + " IS NULL");
            return predicates.toArray(new String[0]);
        } catch (Exception e) {
            log.warn("NTILE分片构建失败，降级为RANGE: {}", e.getMessage());
            return null;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
            try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * 打开源库（老/新系统）只读连接：连接级setReadOnly由数据库强制只读，
     * 从连接层面杠绝核对任务对源表的一切误写可能（源表数据不允许被核对任务改动）。
     * 驱动不支持setReadOnly时只告警不中断（后续语句本身均为SELECT）。
     */
    private static Connection openReadOnlyConnection(String url, String user, String password) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            c.setReadOnly(true);
        } catch (SQLException e) {
            log.warn("源库连接设置只读失败(驱动可能不支持)，仅依赖语句层只读: {}", e.getMessage());
        }
        return c;
    }

    /**
     * 构建jdbc连接参数
     * @param url jdbc连接地址
     * @param user jdbc连接用户名
     * @param password jdbc连接密码
     * @param driver jdbc驱动
     * @param dbTable jdbc表名
     * @return Map<String, String> jdbc连接参数
     */
    private static Map<String, String> buildJdbcConfig(String url, String user, String password, String driver, String dbTable) {
        Map<String, String> config = new HashMap<>();
        config.put("url", url);
        config.put("user", user);
        config.put("password", password);
        config.put("driver", driver);
        config.put("dbtable", dbTable);
        // 控制JDBC每次从数据库拉取的行数，降低驱动内部缓冲压力（OceanBase驱动对大包容易OOM）
        config.put("fetchSize", "1000");
        // MySQL/OceanBase驱动默认可能在executeQuery时把整个结果集装入客户端堆(fetchSize被忽略)，
        // 单分区几十万行即撑爆executor堆；useCursorFetch=true启用服务端游标后fetchSize才生效，按批流式拉取
        if (requiresCursorFetch(driver)) {
            config.put("useCursorFetch", "true");
        }
        // JDBC连接超时配置，防止连接挂起
        config.put("connectTimeout", "60000");
        config.put("socketTimeout", "300000");
        return config;
    }

    /**
     * 检查表/子查询中是否存在指定列
     * @param url jdbc连接地址
     * @param user jdbc连接用户名
     * @param password jdbc连接密码
     * @param driver jdbc驱动
     * @param dbTable 表名或子查询
     * @param columnName 要检查的列名
     * @return boolean 列是否存在
     */
    private static boolean columnExists(String url, String user, String password,
                                        String driver, String dbTable, String columnName) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName(driver);
            conn = openReadOnlyConnection(url, user, password);
            stmt = conn.createStatement();
            String checkSql = "SELECT " + columnName + " FROM " + dbTable + " WHERE 1=0";
            rs = stmt.executeQuery(checkSql);
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (meta.getColumnName(i).equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("检查列{}是否存在失败，回退为普通读取: {}", columnName, e.getMessage());
            return false;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
            try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * 查询分区列的 MIN/MAX 边界，按列类型渲染为 Spark JDBC bounds 所需的字面量。
     * 数值列：直接数字字符串；DATE：yyyy-MM-dd；TIMESTAMP：yyyy-MM-dd HH:mm:ss。
     * Spark 的 JDBCRelation.toInternalBoundValue 按列类型解析：DateType 按天数、TimestampType 按微秒数，
     * 故日期必须传 ISO 字符串而非 epoch 数字；span 用于按值域跨度截断分区数。
     * @return Bounds，获取失败或全为空时返回 null
     */
    private static Bounds getPartitionBounds(String url, String user, String password,
                                             String driver, String dbTable, String partitionColumn, int colType) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName(driver);
            conn = openReadOnlyConnection(url, user, password);
            stmt = conn.createStatement();
            String boundsSql = "SELECT MIN(" + partitionColumn + ") AS MIN_VAL, MAX(" + partitionColumn + ") AS MAX_VAL FROM " + dbTable;
            log.info("查询分区范围SQL: {}", boundsSql);
            rs = stmt.executeQuery(boundsSql);
            if (!rs.next()) {
                return null;
            }
            Bounds b = new Bounds();
            if (colType == COL_TYPE_NUMERIC) {
                BigDecimal min = rs.getBigDecimal("MIN_VAL");
                BigDecimal max = rs.getBigDecimal("MAX_VAL");
                if (min == null || max == null) {
                    return null;
                }
                b.lower = min.toPlainString();
                b.upper = max.toPlainString();
                b.span = max.subtract(min).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
                b.ascending = min.compareTo(max) < 0;
            } else if (colType == COL_TYPE_DATE) {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
                java.sql.Date min = rs.getDate("MIN_VAL");
                java.sql.Date max = rs.getDate("MAX_VAL");
                if (min == null || max == null) {
                    return null;
                }
                b.lower = fmt.format(min);
                b.upper = fmt.format(max);
                b.span = (max.getTime() - min.getTime()) / 86400000L; // 天数
                b.ascending = min.before(max);
            } else {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                java.sql.Timestamp min = rs.getTimestamp("MIN_VAL");
                java.sql.Timestamp max = rs.getTimestamp("MAX_VAL");
                if (min == null || max == null) {
                    return null;
                }
                b.lower = fmt.format(min);
                b.upper = fmt.format(max);
                b.span = (max.getTime() - min.getTime()) * 1000L; // 微秒数（近似，忽略nanos余量）
                b.ascending = min.before(max);
            }
            log.info("分区列{}范围: lower={}, upper={}, span={}, ascending={}", partitionColumn, b.lower, b.upper, b.span, b.ascending);
            return b;
        } catch (Exception e) {
            log.warn("查询分区范围失败: {}", e.getMessage());
            return null;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
            try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * 构建带分区参数的jdbc连接配置（Spark自动按partitionColumn范围拆分为numPartitions个分区并行读取）。
     * lowerBound/upperBound 直接用 Bounds 中已按列类型渲染好的字面量（数值为数字，日期为ISO字符串）。
     */
    private static Map<String, String> buildPartitionedJdbcConfig(String url, String user, String password,
                                                                  String driver, String dbTable,
                                                                  String partitionColumn, int numPartitions,
                                                                  Bounds bounds) {
        Map<String, String> config = buildJdbcConfig(url, user, password, driver, dbTable);
        config.put("partitionColumn", partitionColumn);
        config.put("numPartitions", String.valueOf(numPartitions));
        config.put("lowerBound", bounds.lower);
        config.put("upperBound", bounds.upper);
        return config;
    }

    /**
     * 逐字段比对两侧的一行记录（跳过忽略字段、套用枚举映射与忽略值），
     * 返回是否存在差异，差异字段名写入 diffNameList。与原本内联在循环里的比对逻辑一致。
     */
    private static boolean compareRows(List<Row> fieldRows, Map<Integer, Set<String>> ignoreData,
                                       HashMap<Integer, HashMap<String, String>> enumMap,
                                       String[] rowA, String[] rowB, ArrayList<String> diffNameList) {
        boolean flag = false;
        for (int i = 0; i < fieldRows.size(); i++) {
            Row row = fieldRows.get(i);
            // 跳过配置了忽略对比的字段
            String fieldIgnoreFlag = row.getAs("FIELD_IGNORE_FLAG");
            if ("1".equals(fieldIgnoreFlag)) {
                continue;
            }
            String fieldType = row.getAs("FIELD_TYPE");
            String fieldIgnoreValue = row.getAs("FIELD_IGNORE_VALUE");
            switch (fieldType) {
                case stringFieldType:
                    if (compareString(row, rowA[i], rowB[i], diffNameList)) { flag = true; }
                    break;
                case doubleFieldType:
                    if (compareDouble(row, rowA[i], rowB[i], diffNameList)) { flag = true; }
                    break;
                case enumFieldType:
                    if (compareEnumType(row, rowA[i], rowB[i], diffNameList, enumMap)) { flag = true; }
                    break;
                case dateFieldType:
                    if (compareDateType(row, rowA[i], rowB[i], diffNameList)) { flag = true; }
                    break;
            }
            //提前配置忽略值
            if (flag && StringUtils.isNotEmpty(fieldIgnoreValue) &&
                    ignoreData != null && !ignoreData.isEmpty() &&
                    ignoreData.containsKey(fieldIgnoreValue)) {
                Set<String> ignoreValues = ignoreData.get(fieldIgnoreValue);
                if (ignoreValues != null && ignoreValues.contains(rowA[i])) {
                    flag = false;
                }
            }
        }
        return flag;
    }

    /**
     * 构建一行的排序签名：仅取参与比对的字段（排除 FIELD_IGNORE_FLAG='1'），
     * 使完全一致的重复记录排序后自然对齐，只在忽略字段上有差异的行也视为同一签名。
     */
    private static String rowSignature(List<Row> fieldRows, String[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldRows.size() && i < row.length; i++) {
            Row fr = fieldRows.get(i);
            if ("1".equals((String) fr.getAs("FIELD_IGNORE_FLAG"))) {
                continue;
            }
            sb.append(i).append('=').append(row[i] == null ? "" : row[i]).append('\u0001');
        }
        return sb.toString();
    }

    /** 行内容哈希用的MessageDigest：实例非线程安全，按线程隔离避免executor内加锁竞争 */
    private static final ThreadLocal<MessageDigest> ROW_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5摘要不可用", e);
        }
    });

    /**
     * 行内容唯一值：对参与比对字段的签名(rowSignature)做MD5取32位16进制。
     * 同主键多行时用它判定两侧是否为同一条记录：哈希相同即一致并抵消，
     * 哈希对不上的按单边缺失处理；同时拼进COMP_INDEX_STR使同主键的多条明细在结果页逐条独立。
     */
    private static String rowHash(List<Row> fieldRows, String[] row) {
        MessageDigest md = ROW_DIGEST.get();
        md.reset();
        byte[] d = md.digest(rowSignature(fieldRows, row).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 批量写入 TSS_BCOMP_DTL_NEW：col5=LINE_CONTENT1(老), col6=LINE_CONTENT2(新), col7=LINE_RESULT, col8=FDTL_REASON。
     * oldRow/newRow 为 null 时对应侧内容置空串。
     */
    private static void insertRowToDtl(String flag, PreparedStatement ps, String[] args, String key,
                                       String[] oldRow, String[] newRow, String reason) throws SQLException {
        switch (flag) {
            case OLD_NOT_NEW_EXISTS:
                ps.setString(5, "");
                ps.setString(6, String.join(filedSplit, newRow));
                ps.setString(7, OLD_NOT_NEW_EXISTS);
                ps.setString(8, reason);
                break;
            case OLD_EXISTS_NEW_NOT:
                ps.setString(5, String.join(filedSplit, oldRow));
                ps.setString(6, "");
                ps.setString(7, OLD_EXISTS_NEW_NOT);
                ps.setString(8, reason);
                break;
            case FAILED:
                ps.setString(5, String.join(filedSplit, oldRow));
                ps.setString(6, String.join(filedSplit, newRow));
                ps.setString(7, FAILED);
                ps.setString(8, reason);
                break;
            default:
                return;
        }
        ps.setInt(1, Integer.parseInt(args[bcompIndex]));
        ps.setString(2, args[taskBusiDate]);
        ps.setString(3, args[taskBusiBatch]);
        ps.setString(4, key);
        ps.addBatch();
    }

    /**
     * 执行并清空当前批次，失败只记录日志不中断整批处理。
     */
    private static void flushBatch(PreparedStatement ps) {
        try {
            ps.executeBatch();
            ps.clearBatch();
        } catch (SQLException e) {
            log.error("批量写入TSS_BCOMP_DTL_NEW失败: {}", e.getMessage());
        }
    }

    /**
     *@author Tyc
     *@description 文件总行数写入数据库
     *@date 2023/6/13 14:02
     *@param
     *@return void
     */
    private static void insertLineCount(long count1,long count2,PreparedStatement finalPs2,String[] args) throws SQLException {
        //将文件总数写入task表（用setLong避免行数超过Integer上限时溢出）
        finalPs2.setLong(1, count1);
        finalPs2.setLong(2, count2);
        finalPs2.setInt(3, Integer.parseInt(args[bcompIndex]));
        finalPs2.setString(4, args[taskBusiDate]);
        finalPs2.setString(5, args[taskBusiBatch]);
        finalPs2.setString(6, args[bcompTimeNode]);
        finalPs2.executeUpdate();
    }

    /**
     * @author Tyc
     * description 差异字段特殊处理
     * @date 2023/11/20 17:27
     * @param
     * @return java.util.ArrayList<java.lang.String>
     */
    private static ArrayList<String> diffOperator(HashMap<String, HashMap<String,String>> TssBcompDifffieldMap,HashMap<String, HashMap<String,String>> diffFiledMap,ArrayList<String> diffNameList){
        if (TssBcompDifffieldMap != null){
            for (Map.Entry<String, HashMap<String, String>> entry : TssBcompDifffieldMap.entrySet()) {
                HashMap<String, String> diffConf = entry.getValue();
                String field_name1 = diffConf.get("FIELD_NAME1");
                String field_name2 = diffConf.get("FIELD_NAME2");
                if (diffFiledMap.containsKey(field_name1) && diffFiledMap.containsKey(field_name2)) {
                    HashMap<String, String> diffHashMap1 = diffFiledMap.get(field_name1);
                    HashMap<String, String> diffHashMap2 = diffFiledMap.get(field_name2);
                    BigDecimal Map1_A = new BigDecimal(diffHashMap1.get("A"));
                    BigDecimal Map1_B = new BigDecimal(diffHashMap1.get("B"));
                    BigDecimal Map2_A = new BigDecimal(diffHashMap2.get("A"));
                    BigDecimal Map2_B = new BigDecimal(diffHashMap2.get("B"));

                    boolean flag = false;
                    switch (diffConf.get("OPERATOR_TYPE")){
                        case "1":
                            BigDecimal addA = Map1_A.add(Map2_A);
                            BigDecimal addB = Map1_B.add(Map2_B);
                            if (addA.compareTo(addB) == 0 ){
                                flag = true;
                            }
                            break;
                        case "2":
                            BigDecimal subtractA = Map1_A.subtract(Map2_A);
                            BigDecimal subtractB = Map1_B.subtract(Map2_B);
                            if (subtractA.compareTo(subtractB) == 0){
                                flag = true;
                            }
                            break;
                        case "3":
                            BigDecimal multiplyA = Map1_A.multiply(Map2_A);
                            BigDecimal multiplyB = Map1_B.multiply(Map2_B);
                            if (multiplyA.compareTo(multiplyB) == 0){
                                flag = true;
                            }
                            break;
                        case "4":
                            BigDecimal divideA = Map1_A.divide(Map2_A,3,BigDecimal.ROUND_HALF_UP);
                            BigDecimal divideB = Map1_B.divide(Map2_B,3,BigDecimal.ROUND_HALF_UP);
                            if (divideA.compareTo(divideB) == 0){
                                flag = true;
                            }
                            break;
                        case "5":
                            BigDecimal divideA2 = Map2_A.divide(Map1_A,3,BigDecimal.ROUND_HALF_UP);
                            BigDecimal divideB2 = Map2_B.divide(Map1_B,3,BigDecimal.ROUND_HALF_UP);
                            if (divideA2.compareTo(divideB2) == 0 ){
                                flag = true;
                            }
                            break;
                    }

                    if (flag) {
                        Iterator<String> iterator = diffNameList.iterator();
                        while (iterator.hasNext()){
                            String diffName = iterator.next();
                            if (diffName.equals(field_name1) || diffName.equals(field_name2) ){
                                iterator.remove();
                            }
                        }
                    }

                }

            }
        }
        return diffNameList;
    }

    /**
     * @author Tyc
     * @description 将rowJavaRDD转换为JavaPairRDD
     * @date 2026/2/27 17:39
     * @param type 索引字段类型
     * @param rowJavaRDD 表数据原始RDD
     * @param bcField 核对字段配置列表的广播变量
     * @return org.apache.spark.api.java.JavaPairRDD<java.lang.String,java.lang.String[]> 按规则构建后的RDD
     */
    private static JavaPairRDD<String, String[]> RddToPair(String type,JavaRDD<Row> rowJavaRDD, Broadcast<List<Row>> bcField){
        JavaPairRDD<String, String[]> javaPairRDD = rowJavaRDD.mapToPair(line -> {
            ArrayList<String> keyList= new ArrayList<>();
            ArrayList<String> valueList= new ArrayList<>();

            for (Row tssBcompFieldDataRow : bcField.value()) {
                int fieldIndex = ((Number)tssBcompFieldDataRow.getAs(type)).intValue();
                String fieldType = (String)tssBcompFieldDataRow.getAs("FIELD_TYPE");
                String indexFlag = (String)tssBcompFieldDataRow.getAs("FIELD_INDEX_FLAG");
                String value = null == line.get(fieldIndex-1) ?  "" : line.get(fieldIndex-1).toString().trim();

                if (doubleFieldType.equals(fieldType)){
                    if (StringUtils.isNotEmpty(value)){
                        if (value.isEmpty()) {
                            value = "0.0";
                        }
                        value = String.format("%.7f", Double.parseDouble(value));//防止数值类型小数不同
                        valueList.add(value);
                    }else {
                        valueList.add("");
                    }
                }else {
                    valueList.add(value);
                }

                //索引字段
                if ("1".equals(indexFlag)){
                    keyList.add(value);
                }
            }

            String key = keyList.toString();
            String[] values = valueList.toArray(new String[0]);

            return new Tuple2<>(key, values);

        });

        return javaPairRDD;
    }


    /**
     * @author Tyc
     * @description 批量提交笔数
     * @date 2025/12/3 18:13
     * @param
     * @param sizeStr
     * @return int
     */
    private static int parseBatchSize(String sizeStr) {
        try {
            return Integer.parseInt(sizeStr);
        } catch (NumberFormatException ignored) {
            return 1000;
        }
    }

    /**
     * @author Tyc
     * @description 获取当前日期
     * @date 2025/12/16 16:01
     * @param
     * @return java.lang.String
     */
    public static String getDateTime() {
        Date date = new Date();
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
        return format.format(date);
    }

    /**
     * @author Tyc
     * @description 标准化DataFrame列名为大写,避免不同数据库列名大小写不一致问题
     * @date 2025/12/27 10:00
     * @param df 原始DataFrame
     * @return Dataset<Row> 列名标准化后的DataFrame
     */
    private static Dataset<Row> normalizeColumnNames(Dataset<Row> df) {
        if (df.isEmpty()) {
            log.warn("normalizeColumnNames: 传入的DataFrame为null,直接返回");
            return null;
        }
        for (String colName : df.columns()) {
            String upperColName = colName.toUpperCase();
            if (!colName.equals(upperColName)) {
                df = df.withColumnRenamed(colName, upperColName);
            }
        }
        return df;
    }

}