package com.kksg.applicationServices.scm.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Loads {@code classpath:scm/seed/*.json} at startup so the platform is usable immediately after
 * deployment, with GitHub and Bitbucket already configured.
 *
 * <p><b>Why a programmatic seeder rather than a migration.</b> This project has no migration tool: there is
 * no Flyway or Liquibase dependency, and the schema is managed by {@code spring.jpa.hibernate.ddl-auto:
 * update}. Introducing Flyway for Module 2 alone would mean baselining the existing Hibernate-generated
 * {@code users} and {@code user_logins} tables - a change with Module 1 blast radius that this module has no
 * mandate to make. A reconciling seeder achieves the same goal (working configuration with no manual
 * inserts) within the project's existing strategy. Adopting Flyway properly is recorded as a recommended
 * follow-up in the module documentation, and {@code docs/sql/scm-provider-seed.sql} contains the equivalent
 * statements for operators who prefer to apply configuration by hand.
 *
 * <p>Runs as an {@link ApplicationRunner}, after the context is refreshed and therefore after
 * {@code ddl-auto} has created the tables.
 *
 * <p><b>Seeding failure does not stop startup.</b> Each provider is reconciled in its own transaction, and a
 * failure is logged and skipped. One malformed seed file should not prevent the application from booting -
 * the identity module and any already-configured provider remain perfectly usable, and a hard failure here
 * would turn a configuration typo into a total outage. The error is logged at ERROR so it is not missed.
 */
@Component
@ConditionalOnProperty(name = "scm.seed.enabled", havingValue = "true", matchIfMissing = true)
public class ScmProviderSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScmProviderSeeder.class);

    private static final String SEED_LOCATION_PATTERN = "classpath*:scm/seed/*.json";

    private final ObjectMapper objectMapper;
    private final ScmProviderSeedWriter seedWriter;

    public ScmProviderSeeder(ObjectMapper objectMapper, ScmProviderSeedWriter seedWriter) {
        this.objectMapper = objectMapper;
        this.seedWriter = seedWriter;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * Reconciles every seed document found on the classpath.
     *
     * <p>Public and separate from {@link #run} so integration tests can invoke seeding deterministically
     * rather than depending on runner ordering.
     *
     * @return the outcome for each document that was applied.
     */
    public List<ScmProviderSeedWriter.SeedOutcome> seed() {
        List<Resource> resources = findSeedResources();
        if (resources.isEmpty()) {
            log.warn("SCM_SEED_NO_DOCUMENTS: no provider seed files found at {}", SEED_LOCATION_PATTERN);
            return List.of();
        }

        List<ScmProviderSeedWriter.SeedOutcome> outcomes = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream input = resource.getInputStream()) {
                ScmProviderSeedDocument document = objectMapper.readValue(input, ScmProviderSeedDocument.class);
                // Goes through the Spring proxy, so each provider gets its own transaction.
                outcomes.add(seedWriter.write(document));
            } catch (Exception ex) {
                log.error("SCM_SEED_FAILED: resource={}, reason={}",
                        resource.getFilename(), ex.getMessage(), ex);
            }
        }

        log.info("SCM_SEED_COMPLETED: documents={}, applied={}, skipped={}",
                resources.size(),
                outcomes.stream().filter(outcome -> !outcome.skipped()).count(),
                outcomes.stream().filter(ScmProviderSeedWriter.SeedOutcome::skipped).count());
        return outcomes;
    }

    /**
     * @return seed resources in a stable, filename-ordered sequence. Deterministic ordering keeps
     *         {@code display_order} collisions and log output reproducible across environments, which
     *         classpath scan order alone does not guarantee.
     */
    private List<Resource> findSeedResources() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(SEED_LOCATION_PATTERN);
            return Arrays.stream(found)
                    .sorted(Comparator.comparing(resource ->
                            resource.getFilename() != null ? resource.getFilename() : ""))
                    .toList();
        } catch (Exception ex) {
            log.error("SCM_SEED_DISCOVERY_FAILED: pattern={}", SEED_LOCATION_PATTERN, ex);
            return List.of();
        }
    }
}
