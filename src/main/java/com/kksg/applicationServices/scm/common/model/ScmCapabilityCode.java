package com.kksg.applicationServices.scm.common.model;

/**
 * What a provider is <i>able</i> to do.
 *
 * <p>Deliberately distinct from {@link ScmOperationCode}:
 * <ul>
 *   <li>Capability answers <b>"can this provider do this?"</b> - a product/UI level question.
 *       It is used to hide features, to fail fast with a meaningful error, and to decide whether
 *       a review pipeline step is even attemptable for a given provider.</li>
 *   <li>Operation answers <b>"how do we perform it?"</b> - the concrete HTTP recipe.</li>
 * </ul>
 *
 * <p>The two are intentionally not merged even though the codes overlap today. A provider may
 * declare a capability as supported while the operation row is temporarily deactivated (for
 * example during an incident), and a provider may expose an operation that the platform does not
 * yet advertise as a capability. Collapsing them would make one of those states unrepresentable.
 */
public enum ScmCapabilityCode {

    LIST_REPOSITORIES,
    GET_REPOSITORY,
    LIST_PULL_REQUESTS,
    GET_PULL_REQUEST,
    GET_PULL_REQUEST_FILES,
    GET_PULL_REQUEST_DIFF,
    CREATE_WEBHOOK,
    DELETE_WEBHOOK,
    CREATE_PR_COMMENT,
    CREATE_PR_REVIEW,

    /** Provider issues short-lived access tokens that must be refreshed. */
    OAUTH_TOKEN_REFRESH,

    /** Provider signs webhook payloads so delivery authenticity can be verified. */
    WEBHOOK_SIGNATURE_VERIFICATION;

    public static ScmCapabilityCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ScmCapabilityCode value : values()) {
            if (value.name().equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return null;
    }
}
