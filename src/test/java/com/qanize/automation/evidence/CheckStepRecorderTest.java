package com.qanize.automation.evidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step-numbering and step-writing mechanism the six checks share.
 *
 * <p>These tests pin the behaviour the per-check copies had before it was extracted: numbering is
 * per test case and 1-based, a guarded recorder swallows a reporting failure while an unguarded one
 * lets it out, and a failed step still consumes its sequence number.
 */
@Tag("unit")
class CheckStepRecorderTest {

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private void useRunDirectory() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
    }

    private List<String> stepLines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** One well-formed step. Named arguments are the only thing that varies between the tests. */
    private static ExecutionStep record(
        CheckStepRecorder recorder, String testCaseId, long durationMs
    ) {
        return recorder.append(
            testCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION, "Read the page title.",
            "https://example.com/", "A title is present.", "The title was read.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * A step the model refuses to build.
     *
     * <p>A null phase fails {@code ExecutionStep}'s own validation with an
     * {@code IllegalArgumentException}, which is exactly the construction failure the guarded
     * callers were written to contain.
     */
    private static ExecutionStep recordUnbuildable(CheckStepRecorder recorder, String testCaseId) {
        return recorder.append(
            testCaseId, null, ExecutionMethod.DOM_INSPECTION, "Read the page title.",
            "https://example.com/", "expected", "actual",
            ExecutionStep.Status.PASSED, 7, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    // --- sequence numbering -------------------------------------------------------------------

    @Test
    void theFirstRecordedStepIsNumberOne() {
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("SMOKE");

        assertEquals(1, record(recorder, "smoke-page-1", 7).sequence());
        assertEquals(1, recorder.recordedSteps("smoke-page-1"));
    }

    @Test
    void furtherStepsForTheSameTestCaseIncrementByOne() {
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("LINKS");

        assertEquals(1, record(recorder, "link-1", 7).sequence());
        assertEquals(2, record(recorder, "link-1", 7).sequence());
        assertEquals(3, record(recorder, "link-1", 7).sequence());
        assertEquals(3, recorder.recordedSteps("link-1"));
    }

    @Test
    void adifferentTestCaseStartsItsOwnNumberingAtOne() {
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("LINKS");

        record(recorder, "link-1", 7);
        record(recorder, "link-1", 7);

        // The second case must not continue the first one's numbering: contract 1.1 numbers steps
        // within a case, so a shared counter would misreport both cases.
        assertEquals(1, record(recorder, "link-2", 7).sequence());
        assertEquals(2, recorder.recordedSteps("link-1"));
        assertEquals(1, recorder.recordedSteps("link-2"));
    }

    @Test
    void recordedStepsIsZeroForATestCaseThatHasRecordedNothing() {
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("FORMS");
        record(recorder, "form-1", 7);

        assertEquals(1, recorder.recordedSteps("form-1"));
        assertEquals(0, recorder.recordedSteps("form-2"));
    }

    // --- duration normalization ---------------------------------------------------------------

    @Test
    void aNegativeDurationIsClampedToZeroRatherThanRejected() {
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("SMOKE");

        // Math.max(0, durationMs) happens before the model's non-negative invariant, so a clock
        // that went backwards costs the timing, never the step.
        assertEquals(0, record(recorder, "smoke-page-1", -1).durationMs());
        assertEquals(0, record(recorder, "smoke-page-1", Long.MIN_VALUE).durationMs());
        assertEquals(9, record(recorder, "smoke-page-1", 9).durationMs());
    }

    // --- guarded versus unguarded -------------------------------------------------------------

    @Test
    void aGuardedRecorderSwallowsAReportingFailureAndLogsItsOwnPrefix() {
        CheckStepRecorder recorder = CheckStepRecorder.guarded("LINKS", "link-step-record-failed");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertNull(recordUnbuildable(recorder, "link-1"),
                "A swallowed reporting failure returns null rather than a step");
        } finally {
            System.setOut(originalOut);
        }

        String logged = captured.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("QA Evidence | link-step-record-failed | "),
            "The diagnostic must keep the exact prefix the check used before extraction: " + logged);
        assertTrue(logged.contains("ExecutionStep.phase is required"),
            "The cause must still be reported: " + logged);
    }

    @Test
    void anUnguardedRecorderLetsAReportingFailureReachTheCaller() {
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("NAVIGATION");

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class, () -> recordUnbuildable(recorder, "nav-1"));
        assertEquals("ExecutionStep.phase is required", failure.getMessage());
    }

    @Test
    void aFailedStepStillConsumesItsSequenceNumber() {
        CheckStepRecorder recorder = CheckStepRecorder.guarded("FORMS", "form-step-record-failed");
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            record(recorder, "form-1", 7);
            recordUnbuildable(recorder, "form-1");
        } finally {
            System.setOut(originalOut);
        }

        // The number is taken before the step is built, so the lost step leaves a visible gap
        // rather than being silently renumbered away.
        assertEquals(2, recorder.recordedSteps("form-1"));
        assertEquals(3, record(recorder, "form-1", 7).sequence());
    }

    // --- what actually reaches the file -------------------------------------------------------

    @Test
    void theRecordedStepIsWrittenUnderTheCheckAndTestCaseItWasGiven() throws IOException {
        useRunDirectory();
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("SEARCH");

        ExecutionStep step = recorder.append(
            "search-input", Phase.ACTION, ExecutionMethod.TYPE, "Typed the search term.",
            "#q", "The field accepts the term.", "The field accepted the term.",
            ExecutionStep.Status.PASSED, 12, null
        );

        assertNotNull(step);
        List<String> lines = stepLines();
        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.contains("\"checkId\":\"SEARCH\""), line);
        assertTrue(line.contains("\"testCaseId\":\"search-input\""), line);
        assertTrue(line.contains("\"sequence\":1"), line);
        assertTrue(line.contains("\"durationMs\":12"), line);
        assertTrue(line.contains("Typed the search term."), line);
    }

    @Test
    void everyStepIsAppendedInTheOrderItWasRecorded() throws IOException {
        useRunDirectory();
        CheckStepRecorder recorder = CheckStepRecorder.unguarded("NAVIGATION");

        record(recorder, "nav-1", 1);
        record(recorder, "nav-2", 2);
        record(recorder, "nav-1", 3);

        List<String> lines = stepLines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"testCaseId\":\"nav-1\"")
            && lines.get(0).contains("\"sequence\":1"), lines.get(0));
        assertTrue(lines.get(1).contains("\"testCaseId\":\"nav-2\"")
            && lines.get(1).contains("\"sequence\":1"), lines.get(1));
        assertTrue(lines.get(2).contains("\"testCaseId\":\"nav-1\"")
            && lines.get(2).contains("\"sequence\":2"), lines.get(2));
    }
}
