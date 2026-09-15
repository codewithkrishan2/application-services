package com.kksg.applicationServices.scm.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the optional {@link ScmProviderAdapter} for a provider code.
 *
 * <p>Spring injects every adapter bean on the classpath; the registry indexes them by provider code.
 * Because {@link #find} returns {@link Optional} and every adapter hook has a no-op default, the
 * engine's code path is identical whether an adapter exists or not - there is no
 * "if (adapter == null)" branching scattered through the engine, only
 * {@code find(...).map(...).orElse(default)} at the five hook sites.
 *
 * <p>With no adapters registered - the current state for GitHub and Bitbucket - this resolves to an
 * empty map and every provider is served entirely by database configuration.
 */
@Component
public class ScmAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScmAdapterRegistry.class);

    private final Map<String, ScmProviderAdapter> adaptersByProviderCode;

    public ScmAdapterRegistry(List<ScmProviderAdapter> adapters) {
        Map<String, ScmProviderAdapter> index = new HashMap<>();
        for (ScmProviderAdapter adapter : adapters) {
            String code = normalize(adapter.providerCode());
            if (code == null) {
                log.warn("SCM_ADAPTER_IGNORED: adapter {} declares no provider code",
                        adapter.getClass().getSimpleName());
                continue;
            }
            ScmProviderAdapter previous = index.put(code, adapter);
            if (previous != null) {
                // Two adapters for one provider means the effective one depends on bean ordering,
                // which is not something to leave silent.
                log.error("SCM_ADAPTER_CONFLICT: providerCode={} is claimed by both {} and {}; using {}",
                        code, previous.getClass().getSimpleName(), adapter.getClass().getSimpleName(),
                        adapter.getClass().getSimpleName());
            }
        }
        this.adaptersByProviderCode = Map.copyOf(index);

        if (index.isEmpty()) {
            log.info("SCM_ADAPTER_REGISTRY_READY: no provider adapters registered; "
                    + "all providers are served by database configuration");
        } else {
            log.info("SCM_ADAPTER_REGISTRY_READY: adapters registered for {}", index.keySet());
        }
    }

    public Optional<ScmProviderAdapter> find(String providerCode) {
        String code = normalize(providerCode);
        return code == null ? Optional.empty() : Optional.ofNullable(adaptersByProviderCode.get(code));
    }

    private static String normalize(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        return providerCode.trim().toUpperCase(Locale.ROOT);
    }
}
