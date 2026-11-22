package io.policynim.mcp.health;

import io.policynim.config.PolicyNimProperties;
import io.policynim.mcp.transport.StreamableHttpTransportMarker;
import io.policynim.retrieval.PolicyChunkReadStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
public class RuntimeReadinessConfiguration {

    @Bean
    RuntimeReadinessService runtimeReadinessService(
        PolicyNimProperties properties,
        ObjectProvider<PolicyChunkReadStore> readStoreProvider,
        Optional<StreamableHttpTransportMarker> http
    ) {
        var mcp = properties.getMcp();
        String publicUrl = mcp.mcpUrl(http.map(StreamableHttpTransportMarker::path).orElse(mcp.getStreamableHttpPath()));
        return new StorageRuntimeReadinessService(
            properties.getStorage(),
            readStoreProvider.getIfAvailable(PolicyChunkReadStore::noOp),
            publicUrl
        );
    }
}
