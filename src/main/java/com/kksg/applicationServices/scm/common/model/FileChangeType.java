package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Canonical per-file change classification within a pull request.
 *
 * <p>Module 5 (Code Analysis) uses this to decide what to analyse - for example a
 * {@link #REMOVED} file needs no linting, and a {@link #RENAMED} file needs its previous path to
 * correlate historical findings.
 */
public enum FileChangeType {

    ADDED,
    MODIFIED,
    REMOVED,
    RENAMED,
    UNKNOWN;

    @JsonCreator
    public static FileChangeType fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        for (FileChangeType value : values()) {
            if (value.name().equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
