package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.testsupport.CheckSources;


/**
 * Proves the Basic/Smoke execution evidence without a browser or a website.
 *
 * <p>The Smoke check's actions are driven here directly, in the order the check performs them, so
 * the recorded phases, methods, ordering and categories are verifiable in the unit suite.
 */
@Tag("unit")
class SmokeExecutionEvidenceTest {

    private static final String URL = "https://example.com/";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private SmokeExecutionEvidence recordingTo(Path runDir) {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDir.resolve("evidence.jsonl").toString()
        );
        return new SmokeExecutionEvidence();
    }

    private List<String> persistedLines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** Drives the exact call order of a healthy Smoke run. */
    private SmokeExecutionEvidence successfulRun() {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.navigated(URL, 820);
        execution.readAddress(URL, URL, 3);
        execution.readTitle(URL, "Example Domain", 2);
        execution.readContent(URL, true, 4);
        execution.assertedUsablePage(URL, true, "Loaded with title \"Example Domain\".");
        return execution;
    }

    // --- ordering and sequence -------------------------------------------------------------

    @Test
    void stepsArePersistedInTheOrderTheActionsHappened() throws IOException {
        successfulRun();
        List<String> lines = persistedLines();

        assertEquals(5, lines.size());
        assertTrue(lines.get(0).contains("\"method\":\"NAVIGATE\""), lines.get(0));
        assertTrue(lines.get(1).contains("Read the address"), lines.get(1));
        assertTrue(lines.get(2).contains("Read the page title"), lines.get(2));
        assertTrue(lines.get(3).contains("Read the loaded document"), lines.get(3));
        assertTrue(lines.get(4).contains("\"method\":\"ASSERT\""), lines.get(4));
    }

    @Test
    void sequenceNumbersAreOneBasedGaplessAndMatchTheWriteOrder() throws IOException {
        successfulRun();
        List<String> lines = persistedLines();

        for (int index = 0; index < lines.size(); index++) {
            assertTrue(
                lines.get(index).contains("\"sequence\":" + (index + 1)),
                "line " + index + " should carry sequence " + (index + 1) + ": " + lines.get(index)
            );
        }
    }

    @Test
    void theRecordedStepCountMatchesTheActionsPerformed() {
        assertEquals(5, successfulRun().recordedSteps());
    }

    // --- phases and methods ----------------------------------------------------------------

    @Test
    void aSuccessfulRunProducesTheExpectedPhasesAndMethods() {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);

        assertStep(execution.navigated(URL, 1), Phase.ACTION, ExecutionMethod.NAVIGATE);
        assertStep(execution.readAddress(URL, URL, 1), Phase.ACTION, ExecutionMethod.DOM_INSPECTION);
        assertStep(execution.readTitle(URL, "T", 1), Phase.ACTION, ExecutionMethod.DOM_INSPECTION);
        assertStep(execution.readContent(URL, true, 1), Phase.ACTION, ExecutionMethod.DOM_INSPECTION);
        assertStep(execution.assertedUsablePage(URL, true, "ok"), Phase.ASSERTION, ExecutionMethod.ASSERT);
    }

    @Test
    void theRecorderCannotDescribeAnActionSmokeNeverPerforms() {
        // There is no click, type or HTTP entry point on the Smoke recorder at all, so a step
        // claiming one cannot be produced by this check.
        List<String> methodNames = List.of(SmokeExecutionEvidence.class.getDeclaredMethods())
            .stream().map(java.lang.reflect.Method::getName).toList();

        assertFalse(methodNames.contains("clicked"));
        assertFalse(methodNames.contains("typed"));
        assertFalse(methodNames.contains("requested"));
        assertFalse(methodNames.contains("step"));
    }

    // --- failures ---------------------------------------------------------------------------

    @Test
    void aBlankTitleIsRecordedAsAFailedStepCategorisedAsAWebsiteFinding() throws IOException {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.readTitle(URL, "  ", 5);

        String line = persistedLines().getFirst();
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
        assertTrue(line.contains("The page reported no title"), line);
    }

    @Test
    void aBrowserFailureIsInfrastructureRatherThanAWebsiteFinding() throws IOException {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.browserFailed(URL, "TimeoutException: session not created", 30_000);

        String line = persistedLines().getFirst();
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
        assertFalse(line.contains("WEBSITE_FINDING"), line);
    }

    @Test
    void aPassingStepCarriesNoErrorCategory() throws IOException {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.readContent(URL, true, 1);

        String line = persistedLines().getFirst();
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
        assertFalse(line.contains("errorCategory"), line);
    }

    @Test
    void aFailedAssertionIsRecordedBeforeTheAssertionSoTheVerdictSurvives() throws IOException {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.assertedUsablePage(URL, false, "The browser could not load the page.");

        // The check throws immediately afterwards; the evidence is already on disk.
        String line = persistedLines().getFirst();
        assertTrue(line.contains("\"phase\":\"ASSERTION\""), line);
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
    }

    // --- durations ---------------------------------------------------------------------------

    @Test
    void durationsArePersistedAndNeverNegative() throws IOException {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.navigated(URL, 820);
        // A clock that goes backwards must not produce a negative duration.
        execution.readAddress(URL, URL, -50);

        List<String> lines = persistedLines();
        assertTrue(lines.get(0).contains("\"durationMs\":820"), lines.get(0));
        assertTrue(lines.get(1).contains("\"durationMs\":0"), lines.get(1));
    }

    // --- artifact separation ------------------------------------------------------------------

    @Test
    void stepsNeverEnterTheContractOneEvidenceFile() throws IOException {
        successfulRun();

        Path contractOne = runDirectory.resolve("evidence.jsonl");
        assertFalse(
            Files.isRegularFile(contractOne),
            "contract 1.0 evidence must not be touched by execution-step recording"
        );
        assertNotNull(ExecutionEvidenceRecorder.executionEvidenceFile());
        assertEquals(5, persistedLines().size());
    }

    @Test
    void recordingIsANoOpWhenNoRunDirectoryIsConfigured() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        new SmokeExecutionEvidence().navigated(URL, 1);

        // A direct Maven invocation has no run directory: no steps, never fabricated ones.
        assertEquals(List.of(), persistedLines());
    }

    private static void assertStep(ExecutionStep step, Phase phase, ExecutionMethod method) {
        assertEquals(phase, step.phase(), step.action());
        assertEquals(method, step.method(), step.action());
        assertTrue(step.durationMs() >= 0);
    }

    // --- scope: only Basic/Smoke is instrumented in this slice ---------------------------------

    /**
     * Recorders stay bound to the check they describe.
     *
     * <p>This deliberately no longer states which checks are instrumented — that boundary moves
     * with each approved slice and is owned by
     * {@code NavigationExecutionEvidenceTest#onlyApprovedChecksAreInstrumented}. What it pins is
     * narrower and permanent: no other check may borrow the Smoke recorder, so a step can never be
     * attributed to the wrong check; and no check writes through the raw recorder, so every step
     * goes through a class that can only describe actions that check really performs.
     */
    @Test
    void noOtherCheckUsesTheSmokeRecorderAndNoCheckBypassesItsOwn() throws IOException {
        for (String other : List.of(
            "GenericNavigationTest", "GenericLinkValidationTest", "GenericFormDetectionTest",
            "GenericSearchTest", "GenericLoginTest"
        )) {
            String source = Files.readString(CheckSources.entryClass(other));
            assertFalse(
                source.contains("SmokeExecutionEvidence"),
                other + " must not record through the Smoke check's recorder"
            );
        }
        for (String check : List.of(
            "GenericSmokeTest", "GenericNavigationTest", "GenericLinkValidationTest",
            "GenericFormDetectionTest", "GenericSearchTest", "GenericLoginTest"
        )) {
            assertFalse(
                Files.readString(CheckSources.entryClass(check)).contains("ExecutionEvidenceRecorder"),
                check + " must record through its own evidence class, not the raw recorder"
            );
        }
        assertTrue(
            Files.readString(CheckSources.entryClass("GenericSmokeTest")).contains("SmokeExecutionEvidence"),
            "the Smoke check records through its own recorder"
        );
    }

    /**
     * Every recorded action follows the browser call that performed it.
     *
     * <p>Reading the source keeps this honest without a browser: if a recording call were ever
     * moved above its action, the evidence would claim something that had not happened yet.
     */
    @Test
    void noActionIsRecordedBeforeTheCallThatPerformsIt() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericSmokeTest"));
        String module = Files.readString(PAGE_HEALTH_MODULE);

        // The wrapper opens the page, then records that it did.
        assertOrdered(wrapper, "page.openUrl(requested);", "execution.navigated(");

        // Every page read is performed first and described afterwards.
        assertOrdered(module, "page.currentUrl();", "execution.readAddress(");
        assertOrdered(module, "page.title();", "execution.readTitle(");
        assertOrdered(module, "page.hasLoadedDocument();", "execution.readContent(");
        assertOrdered(module, "page.isTextPresent(expectedText);", "execution.searchedForExpectedText(");
        // The verdict is recorded before the result that reports it is returned.
        assertOrdered(module, "execution.assertedUsablePage(", "if (finalUrl == null");
        // ... and only then does the wrapper turn that result into JUnit.
        assertOrdered(wrapper, "new PageHealthModule(", "assertTrue(result.passed(), result.message());");
    }

    @Test
    void theWrapperStaysAThinJUnitFaceOverTheModule() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericSmokeTest"));

        // Surefire classification depends on these staying exactly as they are.
        assertTrue(wrapper.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertEquals(1, wrapper.split("@Test", -1).length - 1,
            "exactly one @Test, so the reported test count for SMOKE is unchanged");
        assertTrue(wrapper.contains("void configuredBaseUrlLoadsUsablePage()"),
            "the entry point keeps its name");
        assertTrue(wrapper.contains("assumeTrue(config.tests().smoke()"),
            "the selected-check gate cannot move into a module");
        assertTrue(wrapper.contains("new PageHealthModule(page, execution)"),
            "the same recorder instance is passed on, so sequence numbers continue");

        // And performs none of the inspection itself.
        for (String moved : List.of(
            "TestedItem.builder(", "page.getCurrentUrl()", "page.getTitle()",
            "page.hasLoadedDocument()", "page.isTextPresent(",
            "execution.readAddress(", "execution.readTitle(", "execution.readContent(",
            "execution.assertedUsablePage(", "smoke-expected-title", "smoke-expected-text")) {
            assertFalse(wrapper.contains(moved), moved + " belongs to PageHealthModule now");
        }
    }

    @Test
    void theModuleInspectsAndNeverNavigates() throws IOException {
        String module = Files.readString(PAGE_HEALTH_MODULE)
            .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");

        // Reusable on every crawled page later means it must never open one itself.
        for (String forbidden : List.of(
            "openUrl", "driver.get", "new ChromeDriver", "driver.quit",
            ".click(", ".submit()", "sendKeys", "org.junit")) {
            assertFalse(module.contains(forbidden), "PageHealthModule must not contain " + forbidden);
        }
    }

    /** Where the page-health inspection now lives; the wrapper keeps only the JUnit face. */
    private static final Path PAGE_HEALTH_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/smoke/PageHealthModule.java");

    private static void assertOrdered(String source, String earlier, String later) {
        int first = source.indexOf(earlier);
        int second = source.indexOf(later);
        assertTrue(first >= 0, "missing: " + earlier);
        assertTrue(second >= 0, "missing: " + later);
        assertTrue(first < second, earlier + " must appear before " + later);
    }

    /** Guards the categories the Smoke check is allowed to report. */
    @Test
    void smokeOnlyUsesWebsiteFindingAndInfrastructureCategories() throws IOException {
        SmokeExecutionEvidence execution = recordingTo(runDirectory);
        execution.readAddress(URL, "", 1);
        execution.readTitle(URL, null, 1);
        execution.readContent(URL, false, 1);
        execution.browserFailed(URL, "boom", 1);

        for (String line : persistedLines()) {
            boolean known = line.contains(ErrorCategory.WEBSITE_FINDING.name())
                || line.contains(ErrorCategory.INFRASTRUCTURE_ERROR.name());
            assertTrue(known, line);
        }
    }
}
