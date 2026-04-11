package io.policynim.preflight;

import io.policynim.provider.PolicyPreflightGenerator;
import io.policynim.retrieval.SearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PreflightConfiguration {

    @Bean
    PreflightService preflightService(
        SearchService searchService,
        ObjectProvider<PolicyPreflightGenerator> generatorProvider
    ) {
        return new PreflightService(
            searchService,
            generatorProvider.getIfAvailable(PolicyPreflightGenerator::noOp)
        );
    }
}
