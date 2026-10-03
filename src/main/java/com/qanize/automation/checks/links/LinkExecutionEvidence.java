package com.qanize.automation.checks.links;

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
 * Records what the Links check actually did, as it does it.
 *
 * <p>Links is the first check that never uses the browser to test its subject: it discovers
 * anchors with Selenium, then validates each destination with a plain HTTP GET. That distinction
 * is the whole point of recording it — a 1.0 report says "the link passed", which reads the same
 * whether a browser opened the page or a request returned 200.
 *
 * <p>There is therefore no navigation or click entry point for a tested link at all. The only
 * {@code NAVIGATE} this class can describe is opening the submitted page during discovery, and the
 * only {@code HTTP_REQUEST} is the request the check already makes. Nothing here issues a request
 * of its own: every value recorded comes from the response the check already holds.
 *
 * <p>Link targets come from the site's own markup, so each is passed through
 * {@link SensitiveDataFilter#sanitizeUrl(String)} before it is persisted.
 */
public final class LinkExecutionEvidence {

    /** Matches the contract 1.0 id used when nothing eligible is discovered. */
    public static final String DISCOVERY_TEST_CASE_ID = "links-discovery";

    /** The status at or above which the existing check treats a response as a failure. */
    public static final int FAILURE_STATUS_THRESHOLD = 400;

    private static final String CHECK_ID = "LINKS";

    /**
     * Records one step, and cannot throw.
     *
     * <p>The check records the HTTP result <em>inside</em> its own {@code catch (Exception)}
     * block, so anything escaping from here would be caught there and reported as a failed
     * request — turning a working link into a false finding because of a reporting bug. Both the
     * construction and the write are therefore contained: a reporting problem costs one step of
     * evidence and never the result.
     */
    private final CheckStepRecorder steps =
        CheckStepRecorder.guarded(CHECK_ID, "link-step-record-failed");

    /** Which page these steps belong to. Legacy by default, so existing callers are untouched. */
    private final EvidenceScope scope;

    /** The single-page recorder: identities exactly as this check has always written them. */
    public LinkExecutionEvidence() {
        this(EvidenceScope.legacy());
    }

    /**
     * The recorder for one crawled page.
     *
     * <p>Every case id this check uses — discovery and each numbered link — is qualified by page,
     * so page 2's {@code link-1} is a different case from page 1's and each begins at sequence 1.
     */
    public LinkExecutionEvidence(EvidenceScope scope) {
        // Explicit means explicit; see SmokeExecutionEvidence for why null is refused here.
        this.scope = Objects.requireNonNull(
            scope, "scope must not be null; use LinkExecutionEvidence() for the legacy scope");
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

    // --- discovery ---------------------------------------------------------------------------

    /** {@code driver.get(startPage)} returned. The browser is used only to find the links. */
    public ExecutionStep openedStartPage(String startPage, long durationMs) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Opened the submitted page to collect its links.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The submitted page loads so its links can be read.",
            "The submitted page finished loading.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** The real page-content readiness poll. A client-rendered page has no links before it. */
    public ExecutionStep waitedForRenderedContent(String startPage, boolean timedOut, long durationMs) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the page to render its links.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "The page puts at least one link or form into the document.",
            timedOut
                ? "The page still had no links or forms when the wait expired."
                : "The page rendered content that could be inspected.",
            timedOut ? ExecutionStep.Status.FAILED : ExecutionStep.Status.PASSED,
            durationMs, timedOut ? ErrorCategory.NOT_APPLICABLE : null
        );
    }

    /**
     * The anchors were read and classified.
     *
     * <p>{@code limited} is how many eligible links the configured maximum left out, so a run that
     * validated ten of forty links says so instead of implying the site has ten.
     */
    public ExecutionStep discoveredLinks(
        String startPage, int anchors, int candidates, int eligible, int limited, long durationMs
    ) {
        String limitNote = limited > 0
            ? " " + limited + " further eligible link(s) were not requested because of the configured limit."
            : "";
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Read the page's links and decided which are safe to request.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "At least one safe, same-site link is available to request.",
            "Found " + anchors + " link element(s), classified " + candidates
                + " candidate(s), of which " + eligible + " will be requested." + limitNote,
            eligible > 0 ? ExecutionStep.Status.PASSED : ExecutionStep.Status.SKIPPED,
            durationMs, eligible > 0 ? null : ErrorCategory.NOT_APPLICABLE
        );
    }

    /** Nothing eligible was found, so the check stops. Never recorded as a pass. */
    public ExecutionStep nothingEligibleToRequest(String startPage, String reason) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked whether there was any same-site link to request.",
            SensitiveDataFilter.sanitizeUrl(startPage),
            "At least one safe, same-site link is available to request.",
            reason, ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE
        );
    }

    /**
     * A discovered link was reviewed and not requested.
     *
     * <p>{@code linkType} is the check's own classification — external, email, telephone, anchor,
     * download or script — so the report says why it was left out rather than implying it failed.
     * No request is claimed, because none was made.
     */
    public ExecutionStep excludedLink(
        String testCaseId, String label, String rawTarget, String linkType, String reason
    ) {
        String safeTarget = SensitiveDataFilter.sanitizeUrl(rawTarget);
        return append(
            testCaseId, Phase.DISCOVERY, ExecutionMethod.ASSERT,
            "Reviewed the link and did not request it.",
            label,
            "Only safe, same-site links are requested.",
            // Missing detail is left out rather than written as the word "null", which would read
            // like an observation about the link.
            (reason == null || reason.isBlank() ? "The link was not eligible." : reason)
                + (linkType == null || linkType.isBlank() ? "" : " Classified as " + linkType + ".")
                + (safeTarget == null ? "" : " Target: " + safeTarget + "."),
            ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE
        );
    }

    // --- one link ----------------------------------------------------------------------------

    /**
     * The HTTP request the check already performed returned a response.
     *
     * <p>{@code finalUrl} comes from the response object the check already holds, so a redirect is
     * reported only when the client genuinely followed one. No extra request is made for evidence.
     *
     * @param statusCode the real status returned, classified by {@link HttpStatusClassification}
     */
    public ExecutionStep requestedLink(
        String testCaseId, String link, int statusCode, String finalUrl, long durationMs
    ) {
        HttpStatusClassification classification = HttpStatusClassification.of(statusCode);
        boolean redirected = finalUrl != null && !finalUrl.isBlank() && !finalUrl.equals(link);
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.HTTP_REQUEST,
            "Requested the link over HTTP with a GET and read the response status.",
            SensitiveDataFilter.sanitizeUrl(link),
            EXPECTED_RESPONSE,
            "Responded with HTTP " + statusCode + "."
                + (classification.unverified() ? " " + classification.reason(statusCode) : "")
                + (redirected
                    ? " The request was redirected to " + SensitiveDataFilter.sanitizeUrl(finalUrl) + "."
                    : ""),
            stepStatus(classification),
            durationMs, errorCategory(classification)
        );
    }

    /**
     * What one classification means for a recorded step.
     *
     * <p>A protected or rate-limited destination answered, and the check still learned nothing
     * about the page behind that answer — so the step is skipped, exactly as a link that was never
     * eligible to be requested is. Only a broken destination is a website finding.
     */
    private static ExecutionStep.Status stepStatus(HttpStatusClassification classification) {
        return switch (classification) {
            case SUCCESS -> ExecutionStep.Status.PASSED;
            case AUTH_REQUIRED, RATE_LIMITED -> ExecutionStep.Status.SKIPPED;
            case BROKEN, NO_RESPONSE -> ExecutionStep.Status.FAILED;
        };
    }

    private static ErrorCategory errorCategory(HttpStatusClassification classification) {
        return switch (classification) {
            case SUCCESS -> null;
            case AUTH_REQUIRED, RATE_LIMITED -> ErrorCategory.NOT_APPLICABLE;
            case BROKEN -> ErrorCategory.WEBSITE_FINDING;
            case NO_RESPONSE -> ErrorCategory.INFRASTRUCTURE_ERROR;
        };
    }

    /**
     * What every link step says it was looking for.
     *
     * <p>It used to be "a status below 400", which is the sentence that made a 401 read as a
     * failure to meet the expectation. The expectation was never really the number: it is that the
     * destination is reachable, or says plainly why it cannot be checked.
     */
    private static final String EXPECTED_RESPONSE =
        "The destination responds, or explains why it cannot be checked.";

    /**
     * The request could not be completed at all — timeout, DNS, TLS or connection failure.
     *
     * <p>Categorised as {@link ErrorCategory#INFRASTRUCTURE_ERROR}, never as a website finding: no
     * HTTP response was ever received, so nothing has been established about the destination.
     */
    public ExecutionStep requestFailed(
        String testCaseId, String link, String sanitizedFailure, long durationMs
    ) {
        return append(
            testCaseId, Phase.ACTION, ExecutionMethod.HTTP_REQUEST,
            "Tried to request the link over HTTP.",
            SensitiveDataFilter.sanitizeUrl(link),
            "The destination responds with a status below " + FAILURE_STATUS_THRESHOLD + ".",
            "The request could not be completed: " + sanitizedFailure,
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /**
     * An eligible destination the run deliberately did not request.
     *
     * <p>Not an exclusion: the link passed every eligibility rule and would have been requested by
     * any other run. What stopped it is the run's own inability to ask as the visitor the customer
     * signed in as — so asking anyway would have produced an answer about a different website.
     *
     * <p>{@link ErrorCategory#NOT_APPLICABLE}, because nothing was established about the
     * destination at all. Categorising this as a website finding would be the original bug written
     * down more politely.
     */
    public ExecutionStep validationNotPerformed(String testCaseId, String link, String reason) {
        return append(
            testCaseId, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Reviewed the link and did not request it.",
            SensitiveDataFilter.sanitizeUrl(link),
            "The destination responds with a status below " + FAILURE_STATUS_THRESHOLD + ".",
            reason == null || reason.isBlank()
                ? "HTTP validation was not performed for this run." : reason,
            ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE
        );
    }

    /**
     * The verdict for this link, recorded before the check's terminal assertion.
     *
     * <p>The boolean shape, kept for the callers that genuinely have only two answers. A
     * {@code false} here means a broken destination, which is what it has always meant.
     */
    public ExecutionStep assertedLinkUsable(
        String testCaseId, String link, boolean ok, String actual
    ) {
        return assertedLinkUsable(
            testCaseId, link,
            ok ? HttpStatusClassification.SUCCESS : HttpStatusClassification.BROKEN,
            actual);
    }

    /** The same verdict, with the three answers a classified status actually has. */
    public ExecutionStep assertedLinkUsable(
        String testCaseId, String link, HttpStatusClassification classification, String actual
    ) {
        return append(
            testCaseId, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that the link responded successfully.",
            SensitiveDataFilter.sanitizeUrl(link),
            EXPECTED_RESPONSE,
            actual,
            stepStatus(classification),
            0, errorCategory(classification)
        );
    }

    /** How many steps have been recorded for one test case. */
    public int recordedSteps(String testCaseId) {
        return steps.recordedSteps(testCaseId);
    }
}
