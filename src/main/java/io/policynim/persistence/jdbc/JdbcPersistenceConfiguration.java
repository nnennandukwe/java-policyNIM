package io.policynim.persistence.jdbc;

import io.policynim.config.PolicyNimProperties;
import io.policynim.ingest.PolicyChunkStore;
import io.policynim.provider.PolicyEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "policynim.storage", name = "mode", havingValue = "jdbc")
public class JdbcPersistenceConfiguration {

    @Bean
    PolicyChunkStore policyChunkStore(
        JdbcTemplate jdbcTemplate,
        PolicyNimProperties properties,
        PlatformTransactionManager transactionManager,
        ObjectProvider<PolicyEmbeddingModel> embeddingModelProvider
    ) {
        return new JdbcPolicyChunkStore(
            jdbcTemplate,
            properties.getStorage().getTableName(),
            new TransactionTemplate(transactionManager),
            embeddingModelProvider.getIfAvailable(PolicyEmbeddingModel::noOp)
        );
    }
}
