package io.policynim.policy.parse;

import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.model.PolicyMetadata;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownPolicyParser implements PolicyParser {

    private static final Pattern ATX_HEADING = Pattern.compile("^( {0,3})(#{1,6})(?:[ \\t]+|$)(.*)$");
    private static final String FRONTMATTER_DELIMITER = "---";

    private final Yaml yaml = new Yaml(new SafeConstructor(loaderOptions()));

    @Override
    public ParsedPolicyDocument parse(String sourcePath, String text) {
        String normalizedText = normalizeNewlines(text).stripLeading();
        if (normalizedText.startsWith("\uFEFF")) {
            normalizedText = normalizedText.substring(1);
        }

        FrontmatterSplit split = splitFrontmatter(sourcePath, normalizedText);
        if (split.body().trim().isEmpty()) {
            throw new InvalidPolicyDocumentException(
                "Policy document %s does not contain usable Markdown content.".formatted(sourcePath)
            );
        }

        List<HeadingEvent> headings = findHeadingEvents(linesOf(split.body()), split.bodyStartLine());
        String title = stringValue(split.frontmatter().get("title"));
        if (title == null) {
            title = firstHeadingTitle(headings, 1);
        }
        if (title == null) {
            title = firstHeadingTitle(headings, null);
        }
        if (title == null) {
            title = humanizeStem(sourcePath);
        }

        String policyId = defaultIfBlank(stringValue(split.frontmatter().get("policy_id")), derivePolicyId(sourcePath));
        String domain = defaultIfBlank(stringValue(split.frontmatter().get("domain")), deriveDomain(sourcePath));
        String docType = defaultIfBlank(stringValue(split.frontmatter().get("doc_type")), "guidance");

        return new ParsedPolicyDocument(
            sourcePath,
            new PolicyMetadata(
                policyId,
                title,
                docType,
                domain,
                stringList(split.frontmatter().get("tags")),
                stringList(split.frontmatter().get("grounded_in"))
            ),
            split.body(),
            split.bodyStartLine()
        );
    }

    @Override
    public List<DocumentSection> extractSections(ParsedPolicyDocument document) {
        List<String> lines = linesOf(document.body());
        if (lines.isEmpty()) {
            return List.of();
        }
        if (document.body().trim().isEmpty()) {
            return List.of();
        }

        List<HeadingEvent> headings = findHeadingEvents(lines, document.bodyStartLine());
        if (headings.isEmpty()) {
            return List.of(new DocumentSection(
                List.of(document.metadata().title()),
                document.body().trim(),
                document.bodyStartLine(),
                document.bodyStartLine() + lines.size() - 1
            ));
        }

        List<DocumentSection> sections = new ArrayList<>();
        DocumentSection preamble = buildPreambleSection(lines, document.metadata().title(), document.bodyStartLine(), headings.getFirst().line());
        if (preamble != null) {
            sections.add(preamble);
        }

        List<String> headingPath = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            HeadingEvent heading = headings.get(index);
            int nextStart = index + 1 < headings.size()
                ? headings.get(index + 1).line() - 1
                : document.bodyStartLine() + lines.size() - 1;
            if (nextStart < heading.line()) {
                nextStart = heading.line();
            }

            while (headingPath.size() >= heading.level()) {
                headingPath.removeLast();
            }
            headingPath.add(heading.title().isBlank() ? document.metadata().title() : heading.title());

            int relativeStart = heading.line() - document.bodyStartLine();
            int relativeEnd = nextStart - document.bodyStartLine();
            String content = String.join("\n", lines.subList(relativeStart, relativeEnd + 1)).trim();
            if (content.isEmpty()) {
                continue;
            }

            sections.add(new DocumentSection(
                headingPath,
                content,
                document.bodyStartLine() + relativeStart,
                document.bodyStartLine() + relativeEnd
            ));
        }

        if (sections.isEmpty()) {
            throw new InvalidPolicyDocumentException(
                "Policy document %s did not yield any non-empty sections.".formatted(document.sourcePath())
            );
        }
        return sections;
    }

    private FrontmatterSplit splitFrontmatter(String sourcePath, String text) {
        List<String> lines = linesOf(text);
        if (lines.isEmpty() || !FRONTMATTER_DELIMITER.equals(lines.getFirst().trim())) {
            return new FrontmatterSplit(Map.of(), text, 1);
        }

        int closingIndex = -1;
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (FRONTMATTER_DELIMITER.equals(line) || "...".equals(line)) {
                closingIndex = index;
                break;
            }
        }
        if (closingIndex < 0) {
            throw new InvalidPolicyDocumentException(
                "Policy document %s starts frontmatter but never closes it.".formatted(sourcePath)
            );
        }

        String rawFrontmatter = String.join("\n", lines.subList(1, closingIndex));
        String body = String.join("\n", lines.subList(closingIndex + 1, lines.size()));
        return new FrontmatterSplit(parseFrontmatter(sourcePath, rawFrontmatter), body, closingIndex + 2);
    }

    private Map<String, Object> parseFrontmatter(String sourcePath, String rawFrontmatter) {
        if (rawFrontmatter.isBlank()) {
            return Map.of();
        }

        Object loaded;
        try {
            loaded = yaml.load(rawFrontmatter);
        } catch (YAMLException ex) {
            throw new InvalidPolicyDocumentException(
                "Policy document %s has malformed YAML frontmatter.".formatted(sourcePath),
                ex
            );
        }

        Object normalizedValue = normalizeYamlValue(loaded);
        if (!(normalizedValue instanceof Map<?, ?> mapping)) {
            throw new InvalidPolicyDocumentException(
                "Policy document %s has malformed YAML frontmatter.".formatted(sourcePath)
            );
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private static LoaderOptions loaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(50);
        options.setCodePointLimit(1_000_000);
        return options;
    }

    private static Object normalizeYamlValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> mapping) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeYamlValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Collection<?> values) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : values) {
                normalized.add(normalizeYamlValue(item));
            }
            return List.copyOf(normalized);
        }

        throw new InvalidPolicyDocumentException(
            "Policy frontmatter contains unsupported YAML value type: " + value.getClass().getName()
        );
    }

    private static String firstHeadingTitle(List<HeadingEvent> headings, Integer level) {
        for (HeadingEvent heading : headings) {
            if ((level == null || heading.level() == level) && !heading.title().isBlank()) {
                return heading.title();
            }
        }
        return null;
    }

    private static DocumentSection buildPreambleSection(
        List<String> lines,
        String title,
        int bodyStartLine,
        int firstHeadingLine
    ) {
        if (firstHeadingLine <= bodyStartLine) {
            return null;
        }

        int relativeEnd = firstHeadingLine - bodyStartLine;
        String content = String.join("\n", lines.subList(0, relativeEnd)).trim();
        if (content.isEmpty()) {
            return null;
        }

        return new DocumentSection(
            List.of(title, "Preamble"),
            content,
            bodyStartLine,
            firstHeadingLine - 1
        );
    }

    private static List<HeadingEvent> findHeadingEvents(List<String> lines, int bodyStartLine) {
        List<HeadingEvent> headings = new ArrayList<>();
        FenceState fence = null;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int absoluteLine = bodyStartLine + index;

            if (fence != null) {
                if (isFenceCloser(line, fence)) {
                    fence = null;
                }
                continue;
            }

            fence = matchFenceOpener(line);
            if (fence != null) {
                continue;
            }

            Matcher matcher = ATX_HEADING.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            String title = matcher.group(3).trim().replaceAll("[ \\t]+#+[ \\t]*$", "").trim();
            headings.add(new HeadingEvent(absoluteLine, matcher.group(2).length(), title));
        }

        return headings;
    }

    private static FenceState matchFenceOpener(String line) {
        String stripped = stripUpToThreeLeadingSpaces(line);
        if (stripped.length() < 3) {
            return null;
        }

        char marker = stripped.charAt(0);
        if (marker != '`' && marker != '~') {
            return null;
        }

        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == marker) {
            count++;
        }

        return count >= 3 ? new FenceState(marker, count) : null;
    }

    private static boolean isFenceCloser(String line, FenceState fence) {
        String stripped = stripUpToThreeLeadingSpaces(line);
        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == fence.marker()) {
            count++;
        }
        if (count < fence.length()) {
            return false;
        }
        for (int index = count; index < stripped.length(); index++) {
            char current = stripped.charAt(index);
            if (current != ' ' && current != '\t') {
                return false;
            }
        }
        return true;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String string) {
            return string.isBlank() ? List.of() : List.of(string);
        }
        if (!(value instanceof Collection<?> values)) {
            throw new InvalidPolicyDocumentException("Expected a string list in policy frontmatter.");
        }

        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String string = stringValue(item);
            if (string == null || string.isBlank()) {
                continue;
            }
            result.add(string);
        }
        return List.copyOf(result);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = Objects.toString(value, null);
        return string == null || string.isBlank() ? null : string;
    }

    private static String derivePolicyId(String sourcePath) {
        String domain = deriveDomain(sourcePath).replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String stem = fileStem(sourcePath).replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (domain.isBlank()) {
            return stem.toUpperCase(Locale.ROOT);
        }
        return (domain + "-" + stem).toUpperCase(Locale.ROOT);
    }

    private static String deriveDomain(String sourcePath) {
        String[] segments = sourcePath.replace('\\', '/').split("/");
        if (segments.length >= 3 && "policies".equals(segments[0])) {
            return segments[1];
        }
        if (segments.length >= 2) {
            return segments[0];
        }
        return "general";
    }

    private static String humanizeStem(String sourcePath) {
        String[] parts = fileStem(sourcePath).split("[^A-Za-z0-9]+");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            words.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return words.isEmpty() ? "Policy" : String.join(" ", words);
    }

    private static String fileStem(String sourcePath) {
        String normalized = sourcePath.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex >= 0 ? filename.substring(0, dotIndex) : filename;
    }

    private static String stripUpToThreeLeadingSpaces(String value) {
        int index = 0;
        while (index < value.length() && index < 3 && value.charAt(index) == ' ') {
            index++;
        }
        return value.substring(index);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<String> linesOf(String value) {
        if (value.isEmpty()) {
            return List.of();
        }

        String normalized = normalizeNewlines(value);
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (normalized.endsWith("\n") && !lines.isEmpty()) {
            lines.removeLast();
        }
        if (lines.size() == 1 && lines.getFirst().isEmpty()) {
            return List.of();
        }
        return List.copyOf(lines);
    }

    private record FrontmatterSplit(Map<String, Object> frontmatter, String body, int bodyStartLine) {
    }

    private record HeadingEvent(int line, int level, String title) {
    }

    private record FenceState(char marker, int length) {
    }
}
