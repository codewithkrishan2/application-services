package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;

/**
 * An authorized connection, resolved once per request and passed down.
 *
 * <p>Exists so that "this user may use this connection" is established in exactly one place and then
 * <b>carried as a value</b> rather than re-derived. A service that accepted a {@code userId} and a
 * {@code connectionId} and looked them up itself would make the authorization check repeatable, and
 * therefore skippable: the next method added would compile perfectly well without it. Taking this
 * record as a parameter means an unauthorized call cannot be written, because there is no way to obtain
 * one except through {@link ScmResourceAccessService}.
 *
 * <p>The provider code and name are snapshotted here rather than read from {@code connection} at use
 * time. The association is lazy, the surrounding read is deliberately not transactional for the
 * duration of an outbound HTTP call, and dereferencing it later is how a
 * {@code LazyInitializationException} appears in a response path.
 *
 * <p>Nothing credential-bearing is held: no token, and not the token reference either. Credentials are
 * resolved inside the operation engine, immediately before the call that needs them.
 *
 * @param connection the owned, usable connection.
 * @param userId     the authenticated user it belongs to. Carried for logging, so an operation log line
 *                   can be attributed without a second lookup.
 * @param provider   provider code and display name, for the response.
 */
public record ScmResourceContext(ScmConnection connection, Integer userId, ScmResourceProvider provider) {

    public Integer connectionId() {
        return connection.getId();
    }

    public String providerCode() {
        return provider.code();
    }
}
