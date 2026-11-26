package cn.sunline.tss.tssveri.spark;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationIdentitySnapshotTest {
    @Test void freezesCodePhysicalSourceDisplayAndScopeBeforeComparisonStarts() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL","sa","");var sql=connection.createStatement()) {
            try(var reader=Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {RunScript.execute(connection,reader);}
            sql.execute("INSERT INTO TSS_BCOMP_CONF_NEW(BCOMP_INDEX,BCOMP_NAME,BCOMP_MODULE,SOURCE_TABLE_NAME,CONFIGURATION_NAME,CONFIGURATION_SCOPE) VALUES(1,'a_1','dept','a','配置乙','b=2')");
            sql.execute("INSERT INTO TSS_BCOMP_TABLE_SQL_NEW(BCOMP_INDEX) VALUES(1)");
            var frozen=DailyResultPublisher.context(connection,1);
            sql.execute("UPDATE TSS_BCOMP_CONF_NEW SET BCOMP_NAME='changed',SOURCE_TABLE_NAME='other',CONFIGURATION_NAME='已改名',CONFIGURATION_SCOPE='x=9'");
            sql.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES(1,'20261126','one','D','2')");
            String[] args=new String[21];args[6]="1";args[7]="20261126";args[8]="one";args[9]="D";args[20]="20261126";
            var totals=new ResultAggregate(0);totals.matched=3;
            DailyResultPublisher.publish(connection,args,frozen,totals);
            try(var result=sql.executeQuery("SELECT CONFIGURATION_NAME,SOURCE_TABLE_NAME,CONFIGURATION_DISPLAY_NAME,CONFIGURATION_SCOPE FROM TSS_BCOMP_RESULT_NEW")) {
                assertTrue(result.next());assertEquals("a_1",result.getString(1));assertEquals("a",result.getString(2));
                assertEquals("配置乙",result.getString(3));assertEquals("b=2",result.getString(4));
            }
        }
    }
}
