package io.policynim.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.WINDOWS)
class WindowsProfilePathTests {
    @ParameterizedTest
    @ValueSource(strings = {"json", "text"})
    void initReportsAReusableDriveRelativePath(String format, @TempDir Path directory) throws Exception {
        Path location = directory.toRealPath();
        String root = location.getRoot().toString();
        assertThat(root).matches("[A-Za-z]:\\\\");
        String drive = root.substring(0, 2);
        Path fromDriveDirectory = Path.of(drive + ".").toRealPath().relativize(location);
        Files.createDirectory(location.resolve("kept"));
        String tail = fromDriveDirectory.resolve("kept").resolve("..").resolve("team.toml").toString();
        String input = drive + ".\\" + tail;
        String expected = drive + tail;
        assertThat(Path.of(input).isAbsolute()).isFalse();

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var output = new PrintStream(out, true, UTF_8); var errors = new PrintStream(err, true, UTF_8)) {
            int status = ProfileCommand.run(new String[]{"profile", "init", input, "--format", format}, output, errors);
            assertThat(status).isZero();
            assertThat(err.size()).isZero();
            String displayed;
            if (format.equals("json")) {
                displayed = new ObjectMapper().readTree(out.toByteArray()).required("path").textValue();
            } else {
                String line = out.toString(UTF_8).lines().findFirst().orElseThrow();
                assertThat(line).startsWith("Created pinned profile: ");
                displayed = line.substring("Created pinned profile: ".length());
            }
            assertThat(displayed).isEqualTo(expected);
            assertThat(Files.isSameFile(Path.of(displayed), location.resolve("team.toml"))).isTrue();
            out.reset();
            assertThat(ProfileCommand.run(new String[]{"profile", "validate", displayed, "--format=json"}, output, errors)).isZero();
            assertThat(new ObjectMapper().readTree(out.toByteArray()).required("effective_count").intValue()).isEqualTo(26);
            assertThat(err.size()).isZero();
        }
    }
}
