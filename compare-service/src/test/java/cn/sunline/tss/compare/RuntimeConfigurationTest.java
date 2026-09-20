package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class RuntimeConfigurationTest {
    @Test void readsOriginalNamedDynamicDatasourceWithoutRenamingItsKeys() throws Exception {
        var env=new MockEnvironment().withProperty("spring.datasource.dynamic.primary","frw")
            .withProperty("spring.datasource.dynamic.datasource.frw.url","jdbc:h2:mem:original_config")
            .withProperty("spring.datasource.dynamic.datasource.frw.username","sa")
            .withProperty("spring.datasource.dynamic.datasource.frw.password","")
            .withProperty("spring.datasource.dynamic.datasource.frw.driver-class-name","org.h2.Driver");
        try(var pool=new RuntimeConfiguration().dataSource(env); var c=pool.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo("jdbc:h2:mem:original_config");
        }
    }
    @Test void acceptsOldTwoClassConfigAndNewSingleClassConfig() {
        String table="cn.sunline.tss.tssveri.spark.SparkHandlerTable";
        assertThat(SparkJobLauncher.tableMainClass("cn.sunline.tss.tssveri.spark.SparkHandlerFile,"+table)).isEqualTo(table);
        assertThat(SparkJobLauncher.tableMainClass(table)).isEqualTo(table);
        assertThatThrownBy(() -> SparkJobLauncher.tableMainClass("file,")).isInstanceOf(IllegalArgumentException.class);
    }
}
