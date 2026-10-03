package com.qanize.automation.engine;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.qanize.automation.checks.shared.NavigationLinkInspector;
import com.qanize.automation.evidence.SensitiveDataFilter;

/**
 * A deterministic breadth-first page queue.
 *
 * <p>This is the crawl engine core and nothing else. It decides <em>which page is next</em>,
 * whether a page has already been seen, whether the policy's limits allow another visit, and
 * whether a discovered candidate belongs to an allowed origin. It does not open a page, does not
 * run a check, and does not know that Selenium exists.
 *
 * <p><strong>Opening a page is the processor's job.</strong> A {@link PageProcessor} is handed one
 * {@link Page} at a time and returns the candidate links found on it. Today a test supplies a
 * scripted processor; later a Selenium-backed processor will open the page and run the check
 * modules. The queue behaviour is identical either way, which is the point of the split.
 *
 * <p><strong>Deterministic.</strong> Given the same start URL, policy and processor, the attempted
 * order is always identical: candidates are considered in the order the processor returned them,
 * the frontier is a FIFO queue, and every collection used to hold order is insertion-ordered.
 *
 * <p><strong>Stateless between runs.</strong> All crawl state is local to {@link #crawl}. Two
 * calls on the same instance never share a visited set, so a {@code Crawler} is safe to reuse and
 * a leaked visited set cannot silently shrink a later run.
 *
 * <h2>Page identity</h2>
 *
 * <p>Two candidates are the same page when
 * {@link NavigationLinkInspector#normalize(URI)} returns the same string. That method is the
 * project's existing definition of "same destination" and is reused rather than restated, so the
 * Crawler and the Navigation check can never disagree about what a duplicate is. Concretely it
 * lowercases the scheme and host, treats an empty path as {@code /}, drops one trailing slash,
 * <strong>preserves the query string</strong>, and <strong>drops the fragment</strong> — so
 * {@code /a}, {@code /a/} and {@code /a#top} are one page, while {@code /a?page=2} is a different
 * one.
 *
 * <p>That normalizer does not itself include the port, which is harmless here: a crawl never
 * leaves the start page's origin, and the origin rule below <em>does</em> include the port, so
 * every queued page necessarily shares one port and two different ports can never meet inside one
 * crawl to collide.
 *
 * <h2>Origin</h2>
 *
 * <p>A crawl visits the start URL's origin and nothing else. This is not configurable: following
 * an outbound link would mean sending a customer's crawl traffic to a third party who never agreed
 * to be crawled. External links are still discovered and HTTP-validated by the Links check — they
 * are simply never enqueued or opened as pages here.
 *
 * <p>The origin of a URL is <strong>scheme + host + effective port</strong>, compared exactly:
 *
 * <ul>
 *   <li>scheme and host are lowercased ({@code HTTP://Example.COM} and {@code http://example.com}
 *       are the same origin);</li>
 *   <li>the effective port is the explicit port when present, otherwise {@code 443} for
 *       {@code https} and {@code 80} for {@code http}, so {@code https://host} and
 *       {@code https://host:443} are the same origin;</li>
 *   <li>a different scheme is a different origin, so {@code http://host} and {@code https://host}
 *       are <em>not</em> the same origin;</li>
 *   <li>a different port is a different origin, so {@code http://host} and {@code http://host:8080}
 *       are <em>not</em> the same origin;</li>
 *   <li>a subdomain is a different host and therefore a different origin.</li>
 * </ul>
 *
 * <p>This is stricter than {@link NavigationLinkInspector}'s host-only check. That is not a
 * competing definition of equality: it is an admission rule about where traffic may be sent, and
 * erring towards exclusion only costs a missed page, whereas erring the other way sends a
 * customer's crawl somewhere it was never authorised to go.
 *
 * <h2>What is never crawled</h2>
 *
 * <p>Anything that is not an {@code http} or {@code https} URL. {@code mailto:}, {@code tel:},
 * {@code javascript:} and {@code data:} are the named cases, but the rule is a positive one — only
 * the two supported schemes are admitted — so a scheme nobody has thought of yet is excluded by
 * default rather than by an incomplete blocklist.
 *
 * <h2>Sanitization</h2>
 *
 * <p>A {@link Page} carries the <strong>raw</strong> URL, because a processor has to be able to
 * open the address that was actually discovered. Everything in a {@link CrawlResult} is passed
 * through {@link SensitiveDataFilter} first, so a session token or reset key in a discovered link
 * never reaches a report.
 *
 * <h2>Not in this slice</h2>
 *
 * <p>No retries, no parallelism, no redirect handling — a redirect is something a real processor
 * observes and reports; the core never invents or simulates one.
 */
