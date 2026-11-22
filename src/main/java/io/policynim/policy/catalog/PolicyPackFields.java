package io.policynim.policy.catalog;

import io.policynim.policy.parse.SourceMarkdownParser;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Reads declared fields without coercion and collects validation locations. */
record PolicyPackFields(TomlTable table, String prefix, List<String> errors) {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z_][A-Za-z_0-9-]{0,63}");

    Object value(String key) {
        return table.get(key);
    }

    void error(String key, String reason) {
        errors.add(prefix + key + ": " + reason);
    }

    void checkKeys(Set<String> allowed) {
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                error(SAFE_KEY.matcher(key).matches() ? key : "<unknown>", "Extra inputs aren't permitted.");
            }
        }
    }

    String text(String key, boolean required) {
        Object value = value(key);
        if (value == null) {
            if (required) {
                error(key, "Field required.");
            }
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        error(key, "Expected a string.");
        return null;
    }

    String sourceText(String key, boolean required) {
        String text = text(key, required);
        if (text == null) {
            return null;
        }
        String trimmed = SourceMarkdownParser.strip(text);
        if (trimmed.isEmpty()) {
            error(key, "Source text mustn't be blank.");
        }
        return trimmed;
    }

    String memberName(String key) {
        String name = text(key, true);
        if (name != null && (SourceMarkdownParser.strip(name).isEmpty()
            || name.chars().anyMatch(c -> c < 32 || c == 127))) {
            error(key, "Member paths and IDs mustn't be blank or contain control characters.");
        }
        return name;
    }

    String choice(String key, Set<String> choices, String fallback) {
        String value = text(key, false);
        if (value != null && !choices.contains(value)) {
            error(key, "Unsupported value.");
        }
        return value == null ? fallback : value;
    }

    List<String> strings(String key) {
        var values = array(key, false);
        var strings = new ArrayList<String>();
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) instanceof String text) {
                strings.add(text);
            } else {
                error(key + "." + index, "Expected a string.");
            }
        }
        return List.copyOf(strings);
    }

    <T> List<T> tables(String key, boolean required, Function<PolicyPackFields, T> parse) {
        var values = array(key, required);
        var results = new ArrayList<T>();
        for (int index = 0; index < values.size(); index++) {
            String location = key + "." + index;
            if (values.get(index) instanceof TomlTable child) {
                results.add(parse.apply(new PolicyPackFields(child, prefix + location + ".", errors)));
            } else {
                error(location, "Expected a table.");
            }
        }
        return List.copyOf(results);
    }

    private List<?> array(String key, boolean required) {
        Object value = value(key);
        if (value instanceof TomlArray items) {
            if (required && items.isEmpty()) {
                error(key, "Expected at least one member.");
            }
            return items.toList();
        }
        if (value != null || required) {
            error(key, value == null ? "Field required." : "Expected an array.");
        }
        return List.of();
    }
}
