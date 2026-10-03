package com.qanize.automation.checks.links;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.DiscoveryDiagnostics;
import com.qanize.automation.checks.shared.HttpStatusClassification;
import com.qanize.automation.checks.shared.NavigationLinkInspector;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.evidence.DiscoveryEvidence;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.TestedItem;

import org.openqa.selenium.WebElement;

/**
 * The Links check, as production code.
 *
 * <p>Links is the check that never touches its subject with a browser. The page is read once to
 * discover anchors, and every destination is then requested over HTTP — so a destination is never
 * opened, clicked or navigated to. That division is the whole point of the check and is preserved
 * exactly here.
 *
 * <p><strong>It receives an already-open page.</strong> Nothing here navigates, creates a driver or
 * tears one down. The caller opens the start page; this reads what is on it.
 *
 * <p><strong>It never asserts.</strong> The three outcomes a JUnit wrapper needs — passed, nothing
 * eligible to request, or one or more links failed — are returned as a {@link Result}. "Nothing
 * eligible" stays distinct from "failed" because the check has always reported it as a skip rather
 * than a failure, and that difference reaches the customer's summary.
 *
 * <p><strong>One request per link, and no more.</strong> Timeout, method and the classification of
 * what came back are identical on both paths below, and the anonymous path — its shared client and
 * its redirect policy included — is untouched.
 *
 * <p><strong>A status is classified, not thresholded.</strong> The rule used to be "below 400 is a
 * pass, everything else is a link that did not resolve", which reported {@code /basic_auth} as a
 * broken link on a site whose authentication was working perfectly. What a status means is now
 * {@link HttpStatusClassification}'s single answer: a destination that demands credentials, or that
 * is rate limiting this run, was not checked and is reported as a safe skip; only a destination
 * that is missing, gone or erroring is a finding about the website.
 *
 * <p><strong>It speaks as whoever the run signed in as.</strong> A {@link LinkSession} says whether
 * this run has a browser session to present and where it may be presented. An anonymous run — every
 * run that did not sign in, and every legacy single-page wrapper — behaves exactly as it always
 * has. A signed-in run gets its own client, for that run alone. A signed-in run whose session could
 * not be carried requests nothing at all rather than requesting as a stranger, because a 404
 * collected without the session is a statement about a page the customer does not have.
 */
public final class LinksModule {

    private final PageLinks pageLinks;
    private final LinkProbe probe;
    private final WebsiteConfig config;
    private final String basePage;
    private final LinkExecutionEvidence execution;
    private final EvidenceScope scope;
    private final LinkSession session;

    /**
     * The production entry point.
     *
     * @param page      a page whose browser is already on {@code basePage}
     * @param execution the recorder the caller already used for its own steps, so the per-test-case
     *                  sequence numbers continue rather than restarting
     */
    public LinksModule(
        CommonPage page, WebsiteConfig config, String basePage, LinkExecutionEvidence execution
    ) {
        this(page, config, basePage, execution, EvidenceScope.legacy());
    }

    /**
     * The entry point for one crawled page.
     *
     * @param scope which page these links belong to, so page 2's {@code link-1} cannot be mistaken
     *              for page 1's
     */
    public LinksModule(
        CommonPage page, WebsiteConfig config, String basePage, LinkExecutionEvidence execution,
        EvidenceScope scope
    ) {
        this(page, config, basePage, LinkSession.anonymous(), execution, scope);
    }

    /**
     * The entry point for one crawled page of a run that may be signed in.
     *
     * <p>The session comes before the recorder because it is a fact about the run, whereas the
     * recorder and the scope are a pair about this page.
     *
     * @param session what this run may present, and where; never null — an anonymous run passes
     *                {@link LinkSession#anonymous()} explicitly rather than omitting it
     */
    public LinksModule(
        CommonPage page, WebsiteConfig config, String basePage, LinkSession session,
        LinkExecutionEvidence execution, EvidenceScope scope
    ) {
        this(new CommonPageLinks(page), probeFor(session), config, basePage, execution, scope,
            session);
    }

