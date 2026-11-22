package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.policynim.policy.parse.SourceMarkdownParser;

import java.util.List;

/** Members in policy-ID order, before a profile changes their paths or states. */
record CapturedPack(String packId, String version, List<Member> members) {

    CapturedPack {
        members = List.copyOf(members);
    }

    String digest() {
        return CatalogJson.catalogDigest(members.stream().map(member -> member.standaloneEntry).toList());
    }

    record Member(String relativePath, SourceMarkdownParser.ParsedSource source, ObjectNode standaloneEntry) {
        Member {
            standaloneEntry = standaloneEntry.deepCopy();
        }

        @Override
        public ObjectNode standaloneEntry() {
            return standaloneEntry.deepCopy();
        }

        String policyId() {
            return source.document().metadata().policyId();
        }

        String revisionDigest() {
            return CatalogJson.jsonDigest(standaloneEntry);
        }
    }
}
