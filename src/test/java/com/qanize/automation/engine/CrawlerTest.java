package com.qanize.automation.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The crawl engine core, driven entirely by a scripted page processor.
 *
 * <p>No WebDriver, no Chrome, no network, no real website and no mocking framework: the whole
 * point of separating the queue from the page work is that the queue can be proved like this. The
 * assertions are on the exact attempted order and the exact depth and parent of each page, because
 * counts alone would pass for a crawl that visited the right number of wrong pages in the wrong
 * order.
 */
@Tag("unit")
class CrawlerTest {

    private static final String START = "https://example.com/";

    // --- helpers -------------------------------------------------------------------------------

    private static Crawler crawler() {
        return new Crawler(CrawlPolicy.defaults());
    }

    private static Crawler crawler(int maxPages, int maxDepth) {
        return new Crawler(new CrawlPolicy(maxPages, maxDepth));
    }

    private static List<Integer> depths(Crawler.CrawlResult result) {
        return result.attempted().stream().map(Crawler.PageResult::depth).toList();
    }

    private static List<String> parents(Crawler.CrawlResult result) {
        return result.attempted().stream().map(Crawler.PageResult::discoveredFrom).toList();
    }

    // --- 1. the start page on its own ----------------------------------------------------------

    @Test
    void aStartPageThatLinksNowhereIsTheWholeCrawl() {
        ScriptedSite site = new ScriptedSite().page(START);

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(START), result.attemptedUrls());
        assertEquals(List.of(0), depths(result));

