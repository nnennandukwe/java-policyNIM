package io.policynim.policy.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.glavo.url.WebURL;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

record DeclaredPolicySource(String title, String url, String revision, List<String> sections, LocalDate retrievedOn,
                            String license, String attribution, String changeNotice) {

    static DeclaredPolicySource read(PolicyPackFields fields) {
        var source = new DeclaredPolicySource(fields.sourceText("title", true), url(fields),
            fields.sourceText("revision", false), fields.strings("sections"), DeclaredSourceDate.read(fields),
            fields.sourceText("license", false), fields.sourceText("attribution", false),
            fields.sourceText("change_notice", false));
        fields.checkKeys(Set.of("title", "url", "revision", "sections", "retrieved_on", "license", "attribution", "change_notice"));
        return source;
    }

    private static String url(PolicyPackFields fields) {
        String raw = fields.text("url", true);
        if (raw == null) {
            return null;
        }
        int pathEnd = raw.length();
        for (char delimiter : new char[] {'?', '#'}) {
            int index = raw.indexOf(delimiter);
            if (index >= 0) {
                pathEnd = Math.min(pathEnd, index);
            }
        }
        String normalized = raw.substring(0, pathEnd).replace('\\', '/') + raw.substring(pathEnd);
        try {
            var url = WebURL.parse(normalized);
            if (!Set.of("http", "https").contains(url.getScheme()) || url.getHost() == null || url.getHost().isEmpty()) {
                fields.error("url", "Expected an HTTP or HTTPS URL with a host.");
            } else if ((url.getUsername() != null && !url.getUsername().isEmpty())
                || (url.getPassword() != null && !url.getPassword().isEmpty())) {
                fields.error("url", "Source URLs mustn't contain credentials.");
            } else {
                return url.toString();
            }
        } catch (IllegalArgumentException ex) {
            fields.error("url", "Expected a valid HTTP or HTTPS URL.");
        }
        return null;
    }

    ObjectNode json() {
        ObjectNode node = CatalogJson.object().put("title", title).put("url", url).put("revision", revision);
        sections.forEach(node.putArray("sections")::add);
        return node.put("retrieved_on", retrievedOn == null ? null : retrievedOn.toString())
            .put("license", license).put("attribution", attribution).put("change_notice", changeNotice);
    }
}
