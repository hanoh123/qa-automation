package com.qanize.automation.checks.navigation;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.DiscoveryDiagnostics;
import com.qanize.automation.checks.shared.HttpStatusClassification;
import com.qanize.automation.checks.shared.NavigationLinkInspector;
import com.qanize.automation.checks.shared.PageStatusProbe;
import com.qanize.automation.checks.shared.SafePointerClick;
import com.qanize.automation.checks.shared.SelectorUtils;
import com.qanize.automation.checks.shared.WaitUtils;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.evidence.DiscoveryEvidence;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.TestedItem;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * The Navigation check, as production code.
 *
 * <p>Navigation proves that a real destination reachable from the page opens a usable page, and
 * that one configured menu button opens its configured panel. It is deliberately not Links: Links
 * asks an address whether it answers, Navigation opens it in the browser and looks at the result.
 *
 * <p><strong>It receives an already-open page.</strong> The caller opens the start page. This
 * module does navigate — following a destination is the whole check — but it never creates or
 * closes a browser, and it always returns to the start page it was given before moving on, so the
 * next case begins where the previous one did.
 *
 * <p><strong>It never asserts.</strong> Both entry points return a result the caller converts. The
 * link check has three outcomes because a page with no eligible destination has always been
 * reported as a skip rather than a failure, and that difference reaches the customer's summary.
 *
 * <p><strong>It owns no queue.</strong> Destinations come from this one page and are capped by the
 * configured limit. Nothing here records visited pages or decides what to audit next.
 *
 * <p><strong>A destination that came back without a title is a question.</strong> Navigation does
 * not require a title and must not start — plenty of perfectly good pages have none, and the
 * recorded expectation says so: "the destination reports its title, if it has one". But a browser
 * handed a 401 dismisses the credential prompt and renders an empty document, and the only test
 * this check applied to it was "is the page source non-blank", which that empty document passes.
 * So {@code /basic_auth} was reported as a destination that "loaded a usable page" while the Links
 * check, on the same address in the same run, classified it {@code AUTH_REQUIRED}.
 *
 * <p>When, and only when, a destination produced no title, this asks its {@link PageStatusProbe}
 * what the server answered and hands that to {@link HttpStatusClassification} — the same single
 * answer the Links and page-health checks use. A protected or rate-limited destination becomes a
 * safe skip with the recorded reason. Every other answer, and every run with no probe, keeps the
 * verdict this check has always produced.
 */
public final class NavigationModule {

    /** Where a navigation link is looked for first. Unchanged. */
    static final String NAV_SELECTOR =
        "nav a[href], header a[href], [role='navigation'] a[href], .navbar a[href], .menu a[href]";

    private final PageNavigation navigation;
    private final WebsiteConfig config;
    private final String basePage;
    private final NavigationExecutionEvidence execution;
    private final EvidenceScope scope;
    /** Consulted only for a destination that produced no title. {@link PageStatusProbe#NONE} by default. */
    private final PageStatusProbe statusProbe;

    /**
     * The production entry point.
     *
     * @param page      a page whose browser is already on {@code basePage}
     * @param execution the recorder the caller already used, so per-test-case sequence numbers
     *                  continue rather than restarting
     */
    public NavigationModule(
        CommonPage page, WebDriver driver, WebsiteConfig config, String basePage,
        NavigationExecutionEvidence execution
    ) {
        this(page, driver, config, basePage, execution, EvidenceScope.legacy());
    }

    /**
     * The entry point for one crawled page.
     *
     * @param scope which page these destinations belong to, so page 2's {@code nav-1} cannot be
     *              mistaken for page 1's
     * @throws NullPointerException when the scope is null; ask for the legacy scope by name
     */
    public NavigationModule(
        CommonPage page, WebDriver driver, WebsiteConfig config, String basePage,
        NavigationExecutionEvidence execution, EvidenceScope scope
    ) {
        this(page, driver, config, basePage, execution, scope, PageStatusProbe.NONE);
    }

