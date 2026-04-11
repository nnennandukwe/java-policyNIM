package io.policynim.ingest;

import io.policynim.config.PolicyNimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestCommandRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestCommandRunner.class);

    private final IngestService ingestService;
    private final PolicyNimProperties properties;
    private final PrintStream output;

    @Autowired
    public IngestCommandRunner(IngestService ingestService, PolicyNimProperties properties) {
        this(ingestService, properties, System.out);
    }

    IngestCommandRunner(IngestService ingestService, PolicyNimProperties properties, PrintStream output) {
        this.ingestService = Objects.requireNonNull(ingestService, "ingestService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        IngestCommandLine.ParsedCommand command = IngestCommandLine.parse(args);
        if (command.action() == IngestCommandLine.Action.NONE) {
            return;
        }
        if (command.action() == IngestCommandLine.Action.HELP) {
            output.println(IngestCommandLine.USAGE);
            return;
        }

        requireJdbcStorage();
        Path corpusRoot = command.corpusRoot().orElseThrow();
        IngestedPolicyCorpus corpus = ingestService.ingest(new IngestCommand(corpusRoot));
        String message = "Ingested %d policy documents into %d policy chunks from %s.".formatted(
            corpus.documents().size(),
            corpus.chunks().size(),
            corpus.corpusRoot()
        );
        output.println(message);
        LOGGER.info(message);
    }

    private void requireJdbcStorage() {
        if (properties.getStorage().getMode() == PolicyNimProperties.StorageMode.JDBC) {
            return;
        }
        throw new IllegalStateException(
            "The ingest command persists policy chunks and requires policynim.storage.mode=jdbc. "
                + "Re-run " + IngestCommandLine.COMMAND_EXAMPLE
                + " with JDBC storage and datasource settings configured."
        );
    }
}
