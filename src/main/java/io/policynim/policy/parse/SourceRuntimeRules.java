package io.policynim.policy.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static io.policynim.policy.parse.SourceMarkdownParser.strip;

/** Validates the runtime_rules frontmatter grammar and preserves the lines that supply each rule. */
final class SourceRuntimeRules {
    private static final Set<String> MATCHERS = Set.of("path_globs", "command_regexes", "url_host_patterns");
    private static final Set<String> SCALARS = Set.of("action", "effect", "reason");
    private static final Set<String> NON_STRINGS = Set.of("true", "false", "null", "~", "yes", "no", "on", "off");
    private static final Pattern NUMBER = Pattern.compile("(?U)[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private final List<String> lines;
    private final String path;
    private int index;
    private int lastField;

    record Result(List<SourceMarkdownParser.RuntimeRule> rules, int nextIndex) { }

    private SourceRuntimeRules(List<String> lines, String path, int start) {
        this.lines = lines;
        this.path = path;
        this.index = start;
    }

    static Result parse(List<String> lines, int key, String remainder, String sourcePath) {
        var parser = new SourceRuntimeRules(lines, sourcePath, key + 1);
        if (!remainder.isEmpty()) {
            throw parser.invalid(key, "runtime_rules must use block-list syntax.");
        }
        var rules = new ArrayList<SourceMarkdownParser.RuntimeRule>();
        while (parser.hasContent() && parser.line().startsWith("  - ")) {
            rules.add(parser.rule());
        }
        if (parser.index < lines.size() && SourceFrontmatter.indented(parser.line())) {
            throw parser.invalid(parser.index, "expected a rule entry beginning with `-`.");
        }
        if (rules.isEmpty()) {
            throw parser.invalid(key, "runtime_rules must include at least one rule entry.");
        }
        return new Result(List.copyOf(rules), parser.index);
    }

    private SourceMarkdownParser.RuntimeRule rule() {
        int start = index;
        var seen = new HashSet<String>();
        var scalars = new HashMap<String, String>();
        var matchers = new HashMap<String, List<String>>();
        do {
            String raw = line().substring(4);
            int fieldIndex = index++;
            lastField = fieldIndex;
            var field = SourceFrontmatter.FIELD.matcher(raw);
            if (!field.matches()) {
                throw invalid(fieldIndex, "expected `key: value` syntax inside runtime_rules.");
            }
            String name = field.group(1);
            String remainder = strip(field.group(2));
            if (SCALARS.contains(name)) {
                scalars.put(name, readScalar(name, remainder, fieldIndex));
            } else if (MATCHERS.contains(name)) {
                matchers.put(name, readMatchers(remainder, fieldIndex));
            } else {
                throw invalid(fieldIndex, "unknown runtime_rules key `" + name + "`.");
            }
            if (!seen.add(name)) {
                throw invalid(lastField, "duplicate rule key `" + name + "`.");
            }
            if (!hasContent() || line().startsWith("  - ") || !SourceFrontmatter.indented(line())) {
                break;
            }
            if (line().startsWith("      - ")) {
                throw invalid(index, "matcher list items must follow a matcher-family key.");
            }
            if (!line().startsWith("    ")) {
                throw invalid(index, "unexpected indentation inside runtime_rules.");
            }
        } while (true);
        String action = scalars.getOrDefault("action", "");
        String effect = scalars.getOrDefault("effect", "");
        String reason = scalars.getOrDefault("reason", "");
        if (!Set.of("shell_command", "file_write", "http_request").contains(action)) {
            throw invalid(start, "Input should be 'shell_command', 'file_write' or 'http_request'");
        }
        if (!Set.of("confirm", "block").contains(effect)) {
            throw invalid(start, "Input should be 'confirm' or 'block'");
        }
        if (reason.isEmpty()) {
            throw invalid(start, "String should have at least 1 character");
        }
        if (matchers.values().stream().filter(values -> !values.isEmpty()).count() != 1) {
            throw invalid(start, "Value error, runtime_rules must define exactly one non-empty matcher family.");
        }
        return new SourceMarkdownParser.RuntimeRule(action, effect, reason,
            matchers.getOrDefault("path_globs", List.of()), matchers.getOrDefault("command_regexes", List.of()),
            matchers.getOrDefault("url_host_patterns", List.of()), start + 2, lastField + 2);
    }

    private String readScalar(String name, String remainder, int fieldIndex) {
        if (remainder.isEmpty()) {
            throw invalid(fieldIndex, "`" + name + "` must use single-line scalar syntax.");
        }
        if (SourceFrontmatter.compact(remainder)) {
            throw invalid(fieldIndex, "`" + name + "` must be a single string value.");
        }
        String value = strip(SourceFrontmatter.scalar(remainder));
        if (value.isEmpty()) {
            throw invalid(fieldIndex, "`" + name + "` must not be empty.");
        }
        return value;
    }

    private List<String> readMatchers(String remainder, int fieldIndex) {
        if (!remainder.isEmpty()) {
            if (!SourceFrontmatter.compact(remainder)) {
                throw invalid(fieldIndex, "matcher families must use list syntax.");
            }
            List<String> rawItems;
            try {
                rawItems = SourceFrontmatter.splitInline(remainder);
            } catch (InvalidPolicyDocumentException ex) {
                throw invalid(fieldIndex, ex.getMessage());
            }
            return rawItems.stream().map(item -> matcherItem(item, fieldIndex)).toList();
        }
        var entries = new ArrayList<String>();
        while (hasContent() && line().startsWith("      - ")) {
            lastField = index;
            entries.add(matcherItem(strip(line().substring(8)), index));
            index++;
        }
        if (index < lines.size() && SourceFrontmatter.indented(line()) && !line().startsWith("  - ")
                && !(line().startsWith("    ") && !line().startsWith("      "))) {
            throw invalid(index, "expected a matcher list item beginning with `-`.");
        }
        return entries;
    }

    private String matcherItem(String raw, int lineIndex) {
        if (raw.isEmpty()) {
            throw invalid(lineIndex, "matcher list items must not be empty.");
        }
        if (!SourceFrontmatter.quoted(raw) && (NON_STRINGS.contains(raw.toLowerCase(Locale.ROOT))
                || NUMBER.matcher(raw).matches() || SourceFrontmatter.compact(raw)
                || (raw.startsWith("{") && raw.endsWith("}")))) {
            throw invalid(lineIndex, "matcher list items must be strings, not YAML booleans, nulls, numbers, or maps.");
        }
        return SourceFrontmatter.scalar(raw);
    }

    private boolean hasContent() {
        while (index < lines.size() && SourceFrontmatter.ignored(line())) {
            index++;
        }
        return index < lines.size();
    }

    private String line() {
        return lines.get(index);
    }

    private InvalidPolicyDocumentException invalid(int lineIndex, String message) {
        return new InvalidPolicyDocumentException("Policy document " + path + " has invalid runtime_rules at line "
            + (lineIndex + 2) + ": " + message);
    }
}
