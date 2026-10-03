package com.qanize.automation.checks.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.checks.forms.FormExecutionEvidence;
import com.qanize.automation.checks.links.LinkExecutionEvidence;
import com.qanize.automation.checks.navigation.NavigationExecutionEvidence;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceFileReader;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.testsupport.CheckSources;


/**
 * Proves the Search execution evidence without a live site.
 *
 * <p>Search is the check most at risk of being over-read: it types a term and may click a button,
 * but it never verifies that a search ran. Most of what is pinned here is the ceiling on what the
 * evidence is allowed to claim.
 */
@Tag("unit")
class SearchExecutionEvidenceTest {

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

    private List<String> lines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> linesFor(String testCaseId) throws IOException {
        return lines().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    /** Drives the happy path exactly as the check does when all three stages are configured. */
    private SearchExecutionEvidence fullRun() {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 500);
        execution.typedSearchTerm(INPUT, "shoes", 12);
        execution.assertedFieldAcceptedTerm(INPUT, true);
        execution.clickedSubmitControl(SUBMIT, 30);
        execution.assertedSubmitControlClickable(SUBMIT, true);
        execution.waitedForResultsArea(RESULTS, 450);
        execution.assertedResultsAreaVisible(RESULTS, true);
        return execution;
    }

    // --- sequence and correlation ------------------------------------------------------------

    @Test
    void eachTestCaseStartsAtOneAndIsGapless() throws IOException {
        fullRun();

        for (String id : List.of("search-input", "search-submit", "search-results")) {
            List<String> forCase = linesFor(id);
            assertFalse(forCase.isEmpty(), id);
            for (int index = 0; index < forCase.size(); index++) {
                assertTrue(forCase.get(index).contains("\"sequence\":" + (index + 1)),
                    id + " line " + index + ": " + forCase.get(index));
            }
        }
    }

    @Test
    void independentCasesDoNotLeakSequenceState() throws IOException {
        SearchExecutionEvidence execution = fullRun();

        assertEquals(3, execution.recordedSteps("search-input"));
        assertEquals(2, execution.recordedSteps("search-submit"));
        assertEquals(2, execution.recordedSteps("search-results"));
        // A second run object starts over rather than continuing another case's numbering.
        assertEquals(0, new SearchExecutionEvidence().recordedSteps("search-input"));
    }

    @Test
    void theExistingTestCaseIdsAreReusedNotReplaced() throws IOException {
        fullRun();

        assertEquals("search-input", SearchExecutionEvidence.INPUT_TEST_CASE_ID);
        assertEquals("search-submit", SearchExecutionEvidence.SUBMIT_TEST_CASE_ID);
        assertEquals("search-results", SearchExecutionEvidence.RESULTS_TEST_CASE_ID);
        assertTrue(lines().stream().allMatch(line -> line.contains("\"checkId\":\"SEARCH\"")));
    }

    // --- every emitted method corresponds to a real action --------------------------------------

    @Test
    void onlyTheMethodsTheCheckActuallyPerformsAreEmitted() throws IOException {
        fullRun();

        assertTrue(linesFor("search-input").get(0).contains("\"method\":\"NAVIGATE\""));
        assertTrue(linesFor("search-input").get(1).contains("\"method\":\"TYPE\""));
        assertTrue(linesFor("search-input").get(2).contains("\"method\":\"ASSERT\""));
        assertTrue(linesFor("search-submit").get(0).contains("\"method\":\"CLICK\""));
        assertTrue(linesFor("search-results").get(0).contains("\"method\":\"WAIT_FOR_CONDITION\""));
    }

    @Test
    void noHttpRequestOrScreenshotOrBrowserLifecycleStepIsEverEmitted() throws IOException {
        fullRun();

        for (String line : lines()) {
            assertFalse(line.contains("HTTP_REQUEST"), line);
            assertFalse(line.contains("SCREENSHOT"), line);
            assertFalse(line.contains("BROWSER_START"), line);
            assertFalse(line.contains("BROWSER_CLOSE"), line);
        }
    }

    @Test
    void theRecorderHasNoEntryPointForEnterSubmissionOrResultContent() {
        List<String> methodNames = List.of(SearchExecutionEvidence.class.getDeclaredMethods())
            .stream().map(java.lang.reflect.Method::getName).toList();

        for (String forbidden : List.of(
            "pressedEnter", "submittedForm", "navigatedAfterSearch", "readResults",
            "countedResults", "rankedResults", "requested"
        )) {
            assertFalse(methodNames.contains(forbidden), forbidden + " must not exist");
        }
    }

