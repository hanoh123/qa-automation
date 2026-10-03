package com.qanize.automation.checks.smoke;

import java.util.Objects;

import com.qanize.automation.checks.shared.HttpStatusClassification;
import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;


/**
 * Records what the Basic (Smoke) check actually did, as it does it.
 *
 * <p>One method per real action the check performs. There is deliberately no general-purpose
 * {@code step(...)} entry point: a caller that cannot name the action it performed should not be
 * able to record one. Nothing here can describe a click, a keystroke or an HTTP request, because
 * the Smoke check performs none of those.
 *
 * <p>Sequence numbers are assigned in call order, so the persisted timeline is chronological by
 * construction rather than by a later sort. Each call appends immediately: if the browser dies
 * mid-check, every step already taken survives.
 *
 * <p>This class holds no browser reference and performs no I/O of its own, so the whole Smoke
 * evidence shape is verifiable without a website.
 */
public final class SmokeExecutionEvidence {

    /** Matches the contract 1.0 tested-item id for the same page, so the two views line up. */
    public static final String TEST_CASE_ID = "smoke-page-1";

    private static final String CHECK_ID = "SMOKE";

    /**
     * Smoke records against one test case only, so the shared per-case counter is keyed on that
     * single id and numbers exactly as the plain field it replaces did. Unguarded, as before.
     */
    private final CheckStepRecorder steps = CheckStepRecorder.unguarded(CHECK_ID);

    /** Which page these steps belong to. Legacy by default, so existing callers are untouched. */
    private final EvidenceScope scope;

    /** The single-page recorder: identities exactly as this check has always written them. */
    public SmokeExecutionEvidence() {
        this(EvidenceScope.legacy());
    }

    /**
     * The recorder for one crawled page.
     *
     * <p>The scoped case id is what separates one page's steps from another's, so each page's
     * sequence starts at 1 and stays continuous instead of colliding on a shared id.
     */
    public SmokeExecutionEvidence(EvidenceScope scope) {
        // Explicit means explicit. Quietly treating null as legacy would turn a crawler wiring
        // mistake into silently colliding evidence across every page, which is precisely the
        // failure this scoping exists to prevent. A caller who wants legacy asks for it by name.
        this.scope = Objects.requireNonNull(
            scope, "scope must not be null; use SmokeExecutionEvidence() for the legacy scope");
    }

    /** The case these steps are numbered against: {@code smoke-page-1}, qualified by page. */
    public String testCaseId() {
        return scope.testCaseId(TEST_CASE_ID);
    }

    /** Increments first, so the first recorded step is 1 as the contract requires. */
    private ExecutionStep append(
        Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        return steps.append(
            testCaseId(), phase, method, action, targetLabel,
            expected, actual, status, durationMs, errorCategory);
    }

