package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The minimum needed to say which repository a pull request belongs to, and to link back to it.
 *
 * <p>Deliberately not a {@link RepositoryResponse}. Including the full repository on a pull request
 * would mean either a second provider call per pull request - unaffordable on a list - or carrying
 * fields filled from whatever the pull-request payload happened to echo, which differs by provider and
 * would make the same repository look different depending on where it was read from. These four values
 * are known for certain from the request path and the connection, with no extra call and no ambiguity.
 *
 * @param name     repository short name.
 * @param fullName owner-qualified name; the addressable key.
 * @param owner    addressable owner segment.
 * @param provider which provider this came from.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RepositoryRefResponse(String name, String fullName, String owner,
                                    ScmResourceProvider provider) {
}
