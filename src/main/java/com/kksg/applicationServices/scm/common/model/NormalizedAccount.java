package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The provider account behind an authorization, as returned by {@code GET_CURRENT_ACCOUNT}.
 *
 * <p>Needed during the connect flow: {@code external_account_id} is part of a connection's unique key,
 * so it must be discovered before the row can be written. Obtaining it through a configured operation
 * rather than a hardcoded call is what keeps the connect flow provider-agnostic - GitHub reports
 * {@code id}/{@code login}, Bitbucket reports {@code uuid}/{@code username}, and the difference lives
 * in each provider's {@code response_mapping}.
 *
 * <p>{@code defaultWorkspace} is populated for providers that scope repositories under a workspace or
 * group and is stored in connection metadata, so later repository calls can supply it without another
 * lookup.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NormalizedAccount {

    private String externalId;

    /** Login/handle used in API paths. */
    private String username;

    private String displayName;

    private String avatarUrl;

    /** Default workspace/group slug, for providers that scope repositories under one. */
    private String defaultWorkspace;
}
