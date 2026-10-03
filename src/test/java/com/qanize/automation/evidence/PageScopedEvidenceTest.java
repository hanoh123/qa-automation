package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.checks.forms.FormExecutionEvidence;
import com.qanize.automation.checks.links.LinkExecutionEvidence;
import com.qanize.automation.checks.navigation.NavigationExecutionEvidence;
import com.qanize.automation.checks.search.SearchExecutionEvidence;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * What a scoped run actually writes to disk.
 *
 * <p>{@link EvidenceScopeTest} proves the scope in isolation; this drives the two real recorders
 * and reads back the persisted contract 1.1 lines, because the property that matters is not that
 * a string was qualified but that the artifact a reader consumes is well formed for every page.
 *
 * <p>Contract 1.1 requires each test case's steps to start at sequence 1 and run without gaps. Two
 * pages sharing one case id therefore do not merely look confusing — they produce an artifact the
 * reader rejects. That is the failure this scoping exists to prevent, and it is checked here with
 * the real {@link ExecutionTimelineReader}.
 */
@Tag("unit")
class PageScopedEvidenceTest {

    private static final String FIRST = "https://example.com/";
    private static final String SECOND = "https://example.com/about";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private void recordInto(Path runDir) {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY, runDir.resolve("evidence.jsonl").toString());
    }

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** Drives the page-health calls a healthy page produces. */
    private static void healthRun(SmokeExecutionEvidence execution, String url) {
        execution.navigated(url, 820);
        execution.readAddress(url, url, 3);
        execution.readTitle(url, "Example Domain", 2);
    }

    // --- 3, 4, 5. sequences are isolated per page ----------------------------------------------

    @Test
    void eachPagesStepsCarryItsOwnCaseIdAndEachSequenceBeginsAtOne() throws IOException {
        recordInto(runDirectory);

        healthRun(new SmokeExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null)), FIRST);
        healthRun(new SmokeExecutionEvidence(EvidenceScope.forPage(2, SECOND, 1, FIRST)), SECOND);

        List<String> lines = steps();
        assertEquals(6, lines.size(), "three steps on each of two pages");

        List<String> first = lines.stream()
            .filter(line -> line.contains("\"testCaseId\":\"page-001/smoke-page-1\"")).toList();
        List<String> second = lines.stream()
            .filter(line -> line.contains("\"testCaseId\":\"page-002/smoke-page-1\"")).toList();
        assertEquals(3, first.size(), "page 1 owns its own case: " + lines);
        assertEquals(3, second.size(), "and page 2 owns a different one");

        // Each page restarts at 1 and stays continuous, which is exactly what the contract wants.
        assertEquals(List.of(1, 2, 3), sequences(first));
        assertEquals(List.of(1, 2, 3), sequences(second));
    }

    @Test
    void theTimelineReaderAcceptsATwoPageRunAndKeepsThePagesApart() throws IOException {
        recordInto(runDirectory);

        healthRun(new SmokeExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null)), FIRST);
        healthRun(new SmokeExecutionEvidence(EvidenceScope.forPage(2, SECOND, 1, FIRST)), SECOND);

        ExecutionTimelineReader.Result result = readTimeline();

        assertTrue(result.sourcePresent());
        assertEquals(List.of(), result.errors(),
            "an unscoped two-page run would fail here with duplicate and out-of-order sequences");

        List<String> cases = result.checks().stream()
            .flatMap(check -> check.cases().stream())
            .map(ExecutionTimelineReader.CaseTimeline::testCaseId)
            .toList();
        assertEquals(List.of("page-001/smoke-page-1", "page-002/smoke-page-1"), cases,
            "two distinct cases, in visiting order");
    }

    @Test
    void anUnscopedTwoPageRunIsExactlyWhatTheReaderRejects() throws IOException {
        recordInto(runDirectory);

        // The old behaviour, reproduced deliberately: both pages share one case id.
        healthRun(new SmokeExecutionEvidence(), FIRST);
        healthRun(new SmokeExecutionEvidence(), SECOND);

        ExecutionTimelineReader.Result result = readTimeline();

        assertFalse(result.errors().isEmpty(),
            "this is the collision page scoping removes; if this ever passes, the reader stopped "
                + "enforcing per-case sequences and the guarantee above is worthless");
    }

    @Test
    void linksStepsAreScopedPerPageAcrossEveryCaseTheCheckUses() throws IOException {
        recordInto(runDirectory);

        LinkExecutionEvidence first =
            new LinkExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null));
        first.openedStartPage(FIRST, 400);
        first.discoveredLinks(FIRST, 4, 4, 2, 0, 12);

        LinkExecutionEvidence second =
            new LinkExecutionEvidence(EvidenceScope.forPage(2, SECOND, 1, FIRST));
        second.openedStartPage(SECOND, 380);
        second.discoveredLinks(SECOND, 3, 3, 1, 0, 9);

        List<String> lines = steps();
        assertEquals(2, lines.stream()
            .filter(line -> line.contains("\"testCaseId\":\"page-001/links-discovery\"")).count());
        assertEquals(2, lines.stream()
            .filter(line -> line.contains("\"testCaseId\":\"page-002/links-discovery\"")).count());
        assertEquals(List.of(), readTimeline().errors());
    }

    // --- 10. one run never inherits another's numbering ----------------------------------------

    @Test
    void aSecondRunStartsFromScratchRatherThanContinuingTheFirst(@TempDir Path secondRun)
        throws IOException {
        recordInto(runDirectory);
        healthRun(new SmokeExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null)), FIRST);

        // A different run directory, and freshly built recorders and scopes throughout.
        recordInto(secondRun);
        healthRun(new SmokeExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null)), FIRST);

        List<String> secondLines = Files.readAllLines(
            secondRun.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME));
        assertEquals(3, secondLines.size(), "the second run wrote only its own steps");
        assertEquals(List.of(1, 2, 3), sequences(secondLines),
            "sequence state cannot leak between runs because no state outlives a recorder");
        assertEquals(3, steps().size(), "and the first run's file was not appended to");
    }

    // --- 1 & 11. the single-page checks are untouched ------------------------------------------

    @Test
    void aSinglePageRunWritesTheSameCaseIdAndSequencesItAlwaysDid() throws IOException {
        recordInto(runDirectory);

        healthRun(new SmokeExecutionEvidence(), FIRST);

        List<String> lines = steps();
        assertEquals(3, lines.size());
        lines.forEach(line -> assertTrue(
            line.contains("\"testCaseId\":\"" + SmokeExecutionEvidence.TEST_CASE_ID + "\""),
            "the legacy case id is unchanged: " + line));
        lines.forEach(line -> assertFalse(line.contains("page-001"),
            "no page qualification leaks into a single-page run: " + line));
        assertEquals(List.of(1, 2, 3), sequences(lines));
        assertEquals(List.of(), readTimeline().errors());
    }

    /** The reader takes the evidence file itself, not the run directory. */
    private ExecutionTimelineReader.Result readTimeline() {
        return new ExecutionTimelineReader().read(
            runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME));
    }


    // --- 1-5. the three modules added in this slice --------------------------------------------

    @Test
    void navigationStepsAreScopedPerPageAndEachSequenceBeginsAtOne() throws IOException {
        recordInto(runDirectory);

        NavigationExecutionEvidence first =
            new NavigationExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null));
        first.openedStartPage(FIRST, 410);
        first.waitedForRenderedContent(FIRST, false, 12);

        NavigationExecutionEvidence second =
            new NavigationExecutionEvidence(EvidenceScope.forPage(2, SECOND, 1, FIRST));
        second.openedStartPage(SECOND, 390);
        second.waitedForRenderedContent(SECOND, false, 11);

        assertEquals(List.of(1, 2), sequences(linesFor("page-001/navigation-discovery")));
        assertEquals(List.of(1, 2), sequences(linesFor("page-002/navigation-discovery")));
        assertEquals(List.of(), readTimeline().errors());
    }

    @Test
    void formsStepsAreScopedPerPageAndEachSequenceBeginsAtOne() throws IOException {
        recordInto(runDirectory);

        FormExecutionEvidence first =
            new FormExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null));
        first.openedStartPage(FIRST, 300);
        first.waitedForRenderedContent(FIRST, false, 9);

        FormExecutionEvidence second =
            new FormExecutionEvidence(EvidenceScope.forPage(2, SECOND, 1, FIRST));
        second.openedStartPage(SECOND, 280);
        second.waitedForRenderedContent(SECOND, false, 8);

        assertEquals(List.of(1, 2), sequences(linesFor("page-001/forms-discovery")));
        assertEquals(List.of(1, 2), sequences(linesFor("page-002/forms-discovery")));
        assertEquals(List.of(), readTimeline().errors());
    }

    @Test
    void searchStepsAreScopedPerPageAcrossEveryCaseTheCheckUses() throws IOException {
        recordInto(runDirectory);

        SearchExecutionEvidence first =
            new SearchExecutionEvidence(EvidenceScope.forPage(1, FIRST, 0, null));
        first.openedStartPage(FIRST, 350);
        first.typedSearchTerm("#q", "widgets", 14);

        SearchExecutionEvidence second =
            new SearchExecutionEvidence(EvidenceScope.forPage(2, SECOND, 1, FIRST));
        second.openedStartPage(SECOND, 340);
        second.typedSearchTerm("#q", "widgets", 13);

        assertEquals(List.of(1, 2), sequences(linesFor("page-001/search-input")));
        assertEquals(List.of(1, 2), sequences(linesFor("page-002/search-input")));
        assertEquals(List.of(), readTimeline().errors());
    }

    @Test
    void everyPerPageCheckKeepsItsLegacyCaseIdWhenNoScopeIsGiven() throws IOException {
        recordInto(runDirectory);

        new NavigationExecutionEvidence().openedStartPage(FIRST, 400);
        new FormExecutionEvidence().openedStartPage(FIRST, 300);
        new SearchExecutionEvidence().openedStartPage(FIRST, 350);
        new LinkExecutionEvidence().openedStartPage(FIRST, 380);
        new SmokeExecutionEvidence().navigated(FIRST, 820);

        List<String> lines = steps();
        assertEquals(5, lines.size());
        for (String legacy : List.of(
            "\"testCaseId\":\"navigation-discovery\"",
            "\"testCaseId\":\"forms-discovery\"",
            "\"testCaseId\":\"search-input\"",
            "\"testCaseId\":\"links-discovery\"",
            "\"testCaseId\":\"smoke-page-1\"")) {
            assertEquals(1, lines.stream().filter(line -> line.contains(legacy)).count(),
                "the legacy case id is unchanged: " + legacy);
        }
        lines.forEach(line -> assertFalse(line.contains("page-001"),
            "no page qualification leaks into a single-page run: " + line));
        assertEquals(List.of(), readTimeline().errors());
    }

    @Test
    void everyPerPageCheckScopesItsTestedItemAndCaseIdsTheSameWay() {
        EvidenceScope page2 = EvidenceScope.forPage(2, SECOND, 1, FIRST);

        // One mechanism, five checks. Item ids and case ids are qualified identically, which is
        // what lets a reader line the two contract views up for the same page.
        assertEquals("page-002/smoke-page-1", page2.itemId("smoke-page-1"));
        assertEquals("page-002/link-1", page2.itemId("link-1"));
        assertEquals("page-002/nav-1", page2.itemId("nav-1"));
        assertEquals("page-002/forms-1", page2.itemId("forms-1"));
        assertEquals("page-002/search-input", page2.itemId("search-input"));

        assertEquals("page-002/smoke-page-1", new SmokeExecutionEvidence(page2).testCaseId());
        assertEquals("page-002/links-discovery",
            new LinkExecutionEvidence(page2).testCaseId("links-discovery"));
        assertEquals("page-002/navigation-discovery",
            new NavigationExecutionEvidence(page2).testCaseId("navigation-discovery"));
        assertEquals("page-002/forms-interaction",
            new FormExecutionEvidence(page2).testCaseId("forms-interaction"));
        assertEquals("page-002/search-submit",
            new SearchExecutionEvidence(page2).testCaseId("search-submit"));
    }

    // --- 8 & 9. explicit null is a mistake, not a request for legacy ---------------------------

    @Test
    void anExplicitNullScopeFailsFastInEveryPerPageRecorder() {
        // Quietly treating null as legacy would turn one crawler wiring mistake into silently
        // colliding evidence on every page — the exact failure this mechanism exists to prevent.
        // A caller who wants legacy has a no-argument constructor and must ask for it by name.
        assertThrows(NullPointerException.class, () -> new SmokeExecutionEvidence(null));
        assertThrows(NullPointerException.class, () -> new LinkExecutionEvidence(null));
        assertThrows(NullPointerException.class, () -> new NavigationExecutionEvidence(null));
        assertThrows(NullPointerException.class, () -> new FormExecutionEvidence(null));
        assertThrows(NullPointerException.class, () -> new SearchExecutionEvidence(null));

        NullPointerException failure =
            assertThrows(NullPointerException.class, () -> new SearchExecutionEvidence(null));
        assertTrue(failure.getMessage().contains("scope must not be null"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SearchExecutionEvidence()"),
            "the message names the constructor that does mean legacy: " + failure.getMessage());
    }

    @Test
    void theNoArgumentConstructorsStillMeanLegacyInEveryPerPageRecorder() {
        assertEquals("smoke-page-1", new SmokeExecutionEvidence().testCaseId());
        assertEquals("links-discovery",
            new LinkExecutionEvidence().testCaseId(LinkExecutionEvidence.DISCOVERY_TEST_CASE_ID));
        assertEquals("navigation-discovery", new NavigationExecutionEvidence()
            .testCaseId(NavigationExecutionEvidence.DISCOVERY_TEST_CASE_ID));
        assertEquals("forms-discovery", new FormExecutionEvidence()
            .testCaseId(FormExecutionEvidence.DISCOVERY_TEST_CASE_ID));
        assertEquals("search-input", new SearchExecutionEvidence()
            .testCaseId(SearchExecutionEvidence.INPUT_TEST_CASE_ID));
    }

    // --- 10. Login is once per run and stays unscoped -------------------------------------------

    @Test
    void loginHasNoPageScopeAtAll() throws IOException {
        // Login establishes a session once per run rather than per page, so scoping it would be
        // meaningless at best and would invent a second identity for one sign-in at worst.
        for (String file : List.of(
            "src/main/java/com/qanize/automation/checks/login/LoginModule.java",
            "src/main/java/com/qanize/automation/checks/login/LoginExecutionEvidence.java")) {
            String source = Files.readString(Path.of(file));
            assertFalse(source.contains("EvidenceScope"), file + " must not depend on a page scope");
            assertFalse(source.contains("scope.itemId("), file + " must not qualify its ids");
        }

        for (java.lang.reflect.Constructor<?> constructor
            : com.qanize.automation.checks.login.LoginExecutionEvidence.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter.equals(EvidenceScope.class),
                    "Login must expose no page-scope constructor");
            }
        }
    }

    // --- 11. no ambient state was introduced ---------------------------------------------------

    @Test
    void noPerPageCheckIntroducedGlobalOrThreadLocalPageState() throws IOException {
        for (String file : List.of(
            "src/main/java/com/qanize/automation/checks/smoke/SmokeExecutionEvidence.java",
            "src/main/java/com/qanize/automation/checks/links/LinkExecutionEvidence.java",
            "src/main/java/com/qanize/automation/checks/navigation/NavigationExecutionEvidence.java",
            "src/main/java/com/qanize/automation/checks/forms/FormExecutionEvidence.java",
            "src/main/java/com/qanize/automation/checks/search/SearchExecutionEvidence.java")) {
            String code = code(Files.readString(Path.of(file)));
            assertFalse(code.contains("ThreadLocal"), file + " introduced a ThreadLocal");
            assertFalse(code.contains("static EvidenceScope"), file + " introduced global scope state");
            assertTrue(code.contains("private final EvidenceScope scope;"),
                file + " must hold its scope as immutable instance state");
        }
    }

    /** Source with comments removed, so a guard cannot match documentation. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private List<String> linesFor(String caseId) throws IOException {
        return steps().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + caseId + "\"")).toList();
    }

    private static List<Integer> sequences(List<String> lines) {
        return lines.stream().map(line -> {
            int at = line.indexOf("\"sequence\":");
            int start = at + "\"sequence\":".length();
            int end = start;
            while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
            return Integer.parseInt(line.substring(start, end));
        }).toList();
    }
}