public final class Crawler {

    private final CrawlPolicy policy;

    public Crawler(CrawlPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
    }

    /** The policy this crawler enforces. */
    public CrawlPolicy policy() {
        return policy;
    }

    /**
     * Crawls breadth-first from one absolute HTTP or HTTPS URL.
     *
     * <p>The algorithm, exactly:
     *
     * <ol>
     *   <li>The start URL is validated and normalized, marked visited, and queued at depth 0.</li>
     *   <li>While the queue is non-empty <em>and</em> fewer than {@code maxPages} attempts have
     *       been made, take the head of the queue.</li>
     *   <li>Hand it to the processor. Whatever happens, the attempt is recorded and counts towards
     *       {@code maxPages}.</li>
     *   <li>If the processor threw, record a failed page and continue with the pages already
     *       queued. Nothing is retried.</li>
     *   <li>Otherwise take the returned candidates in order. If the children's depth would exceed
     *       {@code maxDepth}, none are queued. Each remaining candidate is resolved against the
     *       page's URL, admitted or rejected by scheme and origin, reduced to its normalized
     *       identity, and queued only if that identity has not been claimed before.</li>
     * </ol>
     *
     * <p>Marking a candidate visited at <em>queue</em> time rather than at visit time is what makes
     * a page discovered from two different parents get processed exactly once: the second discovery
     * finds the identity already claimed.
     *
     * @param startUrl  an absolute {@code http} or {@code https} URL
     * @param processor opens each page and returns the links found on it
     * @throws IllegalArgumentException when the start URL is missing, unparseable, relative, or
     *                                  not an HTTP/HTTPS address
     */
    public CrawlResult crawl(String startUrl, PageProcessor processor) {
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        URI start = requireCrawlableStart(startUrl);
        String startOrigin = origin(start);

        // All state is local: two crawls on one Crawler share nothing.
        Set<String> claimed = new LinkedHashSet<>();
        Deque<Page> frontier = new ArrayDeque<>();
        List<PageResult> attempted = new ArrayList<>();
        boolean depthLimitReached = false;

        claimed.add(NavigationLinkInspector.normalize(start));
        frontier.add(new Page(start.toString(), 0, null));

        while (!frontier.isEmpty() && attempted.size() < policy.maxPages()) {
            Page page = frontier.poll();

            List<String> discovered;
            try {
                discovered = processor.process(page);
            } catch (RuntimeException processingFailure) {
                // A page that could not be processed is a result about the website, not a crash of
                // the crawl. The original exception is kept as the cause; only the sanitized text
                // is ever shown. The already-queued pages are untouched, and nothing is retried.
                attempted.add(PageResult.failed(page, processingFailure));
                continue;
            }
            attempted.add(PageResult.processed(page));

            int childDepth = page.depth() + 1;
            if (childDepth > policy.maxDepth()) {
                // Recorded rather than silently dropped, so a run that stopped early can say why.
                depthLimitReached = true;
                continue;
            }
            if (discovered == null) {
                continue;
            }
            URI base = URI.create(page.url());
            for (String candidate : discovered) {
                Admitted admitted = admit(base, candidate, startOrigin);
                if (admitted == null) {
                    continue;
                }
                if (!claimed.add(admitted.identity())) {
                    continue;
                }
                frontier.add(new Page(admitted.url(), childDepth, page.url()));
            }
        }

        LimitReport limits = new LimitReport(
            policy.maxPages(),
            policy.maxDepth(),
            attempted.size() >= policy.maxPages(),
            depthLimitReached,
            frontier.size());
        return new CrawlResult(List.copyOf(attempted), limits);
    }

