package io.policynim.policy.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.policynim.policy.parse.SourceMarkdownParser.strip;

/** Reads the source format's string fields, block/inline lists, and authored runtime rules. */
final class SourceFrontmatter {
    static final Pattern FIELD = Pattern.compile("([A-Za-z0-9_-]+):(.*)", Pattern.DOTALL);
    private static final Pattern LIST_ITEM = Pattern.compile("(?U)^[\\s\\u001c-\\u001f]*-[\\s\\u001c-\\u001f]+");
    private final String sourcePath;
    private final Map<String, Object> fields = new HashMap<>();
    private List<SourceMarkdownParser.RuntimeRule> rules = List.of();

    SourceFrontmatter(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    void read(List<String> lines) {
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index++);
            if (ignored(line)) {
                continue;
            }
            var field = FIELD.matcher(line);
            if (!field.matches()) {
                throw malformed();
            }
            String key = field.group(1);
            String tail = strip(field.group(2));
            if ("runtime_rules".equals(key)) {
                var parsed = SourceRuntimeRules.parse(lines, index - 1, tail, sourcePath);
                rules = parsed.rules();
                index = parsed.nextIndex();
            } else if (!tail.isEmpty()) {
                fields.put(key, compact(tail) ? splitInline(tail).stream().map(SourceFrontmatter::scalar).toList() : scalar(tail));
            } else {
                var values = new ArrayList<String>();
                while (index < lines.size()) {
                    String next = lines.get(index);
                    if (!ignored(next)) {
                        var marker = LIST_ITEM.matcher(next);
                        if (!marker.find()) {
                            break;
                        }
                        values.add(scalar(strip(next.substring(marker.end()))));
                    }
                    index++;
                }
                if (values.isEmpty() && index < lines.size() && indented(lines.get(index))) {
                    throw malformed();
                }
                fields.put(key, values.isEmpty() ? "" : values);
            }
        }
    }

    private InvalidPolicyDocumentException malformed() {
        return new InvalidPolicyDocumentException("Policy document " + sourcePath + " has malformed YAML frontmatter.");
    }

    static boolean ignored(String line) {
        String text = strip(line);
        return text.isEmpty() || text.startsWith("#");
    }

    static boolean indented(String line) {
        return line.startsWith(" ") || line.startsWith("\t");
    }

    static boolean compact(String value) {
        return value.startsWith("[") && value.endsWith("]");
    }

    static String scalar(String raw) {
        if (quoted(raw)) {
            return raw.substring(1, raw.length() - 1).replace("\\'", "'").replace("\\\"", "\"");
        }
        return raw;
    }

    static boolean quoted(String raw) {
        return raw.length() >= 2 && (raw.charAt(0) == '\'' || raw.charAt(0) == '"')
            && raw.charAt(0) == raw.charAt(raw.length() - 1);
    }

    /** Split an inline list while retaining quote marks for type-sensitive matcher validation. */
    static List<String> splitInline(String raw) {
        var items = new ArrayList<String>();
        int start = 1;
        char quote = 0;
        boolean escaped = false;
        for (int i = 1; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == ',' || i == raw.length() - 1) {
                String item = strip(raw.substring(start, i));
                if (!item.isEmpty()) {
                    items.add(item);
                }
                start = i + 1;
            }
        }
        if (quote != 0) {
            throw new InvalidPolicyDocumentException("Frontmatter contains an unterminated quoted string.");
        }
        return List.copyOf(items);
    }

    String valueOr(String key, String fallback) {
        String value = value(key);
        return value.isEmpty() ? fallback : value;
    }

    String value(String key) {
        Object value = fields.get(key);
        if (value instanceof List<?> items) {
            // The source parser stringifies an explicit list in a scalar metadata field.
            return items.stream().map(item -> repr((String) item)).collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        return value == null ? "" : strip((String) value);
    }

    private static String repr(String value) {
        char quote = value.contains("'") && !value.contains("\"") ? '"' : '\'';
        StringBuilder result = new StringBuilder().append(quote);
        value.codePoints().forEach(point -> {
            if (point == quote || point == '\\') {
                result.append('\\').appendCodePoint(point);
            } else if (point == '\t') {
                result.append("\\t");
            } else if (Character.isISOControl(point) || (point != ' ' && Character.isSpaceChar(point))
                    || Character.getType(point) == Character.FORMAT || Character.getType(point) == Character.UNASSIGNED
                    || Character.getType(point) == Character.PRIVATE_USE) {
                result.append(point <= 255 ? "\\x%02x".formatted(point) : point <= 65535
                    ? "\\u%04x".formatted(point) : "\\U%08x".formatted(point));
            } else {
                result.appendCodePoint(point);
            }
        });
        return result.append(quote).toString();
    }

    List<String> list(String key) {
        Object value = fields.get(key);
        if (value instanceof List<?> items) {
            return items.stream().map(item -> strip((String) item)).filter(item -> !item.isEmpty()).toList();
        }
        String text = value(key);
        return text.isEmpty() ? List.of() : List.of(text);
    }

    List<SourceMarkdownParser.RuntimeRule> rules() {
        return rules;
    }
}
