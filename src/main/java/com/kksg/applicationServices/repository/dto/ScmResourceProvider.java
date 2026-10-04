package com.kksg.applicationServices.repository.dto;

/**
 * Which provider a repository or pull request came from.
 *
 * <p>Carried on every resource rather than left for the client to infer from the connection it
 * queried, because the same repository name can exist on two providers and a client that caches or
 * aggregates results needs the discriminator attached to the data.
 *
 * <p>{@code code} is the stable identifier ({@code GITHUB}); {@code name} is for display. No
 * configuration, base URL or credential property name appears here.
 *
 * @param code provider code, as seeded. Clients may key presentation off this but must not branch
 *             business behaviour on it.
 * @param name human-facing provider name.
 */
public record ScmResourceProvider(String code, String name) {
}
