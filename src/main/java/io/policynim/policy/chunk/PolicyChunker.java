package io.policynim.policy.chunk;

import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.parse.DocumentSection;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.MarkdownPolicyParser;
import io.policynim.policy.parse.PolicyParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PolicyChunker {

    private static final String CHUNK_ID_SEPARATOR = ":";
    private static final String DUPLICATE_SUFFIX_SEPARATOR = "-";
    private static final String SECTION_KEY_SEPARATOR = "__";
    private static final String SECTION_PATH_SEPARATOR = " > ";

    private final PolicyParser defaultParser;

    public PolicyChunker() {
        this(new MarkdownPolicyParser());
    }

    public PolicyChunker(PolicyParser defaultParser) {
        this.defaultParser = defaultParser;
    }

    public List<PolicyChunk> chunk(ParsedPolicyDocument document) {
        return chunk(document, defaultParser);
    }

    public List<PolicyChunk> chunk(ParsedPolicyDocument document, PolicyParser parser) {
        List<DocumentSection> sections = parser.extractSections(document);
        if (sections.isEmpty()) {
            throw new InvalidPolicyDocumentException(
                "Policy document %s did not yield any sections.".formatted(document.sourcePath())
            );
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<PolicyChunk> chunks = new ArrayList<>();
        for (DocumentSection section : sections) {
            List<String> headingPath = section.headingPath().isEmpty()
                ? List.of(document.metadata().title())
                : section.headingPath();
            String baseChunkId = document.metadata().policyId()
                + CHUNK_ID_SEPARATOR
                + sectionKey(headingPath);
            int occurrence = counts.merge(baseChunkId, 1, Integer::sum);
            String chunkId = occurrence == 1
                ? baseChunkId
                : baseChunkId + DUPLICATE_SUFFIX_SEPARATOR + occurrence;

            chunks.add(new PolicyChunk(
                chunkId,
                document.sourcePath(),
                String.join(SECTION_PATH_SEPARATOR, headingPath),
                section.startLine() + "-" + section.endLine(),
                section.content(),
                document.metadata()
            ));
        }

        return List.copyOf(chunks);
    }

    private static String sectionKey(List<String> headingPath) {
        List<String> normalized = new ArrayList<>();
        for (String heading : headingPath) {
            String slug = heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
            normalized.add(slug.isBlank() ? "section" : slug);
        }
        return String.join(SECTION_KEY_SEPARATOR, normalized);
    }
}
