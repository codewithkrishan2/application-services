package com.kksg.applicationServices.repository;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The bounds this module works within.
 *
 * <p>Every value here exists to put a ceiling on work that is otherwise driven by how large a user's
 * account happens to be. A repository search over an account with four thousand repositories and a diff
 * for a dependency-lockfile update are both unbounded by nature; left unbounded they become a slow
 * endpoint, an exhausted provider rate limit, and eventually a heap problem. Configuring the limits
 * rather than hard-coding them means a deployment that genuinely needs more can raise them without a
 * release, and that the chosen numbers are visible rather than buried.
 *
 * <p>They are starting points, not tuned figures. No caching layer sits in front of any of this yet,
 * deliberately - measuring real usage comes before adding one.
 */
@Component
@ConfigurationProperties(prefix = "repository")
@Getter
@Setter
public class RepositoryManagementProperties {

    private final Search search = new Search();
    private final Diff diff = new Diff();

    /**
     * Bounds on the provider-page scan that backs free-text search.
     *
     * <p>Neither configured provider offers a usable server-side search on the listings this module
     * reads, so a search term is applied by this application over pages it fetches. That is a
     * compromise with two honest failure modes - it costs several provider calls, and it can only find
     * what it has looked at - and these two numbers decide the trade.
     */
    @Getter
    @Setter
    public static class Search {

        /**
         * Most provider pages fetched to satisfy one search request.
         *
         * <p>Five at {@link #pageSize} 100 means a search looks at up to 500 items. Beyond that the
         * request stops and reports that more may exist rather than continuing, because a user typing
         * into a filter box is waiting, and twenty sequential provider calls is not a response time.
         */
        private int maxPages = 5;

        /**
         * Page size requested while scanning.
         *
         * <p>Larger than the API's own default page size on purpose: scanning is dominated by
         * round-trip count, so fetching in the largest pages the provider allows is what keeps a search
         * to a few calls. The engine clamps this to each provider's ceiling.
         */
        private int pageSize = 100;
    }

    /** Bounds on parsing a unified diff. */
    @Getter
    @Setter
    public static class Diff {

        /**
         * Most diff lines parsed. Lines beyond this are dropped and the response is marked truncated.
         *
         * <p>A diff is a single text response that is already fully in memory by the time it is parsed,
         * so this limit does not protect against receiving it - the HTTP layer's
         * {@code max-response-bytes} does that. What it bounds is the parsed structure, which is
         * several times the size of the text it came from and is what gets serialised to a client.
         */
        private int maxLines = 20_000;

        /** Most files included. A pull request touching more is reported truncated. */
        private int maxFiles = 300;
    }
}
