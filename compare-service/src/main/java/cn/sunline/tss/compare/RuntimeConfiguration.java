package cn.sunline.tss.compare;

import cn.sunline.tss.compare.metadata.MetadataDatabasePreflight;
import cn.sunline.tss.compare.metadata.MetadataDbType;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RuntimeConfiguration {
    @Bean(destroyMethod="close")
    public HikariDataSource dataSource(Environment env) {
        var source=new HikariDataSource();
        String primary=env.getProperty("spring.datasource.dynamic.primary","master");
        String prefix="spring.datasource.dynamic.datasource."+primary+".";
        source.setJdbcUrl(property(env,prefix,"url"));
        source.setUsername(property(env,prefix,"username"));
        source.setPassword(property(env,prefix,"password"));
        source.setDriverClassName(property(env,prefix,"driver-class-name"));
        source.setMaximumPoolSize(env.getProperty("spring.datasource.hikari.maximum-pool-size",Integer.class,
            env.getProperty("spring.datasource.dynamic.druid.max-active",Integer.class,10)));
        source.setMinimumIdle(env.getProperty("spring.datasource.hikari.minimum-idle",Integer.class,1));
        source.setConnectionTimeout(env.getProperty("spring.datasource.hikari.connection-timeout",Long.class,30000L));
        source.setPoolName("tss-compare-metadata");
        try {
            new MetadataDatabasePreflight(MetadataDbType.parse(env.getProperty("bd.metadata-db-type", "GAUSSDB")))
                .validate(source);
        } catch (RuntimeException error) {
            source.close();
            throw error;
        }
        return source;
    }
    private String property(Environment env,String legacy,String name) {
        String value=env.getProperty(legacy+name,env.getProperty("spring.datasource."+name));
        if(value==null) throw new IllegalArgumentException("缺少数据源配置："+legacy+name+" 或 spring.datasource."+name);
        return value;
    }
}
