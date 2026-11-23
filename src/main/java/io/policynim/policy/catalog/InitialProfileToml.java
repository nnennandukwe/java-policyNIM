package io.policynim.policy.catalog;

import java.util.Locale;
import java.util.regex.Pattern;

/** Writes a fresh TOML 1.0 profile with editable pack tables. */
final class InitialProfileToml {
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private InitialProfileToml() { }

    static String render(PolicyProfile profile) {
        var text = new StringBuilder("schema_version = 1\n");
        field(text, "profile_id", profile.profileId());
        for (var pin : profile.packs()) {
            text.append("\n[[packs]]\n");
            field(text, "path", pin.path());
            field(text, "pack_id", pin.packId());
            field(text, "version", pin.version());
            field(text, "digest", pin.digest());
            text.append("\n[packs.policies]\n");
            pin.policies().forEach((id, digest) -> field(text, BARE_KEY.matcher(id).matches() ? id : quote(id), digest));
        }
        return text.toString().replace("\n", System.lineSeparator());
    }

    private static void field(StringBuilder text, String key, String value) {
        text.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static String quote(String value) {
        var result = new StringBuilder("\"");
        value.codePoints().forEach(ch -> {
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\f' -> result.append("\\f");
                case '\r' -> result.append("\\r");
                default -> {
                    if (ch < 32 || ch == 127) {
                        result.append(String.format(Locale.ROOT, "\\u%04X", ch));
                    } else {
                        result.appendCodePoint(ch);
                    }
                }
            }
        });
        return result.append('"').toString();
    }
}
