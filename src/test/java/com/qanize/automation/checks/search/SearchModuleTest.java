package com.qanize.automation.checks.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
// The only Selenium type this test names, and only to prove classification is by type: the
// classification rule cannot be tested without constructing the exception it discriminates on.
import org.openqa.selenium.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Search behaviour, driven without a browser.
 *
 * <p>The module talks to a page through three actions, so a hand-written fake of those three is
 * enough to exercise every stage, every failure path and the fail-fast ordering between them. No
 * mocking framework and no fake {@code WebDriver}: the point of the seam is that neither is needed.
 */
@Tag("unit")
class SearchModuleTest {

    private static final String PAGE = "https://example.com/";
    private static final String INPUT = "input[type='search']";
    private static final String SUBMIT = "button[type='submit']";
    private static final String RESULTS = "#results";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private SearchExecutionEvidence recording() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        return new SearchExecutionEvidence();
    }

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> stepsFor(String testCaseId) throws IOException {
        return steps().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    private List<String> items() throws IOException {
        Path file = runDirectory.resolve("evidence.jsonl");
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private static WebsiteSearchConfig config(String submitSelector, String resultsSelector) {
        return new WebsiteSearchConfig(true, INPUT, submitSelector, resultsSelector, "shoes");
    }

    private SearchModule module(FakeActions actions, WebsiteSearchConfig search,
                                SearchExecutionEvidence execution) {
        return new SearchModule(actions, search, PAGE, execution);
    }

    // --- input stage --------------------------------------------------------------------------

    @Test
    void typingIntoTheConfiguredFieldIsTheWholeCheckWhenNothingElseIsConfigured() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(true);

        SearchModule.Result result =
            module(actions, config(null, null), recording()).run();

        assertTrue(result.passed());
        assertEquals(SearchModule.Stage.INPUT, result.stage());
        assertNull(result.failureMessage(), "a passing result carries no message");
        assertEquals(1, actions.typeCalls);
        assertEquals(0, actions.clickCalls, "no submit was configured, so none may be attempted");
        assertEquals(0, actions.resultsCalls);
        assertTrue(steps().stream().anyMatch(line -> line.contains("\"method\":\"TYPE\"")), steps().toString());
        assertEquals(1, items().size(), "exactly the search-input item");
        assertTrue(items().getFirst().contains("\"id\":\"search-input\""), items().getFirst());
    }

    @Test
    void aFieldThatNeverMatchedStopsTheCheckBeforeSubmitAndResults() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(false);

        SearchModule.Result result =
            module(actions, config(SUBMIT, RESULTS), recording()).run();

        assertFalse(result.passed());
        assertEquals(SearchModule.Stage.INPUT, result.stage());
        assertEquals("Configured search input was not visible or editable", result.failureMessage());
        // Fail-fast: the later stages must not run, and must therefore leave no evidence at all.
        assertEquals(0, actions.clickCalls);
        assertEquals(0, actions.resultsCalls);
        assertEquals(List.of(), stepsFor(SearchExecutionEvidence.SUBMIT_TEST_CASE_ID));
        assertEquals(List.of(), stepsFor(SearchExecutionEvidence.RESULTS_TEST_CASE_ID));
        assertTrue(steps().stream().noneMatch(line -> line.contains("\"method\":\"TYPE\"")),
            "nothing was typed, so no TYPE step may be claimed");
    }

    @Test
    void aBrowserFailureWhileTypingIsContainedAndBecomesTheInputFailure() throws IOException {
        RuntimeException browserFailure = new IllegalStateException("stale element");
        FakeActions actions = new FakeActions().typeThrows(browserFailure);
        SearchExecutionEvidence execution = recording();

        SearchModule.Result result = assertDoesNotThrow(
            () -> module(actions, config(SUBMIT, null), execution).run(),
            "a typing failure is contained, unlike the submit click");

        assertFalse(result.passed());
        assertEquals(SearchModule.Stage.INPUT, result.stage());
        assertEquals("Configured search input was not visible or editable", result.failureMessage());
        assertEquals(0, actions.clickCalls, "the submit stage must not run after a failed input");
        assertTrue(stepsFor(SearchExecutionEvidence.INPUT_TEST_CASE_ID).stream()
                .anyMatch(line -> line.contains("\"status\":\"FAILED\"")),
            steps().toString());
        assertTrue(items().getFirst().contains("stale element"), items().getFirst());
    }

    // --- submit stage -------------------------------------------------------------------------

    @Test
    void aClickedSubmitControlCarriesTheCheckToItsSubmitStage() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(true).clickReturns(true);

        SearchModule.Result result =
            module(actions, config(SUBMIT, null), recording()).run();

        assertTrue(result.passed());
        assertEquals(SearchModule.Stage.SUBMIT, result.stage());
        assertEquals(1, actions.clickCalls);
        assertEquals(0, actions.resultsCalls, "no results area was configured");
        assertTrue(steps().stream().anyMatch(line -> line.contains("\"method\":\"CLICK\"")), steps().toString());
        assertEquals(2, items().size(), "search-input then search-submit");
        assertTrue(items().get(1).contains("\"id\":\"search-submit\""), items().get(1));
    }

    @Test
    void aSubmitControlThatNeverMatchedStopsTheCheckBeforeTheResultsWait() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(true).clickReturns(false);

        SearchModule.Result result =
            module(actions, config(SUBMIT, RESULTS), recording()).run();

        assertFalse(result.passed());
        assertEquals(SearchModule.Stage.SUBMIT, result.stage());
        assertEquals("Configured search submit control was not clickable", result.failureMessage());
        assertEquals(0, actions.resultsCalls);
        assertEquals(List.of(), stepsFor(SearchExecutionEvidence.RESULTS_TEST_CASE_ID));
    }

    @Test
    void aBrowserFailureDuringTheSubmitClickIsRecordedThenRethrownUnchanged() throws IOException {
        RuntimeException clickFailure = new IllegalStateException("element intercepted");
        FakeActions actions = new FakeActions().typeReturns(true).clickThrows(clickFailure);
        SearchExecutionEvidence execution = recording();

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> module(actions, config(SUBMIT, RESULTS), execution).run());

        assertSame(clickFailure, thrown, "the original exception must be rethrown by reference");
        assertFalse(stepsFor(SearchExecutionEvidence.SUBMIT_TEST_CASE_ID).isEmpty(),
            "the failure is recorded before it is rethrown");
        assertEquals(0, actions.resultsCalls, "nothing runs after the throw");
    }

    // --- results stage ------------------------------------------------------------------------

    @Test
    void aVisibleResultsAreaCompletesTheCheck() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(true).clickReturns(true)
            .resultsAre(new SearchModule.ResultsArea(SearchModule.ResultsLookup.COMPLETED, true, null));

        SearchModule.Result result =
            module(actions, config(SUBMIT, RESULTS), recording()).run();

        assertTrue(result.passed());
        assertEquals(SearchModule.Stage.RESULTS, result.stage());
        assertEquals(3, items().size(), "search-input, search-submit, search-results");
        assertTrue(items().get(2).contains("The results area became visible."), items().get(2));
    }

    @Test
    void aResultsAreaThatNeverAppearedIsAWebsiteFinding() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(true)
            .resultsAre(new SearchModule.ResultsArea(
                SearchModule.ResultsLookup.TIMED_OUT, false, "TimeoutException: waited 10 seconds"));

        SearchModule.Result result =
            module(actions, config(null, RESULTS), recording()).run();

        assertFalse(result.passed());
        assertEquals(SearchModule.Stage.RESULTS, result.stage());
        assertEquals("Configured search results container was not visible", result.failureMessage());
        String step = stepsFor(SearchExecutionEvidence.RESULTS_TEST_CASE_ID).getFirst();
        assertTrue(step.contains("\"errorCategory\":\"WEBSITE_FINDING\""), step);
    }

    @Test
    void aDriverFailureDuringTheResultsLookupIsNotAWebsiteFinding() throws IOException {
        FakeActions actions = new FakeActions().typeReturns(true)
            .resultsAre(new SearchModule.ResultsArea(
                SearchModule.ResultsLookup.LOOKUP_FAILED, false, "SessionNotCreatedException: gone"));

        SearchModule.Result result =
            module(actions, config(null, RESULTS), recording()).run();

        assertFalse(result.passed());
        assertEquals("Configured search results container was not visible", result.failureMessage());
        String step = stepsFor(SearchExecutionEvidence.RESULTS_TEST_CASE_ID).getFirst();
        assertFalse(step.contains("\"errorCategory\":\"WEBSITE_FINDING\""),
            "a driver failure is not the website's fault: " + step);
    }

    // --- how a results-area failure is classified ---------------------------------------------

    @Test
    void aSeleniumTimeoutIsClassifiedByItsTypeAsTheResultsAreaNeverAppearing() {
        assertEquals(
            SearchModule.ResultsLookup.TIMED_OUT,
            SearchModule.classifyLookupFailure(new TimeoutException("Expected condition failed")));
    }

    @Test
    void anOrdinaryFailureIsNotATimeoutEvenWhenItsMessageSaysTimeout() {
        // The trap this rule exists for: a driver failure whose text happens to contain the word.
        // Classifying by message would report the website as failing to show results when the
        // browser was the thing that broke.
        for (RuntimeException notATimeout : List.of(
            new IllegalStateException("connection timeout after 30s"),
            new IllegalStateException("Timeout"),
            new RuntimeException("TimeoutException: read timed out"),
            new RuntimeException("org.openqa.selenium.TimeoutException wrapped in text")
        )) {
            assertEquals(SearchModule.ResultsLookup.LOOKUP_FAILED,
                SearchModule.classifyLookupFailure(notATimeout),
                "message text must never decide the classification: " + notATimeout.getMessage());
        }
    }

    @Test
    void aSubclassOfTheSeleniumTimeoutIsStillATimeout() {
        // Type-based classification follows the hierarchy; a string comparison would not.
        TimeoutException subclass = new TimeoutException("still a timeout") { };
        assertEquals(
            SearchModule.ResultsLookup.TIMED_OUT, SearchModule.classifyLookupFailure(subclass));
    }

    // --- the wrapper's steps and the module's steps share one numbering -----------------------

    @Test
    void theModuleContinuesTheSequenceTheWrapperAlreadyStarted() throws IOException {
        SearchExecutionEvidence execution = recording();
        // Exactly what GenericSearchTest records before handing over: search-input sequence 1.
        execution.openedStartPage(PAGE, 12);

        module(new FakeActions().typeReturns(true), config(null, null), execution).run();

        List<String> input = stepsFor(SearchExecutionEvidence.INPUT_TEST_CASE_ID);
        assertTrue(input.get(0).contains("\"sequence\":1"), input.get(0));
        assertTrue(input.get(0).contains("\"method\":\"NAVIGATE\""), input.get(0));
        // The module must not restart at 1: the numbering is per test case, not per object.
        assertTrue(input.get(1).contains("\"sequence\":2"), input.get(1));
        assertTrue(input.get(1).contains("\"method\":\"TYPE\""), input.get(1));
        assertEquals(3, execution.recordedSteps(SearchExecutionEvidence.INPUT_TEST_CASE_ID),
            "navigate, type, assert");
    }

    // --- what the module is not allowed to be -------------------------------------------------

    @Test
    void theModuleOwnsNoBrowserAndMakesNoAssertion() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/search/SearchModule.java"));

        for (String forbidden : List.of(
            "org.junit", "assertTrue(", "assertFalse(", "assumeTrue(",
            "openUrl", "driver.get", "new ChromeDriver", "driver.quit"
        )) {
            assertFalse(source.contains(forbidden),
                "SearchModule must not contain " + forbidden);
        }
    }

    /**
     * The three page actions, scripted.
     *
     * <p>Small on purpose: replacing a real {@code WebDriver} would take a large stub of methods
     * Search never calls, and would prove less than this does.
     */
    private static final class FakeActions implements SearchModule.PageActions {

        private boolean typeResult;
        private RuntimeException typeFailure;
        private boolean clickResult;
        private RuntimeException clickFailure;
        private SearchModule.ResultsArea resultsArea =
            new SearchModule.ResultsArea(SearchModule.ResultsLookup.COMPLETED, true, null);

        private int typeCalls;
        private int clickCalls;
        private int resultsCalls;

        FakeActions typeReturns(boolean value) { this.typeResult = value; return this; }
        FakeActions typeThrows(RuntimeException failure) { this.typeFailure = failure; return this; }
        FakeActions clickReturns(boolean value) { this.clickResult = value; return this; }
        FakeActions clickThrows(RuntimeException failure) { this.clickFailure = failure; return this; }
        FakeActions resultsAre(SearchModule.ResultsArea area) { this.resultsArea = area; return this; }

        @Override
        public boolean typeIfVisible(String selector, String value) {
            typeCalls++;
            if (typeFailure != null) throw typeFailure;
            return typeResult;
        }

        @Override
        public boolean clickIfVisible(String selector) {
            clickCalls++;
            if (clickFailure != null) throw clickFailure;
            return clickResult;
        }

        @Override
        public SearchModule.ResultsArea awaitResultsArea(String selector) {
            resultsCalls++;
            return resultsArea;
        }
    }
}