    /** Where the Search interaction now lives; the wrapper only opens the page and asserts. */
    private static final Path SEARCH_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/search/SearchModule.java");

    @Test
    void theCheckItselfPressesNoKeyAndSubmitsNoForm() throws IOException {
        // Read both halves. The interaction moved into SearchModule, so checking only the wrapper
        // would pass while proving nothing — the worst possible outcome for a safety rule.
        String wrapper = Files.readString(CheckSources.entryClass("GenericSearchTest"));
        String module = Files.readString(SEARCH_MODULE);

        for (String source : List.of(wrapper, module)) {
            assertFalse(source.contains("Keys.ENTER"), "the check does not press Enter");
            assertFalse(source.contains("Keys.RETURN"), "the check does not press Return");
            assertFalse(source.contains(".submit()"), "the check does not submit a form");
            assertFalse(source.contains("executeScript"), "the check runs no JavaScript");
            assertFalse(source.contains("getPageSource"), "the check reads no page source");
        }
    }

    @Test
    void theWrapperStaysAThinJUnitFaceOverTheModule() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericSearchTest"));

        // Surefire classification depends on all three of these staying exactly as they are.
        assertTrue(wrapper.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertEquals(1, wrapper.split("@Test", -1).length - 1,
            "exactly one @Test, so the reported test count for SEARCH is unchanged");
        assertTrue(wrapper.contains("assumeTrue(config.tests().search()"),
            "the selected-check gate cannot move into a module");

        // It opens the page and hands both the page and its recorder over.
        assertTrue(wrapper.contains("page.openUrl(pageUrl);"),
            "the wrapper still owns baseline navigation until a runner exists");
        assertTrue(wrapper.contains("new SearchModule(page, search, pageUrl, execution)"),
            "the same recorder instance is passed on, so per-test-case sequence numbers continue");

        // And implements none of the Search interaction itself.
        for (String moved : List.of(
            "typeIfVisible", "clickIfVisible", "findVisibleElement", "TestedItem.builder(",
            "execution.typedSearchTerm(", "execution.clickedSubmitControl(",
            "execution.waitedForResultsArea("
        )) {
            assertFalse(wrapper.contains(moved), moved + " belongs to SearchModule now");
        }
    }

    // --- no step is emitted for an action that did not happen ---------------------------------------

    @Test
    void nothingIsTypedWhenNoFieldMatched() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.searchFieldNotFound(INPUT, PAGE, 8);
        execution.assertedFieldAcceptedTerm(INPUT, false);

