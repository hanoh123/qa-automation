package com.qanize.automation.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.qanize.automation.checks.forms.FormExecutionEvidence;
import com.qanize.automation.checks.forms.FormsModule;
import com.qanize.automation.checks.links.LinkExecutionEvidence;
import com.qanize.automation.checks.links.LinkSession;
import com.qanize.automation.checks.links.LinksModule;
import com.qanize.automation.checks.navigation.NavigationExecutionEvidence;
import com.qanize.automation.checks.navigation.NavigationModule;
import com.qanize.automation.checks.search.SearchExecutionEvidence;
import com.qanize.automation.checks.search.SearchModule;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.OpenedPage;
import com.qanize.automation.checks.shared.PageStatusProbe;
import com.qanize.automation.checks.smoke.PageHealthModule;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;

import org.openqa.selenium.WebDriver;

/**
 * The bridge between the crawl queue and the page checks.
 *
 * <p>For each page the {@link Crawler} selects, this opens the page <strong>once</strong>, runs
 * the read-only page checks that are actually selected, and hands the internal links back so the
 * crawler can decide where to go next. It is the only place that knows both a queue and a module
 * exist; neither of those knows about the other.
 *
 * <p><strong>It owns no browser.</strong> It does not create a driver, does not quit one, does not
 * open a second one, and does not retry a failed page open. The caller starts and stops the
 * browser exactly as the single-page checks already do; this borrows the open session.
 *
 * <p><strong>It duplicates no module logic.</strong> Page health is decided by
 * {@link PageHealthModule} and links by {@link LinksModule}; this decides only what to call, in
 * what order, and what to do when the page never opened.
 *
 * <p><strong>Order is fixed:</strong> open, then {@link PageHealthModule}, then
 * {@link LinksModule}. Health runs first because "is this a usable page at all?" is the question
 * whose answer frames every other result. It does not gate Links: a page that opened is a page
 * whose links can be inspected, so a blank title never hides a broken link.
 *
 * <p><strong>Evidence is page-scoped.</strong> See {@link #EVIDENCE_SCOPING}.
 *
 * <p><strong>A failure is photographed where it happens.</strong> When a page will not open, or a
 * selected check returns a failing verdict, or a selected check throws, this asks the run's
 * {@link PageScreenshots} for one picture at that exact moment -- before the next module runs and
 * before Search moves the browser to a results page. Nothing else is photographed, and the request
 * can neither fail a check nor stop the checks that follow.
 */
public final class CrawlerPageProcessor implements Crawler.PageProcessor {

    /**
     * How evidence from different crawled pages is kept apart.
     *
     * <p>This used to be a statement of a limitation: the checks' fixed identities
     * ({@code smoke-page-1}, {@code link-1}) repeated on every page, so evidence collided. That is
     * resolved. Each page gets its own {@link EvidenceScope}, which qualifies every tested-item id
     * and every execution test-case id with a stable page id and publishes the page ownership as
     * metadata, without any change to the frozen reporting contract.
     *
     * <p>What remains outstanding is only the wiring: runtime and the frontend still call the
     * single-page checks, and this class is still reachable only from tests.
     */
    public static final String EVIDENCE_SCOPING =
        "Evidence is page-scoped: every tested-item id and execution test-case id is qualified as "
            + "<pageId>/<moduleId>, and page ownership is published as page.* metadata.";

    private final PageAudit audit;
    private final PageScreenshots screenshots;
    private final boolean pageHealthSelected;
    private final boolean linksSelected;
    private final boolean formsSelected;
    private final boolean navigationSelected;
    private final boolean searchSelected;

    private final List<PageAuditResult> results = new ArrayList<>();
    private int pagesSeen;

    /**
     * The production entry point.
     *
     * @param page   a browser session the caller already started; never created or closed here
     * @param driver the same session, for the two checks whose seams need it; never quit here
     * @param config supplies both the check selections and the settings the modules already read
     */
    public CrawlerPageProcessor(CommonPage page, WebDriver driver, WebsiteConfig config) {
        this(page, driver, config, PageScreenshots.DISABLED);
    }