    /** The seam a test uses to supply its own page and probe. Not part of the public API. */
    LinksModule(
        PageLinks pageLinks, LinkProbe probe, WebsiteConfig config, String basePage,
        LinkExecutionEvidence execution
    ) {
        this(pageLinks, probe, config, basePage, execution, EvidenceScope.legacy());
    }

    /** The seam a test uses to supply its own page, probe and scope. Not part of the public API. */
    LinksModule(
        PageLinks pageLinks, LinkProbe probe, WebsiteConfig config, String basePage,
        LinkExecutionEvidence execution, EvidenceScope scope
    ) {
        this(pageLinks, probe, config, basePage, execution, scope, LinkSession.anonymous());
    }

    /** The same seam, plus the run's session. Not part of the public API. */
    LinksModule(
        PageLinks pageLinks, LinkProbe probe, WebsiteConfig config, String basePage,
        LinkExecutionEvidence execution, EvidenceScope scope, LinkSession session
    ) {
        this.pageLinks = pageLinks;
        this.probe = probe;
        this.config = config;
        this.basePage = basePage;
        this.execution = execution;
        this.scope = Objects.requireNonNull(
            scope, "scope must not be null; use the four-argument constructor for the legacy scope");
        this.session = Objects.requireNonNull(
            session, "session must not be null; an anonymous run passes LinkSession.anonymous()");
    }

    /**
     * The client this run's session requires.
     *
     * <p>An anonymous run gets the process-wide client it has always used — the same object, the
     * same redirect policy, the same connect timeout — so nothing about an unauthenticated run
     * changes. A signed-in run gets a client built for it, holding nothing static, so the session
     * it carries cannot outlive the run or be seen by the next one.
     *
     * <p>A discovery-only run still gets a probe, and never uses it: {@link #run()} returns before
     * reaching the request loop. Building one is cheaper than a null to forget to check.
     *
     * <p>Package-private rather than private so a test can drive the very probe production builds,
     * against a real HTTP server, instead of a stand-in that would prove nothing about which
     * headers actually go on the wire.
     */
    static LinkProbe probeFor(LinkSession session) {
        return session != null && session.authenticated()
            ? new AuthenticatedLinkProbe(session)
            : new HttpLinkProbe();
    }

