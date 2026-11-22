package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The validated file model, in the field order used for the portable profile digest. */
record PolicyProfile(String profileId, List<Pin> packs, List<Decision> exclusions,
                     List<Decision> replacements, List<Conflict> conflicts) {
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    record Pin(String path, String packId, String version, String digest, Map<String, String> policies) {
        ObjectNode publicFields() {
            return CatalogJson.object().put("path", path).put("pack_id", packId).put("version", version).put("digest", digest);
        }

        ObjectNode json() {
            var pin = publicFields();
            var revisions = pin.putObject("policies");
            policies.forEach(revisions::put);
            return pin;
        }
    }

    record Decision(String targetId, String expectedDigest, String reason, String replacementId) {
        ObjectNode json() {
            var result = CatalogJson.object().put("target_id", targetId).put("expected_digest", expectedDigest)
                .put("reason", reason);
            if (replacementId != null) {
                result.put("replacement_id", replacementId);
            }
            return result;
        }
    }

    record Conflict(String left, String right, String reason) {
        ObjectNode json() {
            return CatalogJson.object().put("left", left).put("right", right).put("reason", reason);
        }
    }

    ObjectNode json() {
        var profile = CatalogJson.object().put("schema_version", 1).put("profile_id", profileId);
        var pins = profile.putArray("packs");
        packs.forEach(pin -> pins.add(pin.json()));
        var excluded = profile.putArray("exclusions");
        exclusions.forEach(decision -> excluded.add(decision.json()));
        var replaced = profile.putArray("replacements");
        replacements.forEach(decision -> replaced.add(decision.json()));
        var declared = profile.putArray("conflicts");
        conflicts.forEach(conflict -> declared.add(conflict.json()));
        return profile;
    }
}
