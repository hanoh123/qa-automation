package com.qanize.automation.checks.search;

import java.util.Objects;

import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;


/**
 * Records what the Search check actually did, as it does it.
 *
 * <p>Search is the first check that genuinely edits the page, so it is the first that may emit
 * {@code TYPE} and {@code CLICK}. It is also the check whose result is easiest to over-read: what
 * it proves is narrow, and the wording here is deliberately narrower than the check's name.
 *
 * <p><strong>What the check establishes.</strong> {@code BasePage.typeIfVisible} locates elements
 * matching the configured selector, checks the first is displayed, then calls {@code clear()} and
 * {@code sendKeys(term)}. It never reads the field back, so the strongest honest claim is that the
 * browser sent the term to the field without error — not that the field holds it. When a submit
 * control is configured, {@code clickIfVisible} clicks it; the check never verifies that a search
 * ran, that the page navigated or that anything changed. Only when a results selector is
 * configured does a real wait check that an element became visible, and even then nothing is read
 * about what the results contain.
 *
 * <p>There is therefore no entry point here for pressing Enter, submitting a form, navigating
 * after a search, or describing result relevance, ordering, filters or pagination — because the
 * check does none of those.
 */
public final class SearchExecutionEvidence {

    /** The contract 1.0 ids this check already allocates. They are reused, never replaced. */
    public static final String INPUT_TEST_CASE_ID = "search-input";
    public static final String SUBMIT_TEST_CASE_ID = "search-submit";
    public static final String RESULTS_TEST_CASE_ID = "search-results";

    /** Selectors and terms are labels, not prose; the recorder caps again at its own boundary. */
    static final int MAX_STRUCTURAL_TEXT = 120;

    private static final String CHECK_ID = "SEARCH";

    /** Guarded: persistence must never decide the Search result. */
    private final CheckStepRecorder steps =
        CheckStepRecorder.guarded(CHECK_ID, "search-step-record-failed");

    /** Which page these steps belong to. Legacy by default, so existing callers are untouched. */
    private final EvidenceScope scope;

    /** The single-page recorder: identities exactly as this check has always written them. */
    public SearchExecutionEvidence() {
        this(EvidenceScope.legacy());
    }

