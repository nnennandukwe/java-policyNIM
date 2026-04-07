package io.policynim.persistence.jdbc;

import io.policynim.config.PolicyNimProperties;
import io.policynim.retrieval.PolicyChunkReadStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "policynim.storage", name = "mode", havingValue = "jdbc")
class JdbcRetrievalConfiguration {

    @Bean
    PolicyChunkReadStore policyChunkReadStore(JdbcTemplate jdbcTemplate, PolicyNimProperties properties) {
        return new JdbcPolicyChunkReadStore(jdbcTemplate, properties.getStorage().getTableName());
    }
}