    /**
     * The entry point for a caller that can say what a titleless destination's address answers.
     *
     * <p>The probe is supplied rather than built here, and the decision of whether one exists at
     * all belongs to the caller that knows the run's session — the same division page health
     * already uses. A run that carries a signed-in session supplies {@link PageStatusProbe#NONE},
     * because an anonymous request would be answered for a visitor the customer is not.
     *
     * @param statusProbe asked for a status only when a destination produced no title, never
     *                    otherwise; {@link PageStatusProbe#NONE} keeps the browser-only behaviour
     */
    public NavigationModule(
        CommonPage page, WebDriver driver, WebsiteConfig config, String basePage,
        NavigationExecutionEvidence execution, EvidenceScope scope, PageStatusProbe statusProbe
    ) {
        this(new CommonPageNavigation(page, driver, WebsiteConfigLoader.timeout(config)),
            config, basePage, execution, scope, statusProbe);
    }

    /** The seam a test uses to supply its own page. Not part of the public API. */
    NavigationModule(
        PageNavigation navigation, WebsiteConfig config, String basePage,
        NavigationExecutionEvidence execution
    ) {
        this(navigation, config, basePage, execution, EvidenceScope.legacy());
    }

    /** The seam a test uses to supply its own page and scope. Not part of the public API. */
    NavigationModule(
        PageNavigation navigation, WebsiteConfig config, String basePage,
        NavigationExecutionEvidence execution, EvidenceScope scope
    ) {
        this(navigation, config, basePage, execution, scope, PageStatusProbe.NONE);
    }

    /** The seam a test uses to supply its own page, scope and probe. Not part of the public API. */
    NavigationModule(
        PageNavigation navigation, WebsiteConfig config, String basePage,
        NavigationExecutionEvidence execution, EvidenceScope scope, PageStatusProbe statusProbe
    ) {
        this.navigation = navigation;
        this.config = config;
        this.basePage = basePage;
        this.execution = execution;
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use the constructor without a scope for the legacy scope");
        this.statusProbe = statusProbe == null ? PageStatusProbe.NONE : statusProbe;
    }

    // --- crawler mode: report the page the crawler already opened ------------------------------

