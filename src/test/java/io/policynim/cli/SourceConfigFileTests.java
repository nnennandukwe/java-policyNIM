package io.policynim.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceConfigFileTests {

    @TempDir Path directory;

    @Test
    void quotedAndBareBindingsHaveDifferentEmptyValues() throws IOException {
        var settings = read("POLICYNIM_CORPUS_DIR=\"\"# note\nPOLICYNIM_PROFILE=''\n");
        assertEquals("", settings.corpus());
        assertEquals("", settings.profile());
        settings = read("POLICYNIM_CORPUS_DIR=first\nPOLICYNIM_CORPUS_DIR\nPOLICYNIM_PROFILE=first\nPOLICYNIM_PROFILE\n");
        assertNull(settings.corpus());
        assertNull(settings.profile());
    }

    @Test
    void quotedMultilineValuesKeepTheirContentsAndHideEmbeddedAssignments() throws IOException {
        var settings = read("NOTE=\"unrelated\nPOLICYNIM_PROFILE=ignored\ncontinued\"\r\n"
            + "export 'policynim_corpus_dir' = \" \r\n \" # note\n");
        assertEquals(" \n ", settings.corpus());
        assertNull(settings.profile());
    }

    @Test
    void earlierBindingsBeatProcessVariablesDuringExpansion() throws IOException {
        var settings = read("ROOT=file\nPOLICYNIM_CORPUS_DIR='${ROOT}/${AREA}/${LABEL:-ops}'\n"
            + "POLICYNIM_PROFILE=${MISSING}\n", Map.of("ROOT", "process", "AREA", "selected"));
        assertEquals("file/selected/ops", settings.corpus());
        assertEquals("", settings.profile());
        settings = read("ROOT\nPOLICYNIM_CORPUS_DIR=${ROOT:-fallback}\n", Map.of("ROOT", "process"));
        assertEquals("", settings.corpus());
    }

    @Test
    void onlyTheFirstLineCanTurnOffInterpolation() throws IOException {
        String header = "# policynim-literal-values-v1";
        String source = "export POLICYNIM_CORPUS_DIR=\"${VAR:-pack}\"\n";
        assertEquals("${VAR:-pack}", read(header + "\r\n" + source).corpus());
        assertEquals("pack", read("\n" + header + "\n" + source).corpus());
        assertEquals("pack", read(header + " extra\n" + source).corpus());
    }

    @Test
    void quotingAndCommentsFollowTheFormatOfEachBinding() throws IOException {
        assertEquals("local#one", read("POLICYNIM_CORPUS_DIR=local#one # a comment\n").corpus());
        assertEquals("a'b\\c", read("POLICYNIM_CORPUS_DIR='a\\'b\\\\c'\n").corpus());
        assertEquals("a\tb\n\\q", read("POLICYNIM_CORPUS_DIR=\"a\\tb\\n\\q\"\n").corpus());
        assertEquals("second", read("POLICYNIM_CORPUS_DIR=first\nPOLICYNIM_CORPUS_DIR=second").corpus());
    }

    @Test
    void malformedStatementsDontHideLaterValidBindings() throws IOException {
        var settings = read("NOT A VALID KEY=value\nPOLICYNIM_PROFILE=\"ignored\" trailing\n"
            + "POLICYNIM_CORPUS_DIR=selected");
        assertNull(settings.profile());
        assertEquals("selected", settings.corpus());
    }

    private SourceConfigFile read(String content) throws IOException {
        return read(content, Map.of());
    }

    private SourceConfigFile read(String content, Map<String, String> environment) throws IOException {
        Path file = Files.writeString(directory.resolve("config.env"), content);
        return SourceConfigFile.read(file, environment);
    }
}