    /**
     * The production entry point, with the run's screenshot collaborator.
     *
     * @param screenshots asked for one picture when a page or a check fails, and never otherwise
     */
    public CrawlerPageProcessor(
        CommonPage page, WebDriver driver, WebsiteConfig config, PageScreenshots screenshots
    ) {
        this(page, driver, config, screenshots, LinkSession.anonymous());
    }

    /**
     * The production entry point for a run that may have signed in.
     *
     * <p>The session is a fact about the run: established once, before the first page, and the same
     * for every page after it. So it is handed over once here rather than rediscovered per page.
     * This bridge does nothing with it except pass it on — it inspects nothing, decides nothing
     * from it, and keeps nothing that could be read back out of it.
     *
     * @param session what this run may present to a link destination; {@link LinkSession#anonymous()}
     *                for a run that did not sign in, which is every run that did not ask to
     */
    public CrawlerPageProcessor(
        CommonPage page, WebDriver driver, WebsiteConfig config, PageScreenshots screenshots,
        LinkSession session
    ) {
        this(new SeleniumPageAudit(page, driver, config, session), config, screenshots);
    }

    /** The seam a test uses to supply its own page work. Not part of the public API. */
    CrawlerPageProcessor(PageAudit audit, WebsiteConfig config) {
        this(audit, config, PageScreenshots.DISABLED);
    }

    /** The same seam, plus a way to observe exactly which failures asked for a picture. */
    CrawlerPageProcessor(PageAudit audit, WebsiteConfig config, PageScreenshots screenshots) {
        this.audit = audit;
        this.screenshots = screenshots == null ? PageScreenshots.DISABLED : screenshots;
        // Read once, from the same selections the single-page checks assume on.
        this.pageHealthSelected = config.tests().smoke();
        this.linksSelected = config.tests().links();
        this.formsSelected = config.tests().forms();
        this.navigationSelected = config.tests().navigation();
        this.searchSelected = config.tests().search();
    }

