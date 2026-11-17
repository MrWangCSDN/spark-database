package cn.sunline.tss.tssveri.spark;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.*;
import scala.Tuple2;

import java.io.Serializable;
import java.math.BigDecimal;
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

    public static void main(String[] args) throws Exception {

        if (args == null || args.length != 21) {
            throw new IllegalArgumentException("库表比对需要完整的21个参数");
        }
        log.info("库表比对作业启动，参数数量：{}", args.length);
        SparkRun(args);
    }

    public static void SparkRun(String[] args) throws Exception {
        log.info("spark任务开始执行");

        OpenGaussJdbcDialect.register();
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
        GdbJdbcDialect.register(sc.conf().get("spark.gdb.jdbc-url-prefixes", "jdbc:mysql:,jdbc:gdb:,jdbc:gbase:"));

        Class.forName(args[tssDataSourceDriver]);
        try (Connection connectionOut=DriverManager.getConnection(args[tssDataSourceUrl],args[tssDataSourceUser],args[tssDataSourcePassword])) {
            int limit=readSampleLimit(connectionOut,Long.parseLong(args[bcompIndex]),sc.conf().get("spark.compare.detailLimit","100"));
            DailyResultPublisher.Context resultContext=DailyResultPublisher.context(connectionOut,Long.parseLong(args[bcompIndex]));
            //获取tssBcompField字段信息（需在获取数据源配置之前，因为分区列从field表中读取）
            List<Row> tssBcompFieldDataRows = getTssBcompFieldData(sc,args);

            //替换SQL中的${HDRQ}占位符（使用核对日期args[compDateIndex]）
            String sqlDateParam = DailyResultPublisher.resultDate(args);
            if (sqlDateParam != null && !sqlDateParam.isEmpty()) {
                args[OLD_TABLE_INDEX] = replaceSqlPlaceholders(args[OLD_TABLE_INDEX], sqlDateParam);
                args[NEW_TABLE_INDEX] = replaceSqlPlaceholders(args[NEW_TABLE_INDEX], sqlDateParam);
                log.info("SQL占位符替换完成，核对日期: {}", sqlDateParam);
            }

            boolean matchByName=Boolean.parseBoolean(sc.conf().get("spark.compare.fieldMatchByName","true"));
            var classSource=SparkHandlerTable.class.getProtectionDomain().getCodeSource();
            log.info("字段匹配模式={}, 作业类来源={}",matchByName?"NAME":"POSITION",classSource==null?"unknown":classSource.getLocation());
            Class.forName(args[OLD_DRIVER_INDEX]);
            try(Connection connection=DriverManager.getConnection(args[OLD_URL_INDEX],args[OLD_USER_INDEX],args[OLD_PASSWORD_INDEX])) {
                tssBcompFieldDataRows=QueryFieldResolver.resolve(connection,args[OLD_TABLE_INDEX],tssBcompFieldDataRows,
                    FIELD_OLD_INDEX,"数据库A（源）",matchByName);
            }
            Class.forName(args[NEW_DRIVER_INDEX]);
            try(Connection connection=DriverManager.getConnection(args[NEW_URL_INDEX],args[NEW_USER_INDEX],args[NEW_PASSWORD_INDEX])) {
                tssBcompFieldDataRows=QueryFieldResolver.resolve(connection,args[NEW_TABLE_INDEX],tssBcompFieldDataRows,
                    FIELD_NEW_INDEX,"数据库B（目标）",matchByName);
            }

            //先校验两侧业务键，避免无效配置触发大表计数。
            PrimaryKeyHashPlanner.keyPositions(tssBcompFieldDataRows, FIELD_OLD_INDEX);
            PrimaryKeyHashPlanner.keyPositions(tssBcompFieldDataRows, FIELD_NEW_INDEX);
            //实时统计两侧本次 SQL 的行数，自动选择相同的 HASH 分区数。
            ShardConf shardConf = new ShardConf(AutoHashPartitionPlanner.plan(args));
            //按所有主键字段构造HASH读取计划；失败直接终止
            ReadPlan readPlan = resolveReadPlan(args, tssBcompFieldDataRows, shardConf);
            //回写实际读取策略到TSS_BCOMP_TASK_NEW.BCOMP_READ_MODE，运维可见实际分区数（列不存在时只告警）
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
            //只触发一次比对行动；完整统计与有界样本一起归约。
            JavaRDD<Row> rowJavaRDDOld = readSide(sc, readPlan.oldSide);
            JavaRDD<Row> rowJavaRDDNew = readSide(sc, readPlan.newSide);
            //转换为pairRDD
            JavaPairRDD<String, CountedRow> pairsOra = RddToPair(FIELD_OLD_INDEX,rowJavaRDDOld,bcField);
            JavaPairRDD<String, CountedRow> pairsYjj = RddToPair(FIELD_NEW_INDEX,rowJavaRDDNew,bcField);
            //采用cogroup进行join
            JavaPairRDD<String, Tuple2<Iterable<CountedRow>, Iterable<CountedRow>>> rsCogroup = pairsOra.cogroup(pairsYjj);

            ResultAggregate result=runTwoPass(rsCogroup,limit,tuple -> {
                    Class.forName(args[tssDataSourceDriver]);
                    try(Connection connection=DriverManager.getConnection(args[tssDataSourceUrl],args[tssDataSourceUser],args[tssDataSourcePassword])) {
                        return countPatternsPartition(tuple,bcField.value(),loadIgnoreMap(connection,ignoreIdCsv),loadEnumMap(connection,enumIndexCsv));
                    }
                },(tuple,selection) -> {
                    Class.forName(args[tssDataSourceDriver]);
                    try(Connection connection=DriverManager.getConnection(args[tssDataSourceUrl],args[tssDataSourceUser],args[tssDataSourcePassword])) {
                        return comparePartition(tuple,bcField.value(),loadIgnoreMap(connection,ignoreIdCsv),loadEnumMap(connection,enumIndexCsv),
                            limit,selection.retainedIds(),selection.patternSampleLimit());
                    }
                });
            DailyResultPublisher.publish(connectionOut,args,resultContext,result);
        } catch(Exception error) {
            try(Connection c=DriverManager.getConnection(args[tssDataSourceUrl],args[tssDataSourceUser],args[tssDataSourcePassword]);
                PreparedStatement ps=c.prepareStatement("UPDATE TSS_BCOMP_TASK_NEW SET TASK_STATE='4',TASK_RESULT='3',FTASK_REASON=?,END_DATETIME=? WHERE BCOMP_INDEX=? AND TASK_BUSI_DATE=? AND TASK_BATCH=? AND BCOMP_TIME_NODE=? AND TASK_STATE='2'")) {
                String reason=error instanceof QueryFieldResolver.FieldResolutionException
                    ?error.getMessage():"核对失败（"+error.getClass().getSimpleName()+"），请检查Spark日志";
                ps.setString(1,reason);
                ps.setString(2,getDateTime());ps.setLong(3,Long.parseLong(args[bcompIndex]));ps.setString(4,args[taskBusiDate]);ps.setString(5,args[taskBusiBatch]);ps.setString(6,args[bcompTimeNode]);ps.executeUpdate();
            } catch(Exception updateError) { error.addSuppressed(updateError); }
            throw error;
        } finally { sc.close(); }
    }

    static ResultAggregate comparePartition(Iterator<Tuple2<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>>> groups,
            List<Row> fields,Map<Integer,Set<String>> ignoreData,HashMap<Integer,HashMap<String,String>> enumMap,int limit) {
        return comparePartition(groups,fields,ignoreData,enumMap,limit,null,limit);
    }

    static ResultAggregate comparePartition(Iterator<Tuple2<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>>> groups,
            List<Row> fields,Map<Integer,Set<String>> ignoreData,HashMap<Integer,HashMap<String,String>> enumMap,int limit,
            Set<String> retainedPatternIds,int patternSampleLimit) {
        ResultAggregate result=new ResultAggregate(limit,retainedPatternIds,patternSampleLimit);
        List<String> names=fields.stream().map(row -> (String)row.getAs("FIELD_NAME")).collect(Collectors.toList());
        List<String> labels=fields.stream().map(row -> (String)row.getAs("FIELD_MEMO")).collect(Collectors.toList());
        while(groups.hasNext()) {
            var compared=compareGroup(groups.next(),fields,ignoreData,enumMap);
            result.matched=Math.addExact(result.matched,compared.matched);
            if(compared.type!=null) {
                CountedRow displayed=compared.origin==null?compared.destination:compared.origin;
                result.difference(compared.type,compared.key,compared.differingFields,compared.differences,
                    ()->SampleRow.of(displayed.businessKey,compared.type,names,labels,
                        compared.origin==null?null:compared.origin.values,
                        compared.destination==null?null:compared.destination.values,compared.differingFields));
            }
        }
        return result;
    }

    static PatternCounts countPatternsPartition(Iterator<Tuple2<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>>> groups,
            List<Row> fields,Map<Integer,Set<String>> ignoreData,HashMap<Integer,HashMap<String,String>> enumMap) {
        var result=new PatternCounts();
        while(groups.hasNext()) {
            var compared=compareGroup(groups.next(),fields,ignoreData,enumMap);
            if("4".equals(compared.type)) result.add(compared.differingFields);
        }
        return result;
    }

    private static ComparedGroup compareGroup(Tuple2<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>> group,
            List<Row> fields,Map<Integer,Set<String>> ignoreData,HashMap<Integer,HashMap<String,String>> enumMap) {
        CountedRow origin=uniqueRecord(group._2()._1()),destination=uniqueRecord(group._2()._2());
        CountedRow present=origin==null?destination:origin;
        if(present.multiset) {
            long originCount=origin==null?0:origin.count;
            long destinationCount=destination==null?0:destination.count;
            String type=originCount>destinationCount?"3":destinationCount>originCount?"2":null;
            CountedRow remainingOrigin=originCount>destinationCount?origin:null;
            CountedRow remainingDestination=destinationCount>originCount?destination:null;
            return new ComparedGroup(group._1(),type,List.of(),remainingOrigin,remainingDestination,
                Math.min(originCount,destinationCount),Math.abs(originCount-destinationCount));
        }
        ArrayList<String> differingFields=new ArrayList<>();
        String type=origin==null?"2":destination==null?"3":compareRows(fields,ignoreData,enumMap,origin.values,destination.values,differingFields)?"4":null;
        return new ComparedGroup(group._1(),type,differingFields,origin,destination,type==null?1:0,type==null?0:1);
    }

    private record ComparedGroup(String key,String type,List<String> differingFields,CountedRow origin,CountedRow destination,long matched,long differences) implements Serializable {}

    @FunctionalInterface
    interface CountPartition extends Serializable {
        PatternCounts apply(Iterator<Tuple2<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>>> groups) throws Exception;
    }

    @FunctionalInterface
    interface SamplePartition extends Serializable {
        ResultAggregate apply(Iterator<Tuple2<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>>> groups,PatternCounts.Selection selection) throws Exception;
    }

    static ResultAggregate runTwoPass(JavaPairRDD<String,Tuple2<Iterable<CountedRow>,Iterable<CountedRow>>> groups,int detailLimit,
            CountPartition countPartition,SamplePartition samplePartition) {
        groups.persist(org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK_SER());
        try {
            PatternCounts counts=groups.mapPartitions(values->Collections.singletonList(countPartition.apply(values)).iterator())
                .treeReduce(PatternCounts::merge,3);
            PatternCounts.Selection selection=counts.select(127,detailLimit);
            ResultAggregate result=groups.mapPartitions(values->Collections.singletonList(samplePartition.apply(values,selection)).iterator())
                .treeReduce(ResultAggregate::merge,3);
            result.collapse(selection.collapsedPatternCount(),selection.collapsedDifferentCount());
            return result;
        } finally {
            groups.unpersist(false);
        }
    }

    private static CountedRow uniqueRecord(Iterable<CountedRow> values) {
        Iterator<CountedRow> iterator=values.iterator();
        if(!iterator.hasNext()) return null;
        CountedRow first=iterator.next();
        if(iterator.hasNext()) throw new IllegalArgumentException("SQL结果出现重复核对主键，无法按唯一主键统计差异");
        return first;
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
                "select CONV_ENUM_INDEX, ORIG_ENUM_VALUE, DEST_ENUM_VALUE from tss_bcomp_enum_new where CONV_ENUM_INDEX in (" + enumIndexCsv + ")");
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
                "select IGNORE_ID, IGNORE_VALUE from tss_ignore_value_new where IGNORE_ID in (" + ignoreIdCsv + ")");
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
        String tssbcompfieldSql = "(select * from tss_bcomp_field_new where bcomp_index = ? order by FIELD_OLD_INDEX, FIELD_NEW_INDEX, FIELD_NAME)  tss_bcomp_field_new"  ;
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

    /** A per-table zero is explicit; only NULL uses the current global fallback. */
    static int readSampleLimit(Connection connection, long configurationIndex, String globalLimit) throws SQLException {
        try (PreparedStatement ps=connection.prepareStatement("SELECT BCOMP_SAMPLE_LIMIT FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=?")) {
            ps.setLong(1,configurationIndex);
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) throw new IllegalArgumentException("核对配置不存在");
                Object value=rs.getObject(1);
                try {
                    int limit=new java.math.BigDecimal(value==null?globalLimit:value.toString()).intValueExact();
                    if(limit<0 || limit>10000) throw new IllegalArgumentException("差异明细上限必须在0..10000之间");
                    return limit;
                } catch (NumberFormatException | ArithmeticException error) {
                    throw new IllegalArgumentException("差异明细上限必须为0..10000整数",error);
                }
            }
        }
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

    /** Build the only supported read plan: all configured primary-key columns. */
    static ReadPlan resolveReadPlan(String[] args,List<Row> fields,ShardConf conf) throws Exception {
        PrimaryKeyHashPlanner.partitionCount(conf.partitionNum);
        List<Integer> originKeys=PrimaryKeyHashPlanner.keyPositions(fields,FIELD_OLD_INDEX);
        List<Integer> destinationKeys=PrimaryKeyHashPlanner.keyPositions(fields,FIELD_NEW_INDEX);
        SidePlan origin=hashSidePlan("数据库A",args[OLD_URL_INDEX],args[OLD_USER_INDEX],args[OLD_PASSWORD_INDEX],args[OLD_DRIVER_INDEX],args[OLD_TABLE_INDEX],originKeys,conf.partitionNum);
        SidePlan destination=hashSidePlan("数据库B",args[NEW_URL_INDEX],args[NEW_USER_INDEX],args[NEW_PASSWORD_INDEX],args[NEW_DRIVER_INDEX],args[NEW_TABLE_INDEX],destinationKeys,conf.partitionNum);
        return new ReadPlan(origin,destination);
    }


    private static SidePlan hashSidePlan(String side, String url, String user, String password,
                                         String driver, String source, List<Integer> keys, int count) throws Exception {
        Class.forName(driver);
        String[] predicates;
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            predicates = PrimaryKeyHashPlanner.predicates(connection, source, keys, count);
        }
        String mode = "HASH" + dialectLabel(url, driver) + "主键读取(" + count + "分区)";
        log.info("{}表使用{}", side, mode);
        if (predicates == null) return SidePlan.ofOptions(side, mode, buildJdbcConfig(url, user, password, driver, source));
        return SidePlan.ofPredicates(side, mode, url, source, buildConnProps(user, password, driver), predicates);
    }

    /**
     * 按 SidePlan 实际读取一侧数据：predicates 走 spark.read().jdbc(url,table,predicates,props)，
     * 分区数1走单连接 options 加载，不是失败降级。
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
        return name.contains("oceanbase") || name.contains("gdb") || name.contains("gbase")
                || name.equals("com.mysql.cj.jdbc.driver") || name.equals("com.mysql.jdbc.driver");
    }

    private static String dialectLabel(String url, String driver) {
        String value = (Objects.toString(url, "") + " " + Objects.toString(driver, "")).toLowerCase(Locale.ROOT);
        if (value.contains("mysql") || value.contains("gdb") || value.contains("gbase")) return "GDB/MySQL";
        if (value.contains("postgres") || value.contains("gauss") || value.contains("opengauss")) return "GaussDB/PostgreSQL";
        return "JDBC";
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

    /** Only a partition count; there is no selectable strategy. */
    static class ShardConf {
        final int partitionNum;
        ShardConf(int partitionNum) { this.partitionNum=partitionNum; }
    }

    /** 单侧读取计划：HASH单分区options或多分区predicates。 */
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
            String d = "数据库A=" + oldSide.modeDesc + "; 数据库B=" + newSide.modeDesc;
            return d.length() > 490 ? d.substring(0, 490) : d;
        }
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
     * 逐字段比对两侧的一行记录（跳过忽略字段、套用枚举映射与忽略值），
     * 返回是否存在差异，差异字段名写入 diffNameList。与原本内联在循环里的比对逻辑一致。
     */
    private static boolean compareRows(List<Row> fieldRows, Map<Integer, Set<String>> ignoreData,
                                       HashMap<Integer, HashMap<String, String>> enumMap,
                                       String[] rowA, String[] rowB, ArrayList<String> diffNameList) {
        for(int i=0;i<fieldRows.size();i++) {
            Row row=fieldRows.get(i);
            if("1".equals((String)row.getAs("FIELD_IGNORE_FLAG"))) continue;
            String a=rowA[i]==null?null:rowA[i].trim(), b=rowB[i]==null?null:rowB[i].trim();
            String ignore=row.getAs("FIELD_IGNORE_VALUE");
            if(StringUtils.isNotBlank(ignore) && ignoreData!=null && ignoreData.getOrDefault(Integer.parseInt(ignore.trim()),Collections.emptySet()).contains(a)) continue;
            ArrayList<String> fieldDiff=new ArrayList<>();
            String type=row.getAs("FIELD_TYPE");
            boolean different=switch(type) {
                case stringFieldType -> compareString(row,a,b,fieldDiff);
                case doubleFieldType -> compareDouble(row,a,b,fieldDiff);
                case enumFieldType -> compareEnumType(row,a,b,fieldDiff,enumMap);
                case dateFieldType -> compareDateType(row,a,b,fieldDiff);
                default -> throw new IllegalArgumentException("不支持的核对字段类型："+type);
            };
            if(different) diffNameList.add(row.getAs("FIELD_NAME"));
        }
        return !diffNameList.isEmpty();
    }

    /** Map each complete primary key to configured field values. */
    static JavaPairRDD<String, CountedRow> RddToPair(String type,JavaRDD<Row> rowJavaRDD, Broadcast<List<Row>> bcField){
        JavaPairRDD<String, CountedRow> javaPairRDD = rowJavaRDD.mapToPair(line -> {
            ArrayList<String> keyList= new ArrayList<>();
            ArrayList<String> valueList= new ArrayList<>();
            boolean multiset=false;

            for (Row tssBcompFieldDataRow : bcField.value()) {
                int fieldIndex = ((Number)tssBcompFieldDataRow.getAs(type)).intValue();
                String fieldType = (String)tssBcompFieldDataRow.getAs("FIELD_TYPE");
                String indexFlag = (String)tssBcompFieldDataRow.getAs("FIELD_INDEX_FLAG");
                Object raw=line.get(fieldIndex-1);
                String value=raw==null?null:raw.toString();
                valueList.add(value);
                if("1".equals(indexFlag)) {
                    String keyValue=SampleRow.keyValue(value,fieldType);
                    keyList.add(keyValue);
                    multiset|=keyValue==null||keyValue.isEmpty();
                }
            }

            if(keyList.isEmpty()) throw new IllegalArgumentException("未配置核对主键");
            String key = SampleRow.key(keyList);
            if(key.length()>1000) throw new IllegalArgumentException("核对主键超过1000字符");
            String[] values = valueList.toArray(new String[0]);

            String groupKey=key;
            if(multiset) {
                var identity=new ArrayList<String>(valueList.size()+1);
                identity.add(key);
                identity.addAll(valueList);
                groupKey='\0'+SampleRow.key(identity);
            }
            return new Tuple2<>(groupKey, new CountedRow(key,values,multiset));

        });

        return javaPairRDD.reduceByKey(CountedRow::merge);
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
