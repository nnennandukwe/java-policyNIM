package io.policynim.policy.catalog;

import java.io.Closeable;
import java.io.IOException;

/** Owns the open directory selected for profile creation until its last staged file closes. */
interface ProfileDirectory extends Closeable {
    /** Opens or creates one ordinary child directory relative to the held parent. */
    void descend(String component) throws IOException;

    /** Returns a file stage in the current directory. The directory outlives the stage. */
    ProfileStage stage() throws IOException;
}
