package io.policynim.persistence.jdbc;

import io.policynim.config.PolicyNimProperties;
import io.policynim.ingest.PolicyChunkStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class JdbcPersistenceConfiguration {

    @Bean
    PolicyChunkStore policyChunkStore(
        JdbcTemplate jdbcTemplate,
        PolicyNimProperties properties,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcPolicyChunkStore(
            jdbcTemplate,
            properties.getStorage().getTableName(),
            new TransactionTemplate(transactionManager)
        );
    }
}
