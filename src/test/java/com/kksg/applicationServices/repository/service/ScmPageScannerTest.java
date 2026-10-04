package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.repository.RepositoryManagementProperties;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.common.model.NormalizedRepository;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paging and search over a provider listing.
 *
 * <p>The two behaviours worth pinning down are the ones a client depends on and cannot verify for
 * itself:
 * <ul>
 *   <li><b>Page translation.</b> API page <i>n</i> must become provider page <i>n+1</i>. An off-by-one
 *       here would silently skip or repeat a page of a user's repositories.</li>
 *   <li><b>What {@code totalElements} means.</b> Present only when the result set is genuinely known to
 *       be complete; absent when a bounded scan stopped early. That distinction is the only honest
 *       signal a client can be given, so it must not drift into "absent whenever convenient".</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ScmPageScannerTest {

    private static final ScmResourceProvider PROVIDER = new ScmResourceProvider("GITHUB", "GitHub");

    @Mock
    private ScmOperationRunner runner;

    private RepositoryManagementProperties properties;
    private ScmPageScanner scanner;
    private ScmResourceContext context;

    @BeforeEach
    void setUp() {
        properties = new RepositoryManagementProperties();
        properties.getSearch().setMaxPages(3);
        properties.getSearch().setPageSize(10);
        scanner = new ScmPageScanner(runner, properties);

        ScmConnection connection = new ScmConnection();
        connection.setId(5);
        context = new ScmResourceContext(connection, 1, PROVIDER);
    }

    private NormalizedRepository repo(String name) {
        return NormalizedRepository.builder()
                .externalId(name)
                .name(name)
                .fullName("acme/" + name)
                .owner("acme")
                .build();
    }

    private List<NormalizedRepository> repos(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> repo(prefix + index))
                .toList();
    }

    private PageResponse<String> fetch(PageQuery query) {
        return scanner.fetchPage(context, ScmOperationCode.LIST_REPOSITORIES, Map.of(),
                NormalizedRepository.class, query,
                repository -> query.hasSearch()
                        && repository.getName().toLowerCase().contains(query.search()),
                NormalizedRepository::getName, null);
    }

    /* --------------------------------------------------------------------- *
     * Direct mode - no search
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("maps API page n to provider page n+1")
    void translatesPageNumbering() {
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("r", 20), true));

        fetch(PageQuery.of(2, 20, null));

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), isNull());

        // Providers number their first page 1; this API's first page is 0.
        assertThat(request.getValue().getParameters())
                .containsEntry(ScmOperationRequest.PARAM_PAGE, 3)
                .containsEntry(ScmOperationRequest.PARAM_PAGE_SIZE, 20);
    }

    @Test
    @DisplayName("takes hasNext from the provider rather than guessing from the item count")
    void usesProviderPagingState() {
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("r", 20), true));

        PageResponse<String> page = fetch(PageQuery.of(0, 20, null));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.isHasNext()).isTrue();
        assertThat(page.isLast()).isFalse();
        assertThat(page.isFirst()).isTrue();
        assertThat(page.getPage()).isZero();
        assertThat(page.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("reports no total when the provider publishes none")
    void omitsUnknownTotal() {
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("r", 7), false));

        PageResponse<String> page = fetch(PageQuery.of(0, 20, null));

        // Substituting content.size() here would read as "7 repositories in total", which is a claim
        // the provider never made.
        assertThat(page.getTotalElements()).isNull();
        assertThat(page.getTotalPages()).isNull();
        assertThat(page.isLast()).isTrue();
    }

    @Test
    @DisplayName("passes through a total the provider did publish, and derives the page count")
    void passesThroughKnownTotal() {
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("r", 20), true, 100));

        PageResponse<String> page = fetch(PageQuery.of(0, 20, null));

        assertThat(page.getTotalElements()).isEqualTo(100);
        assertThat(page.getTotalPages()).isEqualTo(5);
    }

    @Test
    @DisplayName("no search means exactly one provider call")
    void directModeIssuesOneCall() {
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("r", 20), true));

        fetch(PageQuery.of(0, 20, null));

        verify(runner, times(1)).run(eq(context), any(), isNull());
        // The four-argument overload is the scan's; the ordinary path must not take it.
        verify(runner, never()).run(eq(context), any(), isNull(), anyBoolean());
    }

    /* --------------------------------------------------------------------- *
     * Scan mode - with search
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("search scans provider pages and keeps only matches")
    void scanFiltersAcrossPages() {
        // One match on page 1, two on page 2 - a filter that only looked at the page the client asked
        // for would find just the first.
        List<NormalizedRepository> firstPage = new ArrayList<>(repos("other", 9));
        firstPage.add(repo("auth-service"));
        List<NormalizedRepository> secondPage = List.of(repo("auth-gateway"), repo("authz-rules"));

        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, firstPage, true))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, secondPage, false));

        PageResponse<String> page = fetch(PageQuery.of(0, 20, "auth"));

        assertThat(page.getContent())
                .containsExactly("auth-service", "auth-gateway", "authz-rules");
        assertThat(page.isHasNext()).isFalse();
        // The scan reached the end of the provider's data, so the total is genuinely known.
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("records connection usage on the first scan call only")
    void recordsUsageOnce() {
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 10), true))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 2), false));

        fetch(PageQuery.of(0, 20, "auth"));

        // "Last used" is a per-request fact; writing it per provider page would be several identical
        // updates in separate transactions for one keystroke in a filter box.
        verify(runner).run(eq(context), any(), isNull(), eq(true));
        verify(runner).run(eq(context), any(), isNull(), eq(false));
    }

    @Test
    @DisplayName("stops at the page bound and then reports no total")
    void scanRespectsPageBound() {
        // Every page is full of matches and claims more exist, so the scan is stopped by maxPages = 3
        // rather than by reaching the end.
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 10), true));

        PageResponse<String> page = fetch(PageQuery.of(0, 20, "auth"));

        verify(runner, times(3)).run(eq(context), any(), isNull(), anyBoolean());
        assertThat(page.getContent()).hasSize(20);
        assertThat(page.isHasNext()).isTrue();
        // Absence of a total is the signal that the result set may be incomplete. A count here would
        // look authoritative and be wrong.
        assertThat(page.getTotalElements()).isNull();
    }

    @Test
    @DisplayName("stops early once it has enough matches to answer")
    void scanStopsOnceSatisfied() {
        // 10 matches on the first page satisfies a request for page 0 of size 5 plus the one extra
        // needed to know hasNext, so there is no reason to fetch a second page.
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 10), true));

        PageResponse<String> page = fetch(PageQuery.of(0, 5, "auth"));

        verify(runner, times(1)).run(eq(context), any(), isNull(), anyBoolean());
        assertThat(page.getContent()).hasSize(5);
        assertThat(page.isHasNext()).isTrue();
    }

    @Test
    @DisplayName("slices the requested page out of the matched set")
    void scanSlicesRequestedPage() {
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 7), false));

        PageResponse<String> page = fetch(PageQuery.of(1, 3, "auth"));

        assertThat(page.getContent()).containsExactly("auth3", "auth4", "auth5");
        assertThat(page.isHasNext()).isTrue();
        assertThat(page.isFirst()).isFalse();
        assertThat(page.getTotalElements()).isEqualTo(7);
    }

    @Test
    @DisplayName("a page beyond the matched set is empty rather than an error")
    void scanPastEndIsEmpty() {
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 3), false));

        PageResponse<String> page = fetch(PageQuery.of(5, 20, "auth"));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.isHasNext()).isFalse();
    }

    @Test
    @DisplayName("hasNext in scan mode never claims a page the scan cannot produce")
    void scanHasNextIsDerivedFromMatchesOnly() {
        // Nothing matched, but the provider says it has more pages. Claiming hasNext here would offer a
        // Next button that returns the same empty page forever, since the scan always restarts at page
        // one and is deterministic.
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("other", 10), true));

        PageResponse<String> page = fetch(PageQuery.of(0, 20, "auth"));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.isHasNext()).isFalse();
    }

    @Test
    @DisplayName("the scan requests the configured scan page size, not the client's")
    void scanUsesConfiguredPageSize() {
        when(runner.run(eq(context), any(), isNull(), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, repos("auth", 10), false));

        fetch(PageQuery.of(0, 5, "auth"));

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), isNull(), anyBoolean());

        // Scanning is dominated by round-trip count, so it fetches in the largest pages configured
        // rather than in the client's page size.
        assertThat(request.getValue().getParameters())
                .containsEntry(ScmOperationRequest.PARAM_PAGE_SIZE, 10)
                .containsEntry(ScmOperationRequest.PARAM_PAGE, 1);
    }

    @Test
    @DisplayName("a null predicate disables search even when a term was supplied")
    void nullPredicateMeansNoSearch() {
        // The changed-files endpoint accepts `search` for contract consistency but passes no predicate,
        // because a client filtering one pull request's file list is filtering data it already holds.
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.GET_PULL_REQUEST_FILES, repos("r", 4), false));

        PageResponse<String> page = scanner.fetchPage(context, ScmOperationCode.GET_PULL_REQUEST_FILES,
                Map.of(), NormalizedRepository.class, PageQuery.of(0, 20, "anything"),
                null, NormalizedRepository::getName, null);

        assertThat(page.getContent()).hasSize(4);
        verify(runner, never()).run(eq(context), any(), isNull(), anyBoolean());
    }

    @Test
    @DisplayName("non-paging parameters are forwarded to the operation")
    void forwardsOperationParameters() {
        when(runner.run(eq(context), any(), isNull()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_PULL_REQUESTS, List.of(), false));

        scanner.fetchPage(context, ScmOperationCode.LIST_PULL_REQUESTS,
                Map.of("owner", "acme", "repo", "api", "state", "MERGED"),
                NormalizedRepository.class, PageQuery.of(0, 20, null),
                null, NormalizedRepository::getName, null);

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), isNull());

        ScmOperationRequest captured = request.getValue();
        assertThat(captured.getOperation()).isEqualTo(ScmOperationCode.LIST_PULL_REQUESTS);
        assertThat(captured.getParameters())
                .containsEntry("owner", "acme")
                .containsEntry("repo", "api")
                .containsEntry("state", "MERGED");
    }
}
