package com.kksg.applicationServices.scm.common.model;

/**
 * Deployment shape of a provider.
 *
 * <p>This is not a provider identity (that is {@code provider_code}). It exists because hosting
 * model, rather than brand, is what changes platform behaviour: a self-hosted instance has a
 * tenant-supplied base URL and often a private TLS chain, so its API base URL must come from
 * connection/provider configuration rather than a well-known constant.
 */
public enum ScmProviderType {

    /** Vendor-hosted multi-tenant service with a fixed public API base URL (github.com, bitbucket.org). */
    CLOUD,

    /** Customer-operated instance; API base URL is supplied by configuration. */
    SELF_HOSTED;

    public static ScmProviderType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ScmProviderType value : values()) {
            if (value.name().equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return null;
    }
}
