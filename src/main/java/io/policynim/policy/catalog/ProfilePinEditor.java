package io.policynim.policy.catalog;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Replaces reviewed pin values at their parsed TOML positions, preserving the other declarations. */
final class ProfilePinEditor {
    private final String text;
    private final List<Integer> lines = new ArrayList<>();
    private final List<Edit> edits = new ArrayList<>();

    private record Edit(int start, int end, String replacement, boolean needsNewline) { }
    private record Revision(String id, int key, int value, int end, String digest) { }

    private ProfilePinEditor(String text) {
        this.text = text;
        lines.add(0);
        for (int next = text.indexOf('\n'); next >= 0; next = text.indexOf('\n', next + 1)) {
            lines.add(next + 1);
        }
    }

    static String update(ProfileToml.Document document, PolicyProfile current) {
        var editor = new ProfilePinEditor(document.text());
        var packs = document.table().getArray("packs");
        if (packs.size() != current.packs().size()) {
            throw new InvalidPolicyDocumentException("Profile pack tables changed; rerun the profile diff.");
        }
        for (int index = 0; index < packs.size(); index++) {
            var table = packs.getTable(index);
            var pin = current.packs().get(index);
            editor.stringValue(table, "path", pin.path());
            editor.stringValue(table, "version", pin.version());
            editor.stringValue(table, "digest", pin.digest());
            editor.revisions(table, pin.policies());
        }
        String updated = editor.apply();
        if (!ProfileToml.parse(updated).profile().equals(current)) {
            throw new InvalidPolicyDocumentException("Profile pins couldn't be updated without changing other data.");
        }
        return updated;
    }

    private void stringValue(TomlTable table, String key, String value) {
        int start = offset(table.entry(key).value().position());
        replace(start, stringEnd(start), literal(value));
    }

    private void revisions(TomlTable pack, Map<String, String> wanted) {
        int start = offset(pack.entry("policies").value().position());
        var policies = pack.getTable("policies");
        var rows = new ArrayList<Revision>();
        for (String id : policies.keySet()) {
            var path = List.of(id);
            var entry = policies.entry(path);
            int value = offset(entry.value().position());
            rows.add(new Revision(id, offset(entry.position()), value, stringEnd(value), policies.getString(path)));
        }
        rows.sort(Comparator.comparingInt(Revision::key));
        var added = wanted.entrySet().stream().filter(pin -> !policies.contains(List.of(pin.getKey()))).toList();
        if (text.charAt(start) == '{') {
            inlineRevisions(start, rows, wanted, added);
            return;
        }
        boolean dotted = text.charAt(start) != '[';
        if (dotted && rows.size() > 1) {
            // The pinned writer's TOML parser returns a table proxy for repeated dotted assignments.
            throw new InvalidPolicyDocumentException("Pack policy revisions must form a TOML table.");
        }
        blockRevisions(rows, wanted, added, dotted);
    }

    private void inlineRevisions(int start, List<Revision> rows, Map<String, String> wanted,
                                 List<Map.Entry<String, String>> added) {
        // TOML 1.0 inline tables separate fields with commas and horizontal whitespace.
        // The reference keeps whitespace from removed entries and writes a comma after each surviving value.
        var result = new StringBuilder("{");
        int cursor = start + 1;
        int written = 0;
        for (var row : rows) {
            result.append(text.substring(cursor, row.key()).replace(",", ""));
            if (wanted.containsKey(row.id())) {
                result.append(text, row.key(), row.value()).append(literal(wanted.get(row.id())));
                if (++written < wanted.size()) {
                    result.append(',');
                }
            }
            cursor = row.end();
        }
        int close = text.indexOf('}', cursor);
        result.append(text, cursor, close);
        for (var item : added) {
            result.append(assignment(item));
            if (++written < wanted.size()) {
                result.append(',');
            }
        }
        replace(start, close + 1, result.append('}').toString());
    }

