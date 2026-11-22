package io.policynim.packaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.support.PackagedMcpHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedExplicitPackIT {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String CREDENTIAL = "SYNTHETIC-PACK-MARKER-673";

    @Test
    void jarIncludesTheReaderAndParserDistributionNotices() throws Exception {
        var notices = List.of(
            "pack-reader/README.md", "pack-reader/asm.txt", "pack-reader/jffi.txt", "pack-reader/libffi.txt",
            "pack-reader/jnr-a64asm.txt", "pack-reader/jnr-x86asm.txt", "pack-reader/jnr-ffi-and-constants.txt",
            "pack-reader/jnr-posix.txt", "pack-reader/third-party-notices.txt",
            "policy-pack-parsers/Apache-2.0.txt", "policy-pack-parsers/ANTLR.txt",
            "policy-pack-parsers/Unicode.txt", "policy-pack-parsers/NOTICE.txt");
        try (var jar = new JarFile(Path.of("target", "policynim.jar").toFile())) {
            for (String name : notices) {
                String path = "META-INF/licenses/" + name;
                var entry = jar.getEntry(path);
                assertNotNull(entry, path);
                try (var expected = getClass().getResourceAsStream("/" + path);
                     var packaged = jar.getInputStream(entry)) {
                    assertNotNull(expected, path);
                    assertArrayEquals(expected.readAllBytes(), packaged.readAllBytes(), path);
                }
            }
        }
    }

    @Test
    void jarContainsPackDependenciesAndNativeBinaries() throws Exception {
        Path path = Path.of("target", "policynim.jar");
        try (var jar = new JarFile(path.toFile())) {
            for (String artifact : List.of("tomlj-2.2.0", "weburl-0.3.0", "jnr-posix-3.2.8", "jnr-ffi-2.3.7",
                    "jnr-constants-0.11.4", "jffi-1.4.7")) {
                assertNotNull(jar.getEntry("BOOT-INF/lib/" + artifact + ".jar"), artifact);
            }
            var nativeJar = jar.getEntry("BOOT-INF/lib/jffi-1.4.7-native.jar");
            assertNotNull(nativeJar, "Missing the jffi native classifier in the installed jar.");
            var binaries = new HashSet<String>();
            try (var nested = new ZipInputStream(jar.getInputStream(nativeJar))) {
                for (var entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
                    binaries.add(entry.getName());
                }
            }
            for (String binary : List.of("jni/Darwin/libjffi-1.2.jnilib", "jni/x86_64-Linux/libjffi-1.2.so",
                    "jni/x86_64-Windows/jffi-1.2.dll")) {
                assertTrue(binaries.contains(binary), binary);
            }
        }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        System.out.printf("Pack artifact %s on %s %s%n", HexFormat.of().formatHex(digest.digest()),
            System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    @Test
    void installedCommandsReadOnlyDeclaredMembersAndRecoverAfterCorrection(@TempDir Path work) throws Exception {
        JsonNode fixture;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/parity/catalog/explicit-logging.json"))) {
            fixture = JSON.readTree(resource);
        }
        Path pack = Files.createDirectory(work.resolve("selected pack"));
        String manifest = fixture.required("manifest").textValue();
        Path declaration = Files.writeString(pack.resolve("pack.toml"), manifest, UTF_8);
        Files.writeString(pack.resolve("policy.md"), fixture.required("member").textValue(), UTF_8);
        Files.write(pack.resolve("README.md"), new byte[]{(byte) 0xff});
        Files.write(pack.resolve("TEMPLATE.md"), new byte[]{(byte) 0xff});
        Path notice = Files.createDirectory(pack.resolve("licenses")).resolve("NOTICE.txt");
        Files.write(notice, new byte[]{(byte) 0xfe});
        JsonNode expected = fixture.required("catalog");
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var cli = new PackCommand(work, pack, occupied.getLocalPort());
            assertEquals(expected, cli.run("list").json(0));
            JsonNode entry = expected.required("policies").get(0);
            ObjectNode view = entry.deepCopy();
            view.put("scope", "source").putNull("generation_id");
            assertEquals(view, cli.run("show", "LOG-001").json(0));
            Files.write(notice, new byte[]{(byte) 0xff});
            var validation = cli.run("validate").json(0);
            assertEquals("ok", validation.required("status").textValue());
            assertEquals(expected.required("digest"), validation.required("catalog_digest"));
            assertEquals(expected.required("unlisted_markdown"), validation.required("unlisted_markdown"));
            for (var example : List.of(
                    Map.entry(manifest.replace("\"policy.md\"", "\"missing.md\""), "policies.0.path"),
                    Map.entry(manifest + "\n[[policies]]\npolicy_id = \"LOG-002\"\npath = \"./policy.md\"\n", "policies.1.path"),
                    Map.entry(manifest.replace("https://example.com", "https://user:" + CREDENTIAL + "@example.com"),
                        "policies.0.sources.0.url"))) {
                Files.writeString(declaration, example.getKey(), UTF_8);
                var invalid = cli.run("list");
                assertFalse((invalid.out() + invalid.err()).contains(CREDENTIAL), "A credential appeared in CLI output.");
                var error = invalid.json(1);
                assertEquals(4, error.size());
                assertEquals("error", error.required("status").textValue());
                assertEquals("source", error.required("scope").textValue());
                assertTrue(error.required("generation_id").isNull());
                assertTrue(error.required("message").textValue().contains(example.getValue()), error::toString);
                Files.writeString(declaration, manifest, UTF_8);
                assertEquals(expected, cli.run("list").json(0));
            }
        }
    }

    private record PackCommand(Path work, Path pack, int port) {

        Reply run(String... words) throws Exception {
            Path home = Files.createDirectories(work.resolve("home"));
            Path cwd = Files.createDirectories(work.resolve("cwd"));
            Path tmp = Files.createDirectories(work.resolve("tmp"));
            var env = Map.of("HOME", home.toString(), "LC_ALL", "C.UTF-8", "POLICYNIM_STORAGE_MODE", "jdbc",
                "SERVER_PORT", Integer.toString(port), "SERVER_ADDRESS", "127.0.0.1",
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:" + port + "/pack_test?connectTimeout=1");
            var launch = PackagedMcpHttp.jarCommand(home, env, "policies").directory(cwd.toFile());
            launch.command().add(1, "-Djava.io.tmpdir=" + tmp);
            launch.command().addAll(List.of(words));
            launch.command().addAll(List.of("--source", "--format", "json", "--corpus-root=" + pack));
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) {
                launch.environment().put("SystemRoot", systemRoot);
            }
            Path out = work.resolve("stdout");
            Path err = work.resolve("stderr");
            var process = launch.redirectErrorStream(false).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The installed pack command timed out.");
                return new Reply(process.exitValue(), Files.readString(out, UTF_8), Files.readString(err, UTF_8));
            } finally {
                PackagedMcpHttp.reap(process);
            }
        }
    }

    private record Reply(int exit, String out, String err) {
        JsonNode json(int expectedExit) throws IOException {
            assertEquals(expectedExit, exit, () -> "stdout: %.1200s%nstderr: %.1200s".formatted(out, err));
            assertTrue(err.isEmpty(), () -> "Unexpected stderr: " + err);
            return JSON.readTree(out);
        }
    }
}
