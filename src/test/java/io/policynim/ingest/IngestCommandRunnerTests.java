package io.policynim.ingest;

import io.policynim.config.PolicyNimProperties;
import io.policynim.policy.chunk.PolicyChunker;
import io.policynim.policy.parse.MarkdownPolicyParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestCommandRunnerTests {

    @TempDir
    Path tempDir;

    @Test
    void runsIngestCommandWithNormalizedCorpusRootWhenJdbcStorageIsConfigured() {
        Path policiesDir = tempDir.resolve("policies");
        RecordingIngestService ingestService = new RecordingIngestService();
        IngestCommandRunner runner = new IngestCommandRunner(
            ingestService,
            jdbcProperties(),
            output()
        );

        runner.run(args("ingest", "--corpus-root=" + policiesDir));

        assertThat(ingestService.lastCommand.corpusRoot()).isEqualTo(policiesDir.toAbsolutePath().normalize());
        assertThat(ingestService.callCount).isEqualTo(1);
    }

    @Test
    void commandInvokesTheConfiguredIngestFlowAndStore() throws IOException {
        Path policiesDir = tempDir.resolve("policies");
        writePolicy(
            policiesDir.resolve("backend/logging.md"),
            """
                ---
                policy_id: BACKEND-LOG-001
                ---
                # Logging

                ## Rules

                Log with request context.
                """
        );
        RecordingPolicyChunkStore store = new RecordingPolicyChunkStore();
        IngestCommandRunner runner = new IngestCommandRunner(
            new IngestService(
                new FileSystemPolicyCorpus(new MarkdownPolicyParser()),
                new PolicyChunker(),
                store
            ),
            jdbcProperties(),
            output()
        );

        runner.run(args("ingest", "--corpus-root=" + policiesDir));

        assertThat(store.storedCorpus.corpusRoot()).isEqualTo(policiesDir.toAbsolutePath().normalize());
        assertThat(store.storedCorpus.chunks()).extracting(chunk -> chunk.chunkId()).containsExactly(
            "BACKEND-LOG-001:logging",
            "BACKEND-LOG-001:logging__rules"
        );
    }

    @Test
    void ignoresNonIngestApplicationStartup() {
        RecordingIngestService ingestService = new RecordingIngestService();
        IngestCommandRunner runner = new IngestCommandRunner(
            ingestService,
            jdbcProperties(),
            output()
        );

        runner.run(args("--policynim.storage.mode=jdbc"));

        assertThat(ingestService.callCount).isZero();
    }

    @Test
    void rejectsIngestCommandWhenCorpusRootIsMissing() {
        IngestCommandRunner runner = new IngestCommandRunner(
            new RecordingIngestService(),
            jdbcProperties(),
            output()
        );

        assertThatThrownBy(() -> runner.run(args("ingest")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(IngestCommandLine.USAGE)
            .hasMessageContaining("--corpus-root=<path>");
    }

    @Test
    void rejectsIngestCommandWhenStorageModeWouldUseNoopStore() {
        IngestCommandRunner runner = new IngestCommandRunner(
            new RecordingIngestService(),
            new PolicyNimProperties(),
            output()
        );

        assertThatThrownBy(() -> runner.run(args("ingest", "--corpus-root=" + tempDir.resolve("policies"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("policynim.storage.mode=jdbc")
            .hasMessageContaining(IngestCommandLine.COMMAND_EXAMPLE);
    }

    @Test
    void printsHelpWithoutRunningIngest() {
        RecordingIngestService ingestService = new RecordingIngestService();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        IngestCommandRunner runner = new IngestCommandRunner(
            ingestService,
            jdbcProperties(),
            new PrintStream(buffer, true, StandardCharsets.UTF_8)
        );

        runner.run(args("ingest", "--help"));

        assertThat(ingestService.callCount).isZero();
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains(IngestCommandLine.USAGE);
    }

    private static DefaultApplicationArguments args(String... args) {
        return new DefaultApplicationArguments(args);
    }

    private static PolicyNimProperties jdbcProperties() {
        PolicyNimProperties properties = new PolicyNimProperties();
        properties.getStorage().setMode(PolicyNimProperties.StorageMode.JDBC);
        return properties;
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static void writePolicy(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.stripLeading());
    }

    private static final class RecordingPolicyChunkStore implements PolicyChunkStore {

        private IngestedPolicyCorpus storedCorpus;

        @Override
        public void replaceAll(IngestedPolicyCorpus corpus) {
            this.storedCorpus = corpus;
        }
    }

    private static final class RecordingIngestService extends IngestService {

        private IngestCommand lastCommand;
        private int callCount;

        private RecordingIngestService() {
            super(corpusRoot -> List.of(), new PolicyChunker());
        }

        @Override
        public IngestedPolicyCorpus ingest(IngestCommand command) {
            this.lastCommand = command;
            this.callCount++;
            return new IngestedPolicyCorpus(command.corpusRoot(), List.of(), List.of());
        }
    }
}
