package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * A repository, as this API reports it.
 *
 * <p>Built from {@code NormalizedRepository}, so by the time a value reaches this class every
 * provider-shaped name is already gone. This DTO exists on top of that normalized model rather than
 * instead of it for two reasons: the normalized model is the vocabulary later modules consume
 * internally and should stay free of presentation concerns, and a REST response is a published
 * contract that must be able to change independently of it.
 *
 * <p><b>{@code id} is not an address.</b> It is the provider's own repository identifier - useful for
 * correlation and stable across renames - but no configured operation accepts it in a URL. Providers
 * address repositories by owner-qualified name, so {@code fullName} (equivalently {@code owner} plus
 * {@code name}) is what a client must use to build a nested request. Treating {@code id} as a handle
 * produces 404s, which is why the field order here leads with the addressable values.
 *
 * <p>Nothing credential-bearing appears: no token, no token reference, no connection secret, and no
 * provider configuration. The only connection-derived value is {@code provider}.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepositoryResponse {

    /** Provider's repository identifier. Correlation only; see the class note. */
    private final String id;

    /** Short name, e.g. {@code my-service}. */
    private final String name;

    /**
     * Owner-qualified name, e.g. {@code acme/my-service}.
     *
     * <p>The addressable key. Note that for providers distinguishing a display name from a URL slug
     * this is built from the slug, so splitting it is correct where using {@code name} would not be.
     */
    private final String fullName;

    private final String description;

    private final String defaultBranch;

    private final RepositoryVisibility visibility;

    /** Human-facing provider URL for the repository. */
    private final String webUrl;

    /**
     * HTTPS clone URL when the provider publishes one.
     *
     * <p>Safe to expose: it is the public clone address and carries no credential. Nothing in this
     * module clones anything - the field is here because the provider reports it and later modules
     * will need it.
     */
    private final String cloneUrl;

    private final RepositoryOwner owner;

    private final ScmResourceProvider provider;

    /** Last activity, or {@code null} when the provider reported none in a readable format. */
    private final Instant updatedAt;
}
