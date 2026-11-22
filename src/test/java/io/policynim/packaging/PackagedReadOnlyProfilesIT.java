package io.policynim.packaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedReadOnlyProfilesIT {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void previewRetainsDisplacedPoliciesAndRecoversAfterAConflict(@TempDir Path directory) throws Exception {
        JsonNode fixture;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/parity/catalog/profile-decisions.json"))) {
            fixture = JSON.readTree(input);
        }
        Path work = Files.createDirectory(directory.resolve("work"));
        Path selected = Files.createDirectory(work.resolve("selected"));
        for (var file : fixture.required("files").properties()) {
            Path path = selected.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue().textValue(), UTF_8);
        }
        Path profile = selected.resolve("team.toml");
        String relative = work.relativize(profile).toString();
        Map<String, String> env = Map.of("HOME", directory.toString(), "POLICYNIM_MCP_TRANSPORT", "stdio",
            "POLICYNIM_PROFILE", relative, "POLICYNIM_EVAL_WORKSPACE_DIR", directory.resolve("evaluations").toString());
        var expected = fixture.required("catalog");
        assertEquals(expected, run(work, env, 0, "profile", "preview", relative));
        assertEquals(expected, run(work, env, 0, "policies", "list", "--source"));
        JsonNode replaced = run(work, env, 0, "policies", "show", "A", "--source");
        assertEquals("replaced", replaced.required("state").textValue());
        assertEquals("D", replaced.required("replacement_id").textValue());
        assertEquals(expected.required("policies").get(0).required("body"), replaced.required("body"));

        String original = Files.readString(profile, UTF_8);
        Files.writeString(profile, original + "\n[[conflicts]]\nleft=\"C\"\nright=\"D\"\nreason=\"Choose one.\"\n", UTF_8);
        var invalid = run(work, env, 1, "profile", "validate", relative);
        assertEquals(2, invalid.size());
        assertEquals("error", invalid.required("status").textValue());
        assertTrue(invalid.required("message").textValue().contains("Declared conflict"));
        Files.writeString(profile, original, UTF_8);
        assertEquals(expected.required("digest"), run(work, env, 0, "profile", "validate", relative).required("catalog_digest"));
    }

    private static JsonNode run(Path work, Map<String, String> env, int exit, String... arguments) throws Exception {
        ProcessBuilder command = PackagedMcpHttp.jarCommand(work.getParent(), env, arguments);
        command.command().add("--format=json");
        Path stdout = work.resolve("stdout");
        Path stderr = work.resolve("stderr");
        Process child = command.directory(work.toFile()).redirectErrorStream(false)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            child.getOutputStream().close();
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), () -> "Timed out " + String.join(" ", arguments));
            assertEquals(exit, child.exitValue(), () -> String.join(" ", arguments));
            assertTrue(Files.readString(stderr, UTF_8).isBlank(), () -> "Unexpected stderr for " + String.join(" ", arguments));
            return JSON.readTree(Files.readAllBytes(stdout));
        } finally {
            PackagedMcpHttp.reap(child);
        }
    }
}