    /**
     * Reports the page the crawler opened, without opening anything.
     *
     * <p><strong>This performs no browser action whatsoever.</strong> No {@code openUrl}, no click,
     * no return to a baseline. In crawler mode the {@code Crawler} owns traversal and has already
     * opened this address exactly once; {@link #followDiscoveredLinks()} would open every
     * destination a second time and then reopen the baseline, so the crawler must not call it.
     *
     * <p>What it produces is an honest Navigation verdict for the page: the address was reachable,
     * and — for a discovered page — which page it was reached from. Nothing here claims a link was
     * clicked, because none was.
     *
     * <p>{@link #followDiscoveredLinks()} is untouched and remains what the standalone single-page
     * check calls.
     *
     * @param destination    the address the crawler opened
     * @param discoveredFrom the page it was found on, or null for the submitted start page
     * @param openMillis     how long the crawler's own open took
     * @param failure        the open failure, or null when the page opened; sanitized here
     */
    public Result recordCrawlerOpenedPage(
        String destination, String discoveredFrom, long openMillis, String failure
    ) {
        Instant startedAt = Instant.now();
        // Sanitized here rather than trusted from the caller: this string reaches an artifact, and
        // a value that must be safe should be made safe at the boundary that publishes it.
        // sanitizeMessage is idempotent for an already-sanitized message.
        String safeFailure = SensitiveDataFilter.sanitizeMessage(failure);
        boolean opened = safeFailure == null;
        boolean startPage = discoveredFrom == null || discoveredFrom.isBlank();
        String safeDestination = SensitiveDataFilter.sanitizeUrl(destination);
        String safeParent = SensitiveDataFilter.sanitizeUrl(discoveredFrom);

        String actual = opened
            ? (startPage
                ? "The submitted start page was opened."
                : "Reached from " + safeParent + ".")
            : (startPage
                ? "The submitted start page could not be opened."
                : "The page discovered on " + safeParent + " could not be opened.");

        if (opened) {
            execution.crawlerOpenedPage(destination, discoveredFrom, openMillis);
        } else {
            execution.crawlerCouldNotOpenPage(destination, discoveredFrom, safeFailure, openMillis);
        }

        TestedItem.Builder item = TestedItem.builder("NAVIGATION", TestedItem.ItemType.PAGE)
            .id(scope.itemId(NavigationExecutionEvidence.CRAWLER_PAGE_TEST_CASE_ID))
            .name(startPage ? "Submitted start page" : "Discovered page")
            .pageUrl(safeDestination)
            .target(safeDestination)
            .action("Recorded the page the crawler opened. No link was followed to reach it.")
            .expected("The page is reachable at its own address.")
            .actual(actual)
            .status(opened ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
            .startedAt(startedAt.toString())
            .completedAt(Instant.now().toString())
            .durationMs(Math.max(0, openMillis))
            .meta("reachedBy", "crawler-open")
            .meta("linkFollowed", "false");
        if (!startPage) item.meta("discoveredFrom", safeParent);
        if (!opened) item.message(safeFailure);
        EvidenceRecorder.record(scope.enrich(item).build());

        return opened ? new Result(Outcome.PASSED, null) : new Result(Outcome.FAILED, actual);
    }

    // --- the discovered-destination check -----------------------------------------------------

    /** Discovers navigation destinations on the open page and opens each eligible one. */
    public Result followDiscoveredLinks() {
        // A JavaScript-rendered page has no links at readyState "complete"; discovering before it
        // renders reports an empty site. Waiting costs nothing on a server-rendered page.
        long stepStart = System.nanoTime();
        boolean contentWaitTimedOut = navigation.awaitRenderedContentTimedOut();
        execution.waitedForRenderedContent(basePage, contentWaitTimedOut, elapsedMs(stepStart));

        stepStart = System.nanoTime();
        List<Anchor> anchors = navigation.discoverAnchors();
        long discoveryMs = elapsedMs(stepStart);
        List<String> texts = new ArrayList<>();
        List<String> hrefs = new ArrayList<>();
        for (Anchor anchor : anchors) {
            texts.add(anchor.text());
            hrefs.add(anchor.href());
        }

        List<NavigationLinkInspector.Candidate> candidates =
            NavigationLinkInspector.classify(config.baseUrl(), texts, hrefs);
        List<NavigationLinkInspector.Candidate> eligible = candidates.stream()
            .filter(NavigationLinkInspector.Candidate::eligible)
            .limit(Math.max(1, config.maxPages()))
            .toList();

        execution.discoveredCandidates(
            basePage, anchors.size(), candidates.size(), eligible.size(), discoveryMs);

        int index = 0;
        for (NavigationLinkInspector.Candidate excluded : candidates) {
            if (excluded.eligible()) continue;
            index++;
            execution.excludedCandidate(
                "nav-skipped-" + index,
                displayName(excluded.text(), excluded.rawHref()),
                excluded.resolved() == null ? excluded.rawHref() : excluded.resolved().toString(),
                excluded.exclusion().reason());
            TestedItem.Builder skippedItem = TestedItem.builder("NAVIGATION", TestedItem.ItemType.LINK)
                .id(scope.itemId("nav-skipped-" + index))
                .name(displayName(excluded.text(), excluded.rawHref()))
                .pageUrl(basePage)
                .target(excluded.resolved() == null ? excluded.rawHref() : excluded.resolved().toString())
                .action("Reviewed the navigation link and did not follow it.")
                .expected("Only safe, same-site navigation links are followed.")
                .actual(excluded.exclusion().reason())
                .status(TestedItem.Status.SKIPPED)
                .message(excluded.exclusion().reason())
                .completedAt(EvidenceRecorder.nowIso())
                .meta("exclusion", excluded.exclusion().name());
            EvidenceRecorder.record(scope.enrich(skippedItem).build());
        }

        // Selected but nothing meaningful to test: report it honestly instead of passing, and say
        // what was actually seen so a zero result can be acted on rather than guessed at.
        DiscoveryDiagnostics.ForLinks diagnostics = new DiscoveryDiagnostics.ForLinks(
            navigation.count("a[href]"),
            (int) candidates.stream().filter(NavigationLinkInspector.Candidate::sameOrigin).count(),
            anchors.size(),
            navigation.count("button, [role='button']"),
            navigation.count("iframe, frame"),
            contentWaitTimedOut
        );
        if (eligible.isEmpty()) {
            String reason = diagnostics.explain(
                "No eligible navigation links were discovered on the submitted page.");
            execution.nothingEligibleToFollow(basePage, reason);
            DiscoveryEvidence.recordNothingDiscovered(
                "NAVIGATION", scope.itemId("navigation-discovery"), "Navigation link discovery", basePage,
                "Opened the submitted page and looked for navigation links to follow.",
                "At least one safe, same-site navigation link is available to follow.",
                reason, diagnostics);
            return new Result(Outcome.NOTHING_ELIGIBLE, reason);
        }

        List<String> failures = new ArrayList<>();
        // How the loop ended, for the verdict below: how many destinations were actually verified,
        // and how many answered without establishing anything.
        int verified = 0;
        int unverifiedDestinations = 0;
        String unverifiedReason = null;
        int tested = 0;
        for (NavigationLinkInspector.Candidate candidate : eligible) {
            tested++;
            String testCaseId = "nav-" + tested;
            URI destination = candidate.resolved();
            Instant startedAt = Instant.now();
            String finalUrl = null;
            String title = null;
            boolean usable = false;
            String failure = null;
            long actionStart = System.nanoTime();
            try {
                navigation.openUrl(destination.toString());
                execution.navigatedToDestination(testCaseId, destination.toString(), elapsedMs(actionStart));

                actionStart = System.nanoTime();
                finalUrl = navigation.currentUrl();
                long addressMs = elapsedMs(actionStart);

                actionStart = System.nanoTime();
                title = navigation.title();
                long titleMs = elapsedMs(actionStart);

                actionStart = System.nanoTime();
                usable = navigation.hasLoadedDocument() && finalUrl != null && !finalUrl.isBlank();
                long contentMs = elapsedMs(actionStart);

                boolean wasRedirected = finalUrl != null
                    && !NavigationLinkInspector.normalize(destination).equals(
                           NavigationLinkInspector.normalize(URI.create(finalUrl)));
                execution.readAddress(testCaseId, destination.toString(), finalUrl, wasRedirected, addressMs);
                execution.readTitle(testCaseId, destination.toString(), title, titleMs);
                execution.readContent(testCaseId, destination.toString(), usable, contentMs);
            } catch (RuntimeException browserFailure) {
                failure = browserFailure.getClass().getSimpleName() + ": " + browserFailure.getMessage();
                execution.destinationFailed(
                    testCaseId, destination.toString(),
                    SensitiveDataFilter.sanitizeMessage(failure), elapsedMs(actionStart));
            }

            boolean redirected = finalUrl != null
                && !NavigationLinkInspector.normalize(destination).equals(
                       NavigationLinkInspector.normalize(URI.create(finalUrl)));

            // The one question the browser cannot answer, asked only where it decides the verdict:
            // a destination that produced no title. A destination that reported one is settled
            // already and is never probed, so an ordinary run costs no additional request.
            Integer status = null;
            HttpStatusClassification classification = HttpStatusClassification.NO_RESPONSE;
            boolean titleless = failure == null && (title == null || title.isBlank());
            if (titleless) {
                String address = finalUrl == null || finalUrl.isBlank()
                    ? destination.toString() : finalUrl;
                OptionalInt probed = statusProbe.statusOf(address);
                if (probed.isPresent()) {
                    status = probed.getAsInt();
                    classification = HttpStatusClassification.of(status);
                    execution.readDestinationStatus(
                        testCaseId, destination.toString(), status, classification.name());
                }
            }
            // Only the two answers that mean "answered, and still unverifiable" change the verdict.
            // A broken or successful status leaves the browser-only rule exactly as it was, so a
            // titleless page that is simply a titleless page still passes.
            boolean unverified = classification.unverified();
            boolean ok = failure == null && usable && !unverified;

            TestedItem.Builder item = TestedItem.builder("NAVIGATION", TestedItem.ItemType.LINK)
                .id(scope.itemId(testCaseId))
                .name(displayName(candidate.text(), candidate.rawHref()))
                .pageUrl(basePage)
                .target(destination.toString())
                .action("Opened the navigation destination in the browser and waited for the page to be ready.")
                .expected("The destination loads a usable page.")
                .status(ok
                    ? TestedItem.Status.PASSED
                    : unverified ? TestedItem.Status.SKIPPED : TestedItem.Status.FAILED)
                .startedAt(startedAt.toString())
                .completedAt(Instant.now().toString())
                .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
                .meta("redirected", redirected ? "true" : "false");
            if (finalUrl != null && !finalUrl.isBlank()) item.meta("finalUrl", finalUrl);
            if (title != null && !title.isBlank()) item.meta("pageTitle", title);
            if (status != null) {
                item.httpStatus(status);
                // The classification itself, under the key a page's access is already published
                // with, so a report never re-derives it from the number or from the sentence.
                item.meta("pageAccess", classification.name());
            }
            String actualSummary = failure != null
                ? "The browser could not open the destination."
                : unverified
                    ? classification.reason(status)
                    : (usable ? "The destination loaded" : "The destination did not return a usable page")
                      + (redirected ? ", redirecting to " + finalUrl : "") + ".";
            item.actual(actualSummary);
            // A skip says why it was skipped wherever a reader looks, and the message is where the
            // summary reads a skip reason from.
            if (failure != null) {
                item.message(failure);
            } else if (unverified) {
                item.message(actualSummary);
            }
            // Last in the chain: enrichment only fills keys the check left absent.
            EvidenceRecorder.record(scope.enrich(item).build());

            // Recorded before the run's verdict is produced, so a failing destination's verdict is
            // already on disk even though the verdict that reports it comes after the loop.
            // Told what the destination turned out to be, so a protected page is not a failure.
            execution.assertedDestinationUsable(
                testCaseId, destination.toString(),
                ok ? HttpStatusClassification.SUCCESS
                   : unverified ? classification : HttpStatusClassification.BROKEN,
                actualSummary);

            // A destination the run could not check never reaches the check's verdict: it is
            // already recorded as a skip, and adding it here would report the website as broken
            // for answering an unauthenticated request correctly.
            if (ok) {
                verified++;
            } else if (unverified) {
                unverifiedDestinations++;
                if (unverifiedReason == null) unverifiedReason = actualSummary;
            } else {
                failures.add(destination + (failure == null ? " did not load a usable page" : " -> " + failure));
            }
            // Back to where this case started, so the next destination is discovered and opened
            // from the same page rather than from wherever the previous one ended.
            long returnStart = System.nanoTime();
            navigation.openUrl(basePage);
            execution.returnedToStartPage(testCaseId, basePage, elapsedMs(returnStart));
        }

        if (!failures.isEmpty()) {
            return new Result(Outcome.FAILED,
                "Navigation destinations failed: " + String.join("; ", failures));
        }
        // Every destination was opened and every one of them answered in a way that established
        // nothing. There is no failure to report and nothing was verified either, so this is the
        // same "nothing to check" a page with no eligible destination already reports.
        if (verified == 0 && unverifiedDestinations > 0) {
            return new Result(Outcome.NOTHING_TO_CHECK, unverifiedReason);
        }
        return new Result(Outcome.PASSED, null);
    }

    // --- the configured menu check ------------------------------------------------------------

    /**
     * Clicks the one configured menu button once and reports whether the one configured panel
     * became visible.
     *
     * <p>Every exit path records its own verdict before returning, so the result is on disk even
     * when the caller is what reports it. There is no retry: a second click on a real website could
     * toggle the menu shut again, and the analyzer could no longer say what it did.
     */
    public Result openConfiguredMenu(WebsiteMenuConfig menu) {
        String menuName = menu.displayName();
        Instant startedAt = Instant.now();

        long step = System.nanoTime();
        boolean readyTimedOut = navigation.awaitRenderedContentTimedOut();
        execution.awaitedMenuPageReady(basePage, readyTimedOut, elapsedMs(step));

        // The configured trigger, resolved once. No fallback selector and no discovered control:
        // the only thing this check may click is the element the site's owner named.
        step = System.nanoTime();
        TriggerInspection trigger = navigation.inspectTrigger(menu.triggerSelector());
        if (!trigger.selectorUsable()) {
            execution.inspectedMenuTrigger(menuName, 0, false, false, elapsedMs(step));
            return menuVerdict(menu, startedAt, false, false, false, false,
                ErrorCategory.CONFIGURATION_ERROR,
                "The configured menu button selector could not be used.");
        }
        boolean single = trigger.matches() == 1;
        boolean displayed = single && trigger.displayed();
        boolean enabled = single && trigger.enabled();
        execution.inspectedMenuTrigger(menuName, trigger.matches(), displayed, enabled, elapsedMs(step));
        if (!single || !displayed || !enabled) {
            return menuVerdict(menu, startedAt, false, false, false, false,
                ErrorCategory.CONFIGURATION_ERROR,
                trigger.matches() == 0
                    ? "The configured menu button was not found on the page."
                    : trigger.matches() > 1
                        ? "The configured menu button matched more than one element."
                        : "The configured menu button was not usable.");
        }

        // Baseline before anything is clicked. An already-open panel makes the transition
        // unobservable, so the click is not performed at all.
        step = System.nanoTime();
        boolean visibleBefore = navigation.anyVisible(menu.panelSelector());
        execution.inspectedMenuPanelBaseline(menuName, visibleBefore, elapsedMs(step));
        if (visibleBefore) {
            return menuVerdict(menu, startedAt, true, true, false, true,
                ErrorCategory.CONFIGURATION_ERROR,
                "The configured menu panel was already visible before the click, "
                    + "so opening it could not be observed.");
        }

        step = System.nanoTime();
        boolean owned = navigation.awaitTriggerOwnsClickPoint();
        execution.awaitedMenuTriggerClickPoint(menuName, owned, elapsedMs(step));
        if (!owned) {
            return menuVerdict(menu, startedAt, true, false, false, false,
                ErrorCategory.INFRASTRUCTURE_ERROR,
                "The configured menu button never became safely clickable, so it was not clicked.");
        }

        // The one and only click.
        step = System.nanoTime();
        boolean dispatched = navigation.clickTriggerOnce();
        execution.clickedMenuTrigger(menuName, dispatched, elapsedMs(step));
        if (!dispatched) {
            return menuVerdict(menu, startedAt, true, false, false, false,
                ErrorCategory.INFRASTRUCTURE_ERROR,
                "The browser could not deliver the click to the configured menu button.");
        }

        step = System.nanoTime();
        boolean visibleAfter = navigation.awaitPanelVisible(menu.panelSelector());
        execution.awaitedMenuPanel(menuName, visibleAfter, elapsedMs(step));

        return menuVerdict(menu, startedAt, true, false, true, visibleAfter,
            visibleAfter ? null : ErrorCategory.WEBSITE_FINDING,
            visibleAfter
                ? "The configured menu panel was hidden before the click and visible after it."
                : "The configured menu panel did not become visible after the click.");
    }

    /**
     * Writes the contract 1.0 item and the contract 1.1 verdict for the menu case.
     *
     * <p>Metadata is four booleans. No selector is persisted anywhere: the customer-facing fields
     * carry the friendly label, because a selector is a locator and not a name.
     */
    private Result menuVerdict(
        WebsiteMenuConfig menu, Instant startedAt, boolean triggerFound, boolean panelVisibleBefore,
        boolean clicked, boolean panelVisibleAfter, ErrorCategory category, String actual
    ) {
        boolean opened = clicked && panelVisibleAfter && !panelVisibleBefore;
        TestedItem.Builder menuItem = TestedItem.builder("NAVIGATION", TestedItem.ItemType.LINK)
            .id(scope.itemId(NavigationExecutionEvidence.MENU_TEST_CASE_ID))
            .name(menu.displayName())
            .pageUrl(basePage)
            .target(menu.displayName())
            .action("Clicked the configured menu button once and waited for the configured panel to appear.")
            .expected("The configured menu panel becomes visible after one click.")
            .actual(actual)
            .status(opened ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
            .startedAt(startedAt.toString())
            .completedAt(Instant.now().toString())
            .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
            .meta("triggerFound", Boolean.toString(triggerFound))
            .meta("panelVisibleBefore", Boolean.toString(panelVisibleBefore))
            .meta("clicked", Boolean.toString(clicked))
            .meta("panelVisibleAfter", Boolean.toString(panelVisibleAfter));
        EvidenceRecorder.record(scope.enrich(menuItem).build());
        execution.assertedMenuOpened(menu.displayName(), opened, category, actual);
        return opened ? new Result(Outcome.PASSED, null) : new Result(Outcome.FAILED, actual);
    }

    /** Wall-clock milliseconds around one real browser call. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String displayName(String text, String href) {
        if (text != null && !text.isBlank()) return text.trim();
        return href == null || href.isBlank() ? "Unnamed link" : href.trim();
    }

    // --- what the caller gets back ------------------------------------------------------------

    /**
     * How a Navigation run ended.
     *
     * <p>{@code NOTHING_ELIGIBLE} is not a failure: a page with no safe same-site destination has
     * always been reported as skipped, which is what stops an untested page reading as a pass.
     */
    /**
     * How one Navigation entry point ended.
     *
     * <p>{@link #NOTHING_TO_CHECK} is distinct from {@link #NOTHING_ELIGIBLE}: there were
     * destinations to open, every one of them was opened, and not one could be verified — an
     * authentication challenge on every candidate being the case that forced it. Reporting that as
     * a pass would be the rule this project has enforced by hand since the SPA discovery defect:
     * a check that verified nothing is never reported as a pass.
     */
    public enum Outcome { PASSED, NOTHING_ELIGIBLE, NOTHING_TO_CHECK, FAILED }

    /** The outcome of one Navigation entry point, and the message the caller reports with it. */
    public record Result(Outcome outcome, String message) {

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }

        public boolean nothingEligible() {
            return outcome == Outcome.NOTHING_ELIGIBLE;
        }

        /** Whether destinations were opened and none of them could be verified. */
        public boolean skipped() {
            return outcome == Outcome.NOTHING_TO_CHECK;
        }
    }

    // --- the page work Navigation needs -------------------------------------------------------

    /** One anchor as this check describes it: a label and a destination, both already read. */
    record Anchor(String text, String href) { }

    /** What was found for the one configured menu-button selector. */
    record TriggerInspection(boolean selectorUsable, int matches, boolean displayed, boolean enabled) { }

    /**
     * The page work Navigation performs. Deliberately not a general abstraction: it exists so this
     * one module can be driven without a browser.
     */
    interface PageNavigation {

        boolean awaitRenderedContentTimedOut();

        /** Navigation-region anchors, falling back to every anchor. Never throws. */
        List<Anchor> discoverAnchors();

        /** How many elements currently match, for the discovery diagnostics. */
        int count(String cssSelector);

        void openUrl(String url);

        String currentUrl();

        String title();

        boolean hasLoadedDocument();

        /** Resolves the one configured trigger and remembers it for the click below. */
        TriggerInspection inspectTrigger(String triggerSelector);

        boolean anyVisible(String panelSelector);

        boolean awaitTriggerOwnsClickPoint();

        boolean clickTriggerOnce();

        boolean awaitPanelVisible(String panelSelector);
    }

    /** The production page work, over the page and driver the caller already has. */
    private static final class CommonPageNavigation implements PageNavigation {

        private final CommonPage page;
        private final WebDriver driver;
        private final Duration timeout;
        private final SafePointerClick safeClick;
        private WebElement resolvedTrigger;

        CommonPageNavigation(CommonPage page, WebDriver driver, Duration timeout) {
            this.page = page;
            this.driver = driver;
            this.timeout = timeout;
            this.safeClick = new SafePointerClick(driver);
        }

        @Override
        public boolean awaitRenderedContentTimedOut() {
            return page.awaitRenderedContent().timedOut();
        }

        @Override
        public List<Anchor> discoverAnchors() {
            List<WebElement> anchors = driver.findElements(By.cssSelector(NAV_SELECTOR));
            if (anchors.isEmpty()) {
                anchors = driver.findElements(By.cssSelector("a[href]"));
            }
            List<Anchor> discovered = new ArrayList<>();
            for (WebElement anchor : anchors) {
                try {
                    String label = anchor.getAccessibleName();
                    if (label == null || label.isBlank()) label = anchor.getText();
                    discovered.add(new Anchor(
                        label == null ? "" : label.trim(), anchor.getAttribute("href")));
                } catch (RuntimeException stale) {
                    // A detached element cannot be described honestly, so it is not recorded at all.
                }
            }
            return discovered;
        }

        @Override
        public int count(String cssSelector) {
            return page.count(cssSelector);
        }

        @Override
        public void openUrl(String url) {
            page.openUrl(url);
        }

        @Override
        public String currentUrl() {
            return page.getCurrentUrl();
        }

        @Override
        public String title() {
            return page.getTitle();
        }

        @Override
        public boolean hasLoadedDocument() {
            return page.hasLoadedDocument();
        }

        @Override
        public TriggerInspection inspectTrigger(String triggerSelector) {
            List<WebElement> triggers;
            try {
                triggers = driver.findElements(SelectorUtils.css(triggerSelector));
            } catch (RuntimeException invalidSelector) {
                resolvedTrigger = null;
                return new TriggerInspection(false, 0, false, false);
            }
            resolvedTrigger = triggers.size() == 1 ? triggers.getFirst() : null;
            boolean displayed = resolvedTrigger != null && safeBoolean(resolvedTrigger, true);
            boolean enabled = resolvedTrigger != null && safeBoolean(resolvedTrigger, false);
            return new TriggerInspection(true, triggers.size(), displayed, enabled);
        }

        @Override
        public boolean anyVisible(String cssSelector) {
            try {
                return driver.findElements(SelectorUtils.css(cssSelector)).stream()
                    .anyMatch(element -> {
                        try {
                            return element.isDisplayed();
                        } catch (RuntimeException stale) {
                            return false;
                        }
                    });
            } catch (RuntimeException unusable) {
                return false;
            }
        }

        @Override
        public boolean awaitTriggerOwnsClickPoint() {
            try {
                WaitUtils.wait(driver, timeout)
                    .until(ignored -> safeClick.ownsItsClickPoint(resolvedTrigger));
                return true;
            } catch (RuntimeException neverClickable) {
                return false;
            }
        }

        @Override
        public boolean clickTriggerOnce() {
            try {
                safeClick.clickOnce(resolvedTrigger);
                return true;
            } catch (RuntimeException clickFailure) {
                return false;
            }
        }

        @Override
        public boolean awaitPanelVisible(String panelSelector) {
            try {
                WaitUtils.wait(driver, timeout).until(ignored -> anyVisible(panelSelector));
                return true;
            } catch (RuntimeException neverAppeared) {
                return false;
            }
        }

        /** Reads one boolean from an element, treating an unreadable element as false. */
        private static boolean safeBoolean(WebElement element, boolean wantDisplayed) {
            try {
                return wantDisplayed ? element.isDisplayed() : element.isEnabled();
            } catch (RuntimeException unreadable) {
                return false;
            }
        }
    }
}
