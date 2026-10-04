package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Who opened a pull request.
 *
 * <p>Both fields are nullable, which is not an oversight: providers return a null author for a pull
 * request opened by an account that has since been deleted, and some return only a UUID on reduced
 * payloads. A client must be able to render "Unknown author" rather than assume the field is there.
 *
 * <p>No email address. Providers expose one on some payloads, it is personal data this product has no
 * use for, and including it would widen the response contract to cover something that can never be
 * narrowed again.
 *
 * @param id       provider's account identifier.
 * @param username handle at the provider.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PullRequestAuthor(String id, String username) {
}
