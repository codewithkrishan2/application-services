package com.kksg.applicationServices.scm.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Parsed form of one {@code classpath:scm/seed/*.json} provider definition.
 *
 * <p>Each file describes a complete provider integration: the provider row, its JSONB configuration, the
 * capabilities it declares, the operation recipes that implement them, and its webhook event mappings.
 * A file like this <i>is</i> a provider integration - adding a provider means adding one of these, not
 * writing a service.
 *
 * <p>Codes are carried as {@code String} rather than as enums so that a malformed value produces a clear,
 * file-scoped seeding error naming the offending code, instead of a Jackson failure that aborts startup
 * without saying which file was at fault.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScmProviderSeedDocument(
        String providerCode,
        String providerName,
        String providerType,
        Integer displayOrder,
        Boolean active,
        Map<String, Object> configuration,
        List<CapabilitySeed> capabilities,
        List<OperationSeed> operations,
        List<EventSeed> events) {

    public List<CapabilitySeed> capabilitiesOrEmpty() {
        return capabilities != null ? capabilities : List.of();
    }

    public List<OperationSeed> operationsOrEmpty() {
        return operations != null ? operations : List.of();
    }

    public List<EventSeed> eventsOrEmpty() {
        return events != null ? events : List.of();
    }

    public boolean activeOrDefault() {
        return active == null || active;
    }

    public int displayOrderOrDefault() {
        return displayOrder != null ? displayOrder : 0;
    }

    /**
     * @param supported an explicit {@code false} is meaningful - it records a verified provider limitation
     *                  rather than an omission. Bitbucket's lack of a formal review API is seeded this way.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapabilitySeed(
            String capabilityCode,
            Boolean supported,
            Map<String, Object> configuration) {

        public boolean supportedOrDefault() {
            return supported == null || supported;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OperationSeed(
            String operationCode,
            String httpMethod,
            String endpointTemplate,
            Map<String, Object> requestConfiguration,
            Map<String, Object> responseMapping,
            Boolean active) {

        public boolean activeOrDefault() {
            return active == null || active;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventSeed(
            String providerEventName,
            String providerAction,
            String normalizedEventType,
            Map<String, Object> configuration,
            Boolean active) {

        /**
         * Normalizes a missing action to {@code ""}. The column is non-null so that the uniqueness
         * constraint works: PostgreSQL treats NULLs as distinct, so a nullable action would permit
         * unlimited duplicate rows for the same event.
         */
        public String providerActionOrEmpty() {
            return providerAction != null ? providerAction.trim() : "";
        }

        public boolean activeOrDefault() {
            return active == null || active;
        }
    }
}