    /**
     * Opens one page and reports the internal links found on it.
     *
     * <p>A page that will not open produces one failed page result, runs no module, and returns no
     * candidates — which lets the crawler carry on with everything already queued rather than
     * ending the run.
     */
    @Override
    public List<String> process(Crawler.Page page) {
        PageAuditContext context = PageAuditContext.of(page, ++pagesSeen);

        long openStart = System.nanoTime();
        try {
            // Exactly one open per page. There is no retry: a page that did not load is a result
            // about the website, not something to ask again for. This is also the only navigation
            // any check gets to claim: Navigation reports this open rather than repeating it.
            audit.open(context);
        } catch (RuntimeException openFailure) {
            long openMillis = elapsedMs(openStart);
            // The earliest truthful moment: the browser is still wherever the failed open left it,
            // and nothing below this line touches it. Exactly one picture for this page is taken
            // here -- the Navigation outcome built next reports this same failed open, so
            // capturing for that as well would photograph one event twice.
            capture(context, CrawlScreenshotRecorder.CheckTarget.PAGE_OPEN);
            // A page that would not open is a Navigation failure, not an absence of Navigation --
            // but only when the check was selected at all.
            // Guarded on this branch too: a reporting crash must not turn a page that merely
            // failed to open into a lost result as well.
            ModuleOutcome navigation = guardedWithoutCapture(navigationSelected,
                () -> ModuleOutcome.of(audit.recordPageReached(context, openMillis, openFailure)));
            results.add(PageAuditResult.pageNeverOpened(context, openFailure, navigation));
            return List.of();
        }
        long openMillis = elapsedMs(openStart);

        // Health first, and its verdict does not gate Links: the page is open either way.
        // Each selected module below runs inside its own failure boundary. One module throwing
        // unexpectedly is a finding about that module, not a reason to discard the results and
        // candidates already collected on this page -- which is exactly what happened before: an
        // escaping exception skipped the results.add() at the end of this method entirely, and
        // the Crawler recorded the whole page as failed with nothing kept.
        //
        // Continuing after a failure is safe for every module here, and only because of what each
        // one does: PageHealth and Links read the document, Forms is wired to its read-only
        // discovery entry point, and crawler-mode Navigation touches no browser at all. Search is
        // last precisely because it may leave the browser on a results page.
        ModuleOutcome health = guarded(
            context, CrawlScreenshotRecorder.CheckTarget.PAGE_HEALTH, pageHealthSelected,
            () -> ModuleOutcome.of(audit.inspectPageHealth(context)));

        ModuleOutcome links = ModuleOutcome.NOT_SELECTED;
        List<String> candidates = List.of();
        // Links owns the candidates, so its boundary is written out rather than delegated: a
        // Links that threw before returning has no trustworthy handoff, and the list stays empty.
        if (linksSelected) {
            try {
                LinksModule.Result linksResult = audit.inspectLinks(context);
                links = ModuleOutcome.of(linksResult);
                candidates = linksResult.internalCandidates();
            } catch (RuntimeException linksFailure) {
                links = ModuleOutcome.crashed(linksFailure);
            }
            // Immediately, and before Forms runs: this page is still the page on screen, and the
            // candidates above are already collected, so a capture cannot cost them.
            captureIfFailed(context, CrawlScreenshotRecorder.CheckTarget.LINKS, links);
        }

        // Forms next: still read-only, so it cannot disturb anything that follows.
        ModuleOutcome forms = guarded(
            context, CrawlScreenshotRecorder.CheckTarget.FORMS, formsSelected,
            () -> ModuleOutcome.of(audit.inspectForms(context)));

        // Navigation reports the page the crawler already opened. It follows no link and opens
        // nothing: the Crawler owns traversal, and Links has already handed it the candidates.
        // Asking Navigation to reopen them would visit every destination twice.
        ModuleOutcome navigation = guarded(
            context, CrawlScreenshotRecorder.CheckTarget.NAVIGATION, navigationSelected,
            () -> ModuleOutcome.of(audit.recordPageReached(context, openMillis, null)));

        // Search runs last, and nothing may follow it: submitting a query leaves the browser on a
        // results page and, unlike Navigation, the check does not come back. The crawler's next
        // page begins with its own open(), so that is safe — but only in this position.
        ModuleOutcome search = guarded(
            context, CrawlScreenshotRecorder.CheckTarget.SEARCH, searchSelected,
            () -> ModuleOutcome.of(audit.inspectSearch(context)));

        results.add(new PageAuditResult(context, ModuleOutcome.PASSED, health, links,
            forms, navigation, search, sanitized(candidates)));
        // Raw, because the crawler opens them. The published copy above is sanitized. Collected
        // before Navigation and Search moved the browser, so they describe this page.
        return candidates;
    }

    /** Every page audited so far, in the order the crawler attempted them. */
    public List<PageAuditResult> results() {
        return List.copyOf(results);
    }

    /**
     * Runs one selected module inside its own failure boundary.
     *
     * <p>An unselected module is never invoked and keeps {@link Status#NOT_SELECTED}: a boundary
     * cannot turn "nobody asked for this" into a failure.
     *
     * <p>Only {@link RuntimeException} is caught. An {@link Error} -- an unrecoverable JVM
     * condition such as exhausted memory -- propagates untouched, because carrying on with the
     * next module after one would produce evidence nobody should trust.
     *
     * <p>The module is invoked exactly once. There is no retry and no reopening of the page: a
     * module that failed has produced its result, and asking again would either repeat a side
     * effect or invent a second verdict for one page.
     */
    private ModuleOutcome guarded(
        PageAuditContext context,
        CrawlScreenshotRecorder.CheckTarget target,
        boolean selected,
        Supplier<ModuleOutcome> module
    ) {
        ModuleOutcome outcome = guardedWithoutCapture(selected, module);
        captureIfFailed(context, target, outcome);
        return outcome;
    }

    /** The same boundary without a picture, for the one failure that is photographed elsewhere. */
    private static ModuleOutcome guardedWithoutCapture(
        boolean selected, Supplier<ModuleOutcome> module
    ) {
        if (!selected) {
            return ModuleOutcome.NOT_SELECTED;
        }
        try {
            return module.get();
        } catch (RuntimeException moduleFailure) {
            return ModuleOutcome.crashed(moduleFailure);
        }
    }

