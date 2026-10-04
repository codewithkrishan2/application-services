package com.kksg.applicationServices.scm.connection.mapper;

import com.kksg.applicationServices.scm.connection.dto.ScmConnectionReadiness;
import com.kksg.applicationServices.scm.connection.dto.ScmConnectionResponse;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;

import java.util.Map;

/**
 * Entity to DTO conversion for connections, following the project's static-mapper convention.
 *
 * <p>Metadata is projected key by key rather than copied wholesale. Metadata is an open map, so a
 * future writer could place something sensitive in it; an allow-list means the response contract cannot
 * silently widen.
 */
public final class ScmConnectionMapper {

    private static final String METADATA_DISPLAY_NAME = "displayName";
    private static final String METADATA_AVATAR_URL = "avatarUrl";

    private ScmConnectionMapper() {
    }

    /**
     * Maps without a readiness signal.
     *
     * <p>Retained for the connect flow, which returns the connection it has just written and where
     * readiness is knowable without computing it: a connection created moments ago from a fresh token
     * exchange is ready by construction.
     */
    public static ScmConnectionResponse toResponse(ScmConnection connection) {
        return toResponse(connection, ScmConnectionReadiness.READY);
    }

    /**
     * @param readiness derived by {@code ScmConnectionReadinessResolver}. Passed in rather than
     *                  computed here because it depends on the provider's OAuth configuration, and a
     *                  static mapper has no business reaching for a Spring bean to get it.
     */
    public static ScmConnectionResponse toResponse(ScmConnection connection,
                                                   ScmConnectionReadiness readiness) {
        ScmProvider provider = connection.getProvider();
        Map<String, Object> metadata = connection.getMetadata();

        return ScmConnectionResponse.builder()
                .id(connection.getId())
                .providerId(provider != null ? provider.getId() : null)
                .providerCode(provider != null ? provider.getProviderCode() : null)
                .providerName(provider != null ? provider.getProviderName() : null)
                .externalAccountId(connection.getExternalAccountId())
                .externalAccountName(connection.getExternalAccountName())
                .connectionStatus(connection.getConnectionStatus() != null
                        ? connection.getConnectionStatus().name() : null)
                .tokenExpiry(connection.getTokenExpiry())
                .connectedAt(connection.getConnectedAt())
                .lastUsedAt(connection.getLastUsedAt())
                .readiness(readiness != null ? readiness.name() : null)
                .usable(readiness != null && readiness.isUsable())
                .reauthorizationRequired(readiness != null && readiness.requiresUserAction())
                .displayName(readString(metadata, METADATA_DISPLAY_NAME))
                .avatarUrl(readString(metadata, METADATA_AVATAR_URL))
                .build();
    }

    private static String readString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value instanceof String text ? text : null;
    }
}
