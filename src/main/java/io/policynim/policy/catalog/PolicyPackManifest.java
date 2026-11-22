package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.InvalidPolicyDocumentException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

record PolicyPackManifest(String packId, String version, List<Member> policies) {

    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){2}");
    private static final Set<String> LEVELS = Set.of("required", "recommended", "informational", "unknown");
    private static final Set<String> KINDS = Set.of("upstream_requirement", "adaptation", "project_recommendation",
        "legacy_unknown");

    static PolicyPackManifest parse(byte[] manifestBytes) {
        var data = PolicyPackToml.decode(manifestBytes);
        var errors = new ArrayList<String>();
        var fields = new PolicyPackFields(data, "", errors);
        if (!Long.valueOf(1).equals(fields.value("schema_version"))) {
            fields.error("schema_version", "Expected the integer 1.");
        }
        String packId = fields.text("pack_id", true);
        if (packId != null && !PACK_ID.matcher(packId).matches()) {
            fields.error("pack_id", "Expected a lowercase pack identifier.");
        }
        String version = fields.text("version", true);
        if (version != null && !VERSION.matcher(version).matches()) {
            fields.error("version", "Expected a three-part numeric version.");
        }
        List<Member> policies = fields.tables("policies", true, Member::read);
        fields.checkKeys(Set.of("schema_version", "pack_id", "version", "policies"));
        if (!errors.isEmpty()) {
            throw new InvalidPolicyDocumentException("Invalid pack.toml: " + String.join("; ", errors));
        }
        var ids = new HashSet<String>();
        var paths = new HashSet<String>();
        for (var policy : policies) {
            if (!ids.add(policy.policyId())) {
                throw new InvalidPolicyDocumentException("Invalid pack.toml: manifest: Duplicate policy_id in pack membership.");
            }
            if (!paths.add(policy.path())) {
                throw new InvalidPolicyDocumentException("Invalid pack.toml: manifest: Duplicate path in pack membership.");
            }
        }
        return new PolicyPackManifest(packId, version, policies);
    }

    record Member(String policyId, String path, String requirementLevel, List<String> applicability,
                  String groundingKind, List<DeclaredPolicySource> sources) {

        private static Member read(PolicyPackFields fields) {
            var member = new Member(fields.memberName("policy_id"), fields.memberName("path"),
                fields.choice("requirement_level", LEVELS, "unknown"), fields.strings("applicability"),
                fields.choice("grounding_kind", KINDS, "legacy_unknown"),
                fields.tables("sources", false, DeclaredPolicySource::read));
            fields.checkKeys(Set.of("policy_id", "path", "requirement_level", "applicability", "grounding_kind", "sources"));
            return member;
        }

        ObjectNode provenance(String contentDigest, PolicyPackManifest pack) {
            ObjectNode node = CatalogJson.object().put("content_digest", contentDigest)
                .put("pack_id", pack.packId()).put("pack_version", pack.version()).put("requirement_level", requirementLevel);
            applicability.forEach(node.putArray("applicability")::add);
            node.put("grounding_kind", groundingKind);
            var array = node.putArray("sources");
            sources.forEach(source -> array.add(source.json()));
            return node;
        }
    }
}
