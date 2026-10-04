package com.kksg.applicationServices.scm.provider.config;

import com.kksg.applicationServices.scm.common.util.PlaceholderResolver;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fills in operation parameters a provider can derive from the connection.
 *
 * <p>Exists because of a real asymmetry between providers, not as speculative generality. GitHub
 * lists a user's repositories from {@code /user/repos} - the credential alone identifies whose
 * repositories to return. Bitbucket has no such endpoint: Atlassian ended support for every
 * cross-workspace API, so the only listing is {@code /2.0/repositories/&#123;workspace&#125;} and the
 * workspace must be supplied. A caller asking for "my repositories" has no business knowing it, and
 * it is not a property of the request - it is a property of the connection.
 *
 * <p>So the provider declares where the value comes from, and this class resolves it. The
 * alternative - a branch on {@code providerCode} somewhere in Repository Management - would put
 * provider knowledge in the one layer the module exists to keep free of it.
 *
 * <h2>Facts exposed to templates</h2>
 * <pre>
 *   {{connection.id}}             connection's own identifier
 *   {{connection.accountId}}      provider's account identifier, as discovered at connect time
 *   {{connection.accountName}}    provider's account login/slug, as discovered at connect time
 *   {{connection.metadata.KEY}}   any string value in the connection's metadata
 * </pre>
 *
 * <p>Only string metadata values are exposed. Metadata is an open map that a future writer could put
 * a structure into, and a nested object rendered into a URL path would produce a plausible-looking
 * wrong request rather than an obvious failure.
 *
 * <p><b>Resolution is best-effort and never throws.</b> A parameter whose candidates all fail to
 * resolve is simply absent, which leaves the engine's existing
 * {@code SCM_OPERATION_PARAMETER_MISSING} check to produce a precise error naming the parameter. That
 * is a better failure than one invented here, and it is the same error a caller would get for any
 * other missing required parameter.
 */
@Component
public class ConnectionParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(ConnectionParameterResolver.class);

    /** Namespace under which connection facts are exposed, keeping them clear of caller parameters. */
    private static final String NAMESPACE = "connection";

    /**
     * @param configuration the provider's parsed configuration, which declares what to derive.
     * @param connection    the connection in use, or {@code null} during the connect flow, where no
     *                      connection row exists yet. A null connection yields no defaults rather
     *                      than an error, because {@code GET_CURRENT_ACCOUNT} is precisely the call
     *                      that discovers the facts the templates refer to.
     * @return parameter name to resolved value, for every declared parameter that could be resolved.
     */
    public Map<String, Object> resolve(ProviderConfiguration configuration, ScmConnection connection) {
        Map<String, List<String>> templates = configuration.connectionParameterTemplates();
        if (templates.isEmpty() || connection == null) {
            return Map.of();
        }

        Map<String, Object> facts = buildFacts(connection);
        Map<String, Object> resolved = new LinkedHashMap<>();

        templates.forEach((name, candidates) -> firstResolvable(candidates, facts)
                .ifPresent(value -> resolved.put(name, value)));

        if (!resolved.isEmpty()) {
            // Keys only. A workspace slug is not a secret, but this map is populated from connection
            // metadata, which is an open map - logging its values would be a standing invitation for
            // whatever a future writer puts there to end up in the log.
            log.debug("SCM_CONNECTION_PARAMETERS_RESOLVED: connectionId={}, parameters={}",
                    connection.getId(), resolved.keySet());
        }
        return resolved;
    }

    /**
     * @return the first candidate template that resolves to a non-blank value.
     *
     * <p>Ordering is the whole point: a provider lists its preferred source first and its fallback
     * second, so an explicit per-connection override wins over the value discovered at connect time.
     */
    private Optional<String> firstResolvable(List<String> candidates, Map<String, Object> facts) {
        for (String candidate : candidates) {
            Optional<String> value = PlaceholderResolver.resolveOptional(candidate, facts)
                    .map(String::trim)
                    .filter(resolved -> !resolved.isEmpty());
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> buildFacts(ScmConnection connection) {
        Map<String, Object> connectionFacts = new LinkedHashMap<>();
        connectionFacts.put("id", connection.getId());
        connectionFacts.put("accountId", connection.getExternalAccountId());
        connectionFacts.put("accountName", connection.getExternalAccountName());
        connectionFacts.put("metadata", stringMetadata(connection));

        return Map.of(NAMESPACE, connectionFacts);
    }

    private Map<String, Object> stringMetadata(ScmConnection connection) {
        Map<String, Object> metadata = connection.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> strings = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (value instanceof String text && !text.isBlank()) {
                strings.put(key, text);
            }
        });
        return strings;
    }
}
