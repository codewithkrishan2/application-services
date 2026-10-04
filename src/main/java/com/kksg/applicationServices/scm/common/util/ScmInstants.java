package com.kksg.applicationServices.scm.common.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Parses the timestamp strings that normalized SCM models carry.
 *
 * <p>Those models keep timestamps as text because the declarative response mapping has no date
 * parser - it can rename and coerce a field, not interpret one. The interpretation therefore happens
 * here, once, rather than in each consuming module.
 *
 * <p>Two formats have to be accepted because providers disagree:
 * <ul>
 *   <li>{@code 2024-05-01T12:00:00Z} - a {@code Z}-suffixed instant, which {@link Instant#parse}
 *       handles;</li>
 *   <li>{@code 2024-05-01T12:00:00.123456+00:00} - a numeric offset with sub-second precision, which
 *       {@link Instant#parse} rejects outright and which only {@link OffsetDateTime} reads.</li>
 * </ul>
 *
 * <p><b>An unparseable value yields {@code null}, never an exception.</b> A timestamp is decoration on
 * a repository listing; a provider that changes its format, or a field that is genuinely absent,
 * should cost a greyed-out "unknown" in the UI rather than a failed page load.
 */
public final class ScmInstants {

    private ScmInstants() {
    }

    /** @return the parsed instant, or {@code null} if the value is absent or not a timestamp. */
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Falls through to the offset form rather than being reported: which of the two formats a
            // provider uses is not an error condition, so the first failed attempt is expected.
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
