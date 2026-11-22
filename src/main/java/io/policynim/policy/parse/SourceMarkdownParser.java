package io.policynim.policy.parse;

import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.model.PolicyMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses a captured UTF-8 policy for source inspection, retaining its body and authored rule spans. */
public final class SourceMarkdownParser {
    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|[\\n\\r\\u000b\\f\\u001c-\\u001e\\u0085\\u2028\\u2029]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    public record RuntimeRule(String action, String effect, String reason, List<String> pathGlobs,
                              List<String> commandRegexes, List<String> urlHostPatterns, int startLine, int endLine) {
        public RuntimeRule {
            pathGlobs = List.copyOf(pathGlobs);
            commandRegexes = List.copyOf(commandRegexes);
            urlHostPatterns = List.copyOf(urlHostPatterns);
        }
    }

    public record ParsedSource(ParsedPolicyDocument document, List<RuntimeRule> rules) {
        public ParsedSource {
            rules = List.copyOf(rules);
        }
    }

    public ParsedPolicyDocument parse(String sourcePath, String text) {
        return parseSource(sourcePath, text).document();
    }

    public ParsedSource parseSource(String sourcePath, String text) {
        int first = 0;
        while (first < text.length() && text.charAt(first) == '\ufeff') {
            first++;
        }
        String body = text.substring(first);
        int startLine = 1;
        SourceFrontmatter metadata = new SourceFrontmatter(sourcePath);
        var separator = LINE_BREAK.matcher(body);
        String openingLine = separator.find() ? body.substring(0, separator.start()) : body;
        if (strip(openingLine).equals("---")) {
            var lines = new ArrayList<>(List.of(LINE_BREAK.split(body, -1)));
            if (lines.getLast().isEmpty()) {
                lines.removeLast();
            }
            int end = 1;
            while (end < lines.size() && !List.of("---", "...").contains(strip(lines.get(end)))) {
                end++;
            }
            if (end == lines.size()) {
                throw new InvalidPolicyDocumentException("Policy document " + sourcePath + " starts frontmatter but never closes it.");
            }
            metadata.read(lines.subList(1, end));
            boolean finalLf = body.endsWith("\n");
            body = String.join("\n", lines.subList(end + 1, lines.size()));
            if (!body.isEmpty() && finalLf) {
                body += "\n";
            }
            startLine = end + 2;
        }
        if (strip(body).isEmpty()) {
            throw new InvalidPolicyDocumentException("Policy document " + sourcePath + " does not contain usable Markdown content.");
        }
        String[] parts = sourcePath.split("/");
        String file = parts[parts.length - 1];
        int extension = file.lastIndexOf('.');
        String stem = extension > 0 && extension < file.length() - 1 ? file.substring(0, extension) : file;
        String domain = parts.length >= 2 && parts[0].equals("policies") ? parts[1] : "general";
        String title = metadata.value("title");
        if (title.isEmpty()) {
            title = SourceHeadingTitle.read(body);
        }
        PolicyMetadata policy = new PolicyMetadata(
            metadata.valueOr("policy_id", inferredId(parts, stem)), title.isEmpty() ? humanTitle(stem) : title,
            metadata.valueOr("doc_type", "guidance"), metadata.valueOr("domain", domain),
            metadata.list("tags"), metadata.list("grounded_in"));
        return new ParsedSource(new ParsedPolicyDocument(sourcePath, policy, body, startLine), metadata.rules());
    }

    private static String inferredId(String[] parts, String stem) {
        var segments = new ArrayList<String>();
        for (int index = parts[0].equals("policies") ? 1 : 0; index < parts.length; index++) {
            String part = index == parts.length - 1 ? stem : parts[index];
            String slug = NON_ALPHANUMERIC.matcher(part.toLowerCase(Locale.ROOT)).replaceAll("-").replaceAll("^-|-$", "");
            if (!slug.isEmpty()) {
                segments.add(slug.toUpperCase(Locale.ROOT));
            }
        }
        return segments.isEmpty() ? "POLICY-DOCUMENT" : String.join("-", segments);
    }

    private static String humanTitle(String stem) {
        String words = strip(stem.replace('-', ' ').replace('_', ' '));
        if (words.isEmpty()) {
            return "Untitled Policy";
        }
        // Lower the whole name so contextual mappings (such as final sigma) see adjacent punctuation.
        String lower = words.toLowerCase(Locale.ROOT);
        var result = new StringBuilder();
        int lowerIndex = 0;
        boolean previousCased = false;
        for (int i = 0; i < words.length();) {
            int point = words.codePointAt(i);
            String character = Character.toString(point);
            int lowerWidth = character.toLowerCase(Locale.ROOT).length();
            boolean cased = Character.isLowerCase(point) || Character.isUpperCase(point) || Character.isTitleCase(point);
            if (cased && !previousCased) {
                result.append(titlecase(point, character));
            } else {
                result.append(lower, lowerIndex, lowerIndex + lowerWidth);
            }
            previousCased = cased;
            lowerIndex += lowerWidth;
            i += character.length();
        }
        return result.toString();
    }

    private static String titlecase(int point, String character) {
        int simple = Character.toTitleCase(point);
        String upper = character.toUpperCase(Locale.ROOT);
        if (simple != point || Character.isTitleCase(point) || upper.codePointCount(0, upper.length()) == 1) {
            return Character.toString(simple);
        }
        // Full Unicode title mappings can expand a ligature or retain a Greek iota subscript.
        if (upper.endsWith("\u0399") && java.text.Normalizer.normalize(character, java.text.Normalizer.Form.NFD).endsWith("\u0345")) {
            return upper.substring(0, upper.length() - 1) + "\u0345";
        }
        int firstWidth = Character.charCount(upper.codePointAt(0));
        return Character.isUpperCase(upper.codePointAt(0))
            ? upper.substring(0, firstWidth) + upper.substring(firstWidth).toLowerCase(Locale.ROOT) : upper;
    }

    // Python str.strip includes nonbreaking spaces and U+0085 in addition to Java whitespace.
    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && whitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && whitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean whitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\u0085';
    }
}