    // --- admission -------------------------------------------------------------------------------

    /** A candidate that passed every admission rule: what to open, and what identifies it. */
    private record Admitted(String url, String identity) { }

    /**
     * A candidate that may be crawled, or null when it may not.
     *
     * <p>Every rejection is silent by design: an unparseable or unsupported href on a page is an
     * ordinary fact about the web, not something that should end a run.
     */
    private Admitted admit(URI base, String candidate, String startOrigin) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        URI resolved = resolve(base, candidate);
        if (resolved == null) {
            return null;
        }
        // A positive list: only these two schemes are ever followed, so mailto, tel, javascript,
        // data and anything not yet invented are excluded without needing to be named.
        String scheme = lower(resolved.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        if (resolved.getHost() == null || resolved.getHost().isBlank()) {
            return null;
        }
        // Unconditional: there is no mode in which a crawl leaves the start page's origin.
        if (!startOrigin.equals(origin(resolved))) {
            return null;
        }
        return new Admitted(resolved.toString(), NavigationLinkInspector.normalize(resolved));
    }

    /** Resolves a possibly-relative candidate, or null when it is not a usable reference. */
    private static URI resolve(URI base, String candidate) {
        try {
            return base.resolve(candidate.trim());
        } catch (IllegalArgumentException notAReference) {
            return null;
        }
    }

    /**
     * Scheme, host and effective port. See the class documentation for the exact rule.
     *
     * <p>Delegated rather than spelled out again here. The rule that decides where a crawl may
     * send traffic and the rule that decides where a signed-in session may be presented have to be
     * the same rule: two copies that drifted would mean a cookie reaching an origin the crawler
     * had already judged to be a third party.
     */
    private static String origin(URI uri) {
        return NavigationLinkInspector.origin(uri);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * The start URL has to be absolute and HTTP(S): a crawl has no page to resolve a relative
     * address against, and every later rule is expressed in terms of the start page's origin.
     */
    private static URI requireCrawlableStart(String startUrl) {
        if (startUrl == null || startUrl.isBlank()) {
            throw new IllegalArgumentException("startUrl must not be blank");
        }
        URI start;
        try {
            start = new URI(startUrl.trim());
        } catch (java.net.URISyntaxException malformed) {
            throw new IllegalArgumentException(
                "startUrl is not a valid URL: " + SensitiveDataFilter.sanitizeUrl(startUrl));
        }
        if (!start.isAbsolute()) {
            throw new IllegalArgumentException(
                "startUrl must be absolute: " + SensitiveDataFilter.sanitizeUrl(startUrl));
        }
        String scheme = lower(start.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                "startUrl must be an http or https address, but the scheme was " + scheme);
        }
        if (start.getHost() == null || start.getHost().isBlank()) {
            throw new IllegalArgumentException(
                "startUrl must contain a host: " + SensitiveDataFilter.sanitizeUrl(startUrl));
        }
        return start;
    }

    // --- what the caller supplies ----------------------------------------------------------------

    /**
     * One page the crawler wants opened.
     *
     * <p>{@code url} is the <strong>raw</strong> discovered address, because a processor has to be
     * able to open exactly what the site linked to. It must be sanitized before it is published;
     * {@link PageResult} does that.
     *
     * @param url            the absolute address to open
     * @param depth          0 for the start page, and one more than the parent for anything else
     * @param discoveredFrom the raw URL of the page this was found on, or null for the start page
     */
    public record Page(String url, int depth, String discoveredFrom) {

