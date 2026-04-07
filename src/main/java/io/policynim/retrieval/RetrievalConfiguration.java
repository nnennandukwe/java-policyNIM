package io.policynim.retrieval;

import io.policynim.provider.PolicyEmbeddingModel;
import io.policynim.provider.PolicyReranker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RetrievalConfiguration {

    @Bean
    SearchService searchService(
        ObjectProvider<PolicyChunkReadStore> readStoreProvider,
        ObjectProvider<PolicyEmbeddingModel> embeddingModelProvider,
        ObjectProvider<PolicyReranker> rerankerProvider
    ) {
        return new SearchService(
            readStoreProvider.getIfAvailable(PolicyChunkReadStore::noOp),
            embeddingModelProvider.getIfAvailable(PolicyEmbeddingModel::noOp),
            rerankerProvider.getIfAvailable(PolicyReranker::noOp)
        );
    }
}
