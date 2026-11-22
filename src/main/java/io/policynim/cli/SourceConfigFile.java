package io.policynim.cli;

import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.SourceMarkdownParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads file-level source selectors without applying them to the process environment. */
record SourceConfigFile(String corpus, String profile) {

    private static final String LITERAL_HEADER = "# policynim-literal-values-v1";
    private static final String SPACE = "[\\s\\x1c-\\x1f&&[^\\n]]";
    private static final Pattern KEY = Pattern.compile(SPACE + "*+(?:export" + SPACE + "++)?+"
        + "(?:'([^']++)'|((?!')[^#=\\s\\x1c-\\x1f]++))" + SPACE + "*+(=)?" + SPACE + "*+",
        Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern COMMENT = Pattern.compile(SPACE + "*+(?:#.*)?", Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL);
    private static final Pattern INLINE_COMMENT = Pattern.compile(SPACE + "++#", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}:]*)(?::-([^}]*))?}");
    private static final Pattern ESCAPE = Pattern.compile("\\\\(.)", Pattern.DOTALL);

    static SourceConfigFile read(Path file, Map<String, String> environment) throws IOException {
        String text = Files.readString(file).replace("\r\n", "\n").replace('\r', '\n');
        int headerEnd = Math.min(lineEnd(text, 0), 128);
        boolean literal = LITERAL_HEADER.equals(SourceMarkdownParser.strip(text.substring(0, headerEnd)));
        var bindings = new LinkedHashMap<String, String>();
        var malformedSelectors = new LinkedHashSet<String>();
        var key = KEY.matcher(text);
        for (int start = 0; start < text.length();) {
            int end = lineEnd(text, start);
            if (key.region(start, text.length()).lookingAt()) {
                String name = key.group(1) == null ? key.group(2) : key.group(1);
                String setting = name.toLowerCase(Locale.ROOT);
                Value value = readValue(text, key.end(), key.group(3) != null);
                if (value != null) {
                    end = lineEnd(text, value.end());
                }
                if (value != null && COMMENT.matcher(text).region(value.end(), end).matches()) {
                    String resolved = literal || value.text() == null ? value.text()
                        : expand(value.text(), bindings, environment);
                    bindings.put(name, resolved);
                    malformedSelectors.remove(setting);
                } else if (setting.equals("policynim_corpus_dir") || setting.equals("policynim_profile")) {
                    malformedSelectors.add(setting);
                }
            }
            start = end + 1;
        }
        for (String name : malformedSelectors) {
            String setting = name.toUpperCase(Locale.ROOT);
            String otherSetting = setting.equals("POLICYNIM_PROFILE") ? "POLICYNIM_CORPUS_DIR" : "POLICYNIM_PROFILE";
            String otherProcessSource = SourceMarkdownParser.strip(environment.getOrDefault(otherSetting, ""));
            if (!environment.containsKey(setting) && otherProcessSource.isEmpty()) {
                throw new InvalidPolicyDocumentException("Invalid " + setting + " assignment in POLICYNIM_CONFIG_FILE. "
                    + "Check its quoting or pass --corpus-root=<path>.");
            }
        }

        // Interpolation uses original key spelling. Settings normalize names after all bindings load.
        String corpus = null;
        String profile = null;
        for (var binding : bindings.entrySet()) {
            switch (binding.getKey().toLowerCase(Locale.ROOT)) {
                case "policynim_corpus_dir" -> corpus = binding.getValue();
                case "policynim_profile" -> profile = binding.getValue();
            }
        }
        return new SourceConfigFile(corpus, profile);
    }

    private record Value(String text, int end) {}

    private static Value readValue(String text, int start, boolean assigned) {
        if (!assigned) {
            return new Value(null, start);
        }
        char quote = start < text.length() ? text.charAt(start) : 0;
        if (quote == '\'' || quote == '"') {
            int end = closingQuote(text, start, quote);
            return end < 0 ? null : new Value(decode(text.substring(start + 1, end), quote), end + 1);
        }
        int end = lineEnd(text, start);
        String value = text.substring(start, end);
        var comment = INLINE_COMMENT.matcher(value);
        if (comment.find()) {
            value = value.substring(0, comment.start());
        }
        return new Value(SourceMarkdownParser.strip(value), end);
    }

    private static int closingQuote(String text, int opening, char quote) {
        int last = -1;
        for (int at = text.indexOf(quote, opening + 1); at >= 0; at = text.indexOf(quote, at + 1)) {
            if (at == opening + 1 || text.charAt(at - 1) != '\\') {
                return at;
            }
            last = at;
        }
        // A final escaped quote can delimit the value when no later delimiter exists.
        return last;
    }

    private static String decode(String value, char quote) {
        String codes = quote == '\'' ? "\\'" : "\\'\"abfnrtv";
        String decoded = quote == '\'' ? "\\'" : "\\'\"\007\b\f\n\r\t\013";
        return ESCAPE.matcher(value).replaceAll(match -> {
            int code = codes.indexOf(match.group(1));
            return Matcher.quoteReplacement(code < 0 ? match.group() : String.valueOf(decoded.charAt(code)));
        });
    }

    private static String expand(String value, Map<String, String> previous, Map<String, String> environment) {
        return VARIABLE.matcher(value).replaceAll(match -> {
            String name = match.group(1);
            String fallback = Objects.toString(match.group(2), "");
            String result = previous.getOrDefault(name, environment.getOrDefault(name, fallback));
            return Matcher.quoteReplacement(Objects.toString(result, ""));
        });
    }

    private static int lineEnd(String text, int start) {
        int newline = text.indexOf('\n', start);
        return newline < 0 ? text.length() : newline;
    }
}
