package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiException;
import cn.sunline.tss.compare.api.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ConfigurationService {
    private static final Set<String> TYPES=Set.of("1","2","3","4");
    private final ConfigurationRepository repository;
    private final CacheReloader cache;
    private final long maxImportBytes;
    private final boolean fieldMatchByName;
    private final AxonConfigScriptParser scriptParser=new AxonConfigScriptParser();
    public ConfigurationService(ConfigurationRepository repository,CacheReloader cache){this(repository,cache,52_428_800L);}
    public ConfigurationService(ConfigurationRepository repository,CacheReloader cache,long maxImportBytes){this(repository,cache,maxImportBytes,false);}
    @Autowired public ConfigurationService(ConfigurationRepository repository,CacheReloader cache,@Value("${compare.config-import-max-bytes:52428800}")long maxImportBytes,@Value("${compare.field-match-by-name:true}")boolean fieldMatchByName){this.repository=repository;this.cache=cache;this.maxImportBytes=maxImportBytes;this.fieldMatchByName=fieldMatchByName;}

    public PageResult<BasicConfiguration> list(int page,int size){return list(page,size,null);}
    public PageResult<BasicConfiguration> list(int page,int size,String keyword){
        if(page<0||size<1||size>100)throw ApiException.badRequest("INVALID_PAGE","分页参数不合法");
        String normalized=keyword==null?null:keyword.trim();
        if(normalized!=null&&normalized.isBlank())normalized=null;
        if(normalized!=null&&normalized.length()>100)throw ApiException.badRequest("INVALID_CONFIGURATION_QUERY","配置名称关键词最多100字符");
        return repository.list(page,size,normalized);
    }
    public ConfigurationDetail find(long index){var value=repository.find(index);if(value==null)throw ApiException.notFound("CONFIGURATION_NOT_FOUND","核对配置不存在");return value;}

    public ConfigurationSaveResult create(ConfigurationCommand command){
        return cn.sunline.tss.compare.ControlPlaneLock.call(() -> createLocked(command,null));
    }

    private ConfigurationSaveResult createLocked(ConfigurationCommand command,Long copySource) {
        validate(command);
        if(repository.find(command.basic().bcompIndex())!=null)throw ApiException.conflict("CONFIGURATION_ID_CONFLICT","核对配置编号已存在");
        repository.insert(command,copySource);return finish(command.basic().bcompIndex());
    }
    public ConfigurationSaveResult update(long index,ConfigurationCommand command){
        return cn.sunline.tss.compare.ControlPlaneLock.call(() -> updateLocked(index, command));
    }

    private ConfigurationSaveResult updateLocked(long index,ConfigurationCommand command) {
        if(command==null||command.basic()==null||index!=command.basic().bcompIndex())throw ApiException.badRequest("CONFIGURATION_ID_MISMATCH","路径编号与请求编号不一致");
        validate(command);var current=find(index);
        if(repository.inUse(index))throw ApiException.conflict("CONFIGURATION_IN_USE","核对配置正被等待或运行任务引用");
        if(!java.util.Objects.equals(current.basic().name(),command.basic().name()))throw ApiException.conflict("CONFIGURATION_CODE_IMMUTABLE","固定配置标识不可修改，请编辑配置名称或创建新配置");
        if(command.basic().sourceTableName()!=null && current.basic().sourceTableName()!=null && !java.util.Objects.equals(current.basic().sourceTableName(),command.basic().sourceTableName()))throw ApiException.conflict("CONFIGURATION_SOURCE_IMMUTABLE","真实来源表不可修改，请创建新配置");
        repository.update(command);return finish(index);
    }
    public ConfigurationSaveResult copy(long index,CopyConfigurationRequest request){
        return cn.sunline.tss.compare.ControlPlaneLock.call(() -> copyLocked(index, request));
    }

    private ConfigurationSaveResult copyLocked(long index,CopyConfigurationRequest request) {
        var source=find(index);
        if(request==null||request.newIndex()<=0||blank(request.newName()))throw ApiException.badRequest("INVALID_COPY_REQUEST","新编号和名称不能为空");
        var b=source.basic();
        var command=new ConfigurationCommand(new BasicConfiguration(request.newIndex(),request.newName().trim(),b.module(),b.memo(),b.range(),b.timeNode(),b.state(),b.partitionNum(),b.dateMode(),b.maxPartitionNum(),b.sourceTableName(),b.configurationName(),b.configurationScope()),source.tableSql(),source.fields());
        return createLocked(command,index);
    }
    public ConfigurationOptions options(){return repository.options(fieldMatchByName);}

    public ConfigScriptImportResult importScript(MultipartFile file){
        return cn.sunline.tss.compare.ControlPlaneLock.call(() -> importScriptLocked(file));
    }

    private ConfigScriptImportResult importScriptLocked(MultipartFile file) {
        if(file==null||file.isEmpty())throw ApiException.badRequest("CONFIG_SCRIPT_REQUIRED","请选择或粘贴 Axon 配置脚本");
        if(file.getSize()>maxImportBytes)throw ApiException.payloadTooLarge("CONFIG_SCRIPT_TOO_LARGE","配置脚本超过允许的大小");
        AxonConfigScriptParser.ParseResult counts;
        try(var reader=new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)){
            counts=repository.importScript(reader,scriptParser);
        }catch(IOException exception){throw ApiException.badRequest("CONFIG_SCRIPT_READ_FAILED","配置脚本读取失败");}
        boolean refreshed=true;
        try{cache.reload();}catch(RuntimeException exception){refreshed=false;}
        return new ConfigScriptImportResult(true,refreshed,counts.configurationCount(),counts.fieldCount(),counts.tableSqlCount(),refreshed?null:"配置已导入，但缓存刷新失败");
    }

    private ConfigurationSaveResult finish(long index){
        boolean refreshed=true;try{cache.reload();}catch(RuntimeException e){refreshed=false;}
        return new ConfigurationSaveResult(find(index),true,refreshed,refreshed?null:"配置已保存，但缓存刷新失败");
    }
    private void validate(ConfigurationCommand command){
        if(command==null||command.basic()==null||command.tableSql()==null||command.fields()==null||command.fields().isEmpty())throw ApiException.badRequest("INVALID_CONFIGURATION","核对配置不完整");
        BasicConfiguration b=command.basic();TableSqlConfiguration s=command.tableSql();
        MaxPartitionNum.effective(b.maxPartitionNum());
        // Legacy Axon imports omit the date mode; keep the same optional rule as task/batch validation.
        if(b.bcompIndex()<=0||blank(b.name())||blank(b.state())||(!blank(b.dateMode())&&!("1".equals(b.dateMode())||"2".equals(b.dateMode())))||blank(s.origSql())||blank(s.destSql())||!repository.databasesExist(s.origDatabaseId(),s.destDatabaseId()))throw ApiException.badRequest("INVALID_CONFIGURATION","核对基本信息、SQL或数据库引用不完整");
        if(b.name().length()>100 || (b.sourceTableName()!=null && b.sourceTableName().length()>256) || (b.configurationName()!=null && b.configurationName().length()>200))throw ApiException.badRequest("INVALID_CONFIGURATION","配置标识、真实表名或配置名称超过允许长度");
        Set<Long> oldIndexes=new HashSet<>(),newIndexes=new HashSet<>();int keys=0;
        Set<String> names=new HashSet<>();
        for(FieldRule f:command.fields()){
            if(f==null||blank(f.name())||f.type()==null||!TYPES.contains(f.type()))throw ApiException.badRequest("INVALID_FIELD","字段名称或类型不合法");
            if(fieldMatchByName) {
                if(!names.add(f.name().trim().toLowerCase(Locale.ROOT)))throw ApiException.badRequest("DUPLICATE_FIELD_NAME","字段英文名必须唯一（不区分大小写）");
            } else {
                if(f.oldIndex()<=0||f.newIndex()<=0)throw ApiException.badRequest("INVALID_FIELD","两侧字段位置必须为正整数");
                if(!oldIndexes.add(f.oldIndex())||!newIndexes.add(f.newIndex()))throw ApiException.badRequest("DUPLICATE_FIELD_POSITION","两侧字段位置必须各自唯一");
            }
            if("1".equals(f.indexFlag()))keys++;
        }
        if(keys==0)throw ApiException.badRequest("BUSINESS_KEY_REQUIRED","至少配置一个业务键字段");
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
}