    /**
     * One picture per failing check, and only for a failure.
     *
     * <p>{@link Status#PASSED}, {@link Status#NOT_SELECTED}, {@link Status#NOT_RUN} and
     * {@link Status#NOTHING_TO_CHECK} photograph nothing: three of them mean no check ran, and the
     * fourth means the website did nothing wrong. Both kinds of failure are photographed, website
     * verdict and analyzer crash alike -- a module that threw is exactly the case where a picture
     * of the page it threw on is worth the most.
     */
    private void captureIfFailed(
        PageAuditContext context, CrawlScreenshotRecorder.CheckTarget target, ModuleOutcome outcome
    ) {
        if (outcome.status() == Status.FAILED) {
            capture(context, target);
        }
    }

    /**
     * Asks for a picture and forgets about it.
     *
     * <p>Swallowing is the whole point. Supporting evidence may not change a module's verdict,
     * stop the modules that follow, discard the candidates this page already produced, or turn a
     * finding about the website into a failure of the analyzer. The collaborator already records
     * every attempt it made, so nothing is lost by not hearing about it here.
     */
    private void capture(PageAuditContext context, CrawlScreenshotRecorder.CheckTarget target) {
        try {
            screenshots.capture(context.pageNumber(), target);
        } catch (RuntimeException ignored) {
            // Intentionally empty: see above. Nothing is logged, because a capture failure can
            // carry text the browser supplied.
        }
    }

    /** Wall-clock milliseconds around the crawler's own page open. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static List<String> sanitized(List<String> urls) {
        return urls.stream().map(SensitiveDataFilter::sanitizeUrl).toList();
    }

    // --- what one page produced ------------------------------------------------------------------

    /** How one module ended on one page. */
    public enum Status {
        PASSED,
        FAILED,
        /** The check was not selected. Explicitly not a pass: nothing was verified. */
        NOT_SELECTED,
        /** The page never opened, so the check could not run. Also not a pass. */
        NOT_RUN,
        /**
         * The check ran and found nothing of its kind on this page — no eligible link, no form.
         *
         * <p>Distinct from a pass, because nothing was verified, and distinct from a failure,
         * because the website did nothing wrong. The single-page wrappers express the same idea
         * by skipping rather than asserting.
         */
        NOTHING_TO_CHECK
    }

    /**
     * Where a {@link Status#FAILED} came from.
     *
     * <p>Two failures that look identical and mean opposite things. A module that returned a
     * failing verdict has told us something about the website. A module that threw has told us
     * something about the analyzer — and reporting that as a website finding blames the customer's
     * site for our own defect. The distinction is typed because the only alternative is reading
     * the exception's text, which is not a classification.
     */
    public enum FailureOrigin {
        /** Not a failure at all. */
        NONE,
        /** The module ran and returned a failing verdict: a finding about the website. */
        WEBSITE_RESULT,
        /** The module threw where a verdict was expected: a fault in the analyzer. */
        ANALYZER_CRASH
    }

    /** One module's verdict on one page, the message it reported with, and where it came from. */
    public record ModuleOutcome(Status status, String message, FailureOrigin failureOrigin) {

        public ModuleOutcome {
            failureOrigin = failureOrigin == null ? FailureOrigin.NONE : failureOrigin;
            if (status == Status.FAILED && failureOrigin == FailureOrigin.NONE) {
                throw new IllegalArgumentException("a failure must say where it came from");
            }
            if (status != Status.FAILED && failureOrigin != FailureOrigin.NONE) {
                throw new IllegalArgumentException(
                    "only a failure has an origin, but " + status + " claimed " + failureOrigin);
            }
        }

        /**
         * The shape every existing caller and test already uses.
         *
         * <p>A failure built this way is a website result, which is what it always meant before
         * the origin existed. Only {@link #crashed} says otherwise.
         */
        public ModuleOutcome(Status status, String message) {
            this(status, message,
                status == Status.FAILED ? FailureOrigin.WEBSITE_RESULT : FailureOrigin.NONE);
        }

