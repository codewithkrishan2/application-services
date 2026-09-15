package com.kksg.applicationServices.scm.provider.mapper;

import com.kksg.applicationServices.scm.provider.dto.ScmProviderResponse;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;

/**
 * Entity to DTO conversion for providers.
 *
 * <p>Static utility, matching the existing {@code UserMapper} convention in the identity module.
 */
public final class ScmProviderMapper {

    private ScmProviderMapper() {
    }

    public static ScmProviderResponse toResponse(ScmProvider provider) {
        return ScmProviderResponse.builder()
                .id(provider.getId())
                .providerCode(provider.getProviderCode())
                .providerName(provider.getProviderName())
                .providerType(provider.getProviderType() != null ? provider.getProviderType().name() : null)
                .active(provider.isActive())
                .displayOrder(provider.getDisplayOrder())
                .build();
    }
}
