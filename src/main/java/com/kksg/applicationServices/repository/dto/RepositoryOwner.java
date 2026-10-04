package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The account a repository belongs to - a user, an organisation, or a workspace.
 *
 * <p>{@code name} is the <b>addressable</b> owner segment, not a display name. It is the value the
 * platform substitutes into {@code {{owner}}} when it asks a provider for this repository or its pull
 * requests, which is why a client can build a link from it and be certain the link resolves. A
 * display name that merely looked right would produce 404s on every nested call.
 *
 * @param id        provider's identifier for the owner, or {@code null} when the provider does not
 *                  expose one on this payload. Display and correlation only - never used to address a
 *                  resource, because providers do not accept it in repository URLs.
 * @param name      addressable owner segment (GitHub login, Bitbucket workspace slug).
 * @param avatarUrl owner avatar, when available.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RepositoryOwner(String id, String name, String avatarUrl) {
}