        /** Whether this failure is the analyzer's fault rather than the website's. */
        public boolean crashed() {
            return failureOrigin == FailureOrigin.ANALYZER_CRASH;
        }

        static final ModuleOutcome NOT_SELECTED =
            new ModuleOutcome(Status.NOT_SELECTED, null, FailureOrigin.NONE);
        static final ModuleOutcome PASSED =
            new ModuleOutcome(Status.PASSED, null, FailureOrigin.NONE);

        static ModuleOutcome of(PageHealthModule.Result result) {
            if (result.passed()) return PASSED;
            // A page the run could not look at — an authentication challenge is the case that
            // forced this — is nothing to check, never a failure of the website.
            if (result.skipped()) {
                return nothingToCheck(SensitiveDataFilter.sanitizeMessage(result.message()));
            }
            return new ModuleOutcome(Status.FAILED, SensitiveDataFilter.sanitizeMessage(result.message()));
        }

        static ModuleOutcome of(LinksModule.Result result) {
            if (result.passed()) return PASSED;
            // "Nothing eligible to request" is the wrapper's skip, not a finding against the site.
            // So is "we found destinations and declined to request them without the sign-in": in
            // both cases nothing was verified and the website did nothing wrong, which is exactly
            // what NOTHING_TO_CHECK means. Reporting either as a failure would blame the customer's
            // site for a limit of ours.
            if (result.nothingEligible() || result.validationNotPerformed()) {
                return nothingToCheck(result.message());
            }
            return failed(result.message());
        }

        static ModuleOutcome of(FormsModule.Result result) {
            if (result.passed()) return PASSED;
            return result.skipped() ? nothingToCheck(result.message()) : failed(result.message());
        }

        static ModuleOutcome of(NavigationModule.Result result) {
            if (result.passed()) return PASSED;
            // "No eligible destination" and "every destination opened answered in a way that
            // established nothing" are both nothing to check, and neither is the website's fault.
            return result.nothingEligible() || result.skipped()
                ? nothingToCheck(result.message())
                : failed(result.message());
        }

        static ModuleOutcome of(SearchModule.Result result) {
            return result.passed() ? PASSED : failed(result.failureMessage());
        }

        /**
         * A module that threw where it was expected to return a verdict.
         *
         * <p>Still a plain {@link Status#FAILED}, because the frozen contract has no "crashed"
         * status and the customer-facing answer is the same: this check did not pass. What is new
         * is {@link FailureOrigin#ANALYZER_CRASH} alongside it, so the run-level outcome can say
         * this was our fault rather than the website's. Only the sanitized text travels.
         */
        static ModuleOutcome crashed(RuntimeException failure) {
            return new ModuleOutcome(
                Status.FAILED,
                SensitiveDataFilter.sanitizeMessage(failure.toString()),
                FailureOrigin.ANALYZER_CRASH);
        }

        private static ModuleOutcome failed(String message) {
            return new ModuleOutcome(
                Status.FAILED,
                SensitiveDataFilter.sanitizeMessage(message),
                FailureOrigin.WEBSITE_RESULT);
        }

        private static ModuleOutcome nothingToCheck(String message) {
            return new ModuleOutcome(
                Status.NOTHING_TO_CHECK,
                SensitiveDataFilter.sanitizeMessage(message),
                FailureOrigin.NONE);
        }

        public boolean ran() {
            return status == Status.PASSED
                || status == Status.FAILED
                || status == Status.NOTHING_TO_CHECK;
        }
    }