    /** Discovers the links on the already-open page and requests each eligible destination. */
    public Result run() {
        // Links are discovered from the rendered DOM, so the render must be given a chance first.
        long stepStart = System.nanoTime();
        boolean contentWaitTimedOut = pageLinks.awaitRenderedContentTimedOut();
        execution.waitedForRenderedContent(basePage, contentWaitTimedOut, elapsedMs(stepStart));

        stepStart = System.nanoTime();
        List<String> texts = new ArrayList<>();
        List<String> hrefs = new ArrayList<>();
        for (DiscoveredLink link : pageLinks.discoverLinks()) {
            texts.add(link.text());
            hrefs.add(link.href());
        }

        List<NavigationLinkInspector.Candidate> candidates =
            NavigationLinkInspector.classify(resolutionBase(), texts, hrefs);
        List<NavigationLinkInspector.Candidate> allEligibleCandidates = candidates.stream()
            .filter(NavigationLinkInspector.Candidate::eligible)
            .toList();
        List<NavigationLinkInspector.Candidate> eligible = allEligibleCandidates.stream()
            .limit(Math.max(1, config.maxLinks()))
            .toList();
        // What a crawler may visit next. Already in DOM order, already same-origin, and already
        // free of duplicates, fragments, mailto/tel/javascript/data, downloads and state-changing
        // links, because the classifier above excluded every one of those.
        //
        // Deliberately not capped by maxLinks: that setting bounds how many destinations this
        // check requests over HTTP, whereas how many pages get crawled is the Crawler's own
        // budget. Conflating the two would let a Links setting silently narrow a crawl.
        //
        // Handed over without fragments: a crawler opens these and records them as pages, and
        // "/pricing#plans" is the same page as "/pricing" wearing a different name.
        List<String> internalCandidates = allEligibleCandidates.stream()
            .map(candidate -> NavigationLinkInspector.withoutFragment(candidate.resolved()).toString())
            .toList();
        long discoveryMs = elapsedMs(stepStart);
        long allEligible = allEligibleCandidates.size();
        execution.discoveredLinks(
            basePage, hrefs.size(), candidates.size(), eligible.size(),
            (int) Math.max(0, allEligible - eligible.size()), discoveryMs);

        int skippedIndex = 0;
        for (NavigationLinkInspector.Candidate excluded : candidates) {
            if (excluded.eligible()) continue;
            skippedIndex++;
            execution.excludedLink(
                "link-skipped-" + skippedIndex,
                displayName(excluded.text(), excluded.rawHref()),
                excluded.resolved() == null ? excluded.rawHref() : excluded.resolved().toString(),
                linkType(excluded),
                excluded.exclusion().reason());
            TestedItem.Builder skippedItem = TestedItem.builder("LINKS", TestedItem.ItemType.LINK)
                .id(scope.itemId("link-skipped-" + skippedIndex))
                .name(displayName(excluded.text(), excluded.rawHref()))
                .pageUrl(basePage)
                .target(excluded.resolved() == null ? excluded.rawHref() : excluded.resolved().toString())
                .action("Reviewed the link and did not request it.")
                .expected("Only safe, same-site links are requested.")
                .actual(excluded.exclusion().reason())
                .status(TestedItem.Status.SKIPPED)
                .message(excluded.exclusion().reason())
                .meta("linkType", linkType(excluded))
                .meta("exclusion", excluded.exclusion().name())
                .completedAt(EvidenceRecorder.nowIso());
            EvidenceRecorder.record(scope.enrich(skippedItem).build());
        }

        // Zero eligible links means nothing was validated; that must not read as a pass.
        DiscoveryDiagnostics.ForLinks diagnostics = new DiscoveryDiagnostics.ForLinks(
            pageLinks.count("a[href]"),
            (int) candidates.stream().filter(NavigationLinkInspector.Candidate::sameOrigin).count(),
            pageLinks.count("nav a[href], header a[href], [role='navigation'] a[href]"),
            pageLinks.count("button, [role='button']"),
            pageLinks.count("iframe, frame"),
            contentWaitTimedOut
        );
        if (eligible.isEmpty()) {
            String reason = diagnostics.explain(
                "No eligible same-origin links were discovered on the submitted page.");
            execution.nothingEligibleToRequest(basePage, reason);
            DiscoveryEvidence.recordNothingDiscovered(
                "LINKS", scope.itemId("links-discovery"), "Link discovery", basePage,
                "Opened the submitted page and looked for same-site links to request.",
                "At least one safe, same-site link is available to request.",
                reason, diagnostics);
            // Discovery itself found nothing to offer, so there is nothing to hand a crawler.
            return new Result(Outcome.NOTHING_ELIGIBLE, reason, List.of());
        }

        // A signed-in run that could not carry its session stops here, with the candidates intact.
        // Requesting these as an anonymous visitor is the one thing that must not happen: the site
        // would answer about pages the customer does not have, and every answer would be recorded
        // against pages that are in fact perfectly healthy.
        if (!session.validatesOverHttp()) {
            return reportValidationNotPerformed(eligible, internalCandidates);
        }

        List<String> failures = new ArrayList<>();
        int tested = 0;
        for (NavigationLinkInspector.Candidate candidate : eligible) {
            tested++;
            String testCaseId = "link-" + tested;
            // The address as it goes on the wire. A fragment is never transmitted in an HTTP
            // request, so carrying one here would only let the report name a destination that
            // differs from the one actually requested.
            URI link = NavigationLinkInspector.withoutFragment(candidate.resolved());
            Instant startedAt = Instant.now();
            Integer statusCode = null;
            String failure = null;
            long requestStart = System.nanoTime();
            // One request, and its outcome. A failure here is this link's verdict and never stops
            // the links that follow, so one broken destination cannot hide the rest of the report.
            Probe probed = probe.request(link, Duration.ofSeconds(config.defaultTimeoutSeconds()));
            if (probed.rawFailure() == null) {
                statusCode = probed.statusCode();
                // Read from the response already in hand; the client follows redirects, so this is
                // the URI it ended on. No additional request is made for evidence.
                execution.requestedLink(
                    testCaseId, link.toString(), statusCode, probed.finalUrl(),
                    elapsedMs(requestStart));
            } else {
                failure = SensitiveDataFilter.sanitizeMessage(probed.rawFailure());
                execution.requestFailed(testCaseId, link.toString(), failure, elapsedMs(requestStart));
            }

            // What the answer means, decided in one place for every caller. A destination that
            // demanded credentials is not a broken link, and a request that never completed is not
            // a status at all.
            HttpStatusClassification classification = failure != null
                ? HttpStatusClassification.NO_RESPONSE
                : HttpStatusClassification.of(statusCode);
            // Unverified is the third answer the check needed and did not have: requested,
            // answered, and still nothing established about the page behind the answer. Skipped
            // rather than passed because nothing was verified, and skipped rather than failed
            // because the website did exactly what it is supposed to do.
            TestedItem.Status itemStatus = switch (classification) {
                case SUCCESS -> TestedItem.Status.PASSED;
                case AUTH_REQUIRED, RATE_LIMITED -> TestedItem.Status.SKIPPED;
                case BROKEN, NO_RESPONSE -> TestedItem.Status.FAILED;
            };
            TestedItem.Builder item = TestedItem.builder("LINKS", TestedItem.ItemType.LINK)
                .id(scope.itemId(testCaseId))
                .name(displayName(candidate.text(), candidate.rawHref()))
                .pageUrl(basePage)
                .target(link.toString())
                .action("Requested the link destination over HTTP and read the response status.")
                .expected("The destination responds, or explains why it cannot be checked.")
                .status(itemStatus)
                .startedAt(startedAt.toString())
                .completedAt(Instant.now().toString())
                .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
                .httpStatus(statusCode)
                .meta("linkType", "internal")
                // The classification itself, so a report never has to re-derive it from the number
                // or from the sentence. A key of its own rather than `exclusion`, which names a
                // link that was never requested — this one was.
                .meta("linkOutcome", classification.name())
                .meta("validationMethod", "HTTP GET");
            String actualSummary = classification.unverified()
                ? classification.reason(statusCode)
                : statusCode != null
                    ? "Responded with HTTP " + statusCode + "."
                    : "The request could not be completed.";
            item.actual(actualSummary);
            // A skip must say why it was skipped wherever a reader looks, and the message is where
            // the summary reads a skip reason from.
            if (failure != null) {
                item.message(failure);
            } else if (classification.unverified()) {
                item.message(actualSummary);
            }
            // Last in the chain: enrichment only fills keys the check left absent, so linkType
            // and validationMethod above can never be replaced by page ownership fields.
            EvidenceRecorder.record(scope.enrich(item).build());

            // Recorded before the result is returned, so a failing link's verdict is already on
            // disk even though the verdict that reports it is produced after the loop.
            execution.assertedLinkUsable(testCaseId, link.toString(), classification, actualSummary);

            // Only a genuinely broken destination reaches the check's verdict. A protected or
            // rate-limited one is already recorded as a skip, and adding it here would put a
            // finding on the customer's report for a page that answered correctly.
            if (classification == HttpStatusClassification.BROKEN) {
                failures.add(link + " returned HTTP " + statusCode);
            } else if (classification == HttpStatusClassification.NO_RESPONSE) {
                failures.add(link + " could not be requested");
            }
        }

        // The candidates ride out on both paths: a destination that answered badly is still a page
        // that exists on this site, so a failed HTTP check must not shrink what a crawler can see.
        return failures.isEmpty()
            ? new Result(Outcome.PASSED, null, internalCandidates)
            : new Result(Outcome.LINKS_FAILED,
                "Internal links failed: " + String.join("; ", failures), internalCandidates);
    }

