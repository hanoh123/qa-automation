package com.qanize.automation.checks.search;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.SelectorUtils;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.TestedItem;

import org.openqa.selenium.TimeoutException;

/**
 * The Search check, as production code.
 *
 * <p>This holds what the check actually does: type the configured term into the configured field,
 * optionally click the configured submit control, and optionally confirm the configured results
 * area became visible. It is the same sequence {@code GenericSearchTest} performed inline, moved
 * here unchanged so it can be tested without a browser and, later, run by something other than
 * JUnit.
 *
 * <p><strong>It receives an already-open page.</strong> Nothing here navigates, creates a driver or
 * tears one down. Opening the page is the caller's job today and the runner's job later; if this
 * class opened the page too, a future crawler would open every page twice.
 *
 * <p><strong>It never asserts.</strong> A failed stage is returned as a {@link Result}, so the same
 * logic can back a JUnit wrapper that asserts and a runner that records. The three failure messages
 * are the ones the check has always used, so the wrapper's assertion output is unchanged.
 *
 * <p><strong>Fail-fast, exactly as before.</strong> Search has never collected failures: a field
 * that would not accept the term stops the check before submit, and a submit that could not be
 * clicked stops it before the results wait. Each stage therefore returns immediately, which is what
 * keeps a later stage from emitting evidence for something that never ran.
 */
public final class SearchModule {

    private final PageActions actions;
    private final WebsiteSearchConfig search;
    private final String pageUrl;
    private final SearchExecutionEvidence execution;
    private final EvidenceScope scope;

    /**
     * The production entry point.
     *
     * @param page      a page whose browser is already on {@code pageUrl}
     * @param execution the recorder the caller already used for its own steps, so the per-test-case
     *                  sequence numbers continue rather than restarting
     */
    public SearchModule(
        CommonPage page, WebsiteSearchConfig search, String pageUrl, SearchExecutionEvidence execution
    ) {
        this(page, search, pageUrl, execution, EvidenceScope.legacy());
    }

    /**
     * The entry point for one crawled page.
     *
     * @param scope which page this search belongs to, so page 2's {@code search-input} cannot be
     *              mistaken for page 1's
     * @throws NullPointerException when the scope is null; ask for the legacy scope by name
     */
    public SearchModule(
        CommonPage page, WebsiteSearchConfig search, String pageUrl,
        SearchExecutionEvidence execution, EvidenceScope scope
    ) {
        this(new CommonPageActions(page), search, pageUrl, execution, scope);
    }

    /** The seam a test uses to supply its own actions. Not part of the public API. */
    SearchModule(
        PageActions actions, WebsiteSearchConfig search, String pageUrl,
        SearchExecutionEvidence execution
    ) {
        this(actions, search, pageUrl, execution, EvidenceScope.legacy());
    }

    /** The seam a test uses to supply its own actions and scope. Not part of the public API. */
    SearchModule(
        PageActions actions, WebsiteSearchConfig search, String pageUrl,
        SearchExecutionEvidence execution, EvidenceScope scope
    ) {
        this.actions = actions;
        this.search = search;
        this.pageUrl = pageUrl;
        this.execution = execution;
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use the constructor without a scope for the legacy scope");
    }

    /**
     * Runs the three Search stages against the already-open page.
     *
     * @throws RuntimeException the original exception, unchanged, when the browser fails during the
     *                          submit click — recorded first, then rethrown by reference
     */
    public Result run() {
        Result input = runInputStage();
        if (!input.passed()) return input;

        String submitSelector = search.optionalSubmitSelector().orElse(null);
        if (submitSelector != null) {
            Result submit = runSubmitStage(submitSelector);
            if (!submit.passed()) return submit;
        }

        String resultsSelector = search.optionalResultsSelector().orElse(null);
        if (resultsSelector != null) {
            return runResultsStage(resultsSelector);
        }
        return submitSelector == null ? Result.succeeded(Stage.INPUT) : Result.succeeded(Stage.SUBMIT);
    }

    // --- stages -----------------------------------------------------------------------------

