package io.policynim.packaging;

import io.policynim.ingest.IngestCommandLine;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReleasePackagingContractsTests {

    private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
    private static final String OFFLINE_PROVIDER_ENV = "POLICYNIM_PROVIDER_NVIDIA_ENABLED=false";

    @Test
    void ciWorkflowSplitsOfflineVerificationAndPackagingJobs() throws IOException {
        Map<String, Object> workflow = yamlMap(".github/workflows/ci.yml");
        Map<String, Object> jobs = mapValue(workflow, "jobs");

        assertThat(jobs).containsOnlyKeys("unit", "integration", "acceptance", "archunit", "package");
        jobs.forEach((name, definition) -> {
            Map<String, Object> job = asMap(definition);
            assertThat(job).as(name).containsEntry("runs-on", "ubuntu-24.04");
            assertThat(actionReferences(job)).as(name).allMatch(
                ReleasePackagingContractsTests::usesPinnedActionReference
            );
        });

        assertThat(runCommands(jobs, "unit"))
            .anySatisfy(command -> assertThat(command)
                .contains(OFFLINE_PROVIDER_ENV)
                .contains("./mvnw -B")
                .contains("test"));
        assertThat(runCommands(jobs, "integration"))
            .anySatisfy(command -> assertThat(command)
                .contains(OFFLINE_PROVIDER_ENV)
                .contains("test-compile")
                .contains("-Dit.test=io.policynim.persistence.jdbc.*IT")
                .contains("failsafe:integration-test failsafe:verify"));
        assertThat(runCommands(jobs, "acceptance"))
            .anySatisfy(command -> assertThat(command)
                .contains(OFFLINE_PROVIDER_ENV)
                .contains("test-compile")
                .contains("-Dit.test=PolicyNimAcceptanceIT")
                .contains("failsafe:integration-test failsafe:verify"));
        assertThat(runCommands(jobs, "archunit"))
            .anySatisfy(command -> assertThat(command)
                .contains(OFFLINE_PROVIDER_ENV)
                .contains("-Dtest=PackageArchitectureTest")
                .contains("test"));
        assertThat(runCommands(jobs, "package"))
            .anySatisfy(command -> assertThat(command)
                .contains(OFFLINE_PROVIDER_ENV)
                .contains("-DskipTests package"))
            .anySatisfy(command -> assertThat(command)
                .contains("jar tf target/policynim.jar")
                .contains("BOOT-INF/classes/application.yml"));
    }

    @Test
    void dockerPackagingUsesPinnedBaseImagesAndNonRootRuntime() throws IOException {
        String dockerfile = Files.readString(REPOSITORY_ROOT.resolve("Dockerfile"));

        assertThat(dockerfile)
            .contains("FROM eclipse-temurin:21-jdk-jammy@sha256:")
            .contains("FROM eclipse-temurin:21-jre-jammy@sha256:")
            .contains("COPY .mvn .mvn")
            .contains("COPY mvnw pom.xml ./")
            .contains("RUN ./mvnw -B -DskipTests package")
            .contains("RUN addgroup --system policynim && adduser --system --ingroup policynim policynim")
            .contains("COPY --from=build /workspace/target/policynim.jar /app/policynim.jar")
            .contains("USER policynim")
            .contains("EXPOSE 8080")
            .contains("HEALTHCHECK")
            .contains("http://127.0.0.1:8080/livez")
            .contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/policynim.jar\"]")
            .doesNotContain("java-policynim-0.0.1-SNAPSHOT.jar")
            .doesNotContain("POLICYNIM_PROVIDER_NVIDIA_API_KEY")
            .doesNotContain("POLICYNIM_MCP_BEARER_TOKEN=");
    }

    @Test
    void dockerContextExcludesBuildOutputsGitMetadataAndLocalSecrets() throws IOException {
        String dockerignore = Files.readString(REPOSITORY_ROOT.resolve(".dockerignore"));

        assertThat(dockerignore)
            .contains("target/")
            .contains(".git/")
            .contains(".env")
            .contains(".qodo/")
            .contains(".claude/")
            .contains(".implementation-plan.md")
            .contains("*.iml");
    }

    @Test
    void docsPublishTheExecutableIngestCommand() throws IOException {
        assertThat(Files.readString(REPOSITORY_ROOT.resolve("README.md")))
            .contains(IngestCommandLine.COMMAND_EXAMPLE);
        assertThat(Files.readString(REPOSITORY_ROOT.resolve("docs/hosted-runbook.md")))
            .contains(IngestCommandLine.COMMAND_EXAMPLE);
    }

    private static boolean usesPinnedActionReference(String actionReference) {
        int separator = actionReference.lastIndexOf('@');
        return separator > 0 && actionReference.substring(separator + 1).matches("[a-f0-9]{40}");
    }

    private static List<String> actionReferences(Map<String, Object> job) {
        return steps(job).stream()
            .filter(step -> step.containsKey("uses"))
            .map(step -> step.get("uses").toString())
            .toList();
    }

    private static List<String> runCommands(Map<String, Object> jobs, String jobName) {
        return steps(asMap(jobs.get(jobName))).stream()
            .filter(step -> step.containsKey("run"))
            .map(step -> step.get("run").toString())
            .toList();
    }

    private static List<Map<String, Object>> steps(Map<String, Object> job) {
        return listOfMaps(job.get("steps"));
    }

    private static Map<String, Object> yamlMap(String path) throws IOException {
        try (Reader reader = Files.newBufferedReader(REPOSITORY_ROOT.resolve(path))) {
            return asMap(new Yaml().load(reader));
        }
    }

    private static Map<String, Object> mapValue(Map<String, Object> map, String key) {
        return asMap(map.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }
}