    /**
     * Reports every eligible destination as unvalidated, and says so in the same words each time.
     *
     * <p>Skipped rather than passed, because nothing was verified, and skipped rather than failed,
     * because the website did nothing wrong — the same distinction the check already draws for a
     * page with no eligible link. The candidates still travel: discovery worked perfectly, and the
     * crawler visiting these pages with the browser is exactly how they do get looked at.
     *
     * <p>No second evidence mechanism: the same recorder and the same tested-item shape as every
     * other Links result, with {@code validationMethod} saying plainly that none was used.
     */
    private Result reportValidationNotPerformed(
        List<NavigationLinkInspector.Candidate> eligible, List<String> internalCandidates
    ) {
        String reason = session.reason();
        int tested = 0;
        for (NavigationLinkInspector.Candidate candidate : eligible) {
            tested++;
            String testCaseId = "link-" + tested;
            URI link = NavigationLinkInspector.withoutFragment(candidate.resolved());
            execution.validationNotPerformed(testCaseId, link.toString(), reason);
            TestedItem.Builder item = TestedItem.builder("LINKS", TestedItem.ItemType.LINK)
                .id(scope.itemId(testCaseId))
                .name(displayName(candidate.text(), candidate.rawHref()))
                .pageUrl(basePage)
                .target(link.toString())
                .action("Reviewed the link and did not request it.")
                .expected("The destination responds with a status below 400.")
                .actual(reason)
                .status(TestedItem.Status.SKIPPED)
                .message(reason)
                .completedAt(EvidenceRecorder.nowIso())
                .meta("linkType", "internal")
                .meta("validationMethod", "none");
            EvidenceRecorder.record(scope.enrich(item).build());
        }
        return new Result(Outcome.VALIDATION_NOT_PERFORMED, reason, internalCandidates);
    }

