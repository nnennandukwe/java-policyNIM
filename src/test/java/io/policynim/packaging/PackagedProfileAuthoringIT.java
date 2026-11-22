package io.policynim.packaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedProfileAuthoringIT {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @TempDir Path directory;
    private Path work;
    private JsonNode fixture;

    @BeforeEach
    void preparePacks() throws Exception {
        work = Files.createDirectory(directory.resolve("work"));
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/parity/catalog/profile-authoring.json"))) {
            fixture = JSON.readTree(input);
        }
        for (var file : fixture.required("files").properties()) {
            Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue().textValue(), UTF_8);
        }
    }

    @Test
    void simultaneousInitializersPublishOneCompleteProfile() throws Exception {
        String name = "deep/profiles/first.toml";
        try (Child first = start("init", name, "--pack", "base", "--id", "alpha");
             Child second = start("init", name, "--pack", "team", "--id", "beta")) {
            Result base = first.finish();
            Result team = second.finish();
            assertEquals(List.of(0, 1), List.of(base.exit(), team.exit()).stream().sorted().toList());
            Result winner = base.exit() == 0 ? base : team;
            assertRefusal(base.exit() == 0 ? team : base);
            Path file = work.resolve(name);
            JsonNode written = winner.json().required("profile");
            assertEquals(base.exit() == 0 ? "alpha" : "beta", written.required("profile_id").textValue());
            assertEquals(base.exit() == 0 ? "review.base" : "review.team",
                written.required("packs").get(0).required("pack_id").textValue());
            byte[] original = Files.readAllBytes(file);
            try (Child reader = start("preview", name)) {
                JsonNode catalog = reader.finish().success();
                assertEquals(written.required("profile_id"), catalog.required("profile_id"));
                assertEquals(written.required("packs").get(0).required("digest"),
                    catalog.required("pack_pins").get(0).required("digest"));
            }
            try (Child writer = start("init", name, "--pack", "base")) {
                assertRefusal(writer.finish());
                assertArrayEquals(original, Files.readAllBytes(file));
            }
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            }
            try (var output = Files.list(file.getParent())) {
                assertEquals(List.of(file), output.toList());
            }
        }
    }

    @Test
    void existingLinksSurviveAndAnUnusedNameSucceeds() throws Exception {
        Path outputs = Files.createDirectory(work.resolve("profiles"));
        Path existing = Files.writeString(outputs.resolve("original"), "operator content", UTF_8);
        Path alias = Files.createLink(outputs.resolve("hard.toml"), existing);
        Path linked = Files.createSymbolicLink(outputs.resolve("linked.toml"), existing.getFileName());
        Path absent = outputs.resolve("absent");
        Path dangling = Files.createSymbolicLink(outputs.resolve("dangling.toml"), absent.getFileName());
        for (Path occupied : List.of(existing, alias, linked, dangling)) {
            try (Child app = start("init", work.relativize(occupied).toString(), "--pack", "team")) {
                assertRefusal(app.finish());
            }
        }
        assertTrue(Files.isSameFile(existing, alias));
        assertEquals(existing.getFileName(), Files.readSymbolicLink(linked));
        assertEquals(absent.getFileName(), Files.readSymbolicLink(dangling));
        assertFalse(Files.exists(absent));
        assertEquals("operator content", Files.readString(existing, UTF_8));
        Path actual = Files.createDirectories(work.resolve("nested/reviews"));
        Files.createSymbolicLink(outputs.resolve("team"), outputs.relativize(actual));
        try (Child app = start("init", "profiles/team/new.toml", "--pack", "team")) {
            JsonNode pin = app.finish().success().required("profile").required("packs").get(0);
            assertEquals("../../team", pin.required("path").textValue());
        }
        try (Child viaLink = start("preview", "profiles/team/new.toml");
             Child direct = start("preview", "nested/reviews/new.toml")) {
            assertEquals(direct.finish().success(), viaLink.finish().success());
        }
    }

    @Test
    void emittedBytesAndReadOnlyReviewSurviveSourceChanges() throws Exception {
        String name = fixture.required("profileDestination").textValue();
        try (Child writer = start("init", name, "--pack", "team", "--pack", "base", "--id", "review")) {
            writer.finish().success();
        }
        Path profile = work.resolve(name);
        byte[] expected = fixture.required("authoredProfile").textValue().replace("\n", System.lineSeparator()).getBytes(UTF_8);
        assertArrayEquals(expected, Files.readAllBytes(profile));
        var modified = Files.getLastModifiedTime(profile);
        for (String key : List.of("clean", "editExpected", "clean")) {
            var state = key.equals("editExpected") ? fixture.required("edit") : fixture.required("files");
            for (var file : state.properties()) {
                Files.writeString(work.resolve(file.getKey()), file.getValue().textValue(), UTF_8);
            }
            try (Child diff = start("diff", name)) {
                JsonNode report = diff.finish().success();
                JsonNode golden = fixture.required(key);
                for (String field : List.of("profile_id", "review_digest", "added", "changed", "removed", "changed_packs",
                    "invalidated_decisions", "validation_error", "warnings")) {
                    assertEquals(golden.required(field), report.required(field), field);
                }
                assertEquals(golden.required("memberCount").asInt(), report.required("current_policies").size());
            }
            assertArrayEquals(expected, Files.readAllBytes(profile));
            assertEquals(modified, Files.getLastModifiedTime(profile));
        }
    }

    private static void assertRefusal(Result result) {
        assertEquals(1, result.exit());
        assertEquals(2, result.json().size());
        assertEquals("error", result.json().required("status").asText());
        assertTrue(result.json().required("message").asText().contains("already exists"));
    }

    private Child start(String... args) throws Exception {
        var builder = PackagedMcpHttp.jarCommand(directory, Map.of("HOME", directory.toString(),
            "POLICYNIM_MCP_TRANSPORT", "stdio", "POLICYNIM_EVAL_WORKSPACE_DIR", directory.resolve("evaluations").toString()), "profile");
        builder.command().addAll(List.of(args));
        builder.command().add("--format=json");
        Path out = Files.createTempFile(directory, "profile-", ".out");
        Path err = Files.createTempFile(directory, "profile-", ".err");
        Process child = builder.directory(work.toFile()).redirectErrorStream(false)
            .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        return new Child(child, out, err);
    }

    private record Result(int exit, JsonNode json) {
        JsonNode success() {
            assertEquals(0, exit, json::toString);
            return json;
        }
    }

    private record Child(Process child, Path out, Path err) implements AutoCloseable {
        Result finish() throws Exception {
            child.getOutputStream().close();
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Installed jar didn't exit");
            assertEquals("", Files.readString(err, UTF_8));
            return new Result(child.exitValue(), JSON.readTree(Files.readAllBytes(out)));
        }

        @Override
        public void close() throws InterruptedException {
            PackagedMcpHttp.reap(child);
        }
    }
}
