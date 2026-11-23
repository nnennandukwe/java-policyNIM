package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.InvalidPolicyDocumentException;
import io.policynim.policy.parse.SourceMarkdownParser.RuntimeRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
        return resolve(file, ProfileToml.read(file));
    }

    static SourceCatalog resolve(Path file, PolicyProfile profile) {
        var inputs = capture(file, profile, true);
        var selection = compose(inputs);
        var current = inputs.profile();
        var pins = current.packs().stream().map(PolicyProfile.Pin::publicFields).toList();
        return SourceCatalog.profiled(current.profileId(), CatalogJson.jsonDigest(current.json()), pins,
            selection.entries(), selection.effectiveCount());
    }

    record Reviewed(ProfileToml.Document document, PolicyProfile current, ObjectNode report) { }

    static ObjectNode diff(Path file) {
        return review(file).report();
    }

    static Reviewed review(Path file) {
        var document = ProfileToml.document(file);
        var inputs = capture(file, document.profile(), false);
        String validationError = null;
        try {
            compose(inputs);
        } catch (InvalidPolicyDocumentException error) {
            validationError = error.getMessage();
        }
        var report = ProfileDiff.report(document, inputs.profile(), inputs.members().stream().map(Member::entry).toList(),
            validationError);
        return new Reviewed(document, inputs.profile(), report);
    }

    private record Member(CapturedPack.Member source, ObjectNode entry) { }
    private record Inputs(PolicyProfile profile, List<Member> members) { }
    private record Selection(List<ObjectNode> entries, int effectiveCount) { }

    private static Inputs capture(Path file, PolicyProfile profile, boolean enforcePins) {
        var pins = new ArrayList<PolicyProfile.Pin>();
        var members = new ArrayList<Member>();
        var locations = new HashSet<PackLocation>();
        for (var expected : profile.packs()) {
            var location = PackLocation.resolve(file, expected.path());
            if (!locations.add(location)) {
                throw new InvalidPolicyDocumentException("A local pack directory was selected more than once.");
            }
            var pack = SourceCatalog.capturePack(location);
            if (!expected.packId().equals(pack.packId())) {
                throw new InvalidPolicyDocumentException("Selected pack_id changed; review the profile selection.");
            }
            var current = pin(expected.path(), pack);
            if (enforcePins && !current.equals(expected)) {
                throw new InvalidPolicyDocumentException("Pack " + expected.packId() + " doesn't match its pin. "
                    + "Run profile diff on the selected profile and review its current source pins.");
            }
            pins.add(current);
            for (var source : pack.members()) {
                var entry = source.standaloneEntry();
                entry.put("path", pack.packId() + "/" + source.relativePath());
                members.add(new Member(source, entry));
            }
        }
        var current = new PolicyProfile(profile.profileId(), pins, profile.exclusions(), profile.replacements(),
            profile.conflicts());
        return new Inputs(current, members);
    }

    static PolicyProfile.Pin pin(String path, CapturedPack pack) {
        var revisions = new LinkedHashMap<String, String>();
        for (var member : pack.members()) {
            revisions.put(member.policyId(), member.revisionDigest());
        }
        return new PolicyProfile.Pin(path, pack.packId(), pack.version(), pack.digest(),
            Collections.unmodifiableMap(revisions));
    }

    private static Selection compose(Inputs inputs) {
        var entries = new LinkedHashMap<String, ObjectNode>();
        for (var member : inputs.members()) {
            if (entries.putIfAbsent(member.source().policyId(), member.entry()) != null) {
                throw new InvalidPolicyDocumentException("Duplicate effective policy IDs across selected packs.");
            }
        }
        Set<String> targets = applyDecisions(inputs.profile(), entries);
        checkRuntimeRules(inputs.members(), targets);
        return new Selection(List.copyOf(entries.values()), entries.size() - targets.size());
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
            var decided = target.deepCopy().put("state", decision.replacementId() == null ? "excluded" : "replaced")
                .put("reason", decision.reason()).put("replacement_id", decision.replacementId());
            entries.put(decision.targetId(), decided);
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

    private static void checkRuntimeRules(List<Member> members, Set<String> inactive) {
        var effects = new HashMap<Matcher, String>();
        for (var selected : members) {
            var member = selected.source();
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
