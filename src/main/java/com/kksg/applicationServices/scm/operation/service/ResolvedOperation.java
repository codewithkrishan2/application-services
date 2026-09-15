package com.kksg.applicationServices.scm.operation.service;

import com.kksg.applicationServices.scm.operation.engine.RequestConfiguration;
import com.kksg.applicationServices.scm.operation.engine.ResponseMapping;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;

/**
 * An operation row together with its two JSONB documents already parsed and validated.
 *
 * <p>Exists so that the engine receives one ready-to-use value instead of re-parsing
 * {@code request_configuration} and {@code response_mapping} at each stage of request building.
 * Keeping the parsed forms adjacent to the entity also makes it impossible for the engine to
 * accidentally use one operation's mapping with another's endpoint.
 */
public record ResolvedOperation(
        ScmProviderOperation operation,
        RequestConfiguration requestConfiguration,
        ResponseMapping responseMapping) {
}
