package cn.sunline.tss.compare.metadata;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MetadataDialectConfiguration {
    @Bean
    MetadataDbType metadataDbType(Environment environment) {
        return MetadataDbType.parse(environment.getProperty("bd.metadata-db-type", "GAUSSDB"));
    }

    @Bean
    MetadataSqlDialect metadataSqlDialect(MetadataDbType type) {
        return MetadataSqlDialect.forType(type);
    }
}
