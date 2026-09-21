package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.ApiException;
import cn.sunline.tss.compare.api.PageResult;
import org.springframework.stereotype.Service;

@Service
public class DatabaseManagementService {
    private final DatabaseManagementRepository repository;
    public DatabaseManagementService(DatabaseManagementRepository repository){this.repository=repository;}

    public PageResult<DatabaseSummary> list(int page,int size){
        if(page<0||size<1||size>100)throw ApiException.badRequest("INVALID_PAGE","分页参数不合法");
        return repository.list(page,size);
    }

    public DatabaseSummary create(DatabaseSaveRequest request){
        validate(request,true);
        JdbcCurrentSchemaPolicy.requireValid(request.url(),request.driver());
        if(repository.find(request.databaseId())!=null)throw ApiException.conflict("DATABASE_ID_CONFLICT","数据库连接编号已存在");
        var stored=stored(request,null);
        repository.insert(stored);
        return stored.summary();
    }

    public DatabaseSummary update(long id,DatabaseSaveRequest request){
        if(id!=request.databaseId())throw ApiException.badRequest("DATABASE_ID_MISMATCH","路径编号与请求编号不一致");
        validate(request,false);
        JdbcCurrentSchemaPolicy.requireValid(request.url(),request.driver());
        var current=repository.find(id);
        if(current==null)throw ApiException.notFound("DATABASE_NOT_FOUND","数据库连接不存在");
        if(repository.inUse(id))throw ApiException.conflict("DATABASE_IN_USE","数据库连接正被等待或运行任务引用");
        if(isBlank(request.newPassword())&&!request.encryption().equals(current.encryption()))
            throw ApiException.badRequest("PASSWORD_REQUIRED","改变加密方式时必须输入新密码");
        var stored=stored(request,current);
        repository.update(stored);
        return stored.summary();
    }

    private DatabaseManagementRepository.StoredDatabase stored(DatabaseSaveRequest request,DatabaseManagementRepository.StoredDatabase current){
        try{
            String password,key;
            if(isBlank(request.newPassword())){password=current.password();key=current.key();}
            else if("1".equals(request.encryption())){key=PasswordEncryption.generateKey();password=PasswordEncryption.encrypt(request.newPassword(),key);}
            else{password=request.newPassword();key=null;}
            return new DatabaseManagementRepository.StoredDatabase(request.databaseId(),request.databaseName().trim(),request.url().trim(),request.user().trim(),password,request.driver().trim(),request.encryption(),key);
        }catch(Exception e){throw new IllegalStateException("数据库密码加密失败",e);}
    }

    private static void validate(DatabaseSaveRequest request,boolean create){
        if(request==null||request.databaseId()<=0||isBlank(request.databaseName())||isBlank(request.url())||isBlank(request.user())||isBlank(request.driver())||!("0".equals(request.encryption())||"1".equals(request.encryption()))||create&&isBlank(request.newPassword()))
            throw ApiException.badRequest("INVALID_DATABASE","数据库连接配置不完整");
    }
    private static boolean isBlank(String value){return value==null||value.isBlank();}
}