    /**
     * The address relative hrefs are resolved against: the page they were found on.
     *
     * <p>For the single-page check the caller passes the submitted address, which is exactly
     * {@code config.baseUrl()}, so this changes nothing there. On a crawled sub-page it is the
     * difference between {@code /about/contact} and {@code /contact}. The configured base is kept
     * as the fallback for an address that cannot be parsed, so this can never make things worse.
     */
    private URI resolutionBase() {
        try {
            URI base = URI.create(basePage);
            return base.isAbsolute() && base.getHost() != null ? base : config.baseUrl();
        } catch (RuntimeException notAnAddress) {
            return config.baseUrl();
        }
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String linkType(NavigationLinkInspector.Candidate candidate) {
        return switch (candidate.exclusion()) {
            case MAIL -> "email";
            case TELEPHONE -> "telephone";
            case FRAGMENT -> "anchor";
            case EXTERNAL -> "external";
            case DOWNLOAD -> "download";
            case SCRIPT -> "script";
            default -> "internal";
        };
    }

    private static String displayName(String text, String href) {
        if (text != null && !text.isBlank()) return text.trim();
        return href == null || href.isBlank() ? "Unnamed link" : href.trim();
    }

    // --- what the caller gets back ------------------------------------------------------------

    /**
     * How a Links run ended.
     *
     * <p>{@code NOTHING_ELIGIBLE} is not a failure and must not become one: the check has always
     * reported a page with no safe same-site links as skipped, which is what stops an unvalidated
     * page from reading as a pass.
     *
     * <p>{@code VALIDATION_NOT_PERFORMED} is not a failure either, and for the same reason from the
     * other direction: there were eligible destinations, and the run declined to request them
     * because it could not do so as the visitor the customer signed in as. It is a separate
     * constant rather than a reuse of {@code NOTHING_ELIGIBLE} because the two say opposite things
     * about what was found, and a report that confused them would tell a customer their page had
     * no links on it.
     */
    public enum Outcome { PASSED, NOTHING_ELIGIBLE, LINKS_FAILED, VALIDATION_NOT_PERFORMED }

    /**
     * The outcome of one Links run, the message the caller reports with it, and the internal
     * pages this one led to.
     *
     * <p>{@code internalCandidates} is an immutable list of absolute {@code http}/{@code https}
     * addresses in DOM discovery order, with no duplicates and nothing that was excluded as a
     * fragment, {@code mailto}, {@code tel}, {@code javascript}, {@code data}, a download, a
     * state-changing link or an external destination. It is what a crawler visits next.
     *
     * <p><strong>These are raw addresses, not display values.</strong> A crawler has to be able to
     * open exactly what the site linked to, so they are not sanitized here — exactly as
     * {@code Crawler.Page} carries a raw URL. Anything that publishes them must sanitize first.
     *
     * <p>Links owns no queue and no visited set: it reports what this page offers and nothing
     * about what has already been seen or what should be visited next.
     */
    public record Result(Outcome outcome, String message, List<String> internalCandidates) {

        public Result {
            internalCandidates = List.copyOf(internalCandidates);
        }

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }

        public boolean nothingEligible() {
            return outcome == Outcome.NOTHING_ELIGIBLE;
        }

        /**
         * Destinations were found and deliberately not requested.
         *
         * <p>Distinct from {@link #nothingEligible()} on purpose: that one means the page offered
         * nothing to test, this one means it offered plenty and the run was not in a position to
         * test it truthfully.
         */
        public boolean validationNotPerformed() {
            return outcome == Outcome.VALIDATION_NOT_PERFORMED;
        }
    }

