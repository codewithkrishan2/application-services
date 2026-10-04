package com.kksg.applicationServices.repository.dto;

/**
 * What one line of a diff represents.
 *
 * <p>Three values, not four: the unified-diff format also carries {@code \ No newline at end of file}
 * markers, which are metadata about the preceding line rather than a line of the file. Those are
 * folded away by the parser instead of being given a type, because a client rendering them as content
 * would show a line that does not exist in either version of the file.
 */
public enum DiffLineType {

    /** Present in the new version only. */
    ADDED,

    /** Present in the old version only. */
    REMOVED,

    /** Unchanged, shown for orientation. */
    CONTEXT
}
