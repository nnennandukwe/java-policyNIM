package io.policynim.ingest;

import java.nio.file.Path;
import java.util.Objects;

public record IngestCommand(Path corpusRoot) {

    public IngestCommand {
        Objects.requireNonNull(corpusRoot, "corpusRoot must not be null");
    }
}
