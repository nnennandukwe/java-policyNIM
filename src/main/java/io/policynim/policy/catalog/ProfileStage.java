package io.policynim.policy.catalog;

import java.io.Closeable;
import java.io.IOException;

/** A staged file and its held destination directory for one create-only publication. */
abstract class ProfileStage implements Closeable {
    abstract void write(byte[] bytes) throws IOException;
    abstract void force() throws IOException;
    abstract void verify() throws IOException;
    abstract void verifyDirectory() throws IOException;
    abstract void publish(String outputName) throws IOException;
    abstract void discard() throws IOException;
}
