package io.policynim.ingest;

import io.policynim.policy.chunk.PolicyChunker;
import io.policynim.policy.parse.MarkdownPolicyParser;
import io.policynim.policy.parse.PolicyParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IngestConfiguration {

    @Bean
    PolicyParser policyParser() {
        return new MarkdownPolicyParser();
    }

    @Bean
    PolicyChunker policyChunker(PolicyParser parser) {
        return new PolicyChunker(parser);
    }

    @Bean
    PolicyCorpus policyCorpus(PolicyParser parser) {
        return new FileSystemPolicyCorpus(parser);
    }

    @Bean
    IngestService ingestService(
        PolicyCorpus corpus,
        PolicyChunker chunker,
        ObjectProvider<PolicyChunkStore> chunkStoreProvider
    ) {
        return new IngestService(corpus, chunker, chunkStoreProvider.getIfAvailable(PolicyChunkStore::noOp));
    }
}
