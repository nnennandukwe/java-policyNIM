package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Reads profile fields in model order and reports validation locations without rejected values. */
record ProfileToml(TomlTable table, String prefix, List<String> errors) {
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    // Profile Text strips Unicode White_Space, including NEL and excluding the ASCII information separators.
    private static final Pattern TEXT = Pattern.compile("\\s*+(\\S(?:.*\\S)?)\\s*+", Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL);
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z_0-9-]{0,63}");
    private static final String UNREADABLE = "Couldn't read profile TOML. Check the profile path, permissions, and TOML syntax.";

    record Document(PolicyProfile profile, String text, TomlTable table) { }

    static PolicyProfile read(Path file) {
        return document(file).profile();
    }

    static Document document(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new InvalidPolicyDocumentException(UNREADABLE);
        }
        try {
            return parse(normalizeNewlines(Files.readString(file)));
        } catch (IOException error) {
            throw new InvalidPolicyDocumentException(UNREADABLE, error);
        }
    }

    static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static Document parse(String normalizedText) {
        TomlTable table;
        try {
            table = PolicyPackToml.decode(normalizedText.getBytes(StandardCharsets.UTF_8));
        } catch (InvalidPolicyDocumentException error) {
            throw new InvalidPolicyDocumentException(UNREADABLE, error);
        }
        var profile = new ProfileToml(table, "", new ArrayList<>()).profile();
        return new Document(profile, normalizedText, table);
    }

    private PolicyProfile profile() {
        if (!Long.valueOf(1).equals(table.get("schema_version"))) {
            error("schema_version");
        }
        String id = text("profile_id", PolicyProfile.NAME);
        var packs = tables("packs", true, ProfileToml::pin);
        var exclusions = tables("exclusions", false, fields -> fields.decision(false));
        var replacements = tables("replacements", false, fields -> fields.decision(true));
        var conflicts = tables("conflicts", false, ProfileToml::conflict);
        keys(Set.of("schema_version", "profile_id", "packs", "exclusions", "replacements", "conflicts"));
        if (!errors.isEmpty()) {
            throw new InvalidPolicyDocumentException("Invalid profile fields: " + String.join(", ", errors)
                + ". Correct the named fields in the profile TOML.");
        }
        var ids = new HashSet<String>();
        for (var pin : packs) {
            if (!ids.add(pin.packId())) {
                throw new InvalidPolicyDocumentException("Invalid profile fields: packs. Each pack_id must appear once.");
            }
        }
        return new PolicyProfile(id, packs, exclusions, replacements, conflicts);
    }

    private PolicyProfile.Pin pin() {
        String path = text("path", null);
        if (path != null) {
            try {
                path = PackLocation.normalize(path);
            } catch (InvalidPolicyDocumentException invalid) {
                error("path");
            }
        }
        var pin = new PolicyProfile.Pin(path, text("pack_id", PolicyProfile.NAME), text("version", VERSION),
            text("digest", DIGEST), revisions());
        keys(Set.of("path", "pack_id", "version", "digest", "policies"));
        return pin;
    }

    private Map<String, String> revisions() {
        var revisions = new TreeMap<String, String>(SourceCatalog::compareCodePoints);
        if (!(table.get("policies") instanceof TomlTable policyTable) || policyTable.isEmpty()) {
            error("policies");
        } else {
            for (String id : policyTable.keySet()) {
                Object value = policyTable.get(List.of(id));
                if (value instanceof String digest && DIGEST.matcher(digest).matches()) {
                    revisions.put(id, digest);
                } else {
                    error("policies." + safeKey(id));
                }
            }
        }
        return Collections.unmodifiableMap(revisions);
    }

    private PolicyProfile.Decision decision(boolean replacement) {
        String target = text("target_id", null);
        String expected = text("expected_digest", DIGEST);
        String reason = text("reason", null);
        String replacementId = replacement ? text("replacement_id", null) : null;
        keys(replacement ? Set.of("target_id", "expected_digest", "reason", "replacement_id")
            : Set.of("target_id", "expected_digest", "reason"));
        return new PolicyProfile.Decision(target, expected, reason, replacementId);
    }

    private PolicyProfile.Conflict conflict() {
        var result = new PolicyProfile.Conflict(text("left", null), text("right", null), text("reason", null));
        keys(Set.of("left", "right", "reason"));
        return result;
    }

    private String text(String key, Pattern pattern) {
        if (table.get(key) instanceof String value) {
            if (pattern != null && pattern.matcher(value).matches()) {
                return value;
            }
            if (pattern == null) {
                var trimmed = TEXT.matcher(value);
                if (trimmed.matches()) {
                    return trimmed.group(1);
                }
            }
        }
        error(key);
        return null;
    }

    private <T> List<T> tables(String key, boolean required, Function<ProfileToml, T> read) {
        var result = new ArrayList<T>();
        Object value = table.get(key);
        if (value instanceof TomlArray array) {
            if (required && array.isEmpty()) {
                error(key);
            }
            for (int index = 0; index < array.size(); index++) {
                String location = key + "." + index;
                if (array.get(index) instanceof TomlTable child) {
                    result.add(read.apply(new ProfileToml(child, prefix + location + ".", errors)));
                } else {
                    error(location);
                }
            }
        } else if (value != null || required) {
            error(key);
        }
        return List.copyOf(result);
    }

    private void keys(Set<String> allowed) {
        table.keySet().stream().filter(key -> !allowed.contains(key)).forEach(key -> error(safeKey(key)));
    }

    private static String safeKey(String key) {
        return SAFE_FIELD.matcher(key).matches() ? key : "<unknown>";
    }

    private void error(String location) {
        errors.add(prefix + location);
    }
}
