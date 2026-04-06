package io.policynim.ingest;

import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.PolicyParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class FileSystemPolicyCorpus implements PolicyCorpus {

    private final PolicyParser parser;

    public FileSystemPolicyCorpus(PolicyParser parser) {
        this.parser = parser;
    }

    @Override
    public List<ParsedPolicyDocument> load(Path corpusRoot) {
        List<Path> markdownFiles = listMarkdownFiles(corpusRoot);
        List<ParsedPolicyDocument> documents = new ArrayList<>();
        Map<String, String> seenPolicyIds = new HashMap<>();

        for (Path path : markdownFiles) {
            String sourcePath = normalizeSourcePath(corpusRoot, path);
            String text;
            try {
                text = Files.readString(path);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to read policy document " + path, ex);
            }

            ParsedPolicyDocument document = parser.parse(sourcePath, text);
            String previousPath = seenPolicyIds.putIfAbsent(document.metadata().policyId(), sourcePath);
            if (previousPath != null) {
                throw new InvalidPolicyDocumentException(
                    "Duplicate effective policy_id %s found in %s and %s."
                        .formatted(document.metadata().policyId(), previousPath, sourcePath)
                );
            }
            documents.add(document);
        }

        return List.copyOf(documents);
    }

    private static List<Path> listMarkdownFiles(Path corpusRoot) {
        try (Stream<Path> paths = Files.walk(corpusRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(path -> corpusRoot.relativize(path).toString().replace('\\', '/')))
                .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to scan policy corpus " + corpusRoot, ex);
        }
    }

    private static String normalizeSourcePath(Path corpusRoot, Path file) {
        String relative = corpusRoot.relativize(file).toString().replace('\\', '/');
        return corpusRoot.getFileName().toString() + "/" + relative;
    }
}
