package com.qanize.automation.checks.navigation;

import java.util.Objects;

import com.qanize.automation.checks.shared.HttpStatusClassification;
import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;


/**
 * Records what the Navigation check actually did, as it does it.
 *
 * <p>Navigation has several concrete test cases in one check — one per destination it follows —
 * so unlike Smoke this keeps a sequence counter <em>per test case</em>. The ids are the ones the
 * check already allocates for contract 1.0 ({@code nav-1}, {@code nav-skipped-1}, and
 * {@code navigation-discovery}), so both views of the same run line up exactly.
 *
 * <p>The check opens destinations with {@code driver.get}, never by clicking a link. There is
 * therefore no {@code CLICK} entry point here at all: the shape of this class is what stops the
 * report from claiming an interaction that did not happen. There <em>is</em> a real wait — the
 * page-content readiness poll — so {@code WAIT} is available, and only for that.
 *
 * <p>Destination URLs come from the site's own markup rather than from the user, so every one is
 * passed through {@link SensitiveDataFilter#sanitizeUrl(String)} before it is persisted.
 */
public final class NavigationExecutionEvidence {

    /** Matches the contract 1.0 id used when nothing eligible is discovered. */
    public static final String DISCOVERY_TEST_CASE_ID = "navigation-discovery";

    /** The single configured click-to-open menu. Absent entirely when no menu is configured. */
    public static final String MENU_TEST_CASE_ID = "nav-menu-1";

    private static final String CHECK_ID = "NAVIGATION";

    /** Unguarded, as before: a reporting failure here propagates rather than being swallowed. */
    /** Which page these steps belong to. Legacy by default, so existing callers are untouched. */
    private final EvidenceScope scope;

    private final CheckStepRecorder steps = CheckStepRecorder.unguarded(CHECK_ID);

    /** The single-page recorder: identities exactly as this check has always written them. */
    public NavigationExecutionEvidence() {
        this(EvidenceScope.legacy());
    }

    /**
     * The recorder for one crawled page.
     *
     * @throws NullPointerException when the scope is null; ask for the legacy scope by name
     */
    public NavigationExecutionEvidence(EvidenceScope scope) {
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use NavigationExecutionEvidence() for the legacy scope");
    }

    /** One of this check's case ids, qualified by page. */
    public String testCaseId(String moduleTestCaseId) {
        return scope.testCaseId(moduleTestCaseId);
    }

    private ExecutionStep append(
        String testCaseId, Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        // Scoped here, once, so no caller has to remember which page it is recording for.
        return steps.append(
            testCaseId(testCaseId), phase, method, action, targetLabel,
            expected, actual, status, durationMs, errorCategory);
    }

    // --- discovery ------------------------------------------------------------------------