    private Result runInputStage() {
        Instant startedAt = Instant.now();
        boolean typed = false;
        String failure = null;
        long typeStart = System.nanoTime();
        try {
            typed = actions.typeIfVisible(search.inputSelector(), search.effectiveQuery());
            long typeMs = elapsedMs(typeStart);
            // One call, one timing boundary, one timed step: locating, clearing and typing cannot
            // be measured apart, so the aggregate is attributed once rather than to two records.
            // A false result means nothing was typed, so no TYPE step may be claimed.
            if (typed) {
                execution.typedSearchTerm(search.inputSelector(), search.effectiveQuery(), typeMs);
            } else {
                execution.searchFieldNotFound(search.inputSelector(), pageUrl, typeMs);
            }
        } catch (RuntimeException browserFailure) {
            // A browser failure while typing is contained and becomes a failed stage, unlike the
            // submit click below. That asymmetry is the check's existing behaviour, kept as-is.
            failure = SensitiveDataFilter.sanitizeMessage(browserFailure.toString());
            execution.searchFieldInteractionFailed(
                search.inputSelector(), failure, elapsedMs(typeStart));
        }

        TestedItem.Builder input = TestedItem.builder("SEARCH", TestedItem.ItemType.SEARCH)
            .id(scope.itemId("search-input"))
            .name("Search field")
            .pageUrl(pageUrl)
            .selector(search.inputSelector())
            .target(search.inputSelector())
            .action("Located the configured search field and typed the configured search term.")
            .expected("The search field is visible and accepts the search term.")
            .status(typed ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
            .startedAt(startedAt.toString())
            .completedAt(Instant.now().toString())
            .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
            .meta("query", search.effectiveQuery());
        input.actual(typed
            ? "The search field accepted the term \"" + search.effectiveQuery() + "\"."
            : "QANIZE looked for the search field \"" + search.inputSelector() + "\" on " + pageUrl
              + " but no visible, editable field matched it.");
        if (failure != null) input.message(failure);
        EvidenceRecorder.record(scope.enrich(input).build());

        // Recorded before the verdict is returned, so it survives whatever the caller does next.
        execution.assertedFieldAcceptedTerm(search.inputSelector(), typed);

        return typed
            ? Result.succeeded(Stage.INPUT)
            : Result.failed(Stage.INPUT, "Configured search input was not visible or editable");
    }

    private Result runSubmitStage(String selector) {
        long clickStart = System.nanoTime();
        boolean clicked;
        try {
            clicked = actions.clickIfVisible(selector);
        } catch (RuntimeException clickFailure) {
            // Recorded, then the same instance is rethrown untouched: a browser failure is not a
            // Search verdict, and must not be downgraded into one.
            execution.submitClickFailed(selector, clickFailure, elapsedMs(clickStart));
            throw clickFailure;
        }
        long clickMs = elapsedMs(clickStart);
        if (clicked) {
            execution.clickedSubmitControl(selector, clickMs);
        } else {
            execution.submitControlNotFound(selector, clickMs);
        }
        execution.assertedSubmitControlClickable(selector, clicked);
        TestedItem.Builder submit = TestedItem.builder("SEARCH", TestedItem.ItemType.SEARCH)
            .id(scope.itemId("search-submit"))
            .name("Search submit control")
            .pageUrl(pageUrl)
            .selector(selector)
            .target(selector)
            .action("Clicked the configured search submit control.")
            .expected("The submit control is visible and clickable.")
            .actual(clicked ? "The control was clicked." : "No visible, clickable control matched the selector.")
            .status(clicked ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
            .completedAt(EvidenceRecorder.nowIso());
        EvidenceRecorder.record(scope.enrich(submit).build());

        return clicked
            ? Result.succeeded(Stage.SUBMIT)
            : Result.failed(Stage.SUBMIT, "Configured search submit control was not clickable");
    }

    private Result runResultsStage(String selector) {
        long waitStart = System.nanoTime();
        ResultsArea area = actions.awaitResultsArea(selector);
        boolean visible = area.lookup() == ResultsLookup.COMPLETED && area.visible();
        String resultsFailure = null;
        if (area.lookup() == ResultsLookup.COMPLETED) {
            execution.waitedForResultsArea(selector, elapsedMs(waitStart));
        } else {
            resultsFailure = SensitiveDataFilter.sanitizeMessage(area.rawFailure());
            // The exception the lookup already caught distinguishes "the site did not show
            // results" from "the driver failed"; neither is guessed from message text.
            if (area.lookup() == ResultsLookup.TIMED_OUT) {
                execution.resultsAreaDidNotAppear(selector, resultsFailure, elapsedMs(waitStart));
            } else {
                execution.resultsLookupFailed(selector, resultsFailure, elapsedMs(waitStart));
            }
        }
        TestedItem.Builder results = TestedItem.builder("SEARCH", TestedItem.ItemType.SEARCH)
            .id(scope.itemId("search-results"))
            .name("Search results area")
            .pageUrl(pageUrl)
            .selector(selector)
            .target(selector)
            .action("Waited for the configured search results area to become visible.")
            .expected("The results area is visible after searching.")
            .actual(visible ? "The results area became visible." : "The results area did not become visible.")
            .status(visible ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
            .completedAt(EvidenceRecorder.nowIso());
        if (resultsFailure != null) results.message(resultsFailure);
        EvidenceRecorder.record(scope.enrich(results).build());
        execution.assertedResultsAreaVisible(selector, visible);

        return visible
            ? Result.succeeded(Stage.RESULTS)
            : Result.failed(Stage.RESULTS, "Configured search results container was not visible");
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    // --- what the caller gets back ------------------------------------------------------------

    /** The three stages, in the order Search performs them. */
    public enum Stage { INPUT, SUBMIT, RESULTS }

    /**
     * The outcome of one Search run.
     *
     * <p>{@code stage} is the last stage that actually ran: the one that failed, or the final one
     * reached. {@code failureMessage} is null on success and otherwise carries the wording the
     * check has always reported, so a wrapper needs only one assertion.
     */
    public record Result(Stage stage, boolean passed, String failureMessage) {

        static Result succeeded(Stage stage) {
            return new Result(stage, true, null);
        }

        static Result failed(Stage stage, String failureMessage) {
            return new Result(stage, false, failureMessage);
        }
    }

    // --- the page actions Search needs --------------------------------------------------------

    /**
     * The three things Search does to a page. Deliberately not a general abstraction: it exists so
     * this one module can be driven by a small fake instead of a browser, and nothing outside this
     * package can adopt it.
     */
    interface PageActions {

        /** @throws RuntimeException when the browser fails; the caller contains it */
        boolean typeIfVisible(String selector, String value);

        /** @throws RuntimeException when the browser fails; the caller rethrows it unchanged */
        boolean clickIfVisible(String selector);

        /** Never throws. The timeout-versus-driver-failure decision is made by the implementation. */
        ResultsArea awaitResultsArea(String selector);
    }

    /** Why the results area was or was not seen. */
    enum ResultsLookup { COMPLETED, TIMED_OUT, LOOKUP_FAILED }

    /**
     * The result of one results-area lookup.
     *
     * <p>No Selenium type crosses this boundary. {@code rawFailure} is the unsanitized exception
     * text, because sanitizing stays in the module where it has always been.
     */
    record ResultsArea(ResultsLookup lookup, boolean visible, String rawFailure) { }

    /**
     * Decides which kind of failure a results-area lookup hit, from the exception's type.
     *
     * <p>Selenium raises {@link TimeoutException} when the element never became visible, which is a
     * fact about the website; anything else is a fact about the driver. The distinction is made by
     * type and never by reading the message, because a perfectly ordinary driver failure can carry
     * the word "timeout" in its text and must not be reported as the site failing to show results.
     *
     * <p>It is a named method rather than two catch clauses so the rule can be tested directly,
     * without a browser: the adapter below is the only caller.
     */
    static ResultsLookup classifyLookupFailure(RuntimeException lookupFailure) {
        return lookupFailure instanceof TimeoutException
            ? ResultsLookup.TIMED_OUT
            : ResultsLookup.LOOKUP_FAILED;
    }

    /**
     * The production actions, over the page the caller already opened.
     *
     * <p>This is the only place that touches the browser. The failure it catches is classified by
     * {@link #classifyLookupFailure}, so no Selenium type reaches the stages above or the result.
     */
    private record CommonPageActions(CommonPage page) implements PageActions {

        @Override
        public boolean typeIfVisible(String selector, String value) {
            return page.typeIfVisible(selector, value);
        }

        @Override
        public boolean clickIfVisible(String selector) {
            return page.clickIfVisible(selector);
        }

        @Override
        public ResultsArea awaitResultsArea(String selector) {
            try {
                return new ResultsArea(
                    ResultsLookup.COMPLETED,
                    page.findVisibleElement(SelectorUtils.css(selector)).isDisplayed(),
                    null);
            } catch (RuntimeException lookupFailure) {
                // Contained, never rethrown: a results area that did not appear is the Search
                // verdict, not a crash. That is the check's existing contract.
                return new ResultsArea(
                    classifyLookupFailure(lookupFailure), false, lookupFailure.toString());
            }
        }
    }
}
