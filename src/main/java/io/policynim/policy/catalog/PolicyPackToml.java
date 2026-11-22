package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;
import org.tomlj.TomlVersion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Pattern;

final class PolicyPackToml {

    private static final Set<String> OFFSET_ERRORS = Set.of("Invalid zone offset hours (valid range -18..+18)",
        "Invalid zone offset (valid range -18:00..+18:00)");
    private static final Pattern WIDE_OFFSET = Pattern.compile("[+-](?:18:(?:0[1-9]|[1-5][0-9])|(?:19|2[0-3]):[0-5][0-9])");

    private PolicyPackToml() {
    }

    static TomlTable decode(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new InvalidPolicyDocumentException("Couldn't read pack.toml as UTF-8 TOML.");
        }
        var parsed = Toml.parse(text, TomlVersion.V1_0_0);
        if (parsed.hasErrors() && parsed.errors().stream().allMatch(e -> OFFSET_ERRORS.contains(e.getMessage()))) {
            parsed = retryWideOffsets(text, parsed);
        }
        if (parsed.hasErrors()) {
            var position = parsed.errors().getFirst().position();
            throw new InvalidPolicyDocumentException("Invalid pack.toml TOML at line " + position.line()
                + ", column " + position.column() + ".");
        }
        return parsed;
    }

    // The reference accepts offsets through 23:59 and stores the local date. Java ZoneOffset ends at 18:00.
    // Replace only offset tokens reported by the parser. The final parse still validates the complete document.
    private static TomlParseResult retryWideOffsets(String text, TomlParseResult original) {
        var positions = original.errors().stream().map(error -> error.position())
            .sorted(Comparator.comparingInt(TomlPosition::line).thenComparingInt(TomlPosition::column)).toList();
        var adjusted = new StringBuilder(text);
        int index = 0;
        int line = 1;
        int column = 1;
        for (var position : positions) {
            while (index < text.length() && (line < position.line() || column < position.column())) {
                int character = text.codePointAt(index);
                index += Character.charCount(character);
                if (character == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            if (line != position.line() || column != position.column() || index + 6 > text.length()
                || !WIDE_OFFSET.matcher(text).region(index, index + 6).matches()) {
                return original;
            }
            adjusted.replace(index, index + 6, "+00:00");
        }
        return Toml.parse(adjusted.toString(), TomlVersion.V1_0_0);
    }
}
