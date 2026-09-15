package com.kksg.applicationServices.scm.connection.mapper;

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

    public static ScmConnectionResponse toResponse(ScmConnection connection) {
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