    /**
     * Everything one crawled page produced, kept together.
     *
     * <p>This is the grouping the crawl result needs: which page, where it sat in the crawl, what
     * happened when it was opened, what each selected check said about it, and what it led to. An
     * unselected check is present and says so rather than being silently absent or, worse,
     * reported as a pass.
     *
     * <p>{@code discoveredCandidates} holds the <em>sanitized</em> addresses. The raw ones go to
     * the crawler and are never published.
     */
    public record PageAuditResult(
        PageAuditContext page,
        ModuleOutcome pageOpen,
        ModuleOutcome pageHealth,
        ModuleOutcome links,
        ModuleOutcome forms,
        ModuleOutcome navigation,
        ModuleOutcome search,
        List<String> discoveredCandidates
    ) {

        public PageAuditResult {
            discoveredCandidates = List.copyOf(discoveredCandidates);
        }

        static PageAuditResult pageNeverOpened(
            PageAuditContext page, RuntimeException failure, ModuleOutcome navigation
        ) {
            // The original cause is not carried into the published result; the crawler already
            // keeps it on its own failed page. Only the sanitized text travels.
            ModuleOutcome notRun = new ModuleOutcome(Status.NOT_RUN, null);
            return new PageAuditResult(
                page,
                new ModuleOutcome(Status.FAILED,
                    SensitiveDataFilter.sanitizeMessage(failure.toString())),
                notRun, notRun, notRun, navigation, notRun,
                List.of());
        }

        /** Whether the browser reached this page at all. */
        public boolean opened() {
            return pageOpen.status() == Status.PASSED;
        }
    }

    /**
     * How this bridge asks for a screenshot without knowing what one is.
     *
     * <p>Narrow on purpose: a page number and a check key go in and nothing comes back. The bridge
     * cannot name a file through it, cannot reach a driver through it, and cannot have a JUnit
     * extension put behind it. The run supplies the implementation; {@link #DISABLED} is what a
     * caller that has no browser gets, which is why the existing constructors still work.
     */
    @FunctionalInterface
    public interface PageScreenshots {

        /** Never throws. A capture problem is the collaborator's to record, not to raise. */
        void capture(int pageNumber, CrawlScreenshotRecorder.CheckTarget target);

        /** Takes no pictures. The default for any caller that did not supply one. */
        PageScreenshots DISABLED = (pageNumber, target) -> { };
    }

    // --- the page work this bridge needs ----------------------------------------------------------

    /**
     * Opening one page and running the two read-only checks on it.
     *
     * <p>Deliberately not a general abstraction: it exists so the ordering, the selection rules and
     * the open-failure behaviour can be proved without a browser. It exposes nothing that could
     * create or close a driver.
     */
    interface PageAudit {

        /** @throws RuntimeException when the page could not be opened */
        void open(PageAuditContext page);

        PageHealthModule.Result inspectPageHealth(PageAuditContext page);

        LinksModule.Result inspectLinks(PageAuditContext page);

        FormsModule.Result inspectForms(PageAuditContext page);

        /**
         * Reports the page the crawler already opened. Performs no browser action at all.
         *
         * @param openMillis how long the crawler's own open took
         * @param openFailure the failure that stopped the open, or null when the page opened
         */
        NavigationModule.Result recordPageReached(
            PageAuditContext page, long openMillis, RuntimeException openFailure);

        /** Leaves the browser on a results page; nothing may run after it. */
        SearchModule.Result inspectSearch(PageAuditContext page);
    }

    /**
     * The production page work, over the one browser session the caller already started.
     *
     * <p>Both modules are given the same {@link CommonPage} instance — the page that
     * {@link #open(PageAuditContext)} just navigated — so neither can be inspecting a different
     * document from the other.
     */
    private static final class SeleniumPageAudit implements PageAudit {

        private final CommonPage page;
        private final WebDriver driver;
        private final WebsiteConfig config;
        private final LinkSession session;

        SeleniumPageAudit(
            CommonPage page, WebDriver driver, WebsiteConfig config, LinkSession session
        ) {
            this.page = page;
            this.driver = driver;
            this.config = config;
            this.session = session == null ? LinkSession.anonymous() : session;
        }

        @Override
        public void open(PageAuditContext context) {
            page.openUrl(context.url());
        }

        @Override
        public PageHealthModule.Result inspectPageHealth(PageAuditContext context) {
            // A fresh recorder and a fresh scope per page: identities and step sequence numbers
            // both run per test case, so this page's first step must not continue another's.
            EvidenceScope scope = scopeFor(context);
            return new PageHealthModule(
                page, new SmokeExecutionEvidence(scope), scope, pageStatusProbe()).inspect(
                context.url(),
                null,
                config.optionalExpectedTitleContains().orElse(null),
                config.optionalExpectedText().orElse(null));
        }

