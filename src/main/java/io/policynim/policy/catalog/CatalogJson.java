package io.policynim.policy.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.model.ParsedPolicyDocument;
import io.policynim.policy.model.PolicyMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class CatalogJson {

    // The source contract uses raw Unicode and lowercase hex for escaped control characters.
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
        .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
        .build();

    private CatalogJson() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ObjectNode entry(ParsedPolicyDocument doc, String path, byte[] content) {
        ObjectNode policy = metadata(doc.metadata(), hex(sha256().digest(content)));
        MessageDigest target = sha256();
        target.update(compact(policy));
        target.update((byte) '\n');
        target.update(doc.body().getBytes(StandardCharsets.UTF_8));
        target.update((byte) '\n');
        target.update(path.getBytes(StandardCharsets.UTF_8));

        ObjectNode entry = object().put("state", "active").put("target_digest", hex(target.digest()));
        entry.putNull("reason").putNull("replacement_id").put("path", path);
        entry.set("policy", policy);
        return entry.put("body", doc.body());
    }

    private static ObjectNode metadata(PolicyMetadata metadata, String contentDigest) {
        ObjectNode policy = object().put("policy_id", metadata.policyId()).put("title", metadata.title())
            .put("doc_type", metadata.docType()).put("domain", metadata.domain());
        var tags = policy.putArray("tags");
        metadata.tags().forEach(tags::add);
        var groundedIn = policy.putArray("grounded_in");
        metadata.groundedIn().forEach(groundedIn::add);
        ObjectNode provenance = policy.putObject("provenance").put("content_digest", contentDigest)
            .putNull("pack_id").putNull("pack_version").put("requirement_level", "unknown");
        provenance.putArray("applicability");
        provenance.put("grounding_kind", "legacy_unknown").putArray("sources");
        return policy;
    }

    static String catalogDigest(Iterable<ObjectNode> entries) {
        MessageDigest catalog = sha256();
        var ordered = entries.iterator();
        while (ordered.hasNext()) {
            catalog.update(compact(ordered.next()));
            if (ordered.hasNext()) {
                catalog.update((byte) '\n');
            }
        }
        return hex(catalog.digest());
    }

    private static byte[] compact(ObjectNode value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Couldn't encode source catalog JSON.", ex);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        return "sha256:" + HexFormat.of().formatHex(bytes);
    }
}