    /** {@code driver.get(startPage)} returned. */
    public ExecutionStep openedStartPage(String startPage, long durationMs) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Opened the submitted page to look for navigation links.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The submitted page loads so its navigation can be read.",
            "The submitted page finished loading.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The page-content readiness poll finished. This is a real wait, not a decorative one: a
     * client-rendered page has no links at readyState complete.
     */
    public ExecutionStep waitedForRenderedContent(String startPage, boolean timedOut, long durationMs) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the page to render something that can be inspected.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The page puts at least one link or form into the document.",
            timedOut
                ? "The page still had no links or forms when the wait expired."
                : "The page rendered content that could be inspected.",
            timedOut ? ExecutionStep.Status.FAILED : ExecutionStep.Status.PASSED,
            durationMs, timedOut ? ErrorCategory.NOT_APPLICABLE : null
        );
    }

    /** The anchor query ran and was classified. Counts are the real ones, never rounded. */
    public ExecutionStep discoveredCandidates(
        String startPage, int anchors, int candidates, int eligible, long durationMs
    ) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Read the page's navigation links and decided which are safe to follow.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "At least one safe, same-site navigation link is available to follow.",
            "Found " + anchors + " link element(s), classified " + candidates
                + " candidate(s), of which " + eligible + " were eligible to follow.",
            eligible > 0 ? ExecutionStep.Status.PASSED : ExecutionStep.Status.SKIPPED,
            durationMs, eligible > 0 ? null : ErrorCategory.NOT_APPLICABLE
        );
    }

    /** Nothing eligible was found, so the check stops. Recorded as skipped, never as a pass. */
    public ExecutionStep nothingEligibleToFollow(String startPage, String reason) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked whether there was any navigation link to follow.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "At least one safe, same-site navigation link is available to follow.",
            reason, ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE
        );
    }

    /**
     * A discovered link was reviewed against the follow rules and rejected.
     *
     * <p>No browser action is claimed, because none happened: this is the classification decision
     * itself, and the target was never opened.
     */
    public ExecutionStep excludedCandidate(
        String testCaseId, String label, String rawTarget, String reason
    ) {
        return append(
            testCaseId, Phase.DISCOVERY, ExecutionMethod.ASSERT,
            "Reviewed the navigation link and did not follow it.",
            label,
            "Only safe, same-site navigation links are followed.",
            reason + " Target: " + SensitiveDataFilter.sanitizeUrl(rawTarget) + ".",
            ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE
        );
    }

    /** The case id for the page the crawler opened directly. Distinct from every legacy id. */
    public static final String CRAWLER_PAGE_TEST_CASE_ID = "nav-page-reached";

    /**
     * The crawler opened this page directly; Navigation performed no browser action at all.
     *
     * <p>In crawler mode the queue owns traversal: it had already opened this address before this
     * step was recorded. Saying so plainly is the point of a separate method — reusing
     * {@link #navigatedToDestination} would credit Navigation with a navigation it did not make,
     * and nothing here may ever read as a click, because no link was clicked.
     *
     * <p><strong>Discovery, not action.</strong> The sentence said "Navigation followed no link"
     * while the step itself was recorded as {@code ACTION} / {@code NAVIGATE} — which the results
     * view renders as "Action · Open page", attributing the crawler's open to this check in the
     * one place a reader looks for what a check did. The phase and method now match the sentence:
     * this step records how a page came to be reached, and {@code ASSERT} is the only thing
     * Navigation itself performed.
     *
     * @param destination    the address the crawler opened
     * @param discoveredFrom the page it was found on, or null for the submitted start page
     * @param durationMs     how long the crawler's own open took
     */
    public ExecutionStep crawlerOpenedPage(
        String destination, String discoveredFrom, long durationMs
    ) {
        boolean startPage = discoveredFrom == null || discoveredFrom.isBlank();
        return append(
            CRAWLER_PAGE_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.ASSERT,
            "Recorded the page the crawler opened. Navigation followed no link to reach it.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The page is reachable at its own address.",
            startPage
                ? "The submitted start page was opened."
                : "Reached from " + SensitiveDataFilter.sanitizeUrl(discoveredFrom) + ".",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The crawler could not open this page, so there is nothing for Navigation to report.
     *
     * @param discoveredFrom the page it was found on, or null for the submitted start page
     */
    public ExecutionStep crawlerCouldNotOpenPage(
        String destination, String discoveredFrom, String sanitizedFailure, long durationMs
    ) {
        boolean startPage = discoveredFrom == null || discoveredFrom.isBlank();
        return append(
            // Same reason as the successful open above: the crawler navigated, Navigation
            // recorded. A failed open is still not this check's action.
            CRAWLER_PAGE_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.ASSERT,
            "Recorded the page the crawler could not open.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The page is reachable at its own address.",
            (startPage
                ? "The submitted start page could not be opened."
                : "The page discovered on " + SensitiveDataFilter.sanitizeUrl(discoveredFrom)
                    + " could not be opened.")
                + (sanitizedFailure == null || sanitizedFailure.isBlank()
                    ? "" : " " + sanitizedFailure),
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING
        );
    }

    // --- one destination ------------------------------------------------------------------

    /** {@code driver.get(destination)} returned. Direct navigation — the check never clicks. */
    public ExecutionStep navigatedToDestination(String testCaseId, String destination, long durationMs) {
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.NAVIGATE,
            "Opened the navigation destination directly in the browser.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The browser reaches the page-ready state for the destination.",
            "The browser finished loading the destination.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** {@code driver.getCurrentUrl()} returned. */
    public ExecutionStep readAddress(
        String testCaseId, String destination, String finalUrl, boolean redirected, long durationMs
    ) {
        boolean present = finalUrl != null && !finalUrl.isBlank();
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the address the browser ended up on.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The browser reports a non-blank address.",
            present
                ? (redirected
                    ? "The browser is on " + SensitiveDataFilter.sanitizeUrl(finalUrl) + " after a redirect."
                    : "The browser is on the requested destination.")
                : "The browser reported no address.",
            present ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, present ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** {@code driver.getTitle()} returned. A blank title is not a failure for this check. */
    public ExecutionStep readTitle(String testCaseId, String destination, String title, long durationMs) {
        boolean present = title != null && !title.isBlank();
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the destination's page title.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The destination reports its title, if it has one.",
            present ? "The title is \"" + title + "\"." : "The destination reported no title.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** {@code driver.getPageSource()} returned and was checked for content. */
    public ExecutionStep readContent(
        String testCaseId, String destination, boolean usable, long durationMs
    ) {
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the destination document to confirm it is usable.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The destination returns a document with readable content.",
            usable ? "The document contained readable content." : "The document was empty.",
            usable ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, usable ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** The browser threw while opening or reading the destination. */
    public ExecutionStep destinationFailed(
        String testCaseId, String destination, String sanitizedFailure, long durationMs
    ) {
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.NAVIGATE,
            "Tried to open and read the navigation destination.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The browser completes the request and returns a usable page.",
            "The browser could not open the destination: " + sanitizedFailure,
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /**
     * What the server answered for a destination the browser rendered without a title.
     *
     * <p>Recorded only when that question was asked, which is only when a titleless document came
     * back. It is an {@code HTTP_REQUEST} because that is literally what it was, and it belongs to
     * the DISCOVERY phase because it establishes what the destination is rather than testing it.
     *
     * <p>Never a website finding on its own: it is the fact the verdict below is built from.
     */
    public ExecutionStep readDestinationStatus(
        String testCaseId, String destination, int statusCode, String classification
    ) {
        return append(
            testCaseId, Phase.DISCOVERY, ExecutionMethod.HTTP_REQUEST,
            "Asked the destination's address what it answers, because it rendered no page title.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The status explains why the destination has no title.",
            "The address answered HTTP " + statusCode + " (" + classification + ").",
            ExecutionStep.Status.PASSED, 0, null
        );
    }

    /**
     * The verdict for this destination, recorded before the check's assertion runs.
     *
     * <p>The boolean shape, for the callers whose answer genuinely is yes or no.
     */
    public ExecutionStep assertedDestinationUsable(
        String testCaseId, String destination, boolean usable, String actual
    ) {
        return assertedDestinationUsable(
            testCaseId, destination,
            usable ? HttpStatusClassification.SUCCESS : HttpStatusClassification.BROKEN,
            actual);
    }

    /**
     * The same verdict, told what the destination turned out to be.
     *
     * <p>A protected or rate-limited destination is recorded as a skip with the reason. Reporting
     * it as a pass is what let an authentication challenge read as "loaded a usable page";
     * reporting it as a failure would blame the website for answering correctly.
     */
    public ExecutionStep assertedDestinationUsable(
        String testCaseId, String destination, HttpStatusClassification classification, String actual
    ) {
        ExecutionStep.Status status = switch (classification) {
            case SUCCESS -> ExecutionStep.Status.PASSED;
            case AUTH_REQUIRED, RATE_LIMITED -> ExecutionStep.Status.SKIPPED;
            case BROKEN, NO_RESPONSE -> ExecutionStep.Status.FAILED;
        };
        ErrorCategory category = switch (classification) {
            case SUCCESS -> null;
            case AUTH_REQUIRED, RATE_LIMITED -> ErrorCategory.NOT_APPLICABLE;
            case BROKEN, NO_RESPONSE -> ErrorCategory.WEBSITE_FINDING;
        };
        return append(
            testCaseId, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that the navigation destination loaded a usable page.",
            SensitiveDataFilter.sanitizeUrl(destination),
            "The destination loads a usable page.",
            actual,
            status, 0, category
        );
    }

    /** The check returns to the start page before trying the next destination. */
    public ExecutionStep returnedToStartPage(String testCaseId, String startPage, long durationMs) {
        return append(
            testCaseId, Phase.CLEANUP, ExecutionMethod.NAVIGATE,
            "Returned to the submitted page before trying the next destination.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The browser is back on the submitted page.",
            "The browser returned to the submitted page.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** How many steps have been recorded for one test case. */
    public int recordedSteps(String testCaseId) {
        return steps.recordedSteps(testCaseId);
    }
    // --- configured menu ------------------------------------------------------------------
    //
    // These steps exist only when a menu is configured. Nothing here names a selector: the
    // customer-facing label is used instead, because a selector is a locator rather than a name
    // and has no place in an artifact a person reads.

    /** {@code page.openUrl(startPage)} returned, for the menu case rather than discovery. */
    public ExecutionStep openedMenuStartPage(String startPage, long durationMs) {
        return append(
            MENU_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Opened the submitted page to test the configured menu.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The submitted page loads so the menu can be opened.",
            "The submitted page finished loading.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** The shared rendered-content readiness wait returned. A timeout is not itself a failure. */
    public ExecutionStep awaitedMenuPageReady(String startPage, boolean timedOut, long durationMs) {
        return append(
            MENU_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the page to finish rendering before looking for the menu button.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The page reaches its rendered-content condition.",
            timedOut
                ? "The page did not reach the rendered-content condition within the timeout."
                : "The page reported rendered content.",
            timedOut ? ExecutionStep.Status.SKIPPED : ExecutionStep.Status.PASSED,
            durationMs, timedOut ? ErrorCategory.NOT_APPLICABLE : null
        );
    }

    /**
     * {@code findElements(trigger)} returned and the single match was inspected.
     *
     * <p>Exactly one usable match is required. Zero means the configured button is not there;
     * more than one means the configuration does not say which button to click, and guessing would
     * mean clicking something nobody chose.
     */
    public ExecutionStep inspectedMenuTrigger(
        String menuName, int matchCount, boolean displayed, boolean enabled, long durationMs
    ) {
        boolean usable = matchCount == 1 && displayed && enabled;
        String actual;
        if (matchCount == 0) {
            actual = "No element matched the configured menu button.";
        } else if (matchCount > 1) {
            actual = matchCount + " elements matched the configured menu button, so it is ambiguous.";
        } else if (!displayed) {
            actual = "The configured menu button is present but not visible.";
        } else if (!enabled) {
            actual = "The configured menu button is visible but disabled.";
        } else {
            actual = "Found one visible, enabled menu button.";
        }
        return append(
            MENU_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for the configured menu button.",
            menuName,
            "Exactly one visible, enabled element matches the configured menu button.",
            actual,
            usable ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, usable ? null : ErrorCategory.CONFIGURATION_ERROR
        );
    }

    /**
     * The panel was inspected before anything was clicked.
     *
     * <p>A panel that is already visible makes the whole check meaningless: there is no
     * hidden-to-visible transition left to observe, so a pass would prove nothing. That is a
     * configuration problem, and the click is not performed.
     */
    public ExecutionStep inspectedMenuPanelBaseline(
        String menuName, boolean visibleBefore, long durationMs
    ) {
        return append(
            MENU_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Checked whether the configured menu panel was already visible before clicking.",
            menuName,
            "The configured menu panel is not visible yet, so opening it can be observed.",
            visibleBefore
                ? "The configured menu panel was already visible, so opening it cannot be observed."
                : "The configured menu panel was not visible.",
            visibleBefore ? ExecutionStep.Status.FAILED : ExecutionStep.Status.PASSED,
            durationMs, visibleBefore ? ErrorCategory.CONFIGURATION_ERROR : null
        );
    }

    /** The bounded wait for the button to own the point a pointer would land on returned. */
    public ExecutionStep awaitedMenuTriggerClickPoint(
        String menuName, boolean owned, long durationMs
    ) {
        return append(
            MENU_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the menu button to be the thing a click would actually reach.",
            menuName,
            "The menu button owns its own click point.",
            owned
                ? "The menu button owns its click point."
                : "The menu button never became safely clickable, so it was not clicked.",
            owned ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, owned ? null : ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** Exactly one pointer click was dispatched. Recorded whether or not it threw. */
    public ExecutionStep clickedMenuTrigger(String menuName, boolean dispatched, long durationMs) {
        return append(
            MENU_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.CLICK,
            "Clicked the configured menu button once.",
            menuName,
            "The click reaches the menu button.",
            dispatched
                ? "The click was delivered to the menu button."
                : "The browser could not deliver the click.",
            dispatched ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, dispatched ? null : ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** The bounded wait for the panel to become visible returned. */
    public ExecutionStep awaitedMenuPanel(String menuName, boolean visible, long durationMs) {
        return append(
            MENU_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the configured menu panel to become visible.",
            menuName,
            "The configured menu panel becomes visible after one click.",
            visible
                ? "The configured menu panel became visible."
                : "The configured menu panel did not become visible within the timeout.",
            visible ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            // A passing step carries no category: that is the producer rule the timeline reader
            // enforces, and breaking it would make the whole run's timeline unreadable.
            durationMs, visible ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /**
     * The verdict, written before the assertion so it survives a failing assertion.
     *
     * <p>It claims only that the panel became visible. It does not claim that a menu item works,
     * that a destination loaded, or that navigation succeeded — the check never tried any of those.
     */
    public ExecutionStep assertedMenuOpened(
        String menuName, boolean opened, ErrorCategory category, String actual
    ) {
        return append(
            MENU_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Compared the menu panel before and after the single click.",
            menuName,
            "The configured menu panel was hidden before the click and visible after it.",
            actual,
            opened ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, opened ? null : category
        );
    }

}
