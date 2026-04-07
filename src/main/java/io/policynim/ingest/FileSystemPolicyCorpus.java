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

    private static final int MAX_CORPUS_DEPTH = 3;
    private static final String DEFAULT_SOURCE_PREFIX = "policies";

    private final PolicyParser parser;

    public FileSystemPolicyCorpus(PolicyParser parser) {
        this.parser = parser;
    }

    @Override
    public List<ParsedPolicyDocument> load(Path corpusRoot) {
        ValidatedCorpusRoot validatedRoot = validateCorpusRoot(corpusRoot);
        List<Path> markdownFiles = listMarkdownFiles(validatedRoot.root());
        List<ParsedPolicyDocument> documents = new ArrayList<>();
        Map<String, String> seenPolicyIds = new HashMap<>();

        for (Path path : markdownFiles) {
            String sourcePath = normalizeSourcePath(validatedRoot.sourcePrefix(), validatedRoot.root(), path);
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

    private static ValidatedCorpusRoot validateCorpusRoot(Path corpusRoot) {
        Path normalizedRoot = corpusRoot.normalize();
        if (!Files.exists(normalizedRoot)) {
            throw new InvalidPolicyDocumentException(
                "Policy corpus root %s does not exist.".formatted(corpusRoot)
            );
        }
        if (!Files.isDirectory(normalizedRoot)) {
            throw new InvalidPolicyDocumentException(
                "Policy corpus root %s must be a directory.".formatted(corpusRoot)
            );
        }
        if (normalizedRoot.equals(normalizedRoot.getRoot())) {
            throw new InvalidPolicyDocumentException(
                "Policy corpus root %s must not be a filesystem root path.".formatted(corpusRoot)
            );
        }
        return new ValidatedCorpusRoot(normalizedRoot, sourcePrefixFor(normalizedRoot));
    }

    private static List<Path> listMarkdownFiles(Path corpusRoot) {
        try (Stream<Path> paths = Files.walk(corpusRoot, MAX_CORPUS_DEPTH)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(path -> corpusRoot.relativize(path).toString().replace('\\', '/')))
                .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to scan policy corpus " + corpusRoot, ex);
        }
    }

    private static String normalizeSourcePath(String sourcePrefix, Path corpusRoot, Path file) {
        String relative = corpusRoot.relativize(file).toString().replace('\\', '/');
        return sourcePrefix + "/" + relative;
    }

    private static String sourcePrefixFor(Path corpusRoot) {
        Path fileName = corpusRoot.getFileName();
        if (fileName == null) {
            return DEFAULT_SOURCE_PREFIX;
        }
        return fileName.toString();
    }

    private record ValidatedCorpusRoot(Path root, String sourcePrefix) {
    }
}
