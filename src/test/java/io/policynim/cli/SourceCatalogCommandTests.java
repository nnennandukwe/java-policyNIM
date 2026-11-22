package io.policynim.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCatalogCommandTests {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return SourceCatalogCommand.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void helpNeedsNoCorpusOrSpringConfiguration() {
        assertThat(run("policies", "show", "--help")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("show", "--corpus-root", "--format", "--source");
        assertThat(err.size()).isZero();
    }

    @Test
    void firstOperandSelectsTheCommandEvenWhenItsSyntaxNeedsCorrection() {
        String[] misplaced = {"--spring.main.banner-mode=off", "policies", "validate"};
        assertThat(SourceCatalogCommand.isSourceCatalogCommand(misplaced)).isTrue();
        assertThat(run(misplaced)).isEqualTo(2);
        assertThat(out.size()).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Place policies first");
        assertThat(SourceCatalogCommand.isSourceCatalogCommand(new String[]{"ingest", "policies"})).isFalse();
        assertThat(SourceCatalogCommand.isSourceCatalogCommand(new String[]{"--policynim.storage.mode=noop"})).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--corpus-root=", "--format=other", "--source=false", "--unknown"})
    void rejectsInvalidOptionsAsUsageErrorsBeforeOpeningTheCorpus(String option) {
        assertThat(run("policies", "validate", "--source", option)).isEqualTo(2);
        assertThat(out.size()).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage");
    }

    @Test
    void requiresAnExactPolicyIdForShow(@TempDir Path root) {
        assertThat(run("policies", "show", "--source", "--corpus-root", root.toString(), "--format", "json"))
            .isEqualTo(2);
        assertThat(out.size()).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("policy ID");
    }

    @Test
    void rejectsMultipleRootsBeforeAccessingEitherOne() {
        assertThat(run("policies", "list", "--source", "--corpus-root=one", "--corpus-root=two"))
            .isEqualTo(2);
        assertThat(out.size()).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--corpus-root");
    }
}