        @Override
        public LinksModule.Result inspectLinks(PageAuditContext context) {
            // The page the browser is ACTUALLY on is the base -- not the address that was queued.
            // Relative hrefs resolve against the document that is loaded, and a link discovered
            // after a redirect belongs to the page it was read from. Attributing it to the
            // requested address is how a run reported dashboard links it had never seen.
            EvidenceScope scope = scopeFor(context);
            return new LinksModule(
                page, config, landedUrl(context), session, new LinkExecutionEvidence(scope), scope)
                .run();
        }

        @Override
        public FormsModule.Result inspectForms(PageAuditContext context) {
            // The read-only entry point only. Filling a configured field is a deliberate, singular
            // interaction the wrapper performs once per run; repeating it on every crawled page
            // would be a behaviour change, not a wiring one.
            EvidenceScope scope = scopeFor(context);
            return new FormsModule(
                page, driver, landedUrl(context), new FormExecutionEvidence(scope), scope)
                .inspectDiscoveredForms();
        }

        @Override
        public NavigationModule.Result recordPageReached(
            PageAuditContext context, long openMillis, RuntimeException openFailure
        ) {
            // Deliberately NOT followDiscoveredLinks(): that opens every discovered destination
            // and then reopens the baseline, so each destination would be visited once by
            // Navigation and again when the Crawler dequeues it. The Crawler owns traversal.
            EvidenceScope scope = scopeFor(context);
            // The base page is where the browser landed, because that is the document whose
            // navigation candidates this module will read. The page it REPORTS reaching is the
            // landed address too: "we opened X and arrived at Y" is two facts, and the second one
            // is the one every later discovery belongs to.
            String landed = landedUrl(context);
            return new NavigationModule(
                page, driver, config, landed, new NavigationExecutionEvidence(scope), scope)
                .recordCrawlerOpenedPage(
                    landed,
                    context.discoveredFrom(),
                    openMillis,
                    openFailure == null
                        ? null
                        : SensitiveDataFilter.sanitizeMessage(openFailure.toString()));
        }

        @Override
        public SearchModule.Result inspectSearch(PageAuditContext context) {
            // config.search() is assumed present when the check is selected, exactly as the
            // single-page wrapper assumes it.
            EvidenceScope scope = scopeFor(context);
            return new SearchModule(
                page, config.search(), landedUrl(context), new SearchExecutionEvidence(scope), scope)
                .run();
        }

        /**
         * Who may ask a page's address what it answers.
         *
         * <p>The session answers that, not this bridge. A probe carries no cookies, so a
         * signed-in run would be asking about a visitor the customer is not, and
         * {@link LinkSession} — which already owns what a run may present to a server — hands back
         * {@link PageStatusProbe#NONE} in that case. Nothing here reads a mode, a cookie or any
         * other part of the session to decide it.
         */
        private PageStatusProbe pageStatusProbe() {
            return session.pageStatusProbe(Duration.ofSeconds(config.defaultTimeoutSeconds()));
        }

        /**
         * The evidence scope for one page.
         *
         * <p>Built from the context each time it is needed and handed to the recorder and the
         * module explicitly. Nothing is remembered between pages, so no page can inherit another's
         * identity and there is no ambient current page to get out of step.
         */
        private EvidenceScope scopeFor(PageAuditContext context) {
            return EvidenceScope.forPage(
                context.pageNumber(), context.url(), landedUrl(context),
                context.depth(), context.discoveredFrom());
        }

        /**
         * The address the browser is on right now, or the requested one when it will not say.
         *
         * <p>Read from the driver at the moment each check runs rather than remembered from the
         * open, so there is no ambient "current page" field to fall out of step with the browser.
         * A driver that refuses the read, or answers with nothing, leaves the requested address in
         * place: a check still has to be attributed to something, and inventing an address would be
         * worse than naming the one that was asked for.
         *
         * <p>Sanitized, because a landing address can carry a return parameter or a session value
         * and it is about to become the {@code pageUrl} of every item this page produces.
         */
        private String landedUrl(PageAuditContext context) {
            return OpenedPage.where(page, context.url()).landed();
        }
    }
}