    /**
     * The recorder for one crawled page.
     *
     * @throws NullPointerException when the scope is null; ask for the legacy scope by name
     */
    public SearchExecutionEvidence(EvidenceScope scope) {
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use SearchExecutionEvidence() for the legacy scope");
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

    /** Keeps a structural attribute short and single-line. Never applied to a page's content. */
    static String structural(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.replaceAll("[\\r\\n]+", " ").trim();
        return trimmed.length() <= MAX_STRUCTURAL_TEXT
            ? trimmed
            : trimmed.substring(0, MAX_STRUCTURAL_TEXT) + "…";
    }

    // --- opening the page -----------------------------------------------------------------------

    /** {@code driver.get(pageUrl)} returned. The only navigation this check performs. */
    public ExecutionStep openedStartPage(String pageUrl, long durationMs) {
        return append(
            INPUT_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Opened the submitted page to find the configured search field.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "The submitted page loads so its search field can be used.",
            "The submitted page finished loading.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The browser threw while opening the submitted page.
     *
     * <p>Takes the exception rather than a pre-sanitized string so that every part of recording —
     * sanitizing included — happens behind the guard in {@link #append}. The caller rethrows the
     * original exception immediately afterwards, and nothing on this path may throw, because a
     * reporting bug must never replace the browser failure the caller needs to see.
     */
    public ExecutionStep startPageOpenFailed(String pageUrl, Throwable browserFailure, long durationMs) {
        return append(
            INPUT_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Tried to open the submitted page to find the configured search field.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "The submitted page loads so its search field can be used.",
            "The browser could not open the submitted page: " + describe(browserFailure)
                + " The search was not attempted.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** Sanitizes without being able to throw; recording must not be able to mask the real failure. */
    private static String describe(Throwable failure) {
        try {
            String sanitized = SensitiveDataFilter.sanitizeMessage(String.valueOf(failure));
            return sanitized == null || sanitized.isBlank() ? "no detail available." : sanitized;
        } catch (RuntimeException unusable) {
            return "no detail available.";
        }
    }

    // --- the search field -----------------------------------------------------------------------

    /** Nothing usable matched. No text was entered, so no typing step follows. */
    public ExecutionStep searchFieldNotFound(String inputSelector, String pageUrl, long durationMs) {
        return append(
            INPUT_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for the configured search field.",
            structural(inputSelector),
            "A visible element matches the configured search-field selector.",
            "No visible element matched the selector on "
                + SensitiveDataFilter.sanitizeUrl(pageUrl) + ". Nothing was typed.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING
        );
    }

    /**
     * The composite locate-and-type call succeeded: one step, one duration.
     *
     * <p>{@code typeIfVisible} finds the elements, checks the first is displayed, clears it and
     * sends the term inside a single call. There is exactly one timing boundary around all of
     * that, so exactly one timed step is emitted. Publishing a separate discovery step would hand
     * the same measured interval to two records, and any later aggregation — Contract 1.1 totals
     * or a Run Timeline — would count the work twice.
     *
     * <p>Wording stops exactly where the check stops: {@code sendKeys} returned without error. The
     * field's value is never read back, so the evidence must not say the field "holds" the term,
     * and must not suggest a search ran.
     */
    public ExecutionStep typedSearchTerm(String inputSelector, String term, long durationMs) {
        return append(
            INPUT_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.TYPE,
            "Found the first displayed match for the configured selector, cleared it and sent the"
                + " configured search term to it.",
            structural(inputSelector),
            "The search field accepts the configured term without error.",
            "Sent the term \"" + structural(term) + "\" to the field. The field's value was not read"
                + " back, and no search was submitted by this step. The duration covers locating,"
                + " clearing and typing together, because they happen in one call.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The browser threw while locating or editing the field.
     *
     * <p>Deliberately recorded as an inspection rather than a {@code TYPE}: the operation locates
     * before it edits, and which half failed is not knowable from what the check catches. Claiming
     * a keystroke that may never have happened would be worse than saying so.
     */
    public ExecutionStep searchFieldInteractionFailed(
        String inputSelector, String sanitizedFailure, long durationMs
    ) {
        return append(
            INPUT_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Tried to locate the configured search field and send the term to it.",
            structural(inputSelector),
            "The search field accepts the configured term without error.",
            "The browser could not complete the operation: " + sanitizedFailure
                + " Whether the field was found or edited is not known.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** The verdict for the field, recorded before the check's assertion can end the test. */
    public ExecutionStep assertedFieldAcceptedTerm(String inputSelector, boolean typed) {
        return append(
            INPUT_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that the configured search field was visible and accepted the term.",
            structural(inputSelector),
            "The search field is visible and accepts the search term.",
            typed
                ? "The term was sent to the field. Whether a search ran was not tested by this check."
                : "No visible, editable field matched the configured selector.",
            typed ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, typed ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    // --- the submit control (only when one is configured) ------------------------------------------

    /**
     * The configured submit control was clicked.
     *
     * <p>A real {@code element.click()} happened. What follows it is not observed: the check does
     * not wait, does not navigate and does not verify that a search was performed.
     */
    public ExecutionStep clickedSubmitControl(String submitSelector, long durationMs) {
        return append(
            SUBMIT_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.CLICK,
            "Clicked the configured search submit control.",
            structural(submitSelector),
            "The submit control is visible and clickable.",
            "The control was clicked. Whether a search was performed, or the page changed, was not"
                + " checked by this step.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** Nothing visible matched the submit selector, so no click occurred. */
    public ExecutionStep submitControlNotFound(String submitSelector, long durationMs) {
        return append(
            SUBMIT_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for the configured search submit control.",
            structural(submitSelector),
            "The submit control is visible and clickable.",
            "No visible, clickable control matched the selector. Nothing was clicked.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING
        );
    }

    /**
     * The browser threw while locating or clicking the configured submit control.
     *
     * <p>Recorded as a failed {@code CLICK}: the operation being attempted was a click, and a
     * failed status asserts the opposite of success, so nothing false is claimed. The observation
     * still says plainly that the click did not complete, because {@code clickIfVisible} can fail
     * while locating as well as while clicking.
     */
    public ExecutionStep submitClickFailed(
        String submitSelector, Throwable browserFailure, long durationMs
    ) {
        return append(
            SUBMIT_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.CLICK,
            "Tried to click the configured search submit control.",
            structural(submitSelector),
            "The submit control is visible and clickable.",
            "The browser could not complete the click: " + describe(browserFailure)
                + " The control was not successfully clicked, and no search outcome was checked.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** The verdict for the submit control, recorded before its assertion. */
    public ExecutionStep assertedSubmitControlClickable(String submitSelector, boolean clicked) {
        return append(
            SUBMIT_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that the configured submit control was clickable.",
            structural(submitSelector),
            "The submit control is visible and clickable.",
            clicked
                ? "The control was clicked. No search outcome was verified."
                : "No visible, clickable control matched the configured selector.",
            clicked ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, clicked ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    // --- the results area (only when one is configured) ----------------------------------------------

    /**
     * The real wait for the configured results area finished.
     *
     * <p>This is a genuine {@code WebDriverWait} on {@code visibilityOfElementLocated}: it ends
     * when the element becomes visible, or times out. Only its visibility is established — nothing
     * is read about what the results contain, how many there are, or whether they are relevant.
     */
    public ExecutionStep waitedForResultsArea(String resultsSelector, long durationMs) {
        return append(
            RESULTS_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the configured search results area to become visible.",
            structural(resultsSelector),
            "The results area becomes visible within the configured timeout.",
            "The results area became visible. Its contents, count, order and relevance were not"
                + " examined.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The results area never became visible within the configured timeout.
     *
     * <p>A website finding: the configured expectation was not met by the site. Distinguished from
     * a driver failure by the exception type the check already caught, not by guessing.
     */
    public ExecutionStep resultsAreaDidNotAppear(
        String resultsSelector, String sanitizedFailure, long durationMs
    ) {
        return append(
            RESULTS_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the configured search results area to become visible.",
            structural(resultsSelector),
            "The results area becomes visible within the configured timeout.",
            "The results area did not become visible within the configured timeout: "
                + sanitizedFailure,
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING
        );
    }

    /** The browser failed while waiting for the results area, for a reason other than a timeout. */
    public ExecutionStep resultsLookupFailed(
        String resultsSelector, String sanitizedFailure, long durationMs
    ) {
        return append(
            RESULTS_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the configured search results area to become visible.",
            structural(resultsSelector),
            "The results area becomes visible within the configured timeout.",
            "The browser could not complete the wait: " + sanitizedFailure
                + " Whether the results area would have appeared is not known.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** The verdict for the results area, recorded before its assertion. */
    public ExecutionStep assertedResultsAreaVisible(String resultsSelector, boolean visible) {
        return append(
            RESULTS_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that the configured results area became visible.",
            structural(resultsSelector),
            "The results area is visible after searching.",
            visible
                ? "The results area became visible. No result content was examined."
                : "The results area did not become visible.",
            visible ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, visible ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** How many steps have been recorded for one test case. */
    public int recordedSteps(String testCaseId) {
        return steps.recordedSteps(testCaseId);
    }
}
