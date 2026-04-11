package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;
import io.policynim.retrieval.PolicyChunkReadStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeReadinessConfiguration {

    @Bean
    RuntimeReadinessService runtimeReadinessService(
        PolicyNimProperties properties,
        ObjectProvider<PolicyChunkReadStore> readStoreProvider
    ) {
        return new StorageRuntimeReadinessService(
            properties,
            readStoreProvider.getIfAvailable(PolicyChunkReadStore::noOp)
        );
    }
}
