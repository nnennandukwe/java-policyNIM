package io.policynim.provider.nvidia;

import io.policynim.provider.PolicyEmbeddingModel;
import io.policynim.provider.PolicyReranker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NvidiaProviderProperties.class)
class NvidiaProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "policynim.provider.nvidia", name = "enabled", havingValue = "true")
    PolicyEmbeddingModel policyEmbeddingModel(RestClient.Builder restClientBuilder, NvidiaProviderProperties properties) {
        validateApiKey(properties);
        return new NvidiaEmbeddingClient(restClientBuilder, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "policynim.provider.nvidia", name = "enabled", havingValue = "true")
    PolicyReranker policyReranker(RestClient.Builder restClientBuilder, NvidiaProviderProperties properties) {
        validateApiKey(properties);
        return new NvidiaReranker(restClientBuilder, properties);
    }

    private static void validateApiKey(NvidiaProviderProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                "policynim.provider.nvidia.api-key must be configured when the NVIDIA provider is enabled."
            );
        }
    }
}
