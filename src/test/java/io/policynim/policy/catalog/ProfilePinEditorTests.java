package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilePinEditorTests {
    @TempDir
    private Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"normal", "inline", "selected"})
    void editsOnlyThePinFieldsAndMatchesTheFullReferenceDocument(String style) throws IOException {
        String before = fixture(style + "-before.toml");
        String after = fixture(style + "-after.toml");
        assertEdit(before, after);
    }

    @Test
    void keepsTheMissingFinalNewlineAndNormalizesPhysicalCarriageReturns() throws IOException {
        String before = fixture("selected-before.toml");
        String after = fixture("selected-after.toml");
        assertThat(before).doesNotEndWith("\n");
        assertEdit(before.replace("\n", "\r\n"), after);
        assertEdit(before + "\r", after + "\n");
    }

    @Test
    void rewritesAPackValueWithoutChangingItsQuotedKeyOrAnOperatorDecision() throws IOException {
        String before = fixture("selected-before.toml")
            .replace("path = \"../team\"", "'path' = '../team'")
            .replace("C = \"sha256:", "\"C\" = \"sha256:");
        String after = fixture("selected-after.toml")
            .replace("path = \"../team\"", "'path' = \"../team\"")
            .replace("C = \"sha256:", "\"C\" = \"sha256:");
        assertEdit(before, after);
    }

    @Test
    void refusesRepeatedDottedAssignmentsAndAcceptsTheRewrittenTable() throws IOException {
        String authored = fixture("normal-before.toml");
        String dotted = authored.replace("[packs.policies]\nA =", "policies.A =")
            .replace("\nB = ", "\npolicies.B = ");
        PolicyProfile wanted = parsed(fixture("normal-after.toml")).profile();
        var prior = parsed(dotted);
        assertThatThrownBy(() -> ProfilePinEditor.update(prior, wanted))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessage("Pack policy revisions must form a TOML table.");
        assertEdit(authored, fixture("normal-after.toml"));
    }

    @Test
    void keepsOneDottedPinInsideAnInlinePackAndRefusesMembershipEdits() throws IOException {
        String before = fixture("inline-before.toml");
        String pin = parsed(before).profile().packs().getFirst().policies().get("C");
        String explicit = "policies = { \"C\" = \"" + pin + "\" }";
        String dotted = before.replace(explicit, "policies.C=\"" + pin + "\"");
        var original = parsed(dotted);
        assertThat(ProfilePinEditor.update(original, original.profile())).isEqualTo(dotted);
        assertEdit(dotted, dotted.replace(pin, "sha256:" + "3".repeat(64)));
        for (String policies : new String[] {"{ C=\"" + pin + "\", D=\"" + pin + "\" }", "{ D=\"" + pin + "\" }"}) {
            var wanted = parsed(before.replace(explicit, "policies = " + policies)).profile();
            assertThatThrownBy(() -> ProfilePinEditor.update(original, wanted))
                .isInstanceOf(InvalidPolicyDocumentException.class)
                .hasMessage("Couldn't access profile inputs or output. Check paths, permissions, and UTF-8 files.");
        }
    }

    @Test
    void preservesAMultilineDecisionWithApparentAssignmentsAndAcceptsOneDottedPin() throws IOException {
        String before = fixture("selected-before.toml");
        String after = fixture("selected-after.toml");
        String reason = "reason = \'Review C at the next release.\'";
        String multiline = "reason = \"\"\"Review C.\n[packs.policies]\nA = 'old'\n# still a string.\"\"\"";
        assertEdit(before.replace("[packs.policies]\nC =", "policies.C =").replace(reason, multiline),
            after.replace("[packs.policies]\nC =", "policies.C =").replace(reason, multiline));
    }

    @Test
    void keepsTheTrailingNoteAndEscapesRemovedUnicodeKeysInTheReferenceComment() throws IOException {
        // # non-English test data
        String oldId = "B.\uD83E\uDD89";
        String newId = "D.\u00e9";
        String before = fixture("normal-before.toml").replace("\nB = ", "\n\"" + oldId + "\" = ")
            .replace("# removed note\n", "# removed note\t \n");
        String after = fixture("normal-after.toml")
            .replace("# Removed policy pin: \"B\"", "# Removed policy pin: \"B.\\ud83e\\udd89\"")
            .replace("#  removed note\n", "#  removed note\t \n")
            .replace("\nD = ", "\n\"" + newId + "\" = ");
        assertEdit(before, after);
    }

    @Test
    void usesCodePointColumnsWhenAnEarlierInlineKeyContainsASupplementaryCharacter() throws IOException {
        // # non-English test data
        String changed = "\"recovery.\uD83E\uDD89\" = ";
        String before = fixture("inline-before.toml").replace("\"A\" = ", changed);
        String after = fixture("inline-after.toml").replace("\"A\" = ", changed);
        assertEdit(before, after);
    }

    @Test
    void appendsANewRevisionBeforeTheLastBlankLineOfTheTable() throws IOException {
        String before = fixture("normal-before.toml");
        String added = "D = \"sha256:" + "1".repeat(64) + "\"\n";
        String after = before.replace("\n\n[[packs]]\npath = \"../base\"", "\n" + added + "\n[[packs]]\npath = \"../base\"");
        assertEdit(before, after);
    }

    @Test
    void appendsOneDottedRevisionBeforeTheFollowingPackComment() throws IOException {
        String before = fixture("normal-before.toml").replace("[packs.policies]\nC =", "'policies'.C =")
            .replace("\n\n[[packs]]\npath = \"../base\"", "\n# End of team.\n\n[[packs]]\npath = \"../base\"");
        String after = before.replace("# End of team.", "'policies'.D = \"sha256:" + "2".repeat(64) + "\"\n# End of team.");
        assertEdit(before, after);
    }

    @Test
    void refusesToPublishIfTheReviewedModelChangesADecision() throws IOException {
        String authored = fixture("selected-before.toml");
        var wrong = parsed(fixture("selected-after.toml").replace("Review C at the next release.", "Revised reason."));
        var prior = parsed(authored);
        assertThatThrownBy(() -> ProfilePinEditor.update(prior, wrong.profile()))
            .isInstanceOf(InvalidPolicyDocumentException.class)
            .hasMessageContaining("without changing other data");
    }

    private void assertEdit(String before, String expected) throws IOException {
        var authored = parsed(before);
        var wanted = parsed(expected);
        assertThat(ProfilePinEditor.update(authored, wanted.profile())).isEqualTo(expected);
    }

    private ProfileToml.Document parsed(String text) throws IOException {
        Path file = Files.createTempFile(workspace, "profile", ".toml");
        Files.writeString(file, text);
        return ProfileToml.document(file);
    }

    private static String fixture(String name) throws IOException {
        try (var stream = Objects.requireNonNull(ProfilePinEditorTests.class
                .getResourceAsStream("/parity/catalog/profile-update/" + name))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