    /** {@code driver.get(url)} returned. Only called after the navigation really completed. */
    public ExecutionStep navigated(String requestedUrl, long durationMs) {
        return append(
            Phase.ACTION, ExecutionMethod.NAVIGATE,
            "Opened the submitted address in the browser.",
            requestedUrl,
            "The browser reaches the page-ready state for the submitted address.",
            "The browser finished loading the submitted address.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** {@code driver.getCurrentUrl()} returned. */
    public ExecutionStep readAddress(String requestedUrl, String finalUrl, long durationMs) {
        boolean present = finalUrl != null && !finalUrl.isBlank();
        boolean redirected = present && !requestedUrl.equals(finalUrl);
        return append(
            Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the address the browser ended up on.",
            requestedUrl,
            "The browser reports a non-blank address.",
            present
                ? (redirected ? "The browser is on " + finalUrl + " after a redirect."
                              : "The browser is on the submitted address.")
                : "The browser reported no address.",
            present ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, present ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** {@code driver.getTitle()} returned. */
    public ExecutionStep readTitle(String requestedUrl, String title, long durationMs) {
        boolean present = title != null && !title.isBlank();
        return append(
            Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the page title.",
            requestedUrl,
            "The page reports a non-blank title.",
            present ? "The title is \"" + title + "\"." : "The page reported no title.",
            present ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, present ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** {@code driver.getPageSource()} returned and was checked for content. */
    public ExecutionStep readContent(String requestedUrl, boolean loaded, long durationMs) {
        return append(
            Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the loaded document to confirm it is not empty.",
            requestedUrl,
            "The page returns a document with readable content.",
            loaded ? "The document contained readable content." : "The document was empty.",
            loaded ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, loaded ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /**
     * The browser threw before the check could finish reading the page.
     *
     * <p>Categorised as {@link ErrorCategory#INFRASTRUCTURE_ERROR}: a browser that cannot complete
     * an action has told us nothing about the website, and reporting it as a website finding is
     * exactly the confusion contract 1.1 exists to remove.
     */
    public ExecutionStep browserFailed(String requestedUrl, String sanitizedFailure, long durationMs) {
        return append(
            Phase.ACTION, ExecutionMethod.NAVIGATE,
            "Tried to open and read the submitted address.",
            requestedUrl,
            "The browser completes the request and returns a readable page.",
            "The browser could not complete the request: " + sanitizedFailure,
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /**
     * What the server answered for a page the browser rendered without a title.
     *
     * <p>Recorded only when that question was asked, which is only when a titleless document came
     * back. It is an {@code HTTP_REQUEST} because that is literally what it was, and it belongs to
     * the DISCOVERY phase because it establishes what the page is rather than testing it.
     *
     * <p>Never a website finding on its own: it is the fact the verdict below is built from.
     */
    public ExecutionStep readProtectedStatus(
        String requestedUrl, int statusCode, String classification
    ) {
        return append(
            Phase.DISCOVERY, ExecutionMethod.HTTP_REQUEST,
            "Asked the address what it answers, because the browser rendered no page title.",
            requestedUrl,
            "The status explains why the document has no title.",
            "The address answered HTTP " + statusCode + " (" + classification + ").",
            ExecutionStep.Status.PASSED, 0, null
        );
    }

    /**
     * The verdict the check is about to assert. Recorded before the assertion, never after it.
     *
     * <p>The boolean shape, for the callers whose answer genuinely is yes or no.
     */
    public ExecutionStep assertedUsablePage(String requestedUrl, boolean usable, String actual) {
        return assertedUsablePage(
            requestedUrl,
            usable ? HttpStatusClassification.SUCCESS : HttpStatusClassification.BROKEN,
            actual);
    }

    /**
     * The same verdict, told what the page turned out to be.
     *
     * <p>A protected or rate-limited page is recorded as a skip with the reason. Reporting it as a
     * failure is what put "the target page did not load as expected" on a report about a page that
     * loaded exactly as its owner intended.
     */
    public ExecutionStep assertedUsablePage(
        String requestedUrl, HttpStatusClassification classification, String actual
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
            Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that the submitted page loaded a usable document.",
            requestedUrl,
            "The page returns a document with a non-blank address and title.",
            actual,
            status, 0, category
        );
    }

    /** The configured expected-title comparison, only when one is configured. */
    public ExecutionStep assertedExpectedTitle(
        String requestedUrl, String expectedTitle, String actualTitle, boolean matched
    ) {
        return append(
            Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Compared the page title with the configured expected text.",
            requestedUrl,
            "The title contains \"" + expectedTitle + "\".",
            "The title is \"" + actualTitle + "\".",
            matched ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, matched ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** The configured expected-text search, only when one is configured. */
    public ExecutionStep searchedForExpectedText(
        String requestedUrl, String expectedText, boolean present, long durationMs
    ) {
        return append(
            Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Searched the loaded page for the configured expected text.",
            requestedUrl,
            "The page contains \"" + expectedText + "\".",
            present ? "The text was found." : "The text was not found.",
            present ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, present ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** How many steps this check has recorded so far. */
    public int recordedSteps() {
        return steps.recordedSteps(TEST_CASE_ID);
    }
}
