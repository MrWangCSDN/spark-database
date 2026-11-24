package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.metadata.MetadataSqlDialect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.HashMap;
import java.io.Reader;

@Repository
public class ConfigurationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MetadataSqlDialect dialect;
    public ConfigurationRepository(DataSource source,@Value("${compare.jdbc-query-timeout-seconds:30}")int timeout){
        this(source, timeout, MetadataSqlDialect.forType(cn.sunline.tss.compare.metadata.MetadataDbType.GAUSSDB));
    }
    @Autowired
    public ConfigurationRepository(DataSource source,@Value("${compare.jdbc-query-timeout-seconds:30}")int timeout, MetadataSqlDialect dialect){
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(timeout);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        this.dialect=dialect;
    }

    ConfigurationDetail find(long index){
        List<BasicConfiguration> basics=jdbc.query("SELECT BCOMP_INDEX,BCOMP_NAME,BCOMP_MODULE,BCOMP_MEMO,BCOMP_RANGE,BCOMP_TIME_NODE,BCOMP_STATE,BCOMP_PARTITION_NUM,BCOMP_DATE_MODE,BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_INDEX=? AND BCOMP_TYPE='2'",
            (rs,n)->new BasicConfiguration(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),(Integer)rs.getObject(8),rs.getString(9),MaxPartitionNum.fromMetadata(rs.getObject(10))),index);
        if(basics.isEmpty())return null;
        TableSqlConfiguration sql=jdbc.queryForObject("SELECT ORIG_DATABASE_ID,DEST_DATABASE_ID,ORIG_SQL,DEST_SQL FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=?",
            (rs,n)->new TableSqlConfiguration(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4)),index);
        List<FieldRule> fields=jdbc.query("SELECT FIELD_NAME,FIELD_TYPE,FIELD_MEMO,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_ENUM_FLAG,FIELD_ENUM_INDEX,FIELD_PARTITION_FLAG,FIELD_IGNORE_VALUE,FIELD_IGNORE_FLAG FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=? ORDER BY FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_NAME",
            (rs,n)->new FieldRule(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getString(7),(Long)rs.getObject(8),rs.getString(9),rs.getString(10),rs.getString(11)),index);
        return new ConfigurationDetail(basics.get(0),sql,fields);
    }

    PageResult<BasicConfiguration> list(int page,int size){
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_TYPE='2'",Long.class);
        var pageSql=dialect.page("SELECT BCOMP_INDEX,BCOMP_NAME,BCOMP_MODULE,BCOMP_MEMO,BCOMP_RANGE,BCOMP_TIME_NODE,BCOMP_STATE,BCOMP_PARTITION_NUM,BCOMP_DATE_MODE,BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_TYPE='2' ORDER BY BCOMP_INDEX",(long)page*size,size);
        List<BasicConfiguration> items=jdbc.query(pageSql.sql(),
            (rs,n)->new BasicConfiguration(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),(Integer)rs.getObject(8),rs.getString(9),MaxPartitionNum.fromMetadata(rs.getObject(10))),pageSql.parameters().toArray());
        return new PageResult<>(items,page,size,total==null?0:total);
    }

    boolean inUse(long index){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=? AND TASK_STATE IN ('1','2')",Integer.class,index);return count!=null&&count>0;}
    boolean databasesExist(long oldId,long newId){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_DATABASE_CONFIG_NEW WHERE DATABASE_ID IN (?,?)",Integer.class,oldId,newId);return count!=null&&count==(oldId==newId?1:2);}

    AxonConfigScriptParser.ParseResult importScript(Reader reader,AxonConfigScriptParser parser){
        try {
            return transaction.execute(status->{
                Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW WHERE TASK_STATE IN ('1','2')",Integer.class);
                if(active!=null&&active>0)throw cn.sunline.tss.compare.api.ApiException.conflict("CONFIG_IMPORT_TASKS_ACTIVE","存在等待或运行任务，不能替换核对配置");
                var limits=new HashMap<Long,Integer>();
                jdbc.query("SELECT BCOMP_INDEX,BCOMP_MAX_PARTITION_NUM FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_TYPE='2'",
                    (org.springframework.jdbc.core.RowCallbackHandler)rs->limits.put(rs.getLong(1),MaxPartitionNum.fromMetadata(rs.getObject(2))));
                var parsed=parser.parse(reader,command->{
                    if(command.kind()==AxonConfigScriptParser.Kind.CLEAR)jdbc.update("DELETE FROM "+command.value());
                    else jdbc.update(command.value(),command.parameters().toArray());
                });
                Long configurations=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW",Long.class);
                Long fields=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_FIELD_NEW",Long.class);
                Long sqls=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TABLE_SQL_NEW",Long.class);
                if(configurations==null||fields==null||sqls==null||configurations!=parsed.configurationCount()||fields!=parsed.fieldCount()||sqls!=parsed.tableSqlCount()||configurations.longValue()!=sqls.longValue())
                    throw cn.sunline.tss.compare.api.ApiException.badRequest("INVALID_CONFIG_SCRIPT","配置脚本三表数据数量不一致");
                Integer missingDatabases=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TABLE_SQL_NEW s LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW o ON o.DATABASE_ID=s.ORIG_DATABASE_ID LEFT JOIN TSS_BCOMP_DATABASE_CONFIG_NEW d ON d.DATABASE_ID=s.DEST_DATABASE_ID WHERE o.DATABASE_ID IS NULL OR d.DATABASE_ID IS NULL",Integer.class);
                if(missingDatabases!=null&&missingDatabases>0)throw cn.sunline.tss.compare.api.ApiException.badRequest("INVALID_CONFIG_SCRIPT","配置脚本引用的数据库连接不存在");
                {
                    Integer missingKeys=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_CONF_NEW c WHERE c.BCOMP_TYPE='2' AND NOT EXISTS (SELECT 1 FROM TSS_BCOMP_FIELD_NEW f WHERE f.BCOMP_INDEX=c.BCOMP_INDEX AND f.FIELD_INDEX_FLAG='1')",Integer.class);
                    if(missingKeys!=null&&missingKeys>0)throw cn.sunline.tss.compare.api.ApiException.badRequest("INVALID_CONFIG_SCRIPT","主键哈希分区必须配置业务键字段");
                }
                jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_PARTITION_NUM=1 WHERE BCOMP_PARTITION_NUM IS NULL");
                jdbc.update("UPDATE TSS_BCOMP_FIELD_NEW SET FIELD_PARTITION_FLAG='0'");
                var legacyLimits=limits.entrySet().stream()
                    .filter(entry->!parsed.explicitMaxPartitionIndexes().contains(entry.getKey()))
                    .map(entry->new Object[]{entry.getValue(),entry.getKey()}).toList();
                if(!legacyLimits.isEmpty()) jdbc.batchUpdate("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_MAX_PARTITION_NUM=? WHERE BCOMP_INDEX=? AND BCOMP_TYPE='2'",legacyLimits);
                return parsed;
            });
        } catch(cn.sunline.tss.compare.api.ApiException exception){throw exception;}
        catch(DataAccessException exception){throw cn.sunline.tss.compare.api.ApiException.unprocessable("CONFIG_SCRIPT_EXECUTION_FAILED","配置脚本执行失败，请检查脚本与数据库约束",exception);}
    }

    void insert(ConfigurationCommand command){transaction.executeWithoutResult(status->{insertBasic(command.basic());insertSql(command.basic().bcompIndex(),command.tableSql());insertFields(command.basic().bcompIndex(),command.fields());});}
    void update(ConfigurationCommand command){transaction.executeWithoutResult(status->{
        BasicConfiguration b=command.basic();
        jdbc.update("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_NAME=?,BCOMP_MODULE=?,BCOMP_MEMO=?,BCOMP_RANGE=?,BCOMP_TIME_NODE=?,BCOMP_STATE=?,BCOMP_PARTITION_NUM=?,BCOMP_DATE_MODE=?,BCOMP_MAX_PARTITION_NUM=COALESCE(?,BCOMP_MAX_PARTITION_NUM,64) WHERE BCOMP_INDEX=? AND BCOMP_TYPE='2'",b.name(),b.module(),b.memo(),b.range(),b.timeNode(),b.state(),1,b.dateMode(),b.maxPartitionNum(),b.bcompIndex());
        jdbc.update("DELETE FROM TSS_BCOMP_TABLE_SQL_NEW WHERE BCOMP_INDEX=?",b.bcompIndex());insertSql(b.bcompIndex(),command.tableSql());
        jdbc.update("DELETE FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=?",b.bcompIndex());insertFields(b.bcompIndex(),command.fields());
    });}

    private void insertBasic(BasicConfiguration b){jdbc.update("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_TYPE,BCOMP_MODULE,BCOMP_MEMO,BCOMP_RANGE,BCOMP_TIME_NODE,BCOMP_STATE,BCOMP_PARTITION_NUM,BCOMP_DATE_MODE,BCOMP_MAX_PARTITION_NUM) VALUES(?,?,'2',?,?,?,?,?,?,?,?)",b.bcompIndex(),b.name(),b.module(),b.memo(),b.range(),b.timeNode(),b.state(),1,b.dateMode(),MaxPartitionNum.effective(b.maxPartitionNum()));}
    private void insertSql(long index,TableSqlConfiguration s){jdbc.update("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX,ORIG_DATABASE_ID,DEST_DATABASE_ID,ORIG_SQL,DEST_SQL) VALUES(?,?,?,?,?)",index,s.origDatabaseId(),s.destDatabaseId(),s.origSql(),s.destSql());}
    private void insertFields(long index,List<FieldRule> fields){for(FieldRule f:fields)jdbc.update("INSERT INTO TSS_BCOMP_FIELD_NEW(BCOMP_INDEX,FIELD_NAME,FIELD_TYPE,FIELD_MEMO,FIELD_OLD_INDEX,FIELD_NEW_INDEX,FIELD_INDEX_FLAG,FIELD_ENUM_FLAG,FIELD_ENUM_INDEX,FIELD_PARTITION_FLAG,FIELD_IGNORE_VALUE,FIELD_IGNORE_FLAG) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",index,f.name(),f.type(),f.memo(),f.oldIndex(),f.newIndex(),f.indexFlag(),f.enumFlag(),f.enumIndex(),"0",f.ignoreValue(),f.ignoreFlag());}

    ConfigurationOptions options(){
        return options(false);
    }
    ConfigurationOptions options(boolean fieldMatchByName){
        List<String> states=jdbc.query("SELECT DISTINCT BCOMP_STATE FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_STATE IS NOT NULL ORDER BY BCOMP_STATE",(rs,n)->rs.getString(1));
        List<ConfigurationOptions.EnumOption> enums=jdbc.query("SELECT CONV_ENUM_INDEX,ORIG_ENUM_VALUE,DEST_ENUM_VALUE FROM TSS_BCOMP_ENUM_NEW ORDER BY CONV_ENUM_INDEX,ORIG_ENUM_VALUE",(rs,n)->new ConfigurationOptions.EnumOption(rs.getLong(1),rs.getString(2),rs.getString(3)));
        List<ConfigurationOptions.IgnoreOption> ignores=jdbc.query("SELECT IGNORE_ID,IGNORE_VALUE FROM TSS_IGNORE_VALUE_NEW ORDER BY IGNORE_ID,IGNORE_VALUE",(rs,n)->new ConfigurationOptions.IgnoreOption(rs.getLong(1),rs.getString(2)));
        return new ConfigurationOptions(states,enums,ignores,fieldMatchByName);
    }
}
