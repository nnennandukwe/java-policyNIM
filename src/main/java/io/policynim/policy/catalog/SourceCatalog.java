package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.SourceMarkdownParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class SourceCatalog {

    private final NavigableMap<String, ObjectNode> entries;
    private final String digest;

    private SourceCatalog(NavigableMap<String, ObjectNode> entries) {
        this.entries = entries;
        this.digest = CatalogJson.catalogDigest(entries.values());
    }

    public static SourceCatalog read(Path root) {
        NavigableMap<String, ObjectNode> entries = new TreeMap<>(SourceCatalog::compareCodePoints);
        Map<String, String> sourcePaths = new HashMap<>();
        SourceMarkdownParser parser = new SourceMarkdownParser();
        PlainSourceDirectory.forEachFile(root, (path, sourcePath, content) -> {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
            } catch (CharacterCodingException ex) {
                throw PlainSourceDirectory.readFailure(sourcePath, ex);
            }
            ParsedPolicyDocument doc = parser.parse(sourcePath, text);
            String id = doc.metadata().policyId();
            String previous = sourcePaths.putIfAbsent(id, sourcePath);
            if (previous != null) {
                throw new InvalidPolicyDocumentException("Duplicate effective policy_id " + quotedId(id)
                    + " in " + previous + " and " + sourcePath + ".");
            }
            entries.put(id, CatalogJson.entry(doc, path, content));
        });
        if (entries.isEmpty()) {
            throw new InvalidPolicyDocumentException("The source catalog has no policies. "
                + "Check POLICYNIM_CORPUS_DIR or pack.toml membership.");
        }
        return new SourceCatalog(entries);
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
                + " isn't in the source catalog. Run `policynim policies list --source`.");
        }
        return sourceScope(entry.deepCopy());
    }

    private static ObjectNode sourceScope(ObjectNode node) {
        return node.put("scope", "source").putNull("generation_id");
    }

    private void counts(ObjectNode node) {
        node.put("authored_count", entries.size()).put("effective_count", entries.size())
            .putNull("indexed_count").putArray("unlisted_markdown");
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
