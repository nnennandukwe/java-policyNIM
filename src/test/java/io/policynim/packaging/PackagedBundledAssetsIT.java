package io.policynim.packaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedBundledAssetsIT {

    private static final Path JAR = Path.of("target", "policynim.jar");
    private static final String BUNDLE = "BOOT-INF/classes/io/policynim/bundled/";
    private static final String LICENSES = "META-INF/licenses/policynim-bundle/";

    @Test
    void packagedFilesMatchThePinnedPolicyAndEvaluationTrees() throws Exception {
        var expected = expectedHashes();
        assertEquals(59, expected.size());
        try (var jar = new JarFile(JAR.toFile())) {
            var members = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .map(JarEntry::getName)
                .filter(name -> name.startsWith(BUNDLE + "policies/") || name.startsWith(BUNDLE + "evals/"))
                .map(name -> name.substring(BUNDLE.length()))
                .sorted()
                .toList();
            assertEquals(expected.keySet().stream().sorted().toList(), members, "Bundled resource names");
            for (var file : expected.entrySet()) {
                assertEquals(file.getValue(), sha256(read(jar, BUNDLE + file.getKey())), file.getKey());
            }
        }
    }

    @Test
    void installedEvaluationFixturesContainTheirAuthoredCases() throws Exception {
        var counts = Map.of("default_cases.json", 6, "grounded_cases.json", 10,
            "engineering_scenarios.json", 64, "security_mcp_scenarios.json", 34);
        var json = new ObjectMapper();
        try (var jar = new JarFile(JAR.toFile())) {
            for (var fixture : counts.entrySet()) {
                String name = fixture.getKey();
                var cases = json.readTree(read(jar, BUNDLE + "evals/" + name)).required("cases");
                assertTrue(cases.isArray(), name);
                assertEquals(fixture.getValue().intValue(), cases.size(), name);
            }
        }
    }

    @Test
    void jarCarriesTheUpstreamLicenseAndSourceAttribution() throws Exception {
        try (var jar = new JarFile(JAR.toFile())) {
            assertEquals("8a60c102e091e477f194fa90d76bcfc4a0e76263bfd9e540f3f05133e7a1816b",
                sha256(read(jar, LICENSES + "LICENSE")));
            var notice = new String(read(jar, LICENSES + "NOTICE.md"), StandardCharsets.UTF_8);
            for (String pointer : List.of("fcc5322a6672b0275dcc6e0c299e631df8e8de19",
                "io/policynim/bundled/policies/", "io/policynim/bundled/evals/", "CC BY-SA 4.0")) {
                assertTrue(notice.contains(pointer), pointer);
            }
        }
    }

    private static Map<String, String> expectedHashes() throws IOException {
        String path = "/parity/catalog/bundled-assets.sha256";
        try (var stream = PackagedBundledAssetsIT.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            var expected = new HashMap<String, String>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String[] fields = line.split("  ", 2);
                assertEquals(2, fields.length, line);
                assertNull(expected.put(fields[1], fields[0]), fields[1]);
            }
            return expected;
        }
    }

    private static byte[] read(JarFile jar, String path) throws IOException {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, path);
        try (var stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
