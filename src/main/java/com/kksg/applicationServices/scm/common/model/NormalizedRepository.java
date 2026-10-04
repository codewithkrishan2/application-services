package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Provider-independent view of a source repository.
 *
 * <p>This is the shape Module 3 (Repository Management) persists and reasons about. It is
 * produced by the response-mapping stage of the operation engine, so Module 3 never sees
 * {@code full_name} (GitHub) or {@code links.clone[]} (Bitbucket).
 *
 * <p>{@code externalId} is always a string even when a provider uses a numeric id, because
 * providers disagree on the type (GitHub: {@code int}, Bitbucket: {@code uuid string}) and the
 * platform must store one column type. Normalization to string happens declaratively via the
 * {@code TO_STRING} transform in {@code response_mapping}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NormalizedRepository {

    private String externalId;

    /** Repository short name, e.g. {@code my-project}. */
    private String name;

    /** Owner-qualified name, e.g. {@code acme/my-project}. */
    private String fullName;

    /**
     * Owning user, organisation or workspace slug.
     *
     * <p>This is the <b>addressable</b> owner segment, not a display name: it is what
     * {@code GET_REPOSITORY} and every pull-request operation substitute into {@code {{owner}}}.
     */
    private String owner;

    /** Provider's stable identifier for the owner, when it exposes one. Display and linking only. */
    private String ownerExternalId;

    /** Owner avatar, when the provider exposes one. Display only. */
    private String ownerAvatarUrl;

    private Boolean isPrivate;

    private String defaultBranch;

    private String description;

    /** HTTPS clone URL, when the provider exposes one. */
    private String cloneUrl;

    /** Human-facing URL for the repository. */
    private String webUrl;

    /**
     * Last activity timestamp as the provider reported it, ISO-8601.
     *
     * <p>Left as text rather than parsed to an {@code Instant} for the same reason
     * {@code externalId} is a string: providers disagree on precision and offset format, and the
     * engine's declarative mapping has no date parser. Callers that need a real instant parse it,
     * and a value that will not parse is reported as absent rather than failing the listing.
     */
    private String updatedAt;
}
