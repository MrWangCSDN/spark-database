package cn.sunline.tss.compare.database;

import cn.sunline.tss.compare.api.PageResult;
import cn.sunline.tss.compare.metadata.MetadataDbType;
import cn.sunline.tss.compare.metadata.MetadataSqlDialect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

@Repository
public class DatabaseManagementRepository {
    private final JdbcTemplate jdbc;
    private final MetadataSqlDialect dialect;
    public DatabaseManagementRepository(DataSource source,
                                        @Value("${compare.jdbc-query-timeout-seconds:30}") int queryTimeout) {
        this(source, queryTimeout, MetadataSqlDialect.forType(MetadataDbType.GAUSSDB));
    }
    @Autowired
    public DatabaseManagementRepository(DataSource source,
                                        @Value("${compare.jdbc-query-timeout-seconds:30}") int queryTimeout,
                                        MetadataSqlDialect dialect) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(queryTimeout);
        this.dialect = dialect;
    }

    PageResult<DatabaseSummary> list(int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_DATABASE_CONFIG_NEW", Long.class);
        var pageSql = dialect.page("SELECT DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_DRIVER,DATABASE_ENCRYPTION,DATABASE_PASSWORD FROM TSS_BCOMP_DATABASE_CONFIG_NEW ORDER BY DATABASE_ID", (long) page * size, size);
        List<DatabaseSummary> items = jdbc.query(pageSql.sql(),
            (rs,n) -> new DatabaseSummary(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)!=null && !rs.getString(7).isEmpty()),
            pageSql.parameters().toArray());
        return new PageResult<>(items,page,size,total==null?0:total);
    }

    StoredDatabase find(long id) {
        List<StoredDatabase> rows=jdbc.query("SELECT DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION,DATABASE_GENERATERKEY FROM TSS_BCOMP_DATABASE_CONFIG_NEW WHERE DATABASE_ID=?",
            (rs,n)->new StoredDatabase(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),id);
        return rows.isEmpty()?null:rows.get(0);
    }

    boolean inUse(long id) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM TSS_BCOMP_TASK_NEW t JOIN TSS_BCOMP_TABLE_SQL_NEW s ON s.BCOMP_INDEX=t.BCOMP_INDEX WHERE t.TASK_STATE IN ('1','2') AND (s.ORIG_DATABASE_ID=? OR s.DEST_DATABASE_ID=?)",Integer.class,id,id);
        return count!=null&&count>0;
    }

    void insert(StoredDatabase db) {
        jdbc.update("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME,DATABASE_URL,DATABASE_USER,DATABASE_PASSWORD,DATABASE_DRIVER,DATABASE_ENCRYPTION,DATABASE_GENERATERKEY) VALUES(?,?,?,?,?,?,?,?)",
            db.id(),db.name(),db.url(),db.user(),db.password(),db.driver(),db.encryption(),db.key());
    }

    void update(StoredDatabase db) {
        jdbc.update("UPDATE TSS_BCOMP_DATABASE_CONFIG_NEW SET DATABASE_NAME=?,DATABASE_URL=?,DATABASE_USER=?,DATABASE_PASSWORD=?,DATABASE_DRIVER=?,DATABASE_ENCRYPTION=?,DATABASE_GENERATERKEY=? WHERE DATABASE_ID=?",
            db.name(),db.url(),db.user(),db.password(),db.driver(),db.encryption(),db.key(),db.id());
    }

    record StoredDatabase(long id,String name,String url,String user,String password,String driver,String encryption,String key) {
        DatabaseSummary summary(){return new DatabaseSummary(id,name,url,user,driver,encryption,password!=null&&!password.isEmpty());}
    }
}
