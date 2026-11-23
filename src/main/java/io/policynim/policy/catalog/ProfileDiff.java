package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

/** Compares the authored pins with the current revisions of each selected pack. */
final class ProfileDiff {
    private ProfileDiff() { }

    static ObjectNode report(ProfileToml.Document document, PolicyProfile current, List<ObjectNode> entries,
                             String validationError) {
        var added = new ArrayList<String>();
        var changed = new ArrayList<String>();
        var removed = new ArrayList<String>();
        var changedPacks = new ArrayList<String>();
        // Capture keeps the declared pack order and requires each selected pack ID to match.
        for (int index = 0; index < current.packs().size(); index++) {
            var previous = document.profile().packs().get(index);
            var now = current.packs().get(index);
            if (!previous.equals(now)) {
                changedPacks.add(now.packId());
            }
            now.policies().forEach((id, revision) -> {
                String expected = previous.policies().get(id);
                if (expected == null) {
                    added.add(id);
                } else if (!revision.equals(expected)) {
                    changed.add(id);
                }
            });
            previous.policies().keySet().stream().filter(id -> !now.policies().containsKey(id)).forEach(removed::add);
        }

        var targets = new HashMap<String, String>();
        for (var entry : entries) {
            targets.put(policyId(entry), entry.get("target_digest").textValue());
        }
        var invalidated = Stream.concat(current.exclusions().stream(), current.replacements().stream())
            .filter(decision -> !decision.expectedDigest().equals(targets.get(decision.targetId())))
            .map(PolicyProfile.Decision::targetId).toList();

        var result = CatalogJson.object().put("profile_id", current.profileId())
            .put("review_digest", CatalogJson.reviewDigest(document.text(), current.json()));
        addSorted(result, "added", added);
        addSorted(result, "changed", changed);
        addSorted(result, "removed", removed);
        addSorted(result, "changed_packs", changedPacks);
        addSorted(result, "invalidated_decisions", invalidated);
        var policies = result.putArray("current_policies");
        entries.stream().sorted(Comparator.comparing(ProfileDiff::policyId, SourceCatalog::compareCodePoints))
            .forEach(policies::add);
        result.put("validation_error", validationError);
        result.putArray("warnings");
        return result;
    }

    private static String policyId(ObjectNode entry) {
        return entry.get("policy").get("policy_id").textValue();
    }

    private static void addSorted(ObjectNode result, String field, List<String> ids) {
        var values = result.putArray(field);
        ids.stream().sorted(SourceCatalog::compareCodePoints).forEach(values::add);
    }
}