        /** Whether this is the page the crawl started from. */
        public boolean isStartPage() {
            return discoveredFrom == null;
        }
    }

    /**
     * Opens one page and reports the links found on it.
     *
     * <p>The returned hrefs may be relative, absolute, duplicated, external or unusable: filtering
     * is the crawler's job, not the processor's. Returning null or an empty list simply means the
     * page led nowhere.
     *
     * <p>Throwing is how a processor reports that the page could not be handled. The crawler turns
     * that into one failed page and carries on.
     */
    @FunctionalInterface
    public interface PageProcessor {

        /**
         * @param page the page to open
         * @return the links found, in the order they should be considered
         * @throws RuntimeException when the page could not be processed
         */
        List<String> process(Page page);
    }

    // --- what the caller gets back ---------------------------------------------------------------

    /** How one page attempt ended. */
    public enum PageStatus { PROCESSED, FAILED }

    /**
     * One attempted page, safe to publish.
     *
     * <p>{@code url} and {@code discoveredFrom} have been through
     * {@link SensitiveDataFilter#sanitizeUrl(String)}, and {@code message} through
     * {@link SensitiveDataFilter#sanitizeMessage(String)}.
     *
     * <p>{@code cause} is the original, <em>unsanitized</em> exception, kept so a developer can
     * diagnose a failure. It is deliberately excluded from {@link #toString()} so that logging a
     * result cannot leak what the sanitizer removed from {@code message}.
     */
    public record PageResult(
        String url,
        int depth,
        String discoveredFrom,
        PageStatus status,
        String message,
        Throwable cause
    ) {

        static PageResult processed(Page page) {
            return new PageResult(
                SensitiveDataFilter.sanitizeUrl(page.url()),
                page.depth(),
                SensitiveDataFilter.sanitizeUrl(page.discoveredFrom()),
                PageStatus.PROCESSED,
                null,
                null);
        }

        static PageResult failed(Page page, RuntimeException failure) {
            return new PageResult(
                SensitiveDataFilter.sanitizeUrl(page.url()),
                page.depth(),
                SensitiveDataFilter.sanitizeUrl(page.discoveredFrom()),
                PageStatus.FAILED,
                SensitiveDataFilter.sanitizeMessage(failure.toString()),
                failure);
        }

        public boolean succeeded() {
            return status == PageStatus.PROCESSED;
        }

        @Override
        public String toString() {
            return "PageResult[url=" + url + ", depth=" + depth
                + ", discoveredFrom=" + discoveredFrom
                + ", status=" + status + ", message=" + message + "]";
        }
    }

    /**
     * Why a crawl stopped, in terms the caller configured.
     *
     * @param maxPages           the configured page budget
     * @param maxDepth           the configured depth budget
     * @param pageLimitReached   whether the page budget was fully consumed
     * @param depthLimitReached  whether at least one page's children were dropped for depth
     * @param notVisited         eligible pages that were queued but never reached
     */
    public record LimitReport(
        int maxPages,
        int maxDepth,
        boolean pageLimitReached,
        boolean depthLimitReached,
        int notVisited
    ) {

        /** Whether anything was left undone because of a limit. */
        public boolean stoppedEarly() {
            return notVisited > 0 || depthLimitReached;
        }
    }

    /** Everything one crawl did, in the order it did it. */
    public record CrawlResult(List<PageResult> attempted, LimitReport limits) {

        public CrawlResult {
            attempted = List.copyOf(attempted);
        }

        /** The pages the processor handled, in attempt order. */
        public List<PageResult> successful() {
            return attempted.stream().filter(PageResult::succeeded).toList();
        }

        /** The pages the processor could not handle, in attempt order. */
        public List<PageResult> failed() {
            return attempted.stream().filter(result -> !result.succeeded()).toList();
        }

        /** The sanitized URLs of every attempt, in order. The crawl's observable shape. */
        public List<String> attemptedUrls() {
            return attempted.stream().map(PageResult::url).toList();
        }
    }
}
