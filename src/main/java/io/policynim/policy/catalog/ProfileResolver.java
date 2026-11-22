package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.SourceMarkdownParser.RuntimeRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Verifies selected pack pins, then applies decisions to the captured members. */
final class ProfileResolver {
    private ProfileResolver() { }

    static SourceCatalog resolve(Path file) {
        var profile = ProfileToml.read(file);
        var packs = verifiedPacks(profile, file);
        var members = new LinkedHashMap<String, CapturedPack.Member>();
        var entries = new LinkedHashMap<String, ObjectNode>();
        for (var pack : packs) {
            for (var member : pack.members()) {
                if (members.putIfAbsent(member.policyId(), member) != null) {
                    throw new InvalidPolicyDocumentException("Duplicate effective policy IDs across selected packs.");
                }
                var entry = member.standaloneEntry();
                entry.put("path", pack.packId() + "/" + member.relativePath());
                entries.put(member.policyId(), entry);
            }
        }
        Set<String> targets = applyDecisions(profile, entries);
        checkRuntimeRules(members, targets);
        var pins = profile.packs().stream().map(PolicyProfile.Pin::publicFields).toList();
        return SourceCatalog.profiled(profile.profileId(), CatalogJson.jsonDigest(profile.json()), pins,
            List.copyOf(entries.values()), entries.size() - targets.size());
    }

    private static List<CapturedPack> verifiedPacks(PolicyProfile profile, Path file) {
        var packs = new ArrayList<CapturedPack>();
        var locations = new HashSet<PackLocation>();
        for (var pin : profile.packs()) {
            var location = PackLocation.resolve(file, pin.path());
            if (!locations.add(location)) {
                throw new InvalidPolicyDocumentException("A local pack directory was selected more than once.");
            }
            var pack = SourceCatalog.capturePack(location);
            if (!pin.packId().equals(pack.packId())) {
                throw new InvalidPolicyDocumentException("Selected pack_id changed; review the profile selection.");
            }
            if (!pin.version().equals(pack.version()) || !pin.digest().equals(pack.digest())
                    || !matchesRevisions(pin, pack)) {
                throw new InvalidPolicyDocumentException("Pack " + pin.packId() + " doesn't match its pin. "
                    + "Review the selected pack and the pins in the profile TOML.");
            }
            packs.add(pack);
        }
        return packs;
    }

    private static boolean matchesRevisions(PolicyProfile.Pin pin, CapturedPack pack) {
        return pin.policies().size() == pack.members().size() && pack.members().stream()
            .allMatch(member -> member.revisionDigest().equals(pin.policies().get(member.policyId())));
    }

    private static Set<String> applyDecisions(PolicyProfile profile, Map<String, ObjectNode> entries) {
        var decisions = new ArrayList<>(profile.exclusions());
        decisions.addAll(profile.replacements());
        var targets = new HashSet<String>();
        var replacements = new HashSet<String>();
        for (var decision : decisions) {
            if (!targets.add(decision.targetId())
                    || decision.replacementId() != null && !replacements.add(decision.replacementId())) {
                throw new InvalidPolicyDocumentException("Contradictory exclusions or multiple replacements.");
            }
        }
        if (targets.stream().anyMatch(replacements::contains)) {
            throw new InvalidPolicyDocumentException("Replacement chains, cycles, or excluded replacements "
                + "aren't supported; keep replacements active.");
        }
        for (var decision : decisions) {
            var target = entries.get(decision.targetId());
            if (target == null) {
                throw new InvalidPolicyDocumentException("Unknown target " + SourceCatalog.quotedId(decision.targetId())
                    + "; review IDs.");
            }
            if (!decision.expectedDigest().equals(target.path("target_digest").textValue())) {
                throw new InvalidPolicyDocumentException("Stale target digest for " + SourceCatalog.quotedId(decision.targetId())
                    + "; review its current content before editing expected_digest.");
            }
            if (decision.replacementId() != null && !entries.containsKey(decision.replacementId())) {
                throw new InvalidPolicyDocumentException("Unknown replacement ID; select its pack first.");
            }
            target.put("state", decision.replacementId() == null ? "excluded" : "replaced")
                .put("reason", decision.reason()).put("replacement_id", decision.replacementId());
        }
        if (entries.size() == targets.size()) {
            throw new InvalidPolicyDocumentException("The profile must retain at least one effective policy.");
        }
        for (var conflict : profile.conflicts()) {
            if (conflict.left().equals(conflict.right()) || !entries.containsKey(conflict.left())
                    || !entries.containsKey(conflict.right())) {
                throw new InvalidPolicyDocumentException("Declared conflicts must name two known, distinct IDs.");
            }
            if (!targets.contains(conflict.left()) && !targets.contains(conflict.right())) {
                throw new InvalidPolicyDocumentException("Declared conflict: " + conflict.left() + " / " + conflict.right()
                    + ". Add a reviewed exclusion or replacement.");
            }
        }
        return targets;
    }

    private record Matcher(String action, Set<String> paths, Set<String> commands, Set<String> hosts) {
        Matcher(RuntimeRule rule) {
            this(rule.action(), Set.copyOf(rule.pathGlobs()), Set.copyOf(rule.commandRegexes()),
                rule.urlHostPatterns().stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
        }
    }

    private static void checkRuntimeRules(Map<String, CapturedPack.Member> members, Set<String> inactive) {
        var effects = new HashMap<Matcher, String>();
        for (var member : members.values()) {
            if (inactive.contains(member.policyId())) {
                continue;
            }
            for (var rule : member.source().rules()) {
                String previous = effects.putIfAbsent(new Matcher(rule), rule.effect());
                if (previous != null && !previous.equals(rule.effect())) {
                    throw new InvalidPolicyDocumentException("Conflicting runtime effects for identical matchers; "
                        + "review selected policies.");
                }
            }
        }
    }
}