    private void blockRevisions(List<Revision> rows, Map<String, String> wanted,
                                List<Map.Entry<String, String>> added, boolean dotted) {
        var append = new StringBuilder();
        for (var row : rows) {
            if (wanted.containsKey(row.id())) {
                replace(row.value(), row.end(), literal(wanted.get(row.id())));
            } else {
                int lineEnd = afterLine(row.end());
                int commentEnd = text.charAt(lineEnd - 1) == '\n' ? lineEnd - 1 : lineEnd;
                String note = text.substring(row.end(), commentEnd).stripLeading();
                append.append("# Removed policy pin: ").append(asciiJson(row.id())).append(" = ")
                    .append(literal(row.digest())).append('\n');
                if (note.startsWith("#")) {
                    append.append("# ").append(note.substring(1)).append('\n');
                }
                replace(text.lastIndexOf('\n', row.key() - 1) + 1, lineEnd, "");
            }
        }
        boolean hasRemoval = !append.isEmpty();
        String prefix = dotted ? dottedPrefix(rows.getFirst().key()) : "";
        for (var item : added) {
            append.append(prefix).append(assignment(item)).append('\n');
        }
        if (!append.isEmpty()) {
            int at = afterLine(rows.getLast().end());
            if (!dotted) {
                at = afterTableTrivia(at, hasRemoval);
            }
            edits.add(new Edit(at, at, append.toString(), !hasRemoval));
        }
    }

    private String dottedPrefix(int start) {
        char first = text.charAt(start);
        int keyEnd = first == '"' || first == '\'' ? stringEnd(start) : start;
        return text.substring(start, text.indexOf('.', keyEnd) + 1);
    }

    private static String assignment(Map.Entry<String, String> item) {
        return InitialProfileToml.key(item.getKey(), false) + " = " + literal(item.getValue());
    }

    private static String literal(String value) {
        return InitialProfileToml.quote(value, false);
    }

    private static String asciiJson(String id) {
        var ascii = new StringBuilder();
        for (char unit : literal(id).toCharArray()) {
            if (unit > 127) {
                ascii.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
            } else {
                ascii.append(unit);
            }
        }
        return ascii.toString();
    }

    private int offset(TomlPosition position) {
        return text.offsetByCodePoints(lines.get(position.line() - 1), position.column() - 1);
    }

    private int stringEnd(int start) {
        char quote = text.charAt(start);
        int delimiter = text.startsWith(String.valueOf(quote).repeat(3), start) ? 3 : 1;
        for (int cursor = start + delimiter; cursor < text.length();) {
            char next = text.charAt(cursor++);
            if (next == '\\' && quote == '"') {
                cursor++;
            } else if (next == quote) {
                int run = 1;
                while (cursor < text.length() && text.charAt(cursor) == quote) {
                    cursor++;
                    run++;
                }
                if (run >= delimiter) {
                    return cursor;
                }
            }
        }
        throw new InvalidPolicyDocumentException("Couldn't locate the end of a profile pin value.");
    }

    private int afterLine(int position) {
        int newline = text.indexOf('\n', position);
        return newline < 0 ? text.length() : newline + 1;
    }

    private int afterTableTrivia(int start, boolean includeTrailingBlanks) {
        int cursor = start;
        int insert = start;
        while (cursor < text.length()) {
            int end = afterLine(cursor);
            String line = text.substring(cursor, end).stripLeading();
            if (!line.isEmpty() && !line.startsWith("#")) {
                break;
            }
            if (includeTrailingBlanks || !line.isEmpty()) {
                insert = end;
            }
            cursor = end;
        }
        return insert;
    }

    private void replace(int start, int end, String replacement) {
        edits.add(new Edit(start, end, replacement, false));
    }

    private String apply() {
        edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        var updated = new StringBuilder();
        int cursor = 0;
        for (var edit : edits) {
            updated.append(text, cursor, edit.start());
            if (edit.needsNewline() && !updated.isEmpty() && updated.charAt(updated.length() - 1) != '\n') {
                updated.append('\n');
            }
            updated.append(edit.replacement());
            cursor = edit.end();
        }
        return updated.append(text, cursor, text.length()).toString();
    }
}