        Crawler.PageResult page = result.attempted().get(0);
        assertNull(page.discoveredFrom(), "the start page was not discovered from anywhere");
        assertEquals(Crawler.PageStatus.PROCESSED, page.status());
        assertNull(page.message(), "a page that worked carries no message");
        assertEquals(1, result.successful().size());
        assertTrue(result.failed().isEmpty());
        assertTrue(site.pageAt(0).isStartPage());
        assertFalse(result.limits().stoppedEarly(), "nothing was left undone");
    }

    // --- 2. breadth-first order ----------------------------------------------------------------

    @Test
    void everyPageAtOneDepthIsVisitedBeforeAnyPageBelowIt() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "/a", "/b")
            .page("https://example.com/a", "/a1", "/a2")
            .page("https://example.com/b", "/b1")
            .page("https://example.com/a1")
            .page("https://example.com/a2")
            .page("https://example.com/b1");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // Depth 1 is exhausted before depth 2 begins, and siblings keep discovery order.
        assertEquals(List.of(
            START,
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/a1",
            "https://example.com/a2",
            "https://example.com/b1"), result.attemptedUrls());
        assertEquals(List.of(0, 1, 1, 2, 2, 2), depths(result));
        assertEquals(Arrays.asList(
            null,
            START,
            START,
            "https://example.com/a",
            "https://example.com/a",
            "https://example.com/b"), parents(result));
    }

    // --- 3. one page, however many parents discover it -----------------------------------------

    @Test
    void aPageDiscoveredFromTwoParentsIsProcessedOnceAndKeepsItsFirstParent() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "/a", "/b")
            .page("https://example.com/a", "/shared")
            .page("https://example.com/b", "/shared")
            .page("https://example.com/shared");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(
            START,
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/shared"), result.attemptedUrls());
        // Claimed when it was first queued, so the second discovery adds nothing and the recorded
        // parent is the page that actually led there.
        assertEquals("https://example.com/a", parents(result).get(3));
        assertEquals(1, site.visits("https://example.com/shared"),
            "the shared page is opened exactly once");
    }

    // --- 4. fragments do not make a second page ------------------------------------------------

    @Test
    void fragmentVariantsOfOneAddressAreProcessedOnce() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "/a", "/a#top", "/a#bottom", "#here", "/a/")
            .page("https://example.com/a");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // "#here" resolves back to the start page, and the trailing slash is not a new page either.
        assertEquals(List.of(START, "https://example.com/a"), result.attemptedUrls());
        assertEquals(1, site.visits("https://example.com/a"));
    }

    @Test
    void aQueryStringDoesIdentifyADifferentPage() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "/list", "/list?page=2")
            .page("https://example.com/list")
            .page("https://example.com/list?page=2");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // The project's normalizer preserves the query, and the crawler does not second-guess it.
        assertEquals(List.of(
            START,
            "https://example.com/list",
            "https://example.com/list?page=2"), result.attemptedUrls());
    }

    // --- 5 & 6. origin -------------------------------------------------------------------------

    @Test
    void aSameOriginCandidateIsAcceptedIncludingItsDefaultPortSpelling() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "/relative", "https://example.com/absolute", "//example.com/schemeless")
            .page("https://example.com/relative")
            .page("https://example.com/absolute")
            .page("https://example.com/schemeless");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(
            START,
            "https://example.com/relative",
            "https://example.com/absolute",
            "https://example.com/schemeless"), result.attemptedUrls());
    }

    @Test
    void anExplicitDefaultPortIsTheSameOriginAsNoPort() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "https://example.com:443/portly")
            .page("https://example.com:443/portly");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(2, result.attempted().size(),
            "443 is the effective port of https, so this is the start origin: "
                + result.attemptedUrls());
        assertEquals(List.of(0, 1), depths(result));
    }

    @Test
    void anExternalHostnameIsNeverCrawled() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "https://other.example/elsewhere", "https://sub.example.com/child")
            .page("https://other.example/elsewhere")
            .page("https://sub.example.com/child");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // Discovering an outbound link is fine and the Links check still validates it over HTTP.
        // Opening it as a page is not: a subdomain is a different host, and so is a stranger.
        assertEquals(List.of(START), result.attemptedUrls());
        assertEquals(0, site.visits("https://other.example/elsewhere"));
        assertEquals(0, site.visits("https://sub.example.com/child"));
    }

    @Test
    void aDifferentSchemeIsNeverCrawled() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "http://example.com/insecure")
            .page("http://example.com/insecure");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(START), result.attemptedUrls(),
            "http and https are different origins even on one host");
        assertEquals(0, site.visits("http://example.com/insecure"));
    }

    @Test
    void aDifferentEffectivePortIsNeverCrawled() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "https://example.com:8443/oddport")
            .page("https://example.com:8443/oddport");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(START), result.attemptedUrls());
        assertEquals(0, site.visits("https://example.com:8443/oddport"));
    }

    @Test
    void thereIsNoWayToAskTheCrawlerToLeaveTheStartOrigin() {
        // The policy carries limits only. If a scope setting is ever reintroduced, this fails.
        List<String> knobs = Arrays.stream(CrawlPolicy.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).toList();

        assertEquals(List.of("maxPages", "maxDepth"), knobs, "the whole configurable surface");
    }

    @Test
    void externalCandidatesDoNotConsumeThePageBudget() {
        // Nine same-origin pages hidden among external links. If an excluded candidate spent
        // budget, or were queued and skipped, the crawl would stop short of all nine.
        ScriptedSite site = new ScriptedSite();
        List<String> hrefs = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            hrefs.add("https://other.example/skip" + index);
            hrefs.add("http://example.com/skip" + index);
            hrefs.add("https://example.com:8443/skip" + index);
            hrefs.add("/p" + index);
            site.page("https://example.com/p" + index);
        }
        site.page(START, hrefs.toArray(new String[0]));

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(
            START,
            "https://example.com/p1", "https://example.com/p2", "https://example.com/p3",
            "https://example.com/p4", "https://example.com/p5", "https://example.com/p6",
            "https://example.com/p7", "https://example.com/p8", "https://example.com/p9"),
            result.attemptedUrls());
        assertEquals(10, result.attempted().size(), "the start page plus all nine internal pages");
        assertEquals(0, result.limits().notVisited(),
            "an excluded candidate is never queued, so it cannot sit in the backlog either");
    }

    // --- 7. schemes ----------------------------------------------------------------------------

    @Test
    void unsupportedSchemesAreNeverCrawled() {
        ScriptedSite site = new ScriptedSite().page(START,
            "mailto:someone@example.com",
            "tel:+441234567890",
            "javascript:void(0)",
            "JavaScript:doThing()",
            "data:text/html;base64,PGgxPmhpPC9oMT4=",
            "ftp://example.com/file.txt",
            "file:///etc/passwd");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(START), result.attemptedUrls(),
            "only http and https are ever followed");
    }

    @Test
    void anInvalidCandidateIsSkippedRatherThanEndingTheRun() {
        ScriptedSite site = new ScriptedSite()
            .page(START, null, "", "   ", "http://", "ht tp://broken", "/good")
            .page("https://example.com/good");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // The unusable hrefs are ordinary facts about a real page, so the crawl carries on.
        assertEquals(List.of(START, "https://example.com/good"), result.attemptedUrls());
    }

    // --- 8. the page budget --------------------------------------------------------------------

    @Test
    void theDefaultBudgetOfTenPagesIsEnforcedExactlyAndIncludesTheStartPage() {
        ScriptedSite site = new ScriptedSite();
        List<String> hrefs = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            hrefs.add("/p" + index);
            site.page("https://example.com/p" + index);
        }
        site.page(START, hrefs.toArray(new String[0]));

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // Ten attempts total: the start page plus nine of its children, in discovery order.
        assertEquals(List.of(
            START,
            "https://example.com/p1", "https://example.com/p2", "https://example.com/p3",
            "https://example.com/p4", "https://example.com/p5", "https://example.com/p6",
            "https://example.com/p7", "https://example.com/p8", "https://example.com/p9"),
            result.attemptedUrls());
        assertEquals(10, result.attempted().size());
        assertTrue(result.limits().pageLimitReached());
        assertEquals(3, result.limits().notVisited(), "p10, p11 and p12 were queued but not reached");
        assertEquals(0, site.visits("https://example.com/p10"));
        assertTrue(result.limits().stoppedEarly());
    }

    @Test
    void aBudgetOfOneVisitsOnlyTheStartPage() {
        ScriptedSite site = new ScriptedSite().page(START, "/a", "/b").page("https://example.com/a");

        Crawler.CrawlResult result = crawler(1, 5).crawl(START, site);

        assertEquals(List.of(START), result.attemptedUrls());
        assertTrue(result.limits().pageLimitReached());
        assertEquals(2, result.limits().notVisited());
    }

    // --- 9. the depth budget -------------------------------------------------------------------

    @Test
    void depthZeroFollowsNothingAndDepthOneFollowsOnlyTheStartPagesLinks() {
        ScriptedSite flat = new ScriptedSite().page(START, "/a").page("https://example.com/a", "/a1");

        Crawler.CrawlResult zero = crawler(10, 0).crawl(START, flat);
        assertEquals(List.of(START), zero.attemptedUrls());
        assertTrue(zero.limits().depthLimitReached());
        assertEquals(0, zero.limits().notVisited(), "nothing was ever queued to be skipped");

        Crawler.CrawlResult one = crawler(10, 1).crawl(START,
            new ScriptedSite().page(START, "/a").page("https://example.com/a", "/a1"));
        assertEquals(List.of(START, "https://example.com/a"), one.attemptedUrls());
        assertEquals(List.of(0, 1), depths(one));
        assertTrue(one.limits().depthLimitReached(), "/a1 was refused for depth");
    }

    @Test
    void aCrawlThatFitsInsideItsLimitsReportsNeitherLimitReached() {
        ScriptedSite site = new ScriptedSite().page(START, "/a").page("https://example.com/a");

        Crawler.CrawlResult result = crawler(10, 5).crawl(START, site);

        assertFalse(result.limits().pageLimitReached());
        assertFalse(result.limits().depthLimitReached());
        assertEquals(0, result.limits().notVisited());
        assertFalse(result.limits().stoppedEarly());
    }

    // --- 10 & 11. failures ---------------------------------------------------------------------

    @Test
    void onePageFailureIsRecordedOnceAndTheQueuedPagesStillRun() {
        RuntimeException broken = new IllegalStateException("the page never became ready");
        ScriptedSite site = new ScriptedSite()
            .page(START, "/a", "/b", "/c")
            .page("https://example.com/a")
            .fails("https://example.com/b", broken)
            .page("https://example.com/c");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(
            START,
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/c"), result.attemptedUrls());
        assertEquals(1, site.visits("https://example.com/b"), "a failure is never retried");

        assertEquals(List.of("https://example.com/b"),
            result.failed().stream().map(Crawler.PageResult::url).toList());
        assertEquals(3, result.successful().size());

        Crawler.PageResult failure = result.failed().get(0);
        assertEquals(Crawler.PageStatus.FAILED, failure.status());
        assertEquals(1, failure.depth(), "a failed page keeps its place in the tree");
        assertEquals(START, failure.discoveredFrom());
        assertTrue(failure.message().contains("the page never became ready"), failure.message());
        assertSame(broken, failure.cause(), "the original exception is preserved as the cause");
    }

    @Test
    void aFailedPageStillSpendsPageBudget() {
        ScriptedSite site = new ScriptedSite()
            .page(START, "/a", "/b")
            .fails("https://example.com/a", new IllegalStateException("no"))
            .page("https://example.com/b");

        Crawler.CrawlResult result = crawler(2, 5).crawl(START, site);

        // Two attempts were allowed and the failed one consumed the second, so /b is never reached.
        assertEquals(List.of(START, "https://example.com/a"), result.attemptedUrls());
        assertEquals(0, site.visits("https://example.com/b"));
        assertTrue(result.limits().pageLimitReached());
    }

    @Test
    void aStartPageFailureEndsTheRunSafelyWithOneFailedPage() {
        ScriptedSite site = new ScriptedSite()
            .fails(START, new IllegalStateException("the site never answered"));

        Crawler.CrawlResult result = crawler().crawl(START, site);

        assertEquals(List.of(START), result.attemptedUrls());
        assertTrue(result.successful().isEmpty());
        assertEquals(1, result.failed().size());
        assertEquals(0, result.failed().get(0).depth());
        assertNull(result.failed().get(0).discoveredFrom());
        assertEquals(0, result.limits().notVisited(), "nothing else was ever discovered");
        assertFalse(result.limits().pageLimitReached());
    }

    // --- 12 & 13. determinism ------------------------------------------------------------------

    @Test
    void repeatedCallsOnOneCrawlerDoNotShareVisitedState() {
        Crawler shared = crawler();

        Crawler.CrawlResult first = shared.crawl(START, tree());
        Crawler.CrawlResult second = shared.crawl(START, tree());

        assertEquals(first.attemptedUrls(), second.attemptedUrls(),
            "a leaked visited set would make the second crawl shorter");
        assertEquals(4, second.attempted().size());
        assertEquals(depths(first), depths(second));
        assertEquals(parents(first), parents(second));
    }

    @Test
    void theSameInputAlwaysProducesTheSameOrder() {
        List<String> expected = crawler().crawl(START, tree()).attemptedUrls();

        for (int run = 0; run < 5; run++) {
            Crawler.CrawlResult repeat = crawler().crawl(START, tree());
            assertEquals(expected, repeat.attemptedUrls(), "run " + run + " diverged");
            assertEquals(List.of(0, 1, 1, 2), depths(repeat));
        }
    }

    /** A small deterministic site used by the determinism tests. */
    private static ScriptedSite tree() {
        return new ScriptedSite()
            .page(START, "/a", "/b")
            .page("https://example.com/a", "/a1")
            .page("https://example.com/b")
            .page("https://example.com/a1");
    }

    // --- 14. nothing sensitive escapes ---------------------------------------------------------

    @Test
    void sensitiveQueryValuesAndExceptionTextAreNotExposedInResults() {
        String secret = "abcdef0123456789abcdef";
        String leaky = "https://example.com/dash?session_token=" + secret + "&page=2";
        ScriptedSite site = new ScriptedSite()
            .page(START, "/dash?session_token=" + secret + "&page=2")
            .fails(leaky, new IllegalStateException("login failed for password=" + secret));

        Crawler.CrawlResult result = crawler().crawl(START, site);

        // The processor still received the raw address, because it has to be able to open it.
        assertEquals(leaky, site.pageAt(1).url());

        Crawler.PageResult published = result.attempted().get(1);
        assertFalse(published.url().contains(secret),
            "the token value must not reach a result: " + published.url());
        assertTrue(published.url().contains("session_token=[redacted]"), published.url());
        assertTrue(published.url().contains("page=2"),
            "an ordinary parameter survives, so the report stays truthful: " + published.url());
        assertFalse(published.message().contains(secret),
            "the failure message must not leak it either: " + published.message());

        // The cause is kept for diagnosis, so toString must not undo the sanitizing.
        assertTrue(published.cause().getMessage().contains(secret),
            "the raw exception is still available to a developer");
        assertFalse(published.toString().contains(secret),
            "logging a result must not print the unsanitized cause: " + published.toString());
    }

    @Test
    void aParentUrlIsSanitizedTooBecauseChildrenCarryItForward() {
        String secret = "zyxwvu9876543210zyxwvu";
        ScriptedSite site = new ScriptedSite()
            .page(START, "/hub?api_key=" + secret)
            .page("https://example.com/hub?api_key=" + secret, "/leaf")
            .page("https://example.com/leaf");

        Crawler.CrawlResult result = crawler().crawl(START, site);

        Crawler.PageResult leaf = result.attempted().get(2);
        assertEquals("https://example.com/leaf", leaf.url());
        assertFalse(leaf.discoveredFrom().contains(secret),
            "the parent link is published as evidence and must be safe: " + leaf.discoveredFrom());
        assertTrue(leaf.discoveredFrom().contains("api_key=[redacted]"), leaf.discoveredFrom());
    }

    // --- the start URL has to be crawlable -----------------------------------------------------

    @Test
    void anUnusableStartUrlIsRejectedClearly() {
        Crawler crawler = crawler();
        ScriptedSite site = new ScriptedSite();

        assertThrows(IllegalArgumentException.class, () -> crawler.crawl(null, site));
        assertThrows(IllegalArgumentException.class, () -> crawler.crawl("   ", site));
        assertThrows(IllegalArgumentException.class, () -> crawler.crawl("/relative/path", site));
        assertThrows(IllegalArgumentException.class,
            () -> crawler.crawl("mailto:someone@example.com", site));
        assertThrows(IllegalArgumentException.class, () -> crawler.crawl("ftp://example.com/x", site));
        assertThrows(IllegalArgumentException.class, () -> crawler.crawl(START, null));
        assertThrows(IllegalArgumentException.class, () -> new Crawler(null));

        IllegalArgumentException relative = assertThrows(IllegalArgumentException.class,
            () -> crawler.crawl("/relative/path", site));
        assertTrue(relative.getMessage().contains("absolute"), relative.getMessage());
    }

    @Test
    void aStartUrlWithoutATrailingSlashStillWorks() {
        ScriptedSite site = new ScriptedSite()
            .page("https://example.com", "/a")
            .page("https://example.com/a");

        Crawler.CrawlResult result = crawler().crawl("https://example.com", site);

        assertEquals(List.of("https://example.com", "https://example.com/a"),
            result.attemptedUrls());
    }

    // --- the scripted site ---------------------------------------------------------------------

    /**
     * A website expressed as a map from address to the links on that page.
     *
     * <p>It records every page it was handed, so a test can prove not only what the result says
     * but what the crawler actually asked for — a page opened twice would show up here even if the
     * result looked correct.
     */
    private static final class ScriptedSite implements Crawler.PageProcessor {

        private final Map<String, List<String>> links = new LinkedHashMap<>();
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        private final List<Crawler.Page> opened = new ArrayList<>();

        /** Uses {@code Arrays.asList} rather than {@code List.of} so a null href can be scripted. */
        ScriptedSite page(String url, String... hrefs) {
            links.put(url, Arrays.asList(hrefs));
            return this;
        }

        ScriptedSite fails(String url, RuntimeException failure) {
            failures.put(url, failure);
            return this;
        }

        @Override
        public List<String> process(Crawler.Page page) {
            opened.add(page);
            RuntimeException failure = failures.get(page.url());
            if (failure != null) {
                throw failure;
            }
            return links.getOrDefault(page.url(), List.of());
        }

        /** How many times the crawler actually opened one address. */
        int visits(String url) {
            return (int) opened.stream().filter(page -> page.url().equals(url)).count();
        }

        Crawler.Page pageAt(int index) {
            return opened.get(index);
        }
    }
}