    // --- the page reads Links needs -----------------------------------------------------------

    /** One anchor as this check describes it: a label and a destination, both already read. */
    record DiscoveredLink(String text, String href) { }

    /**
     * The three page reads Links performs. Deliberately not a general abstraction: it exists so
     * this one module can be driven without a browser.
     */
    interface PageLinks {

        /** Whether waiting for rendered content ran out of time. */
        boolean awaitRenderedContentTimedOut();

        /** Every anchor on the page, already reduced to text and href. Never throws. */
        List<DiscoveredLink> discoverLinks();

        /** How many elements currently match, for the discovery diagnostics. */
        int count(String cssSelector);
    }

    /** One HTTP response, or the reason there was not one. Never both. */
    record Probe(Integer statusCode, String finalUrl, String rawFailure) { }

    /** The single network call this check makes. */
    interface LinkProbe {

        /** Requests one destination. Never throws: a failure is returned as {@code rawFailure}. */
        Probe request(URI link, Duration timeout);
    }

    /**
     * The production page reads, over the page the caller already opened.
     *
     * <p>A detached element cannot be described accurately, so it is dropped here rather than
     * reported — exactly as the check has always done.
     */
    private record CommonPageLinks(CommonPage page) implements PageLinks {

        @Override
        public boolean awaitRenderedContentTimedOut() {
            return page.awaitRenderedContent().timedOut();
        }

        @Override
        public List<DiscoveredLink> discoverLinks() {
            List<DiscoveredLink> discovered = new ArrayList<>();
            for (WebElement link : page.getLinks()) {
                try {
                    String label = link.getAccessibleName();
                    if (label == null || label.isBlank()) label = link.getText();
                    discovered.add(new DiscoveredLink(
                        label == null ? "" : label.trim(), link.getAttribute("href")));
                } catch (RuntimeException stale) {
                    // Detached elements cannot be described accurately and are not reported.
                }
            }
            return discovered;
        }

        @Override
        public int count(String cssSelector) {
            return page.count(cssSelector);
        }
    }