        assertTrue(lines().stream().noneMatch(line -> line.contains("\"method\":\"TYPE\"")),
            "no TYPE step may exist when nothing was typed");
        assertTrue(lines().getFirst().contains("Nothing was typed."), lines().getFirst());
    }

    @Test
    void noClickStepExistsWhenNoSubmitControlMatched() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.submitControlNotFound(SUBMIT, 5);
        execution.assertedSubmitControlClickable(SUBMIT, false);

        assertTrue(lines().stream().noneMatch(line -> line.contains("\"method\":\"CLICK\"")));
        assertTrue(lines().getFirst().contains("Nothing was clicked."), lines().getFirst());
    }

    @Test
    void anInteractionFailureIsNotRecordedAsAKeystroke() throws IOException {
        // typeIfVisible locates before it edits; which half failed is unknowable, so no TYPE.
        SearchExecutionEvidence execution = recording();
        execution.searchFieldInteractionFailed(INPUT, "StaleElementReferenceException", 9);

        String line = lines().getFirst();
        assertFalse(line.contains("\"method\":\"TYPE\""), line);
        assertTrue(line.contains("\"method\":\"DOM_INSPECTION\""), line);
        assertTrue(line.contains("Whether the field was found or edited is not known."), line);
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
    }

    @Test
    void noStepIsEmittedForAStageTheConfigurationDidNotEnable() throws IOException {
        // Submit and results are optional; when unconfigured the check never runs those stages.
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 100);
        execution.typedSearchTerm(INPUT, "shoes", 5);
        execution.assertedFieldAcceptedTerm(INPUT, true);

        assertEquals(List.of(), linesFor("search-submit"));
        assertEquals(List.of(), linesFor("search-results"));
    }

    // --- one measurement is attributed once ---------------------------------------------------------

    @Test
    void oneCompositeTypeCallProducesExactlyOneTimedStep() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 500);
        execution.typedSearchTerm(INPUT, "shoes", 76);
        execution.assertedFieldAcceptedTerm(INPUT, true);

        // typeIfVisible has one timing boundary, so it may contribute one timed step. A second
        // record carrying the same interval would double-count the work in any later aggregation.
        List<String> timed = linesFor("search-input").stream()
            .filter(line -> !line.contains("\"method\":\"ASSERT\""))
            .filter(line -> !line.contains("\"method\":\"NAVIGATE\""))
            .toList();
        assertEquals(1, timed.size(), "exactly one step for the composite call: " + timed);
        assertTrue(timed.getFirst().contains("\"method\":\"TYPE\""), timed.getFirst());
    }

    @Test
    void theAggregateDurationAppearsExactlyOnce() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 500);
        execution.typedSearchTerm(INPUT, "shoes", 76);
        execution.assertedFieldAcceptedTerm(INPUT, true);

        long carrying76 = linesFor("search-input").stream()
            .filter(line -> line.contains("\"durationMs\":76")).count();
        assertEquals(1, carrying76, "the measured interval must be attributed to one step only");
    }

    @Test
    void theSuccessfulInputSequenceIsNavigateThenTypeThenAssert() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 500);
        execution.typedSearchTerm(INPUT, "shoes", 76);
        execution.assertedFieldAcceptedTerm(INPUT, true);

        List<String> forCase = linesFor("search-input");
        assertEquals(3, forCase.size());
        assertTrue(forCase.get(0).contains("\"phase\":\"SETUP\""), forCase.get(0));
        assertTrue(forCase.get(1).contains("\"phase\":\"ACTION\""), forCase.get(1));
        assertTrue(forCase.get(2).contains("\"phase\":\"ASSERTION\""), forCase.get(2));
        for (int index = 0; index < forCase.size(); index++) {
            assertTrue(forCase.get(index).contains("\"sequence\":" + (index + 1)), forCase.get(index));
        }
    }

    @Test
    void theTypeStepSaysItsDurationCoversTheWholeCompositeCall() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.typedSearchTerm(INPUT, "shoes", 76);

        String line = lines().getFirst();
        assertTrue(line.contains("Found the first displayed match"), line);
        assertTrue(line.contains("covers locating, clearing and typing together"), line);
    }

    @Test
    void noSeparatelyTimedDiscoveryStepCanBeEmittedForASuccessfulTypeCall() {
        // The entry point that produced the duplicate interval no longer exists, so the defect
        // cannot be reintroduced by calling it again.
        List<String> methodNames = List.of(SearchExecutionEvidence.class.getDeclaredMethods())
            .stream().map(java.lang.reflect.Method::getName).toList();

        assertFalse(methodNames.contains("foundSearchField"),
            "a successful composite call must not have a separate discovery step");
    }

    @Test
    void aFailurePathIsNotPaddedWithAZeroDurationDiscoveryStep() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 100);
        execution.searchFieldNotFound(INPUT, PAGE, 8);
        execution.assertedFieldAcceptedTerm(INPUT, false);

        List<String> forCase = linesFor("search-input");
        assertEquals(3, forCase.size(), "no filler step is added to keep the old shape");
        assertTrue(forCase.get(1).contains("\"durationMs\":8"), forCase.get(1));
    }

    // --- the ceiling on what may be claimed ------------------------------------------------------

    @Test
    void typingNeverClaimsTheFieldHoldsTheTermOrThatASearchRan() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.typedSearchTerm(INPUT, "shoes", 12);

        String line = lines().getFirst();
        assertTrue(line.contains("The field's value was not read back"), line);
        assertTrue(line.contains("no search was submitted by this step"), line);
    }

    @Test
    void clickingNeverClaimsASearchWasPerformedOrThePageChanged() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.clickedSubmitControl(SUBMIT, 30);

        String line = lines().getFirst();
        assertTrue(line.contains("Whether a search was performed, or the page changed, was not"), line);
    }

    @Test
    void theResultsWaitNeverClaimsRelevanceOrderingOrContent() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.waitedForResultsArea(RESULTS, 450);
        execution.assertedResultsAreaVisible(RESULTS, true);

        for (String line : lines()) {
            assertFalse(line.contains("relevant"), line);
            assertFalse(line.contains("pagination"), line);
            assertFalse(line.contains("filter"), line);
        }
        assertTrue(lines().get(0).contains("contents, count, order and relevance were not"), lines().get(0));
        assertTrue(lines().get(1).contains("No result content was examined."), lines().get(1));
    }

    @Test
    void theFieldVerdictSaysExactlyWhatWasEstablished() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.assertedFieldAcceptedTerm(INPUT, true);

        String line = lines().getFirst();
        assertTrue(line.contains("Whether a search ran was not tested by this check."), line);
    }

    // --- status and error classification -----------------------------------------------------------

    @Test
    void aMissingFieldOrControlIsAWebsiteFinding() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.searchFieldNotFound(INPUT, PAGE, 8);
        execution.submitControlNotFound(SUBMIT, 5);

        for (String line : lines()) {
            assertTrue(line.contains("\"status\":\"FAILED\""), line);
            assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
        }
    }

    @Test
    void aResultsTimeoutIsAWebsiteFindingButADriverFailureIsNot() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.resultsAreaDidNotAppear(RESULTS, "TimeoutException: expired", 10_000);
        execution.resultsLookupFailed(RESULTS, "SessionNotCreatedException", 40);

        List<String> forCase = linesFor("search-results");
        assertTrue(forCase.get(0).contains("\"errorCategory\":\"WEBSITE_FINDING\""), forCase.get(0));
        assertTrue(forCase.get(0).contains("did not become visible within the configured timeout"), forCase.get(0));
        assertTrue(forCase.get(1).contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), forCase.get(1));
        assertTrue(forCase.get(1).contains("is not known"), forCase.get(1));
    }

    @Test
    void theCheckDistinguishesATimeoutByExceptionTypeNotByMessageText() throws IOException {
        // The rule moved with the results lookup into SearchModule. Reading the wrapper would now
        // find nothing and prove nothing, so this reads where the classification actually is.
        String module = Files.readString(SEARCH_MODULE);
        String wrapper = Files.readString(CheckSources.entryClass("GenericSearchTest"));

        assertTrue(module.contains("instanceof TimeoutException"),
            "classification must use the exception the lookup already caught");
        assertTrue(module.contains("static ResultsLookup classifyLookupFailure(RuntimeException"),
            "the rule is one named, directly testable decision rather than scattered catch clauses");

        // Never by message text, in either file. Each of these is a way of asking the message
        // instead of the type, which is exactly the mistake this guard exists to prevent.
        for (String source : List.of(module, wrapper)) {
            assertFalse(source.contains("contains(\"Timeout"), "never classify by message text");
            assertFalse(source.contains("contains(\"timeout"), "never classify by message text");
            assertFalse(source.contains("getMessage().contains("), "never classify by message text");
            assertFalse(source.contains("toString().contains("), "never classify by message text");
            assertFalse(source.contains("startsWith(\"Timeout"), "never classify by message text");
        }
        // The wrapper no longer performs the results lookup, so it must not classify at all.
        assertFalse(wrapper.contains("TimeoutException"),
            "the results lookup and its classification belong to SearchModule now");
    }

    @Test
    void aPassingStepCarriesNoErrorCategory() throws IOException {
        fullRun();

        for (String line : lines()) {
            if (line.contains("\"status\":\"PASSED\"")) {
                assertFalse(line.contains("errorCategory"), line);
            }
        }
    }

    // --- sensitive data ------------------------------------------------------------------------------

    @Test
    void aCredentialShapedUrlIsSanitized() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage("https://admin:hunter2@example.com/s?session=abc123&q=shoes", 10);
        execution.searchFieldNotFound(INPUT, "https://example.com/s?token=zzz&q=shoes", 5);

        for (String line : lines()) {
            assertFalse(line.contains("hunter2"), line);
            assertFalse(line.contains("abc123"), line);
            assertFalse(line.contains("zzz"), line);
        }
        assertTrue(lines().get(0).contains("session=[redacted]"), lines().get(0));
        assertTrue(lines().get(1).contains("token=[redacted]"), lines().get(1));
        // An ordinary search parameter stays readable.
        assertTrue(lines().get(0).contains("q=shoes"), lines().get(0));
    }

    @Test
    void anOrdinarySearchTermIsNotDestroyed() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.typedSearchTerm(INPUT, "getting-started-guide", 5);

        // sanitizeMessage would turn this into [redacted-token]; that would make the evidence
        // useless without protecting anything the run configuration does not already publish.
        assertTrue(lines().getFirst().contains("getting-started-guide"), lines().getFirst());
    }

    @Test
    void structuralTextAndTermsAreBounded() throws IOException {
        SearchExecutionEvidence execution = recording();
        String huge = "s".repeat(SearchExecutionEvidence.MAX_STRUCTURAL_TEXT + 300);
        execution.typedSearchTerm(huge, huge, 5);

        assertEquals(huge.substring(0, SearchExecutionEvidence.MAX_STRUCTURAL_TEXT) + "…",
            SearchExecutionEvidence.structural(huge));
        assertFalse(lines().getFirst().contains(huge), "the untruncated value must not appear");
    }

    @Test
    void noPageSourceCookieOrHeaderCanReachTheEvidence() {
        // Structural: the recorder accepts selectors, a term, booleans and durations. There is no
        // parameter through which page content, a cookie or a header could arrive.
        String signatures = List.of(SearchExecutionEvidence.class.getDeclaredMethods()).stream()
            .map(java.lang.reflect.Method::toGenericString)
            .reduce("", (left, right) -> left + "\n" + right)
            .toLowerCase(java.util.Locale.ROOT);

        for (String forbidden : List.of("cookie", "header", "token", "session", "pagesource", "html")) {
            assertFalse(signatures.contains(forbidden), forbidden + " must not be a parameter");
        }
    }

    // --- a thrown action still leaves an accurate record, then fails identically ------------------------

    @Test
    void aFailedPageOpenRecordsFailedNavigateAsInfrastructure() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.startPageOpenFailed(PAGE, new IllegalStateException("driver died"), 700);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"NAVIGATE\""), line);
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
        assertTrue(line.contains("The search was not attempted."), line);
        // It must not read as a successful navigation.
        assertFalse(line.contains("finished loading"), line);
    }

    @Test
    void aFailedSubmitClickRecordsFailedClickAsInfrastructure() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.submitClickFailed(SUBMIT, new IllegalStateException("click intercepted"), 40);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"CLICK\""), line);
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
        assertTrue(line.contains("The control was not successfully clicked"), line);
        // A failed click must never be mistaken for the successful one.
        assertFalse(line.contains("The control was clicked."), line);
    }

    /**
     * Exercises the exact pattern the check uses, so the rethrow is proven behaviourally rather
     * than only by reading the source.
     */
    private static void recordThenRethrow(Runnable action, java.util.function.Consumer<Throwable> record) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            record.accept(failure);
            throw failure;
        }
    }

    @Test
    void theOriginalExceptionInstanceIsRethrownUnchanged() throws IOException {
        SearchExecutionEvidence execution = recording();
        IllegalStateException original = new IllegalStateException("session not created");

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> recordThenRethrow(
                () -> { throw original; },
                failure -> execution.startPageOpenFailed(PAGE, failure, 5))
        );

        // The same object, not a copy, not a wrapper, not an assertion failure.
        assertSame(original, thrown, "the original exception instance must escape unchanged");
        assertEquals("session not created", thrown.getMessage());
        assertEquals(1, lines().size(), "the failure was recorded before it escaped");
    }

    @Test
    void aReportingFailureCannotReplaceTheOriginalException() throws IOException {
        SearchExecutionEvidence execution = recording();
        IllegalStateException original = new IllegalStateException("stale element");

        // A throwable whose toString() blows up is the worst case for the sanitizing path.
        Throwable hostile = new RuntimeException() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString exploded");
            }
        };
        assertNotNull(execution.submitClickFailed(SUBMIT, hostile, 1),
            "recording must survive an unprintable failure");

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> recordThenRethrow(
                () -> { throw original; },
                failure -> execution.submitClickFailed(SUBMIT, hostile, 1))
        );
        assertSame(original, thrown, "reporting must never replace the browser failure");
    }

    @Test
    void aMissingSubmitControlStillEmitsNoClickAtAll() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.submitControlNotFound(SUBMIT, 5);
        execution.assertedSubmitControlClickable(SUBMIT, false);

        assertTrue(lines().stream().noneMatch(line -> line.contains("\"method\":\"CLICK\"")),
            "a selector that matched nothing is a website finding, not a failed click");
        assertTrue(lines().getFirst().contains("\"errorCategory\":\"WEBSITE_FINDING\""));
    }

    @Test
    void successfulNavigationAndClickWordingIsUnchanged() throws IOException {
        SearchExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 500);
        execution.clickedSubmitControl(SUBMIT, 30);

        assertTrue(lines().get(0).contains("The submitted page finished loading."), lines().get(0));
        assertTrue(lines().get(0).contains("\"status\":\"PASSED\""), lines().get(0));
        assertTrue(lines().get(1).contains("The control was clicked."), lines().get(1));
        assertTrue(lines().get(1).contains("\"status\":\"PASSED\""), lines().get(1));
    }

    @Test
    void theCheckRecordsThenRethrowsWithoutWrappingOrDowngrading() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericSearchTest"));
        String module = Files.readString(SEARCH_MODULE);

        // The failure is recorded first, then the very same variable is rethrown. Opening the page
        // is still the wrapper's job, so that pair stays there; the submit click moved.
        assertOrdered(wrapper, "execution.startPageOpenFailed(", "throw navigationFailure;");
        assertTrue(wrapper.contains("throw navigationFailure;"), "rethrown by reference");
        assertOrdered(module, "execution.submitClickFailed(", "throw clickFailure;");
        assertTrue(module.contains("throw clickFailure;"), "rethrown by reference");

        // Never converted into something weaker or different, on either side of the split.
        for (String source : List.of(wrapper, module)) {
            assertFalse(source.contains("throw new "), "the original exception must not be replaced");
            assertFalse(source.contains("assumeTrue(false"), "a browser failure must not become a skip");
        }
    }

    @Test
    void noSuccessStepIsRecordedBeforeTheActionCompletes() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericSearchTest"));
        String module = Files.readString(SEARCH_MODULE);

        // The success record sits after the try block, so a throw can never reach it.
        assertOrdered(wrapper, "throw navigationFailure;", "execution.openedStartPage(");
        assertOrdered(module, "throw clickFailure;", "execution.clickedSubmitControl(");
    }

    // --- the result never depends on reporting ---------------------------------------------------------

    @Test
    void aReportingFailureCannotChangeTheSearchResult() throws IOException {
        SearchExecutionEvidence execution = recording();

        execution.typedSearchTerm(null, null, -5);
        execution.clickedSubmitControl(null, -1);
        assertNotNull(execution.waitedForResultsArea(RESULTS, 1),
            "a healthy step still records afterwards");

        // The containment did not disappear when the recorder was extracted: it moved, once, into
        // the shared CheckStepRecorder. Three facts replace the old "this file contains the text
        // catch (RuntimeException reportingFailure)" assertion, which proved none of them — not the
        // check id, not the diagnostic label, and nothing at all about run-time behaviour.

        // 1. This class records through the guarded factory, with its own identity and its own
        //    label. Switching to the unguarded factory, or changing the label, fails here.
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/search/SearchExecutionEvidence.java"));
        assertTrue(
            source.contains("CheckStepRecorder.guarded(CHECK_ID, \"search-step-record-failed\")"),
            "Search must record through the guarded shared recorder under its own failure label");
        assertFalse(source.contains("CheckStepRecorder.unguarded("),
            "Search must not become an unguarded recorder");
        assertFalse(source.contains("ExecutionEvidenceRecorder.record("),
            "no Search step may bypass the guarded recorder and write directly");

        // 2. CHECK_ID really is SEARCH — read from what was just written, not from the source.
        assertTrue(
            linesFor(SearchExecutionEvidence.RESULTS_TEST_CASE_ID).getFirst()
                .contains("\"checkId\":\"SEARCH\""),
            linesFor(SearchExecutionEvidence.RESULTS_TEST_CASE_ID).getFirst());

        // 3. That exact recorder configuration contains a reporting failure at run time. A step the
        //    model refuses to build is swallowed, logged under this check's label and reported as
        //    null, so persistence can never decide the Search result. Without this the assertions
        //    above would only pin spelling.
        CheckStepRecorder guarded = CheckStepRecorder.guarded("SEARCH", "search-step-record-failed");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        ExecutionStep swallowed;
        try {
            swallowed = guarded.append(
                SearchExecutionEvidence.INPUT_TEST_CASE_ID, null, ExecutionMethod.TYPE,
                "Typed the configured search term.", INPUT, "expected", "actual",
                ExecutionStep.Status.PASSED, 5, ErrorCategory.INFRASTRUCTURE_ERROR);
        } finally {
            System.setOut(originalOut);
        }
        assertNull(swallowed, "a contained reporting failure yields no step and no exception");
        assertTrue(
            captured.toString(StandardCharsets.UTF_8)
                .contains("QA Evidence | search-step-record-failed | "),
            captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recordingIsANoOpWithoutARunDirectory() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);

        assertNotNull(new SearchExecutionEvidence().typedSearchTerm(INPUT, "shoes", 5));
        assertEquals(List.of(), lines());
    }

    // --- isolation and structure ---------------------------------------------------------------------------

    @Test
    void searchSharesTheFileWithTheOtherApprovedChecksWithoutColliding() throws IOException {
        SearchExecutionEvidence search = recording();
        new SmokeExecutionEvidence().navigated(PAGE, 10);
        new NavigationExecutionEvidence().navigatedToDestination("nav-1", PAGE, 20);
        new LinkExecutionEvidence().requestedLink("link-1", PAGE, 200, PAGE, 30);
        new FormExecutionEvidence().discoveredForms(PAGE, 1, 0, 0, 0, 40);
        search.typedSearchTerm(INPUT, "shoes", 50);

        List<String> all = lines();
        assertEquals(5, all.size());
        assertTrue(all.get(4).contains("\"checkId\":\"SEARCH\""), all.get(4));
    }

    @Test
    void contractOneEvidenceStaysReadableAlongsideSearchSteps() throws IOException {
        SearchExecutionEvidence execution = recording();
        Path contractOne = runDirectory.resolve("evidence.jsonl");

        EvidenceRecorder.record(TestedItem.builder("SEARCH", TestedItem.ItemType.SEARCH)
            .id("search-input").name("Search field").pageUrl(PAGE)
            .status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso()).build());
        execution.typedSearchTerm(INPUT, "shoes", 5);

        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
        assertEquals(1, Files.readAllLines(contractOne).size());
    }

    @Test
    void noOtherCheckUsesTheSearchRecorder() throws IOException {
        for (String other : List.of(
            "GenericSmokeTest", "GenericNavigationTest", "GenericLinkValidationTest",
            "GenericFormDetectionTest", "GenericLoginTest"
        )) {
            assertFalse(
                Files.readString(CheckSources.entryClass(other)).contains("SearchExecutionEvidence"),
                other + " must not record through the Search check's recorder"
            );
        }
        assertTrue(Files.readString(CheckSources.entryClass("GenericSearchTest"))
            .contains("SearchExecutionEvidence"));
    }

    @Test
    void thereIsExactlyOneSearchCheckAndNoNewDirectory() throws IOException {
        {
            List<String> names = CheckSources.entryClassFileNames();
            assertEquals(7, names.size(), "the seven customer-facing checks, and nothing else: " + names);
            assertEquals(1, names.stream().filter(name -> name.contains("Search")).count());
        }
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/search")));
    }

    @Test
    void everyRecordedActionFollowsTheCallThatPerformedIt() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericSearchTest"));
        String module = Files.readString(SEARCH_MODULE);

        // The wrapper opens the page, then records that it did.
        assertOrdered(wrapper, "page.openUrl(pageUrl);", "execution.openedStartPage(");

        // Every Search action is performed first and described afterwards, never the reverse.
        assertOrdered(module, "actions.typeIfVisible(", "execution.typedSearchTerm(");
        assertOrdered(module, "actions.clickIfVisible(", "execution.clickedSubmitControl(");
        assertOrdered(module, "actions.awaitResultsArea(", "execution.waitedForResultsArea(");

        // Each stage verdict is recorded before the result that reports it is returned, so the
        // verdict is on disk whatever the caller does with the result.
        assertOrdered(module, "execution.assertedFieldAcceptedTerm(", "Result.failed(Stage.INPUT,");
        assertOrdered(module, "execution.assertedSubmitControlClickable(", "Result.failed(Stage.SUBMIT,");
        assertOrdered(module, "execution.assertedResultsAreaVisible(", "Result.failed(Stage.RESULTS,");

        // ... and only then does the wrapper turn that result into the single JUnit assertion.
        assertOrdered(wrapper, "new SearchModule(", "assertTrue(result.passed(), result.failureMessage());");
    }

    private static void assertOrdered(String source, String earlier, String later) {
        int first = source.indexOf(earlier);
        int second = source.indexOf(later);
        assertTrue(first >= 0, "missing: " + earlier);
        assertTrue(second >= 0, "missing: " + later);
        assertTrue(first < second, earlier + " must appear before " + later);
    }
}
