package io.policynim.policy.catalog;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Holds the selected parent and sibling lock throughout one reviewed replacement. */
interface ProfileUpdateFiles extends Closeable {
    record Original(String text, int mode, Object identity) {
        static Original from(byte[] raw, int mode, Object identity) throws IOException {
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
            return new Original(ProfileToml.normalizeNewlines(text), mode, identity);
        }
    }

    Original readOriginal() throws IOException;
    void write(byte[] bytes) throws IOException;
    void preserveMode(int mode) throws IOException;
    void force() throws IOException;
    void verify(Original original) throws IOException;
    void replace() throws IOException;
    void syncDirectory() throws IOException;
    void discard() throws IOException;
}