    /**
     * The production probe.
     *
     * <p>The client is configured once and shared, and follows redirects, so {@code response.uri()}
     * is the address the request ended on. {@code Exception} is caught rather than
     * {@code RuntimeException} because a send reports network trouble as a checked exception, and a
     * broken destination is this link's verdict rather than a crash of the whole check.
     */
    private static final class HttpLinkProbe implements LinkProbe {

        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        @Override
        public Probe request(URI link, Duration timeout) {
            try {
                HttpRequest request = HttpRequest.newBuilder(link)
                    .timeout(timeout)
                    .GET()
                    .build();
                HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
                return new Probe(
                    response.statusCode(),
                    response.uri() == null ? null : response.uri().toString(),
                    null);
            } catch (Exception requestFailure) {
                return new Probe(null, null, requestFailure.toString());
            }
        }
    }

    /**
     * The probe for a run that signed in.
     *
     * <p>Two differences from the anonymous one, and both exist to make a safety property provable
     * rather than assumed.
     *
     * <p><strong>Its client belongs to this run.</strong> Not static, not shared, holding no cookie
     * handler of its own. The session reaches it as a constructor argument and dies with it, so the
     * next run cannot inherit anything and nothing accumulates between them.
     *
     * <p><strong>It follows redirects itself.</strong> Handing that to the client would mean
     * trusting a cookie handler's internal rules to decide what happens when a same-origin address
     * redirects to a third party — a property nobody can see, test or point at. Instead the client
     * is told never to redirect, and each hop is a separate request whose {@code Cookie} header is
     * decided again from scratch. The first hop that leaves the start origin is asked for, and
     * given, nothing, and so is everything after it. The destination is still followed and still
     * reported, exactly as the public-links contract says; it simply arrives as a stranger.
     *
     * <p>Redirect handling otherwise mirrors what the anonymous client does: the redirect statuses,
     * a cap on how many are followed, and the refusal to be walked from {@code https} down to
     * {@code http} — where the response is reported as it stands rather than pursued.
     */
    private static final class AuthenticatedLinkProbe implements LinkProbe {

        /** The same ceiling {@code HttpClient} applies to its own redirect chains. */
        private static final int MAX_REDIRECTS = 5;

        private final HttpClient client;
        private final LinkSession session;

        AuthenticatedLinkProbe(LinkSession session) {
            this.session = session;
            this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        }

        @Override
        public Probe request(URI link, Duration timeout) {
            URI target = link;
            try {
                for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                    HttpRequest.Builder request = HttpRequest.newBuilder(target)
                        .timeout(timeout)
                        .GET();
                    // Asked again for every hop. The session answers null for anything that is not
                    // the start origin, so a redirect away from it silently stops carrying the
                    // cookie instead of needing to be remembered about here.
                    String cookies = session.cookieHeaderFor(target);
                    if (cookies != null) {
                        request.header("Cookie", cookies);
                    }
                    HttpResponse<Void> response =
                        client.send(request.build(), HttpResponse.BodyHandlers.discarding());
                    URI next = redirectTarget(response, target);
                    if (next == null) {
                        return new Probe(response.statusCode(), target.toString(), null);
                    }
                    target = next;
                }
                return new Probe(null, null, "The destination redirected too many times.");
            } catch (Exception requestFailure) {
                // Scrubbed even though nothing here is expected to contain a session value: the
                // header is never rendered and no cookie reaches a message. "Expected" is not a
                // guarantee, and a value that escapes once is in the report for good.
                return new Probe(null, null, session.redactFrom(requestFailure.toString()));
            }
        }

        /** Where this response points next, or null when this response is the answer. */
        private static URI redirectTarget(HttpResponse<Void> response, URI from) {
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return null;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                return null;
            }
            URI next;
            try {
                next = from.resolve(location.trim());
            } catch (IllegalArgumentException notAReference) {
                return null;
            }
            String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            // The one redirect the standard client also refuses to follow.
            if ("https".equalsIgnoreCase(from.getScheme()) && "http".equals(scheme)) {
                return null;
            }
            return next;
        }
    }
}
