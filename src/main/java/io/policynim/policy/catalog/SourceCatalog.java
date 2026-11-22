package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.SourceMarkdownParser;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;

public final class SourceCatalog {

    private static final String BUNDLE_ROOT = "/io/policynim/bundled/policies/";

    private final NavigableMap<String, ObjectNode> entries;
    private final String digest;
    private final List<String> unlisted;

    private SourceCatalog(Builder builder, List<String> unlisted) {
        this.entries = builder.entries;
        this.digest = CatalogJson.catalogDigest(entries.values());
        this.unlisted = List.copyOf(unlisted);
    }

    public static SourceCatalog read(Path selected) {
        try {
            SourceDirectory directory = SourceDirectory.open(selected);
            Builder builder = new Builder();
            List<String> unlisted = List.of();
            boolean manifested = directory.hasManifest();
            if (manifested) {
                unlisted = PackSourceFiles.read(directory.root(), builder::addPackMember);
            } else {
                directory.readPlainFiles(builder::addPlainFile);
            }
            directory.verifySelection(manifested);
            if (builder.entries.isEmpty()) {
                throw new InvalidPolicyDocumentException("The source catalog has no policies. "
                    + "Check that the directory passed to --corpus-root contains .md policy files.");
            }
            return new SourceCatalog(builder, unlisted);
        } catch (IOException | DirectoryIteratorException error) {
            throw new InvalidPolicyDocumentException("Couldn't inspect policy root " + selected + ".", error);
        }
    }

    public static SourceCatalog readBundled() {
        return readBundled(SourceCatalog.class::getResourceAsStream);
    }

    static SourceCatalog readBundled(Function<String, InputStream> resources) {
        var builder = new Builder();
        var paths = new HashSet<String>();
        String path = "pack.toml";
        try {
            var manifest = PolicyPackManifest.parse(readResource(resources, path));
            for (int index = 0; index < manifest.policies().size(); index++) {
                var member = manifest.policies().get(index);
                String field = "pack.toml policies." + index + ".path";
                String memberPath = PackSourceFiles.normalizedPath(member.path(), field);
                if (!paths.add(memberPath)) {
                    throw new InvalidPolicyDocumentException(field + " repeats a member.");
                }
                path = memberPath;
                builder.addPackMember(path, "policies/" + path, readResource(resources, path), manifest, member);
                path = "pack.toml";
            }
            return new SourceCatalog(builder, List.of());
        } catch (IOException | InvalidPolicyDocumentException error) {
            String problem = switch (error) {
                case FileNotFoundException missing -> "Missing";
                case IOException unreadable -> "Couldn't read";
                default -> "Invalid";
            };
            throw new InvalidPolicyDocumentException(problem + " bundled policy resource policies/" + path
                + ". Reinstall PolicyNIM or pass --corpus-root=<path> to select a local policy directory.", error);
        }
    }

    private static byte[] readResource(Function<String, InputStream> resources, String relativePath) throws IOException {
        try (InputStream input = resources.apply(BUNDLE_ROOT + relativePath)) {
            if (input == null) {
                throw new FileNotFoundException(relativePath);
            }
            return input.readAllBytes();
        }
    }

    private static final class Builder {
        private final NavigableMap<String, ObjectNode> entries = new TreeMap<>(SourceCatalog::compareCodePoints);
        private final Map<String, String> sourcePaths = new HashMap<>();
        private final SourceMarkdownParser parser = new SourceMarkdownParser();

        void addPlainFile(String path, String sourcePath, byte[] bytes) {
            var doc = parser.parse(sourcePath, decode(sourcePath, bytes));
            addEntry(path, doc, CatalogJson.legacyProvenance(CatalogJson.contentDigest(bytes)));
        }

        void addPackMember(String path, String sourcePath, byte[] bytes, PolicyPackManifest manifest,
                           PolicyPackManifest.Member member) {
            var doc = parser.parsePackMember(sourcePath, decode(sourcePath, bytes)).document();
            if (!member.policyId().equals(doc.metadata().policyId())) {
                throw new InvalidPolicyDocumentException("pack.toml policy_id for " + member.path()
                    + " doesn't match its Markdown policy_id.");
            }
            addEntry(path, doc, member.provenance(CatalogJson.contentDigest(bytes), manifest));
        }

        private static String decode(String sourcePath, byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException invalid) {
                throw SourceDirectory.readFailure(sourcePath, invalid);
            }
        }

        private void addEntry(String path, ParsedPolicyDocument doc, ObjectNode provenance) {
            String id = doc.metadata().policyId();
            String previous = sourcePaths.putIfAbsent(id, doc.sourcePath());
            if (previous != null) {
                throw new InvalidPolicyDocumentException("Duplicate effective policy_id " + quotedId(id)
                    + " in " + previous + " and " + doc.sourcePath() + ".");
            }
            entries.put(id, CatalogJson.entry(doc, path, provenance));
        }
    }

    public ObjectNode list() {
        ObjectNode result = CatalogJson.object().put("schema_version", 1);
        sourceScope(result);
        result.put("digest", digest).putNull("profile_id").putNull("profile_digest").putArray("pack_pins");
        counts(result);
        var policies = result.putArray("policies");
        entries.values().forEach(entry -> policies.add(entry.deepCopy()));
        return result;
    }

    public ObjectNode validate() {
        ObjectNode result = CatalogJson.object().put("status", "ok");
        sourceScope(result);
        result.put("catalog_digest", digest);
        counts(result);
        return result;
    }

    public ObjectNode show(String policyId) {
        ObjectNode entry = entries.get(policyId);
        if (entry == null) {
            throw new InvalidPolicyDocumentException("Policy ID " + quotedId(policyId)
                + " isn't in the source catalog. Run `java -jar <jar> policies list --source --corpus-root=<path>`.");
        }
        return sourceScope(entry.deepCopy());
    }

    private static ObjectNode sourceScope(ObjectNode node) {
        return node.put("scope", "source").putNull("generation_id");
    }

    private void counts(ObjectNode node) {
        var paths = node.put("authored_count", entries.size()).put("effective_count", entries.size())
            .putNull("indexed_count").putArray("unlisted_markdown");
        unlisted.forEach(paths::add);
    }

    static int compareCodePoints(String left, String right) {
        int a = 0;
        int b = 0;
        while (a < left.length() && b < right.length()) {
            int first = left.codePointAt(a);
            int second = right.codePointAt(b);
            if (first != second) {
                return Integer.compare(first, second);
            }
            a += Character.charCount(first);
            b += Character.charCount(second);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    // The reference CLI presents IDs as Python string literals, including quotes and control escapes.
    private static String quotedId(String id) {
        char quote = id.indexOf('\'') >= 0 && id.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder literal = new StringBuilder().append(quote);
        id.codePoints().forEach(code -> {
            if (code == quote || code == '\\') {
                literal.append('\\').appendCodePoint(code);
            } else if (code == '\n' || code == '\t' || code == '\r') {
                literal.append(code == '\n' ? "\\n" : code == '\t' ? "\\t" : "\\r");
            } else if (code != ' ' && !printable(code)) {
                literal.append((code < 256 ? "\\x%02x" : code < 65536 ? "\\u%04x" : "\\U%08x").formatted(code));
            } else {
                literal.appendCodePoint(code);
            }
        });
        return literal.append(quote).toString();
    }

    private static boolean printable(int code) {
        return switch (Character.getType(code)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
