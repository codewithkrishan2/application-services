package com.kksg.applicationServices.scm.operation.engine;

import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;

import java.util.Map;

/**
 * Everything known about one in-flight operation, passed to the request builder, the response
 * normalizer and any provider adapter.
 *
 * <p>Bundling these into a single value keeps hook signatures stable: adding a new piece of context
 * later does not change every adapter's method signature, which matters because adapters are an
 * extension point that code outside this module may implement.
 *
 * @param provider      the provider being called.
 * @param configuration parsed provider configuration.
 * @param connection    the authorizing connection; {@code null} only for unauthenticated calls.
 * @param operationCode the normalized operation being performed.
 * @param resolved      operation row plus its parsed request/response documents.
 * @param parameters    effective parameter map - caller parameters merged with resolved path aliases
 *                      and paging defaults, which is what placeholders were resolved against.
 */
public record ScmOperationContext(
        ScmProvider provider,
        ProviderConfiguration configuration,
        ScmConnection connection,
        ScmOperationCode operationCode,
        com.kksg.applicationServices.scm.operation.service.ResolvedOperation resolved,
        Map<String, Object> parameters) {

    public String providerCode() {
        return provider != null ? provider.getProviderCode() : null;
    }

    public Integer connectionId() {
        return connection != null ? connection.getId() : null;
    }
}
