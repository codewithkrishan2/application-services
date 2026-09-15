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

    /** Owning user, organisation or workspace slug. */
    private String owner;

    private Boolean isPrivate;

    private String defaultBranch;

    private String description;

    /** HTTPS clone URL, when the provider exposes one. */
    private String cloneUrl;

    /** Human-facing URL for the repository. */
    private String webUrl;
}
