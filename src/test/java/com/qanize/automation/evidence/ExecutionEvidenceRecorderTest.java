package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;
import com.qanize.automation.model.TestedItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persistence boundary: envelope, size caps, artifact separation and failure tolerance. */
@Tag("unit")
class ExecutionEvidenceRecorderTest {

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

    private static ExecutionStep step(String expected, String actual) {
        return new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.DOM_INSPECTION, "Read the page title.",
            "https://example.com/", "", expected, actual, ExecutionStep.Status.PASSED, 7, ""
        );
    }

    private List<String> stepLines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    // --- envelope ---------------------------------------------------------------------------

    @Test
    void theLineCarriesTheCorrelationTheFlattenedStepCannotHold() throws IOException {
        useRunDirectory();
        ExecutionEvidenceRecorder.record("SMOKE", "smoke-page-1", step("e", "a"));

        String line = stepLines().getFirst();
        assertTrue(line.contains("\"record\":\"EXECUTION_STEP\""), line);
        assertTrue(line.contains("\"checkId\":\"SMOKE\""), line);
        assertTrue(line.contains("\"testCaseId\":\"smoke-page-1\""), line);
        assertTrue(line.contains("\"sequence\":1"), line);
    }

    @Test
    void anAbsentErrorCategoryIsOmittedRatherThanWrittenAsAnEmptyValue() {
        String line = ExecutionEvidenceRecorder.toJsonLine("SMOKE", "smoke-page-1", step("e", "a"), null);
        assertFalse(line.contains("errorCategory"), line);
    }

    @Test
    void aPresentErrorCategoryIsWrittenOnTheLine() {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-page-1", step("e", "a"), ErrorCategory.INFRASTRUCTURE_ERROR);
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
    }

    // --- size caps --------------------------------------------------------------------------

    @Test
    void textIsCappedDeterministicallyAtTheDocumentedBoundary() {
        String long1 = "a".repeat(ExecutionEvidenceRecorder.MAX_TEXT_LENGTH);
        String long2 = "a".repeat(ExecutionEvidenceRecorder.MAX_TEXT_LENGTH + 1);

        assertEquals(long1, ExecutionEvidenceRecorder.cap(long1), "exactly at the cap is untouched");
        assertEquals(long1 + "…", ExecutionEvidenceRecorder.cap(long2), "one over is truncated");
        // Deterministic: the same input always truncates identically.
        assertEquals(ExecutionEvidenceRecorder.cap(long2), ExecutionEvidenceRecorder.cap(long2));
    }

    @Test
    void truncationIsObservableAndNamesEveryAffectedField() {
        String oversized = "x".repeat(ExecutionEvidenceRecorder.MAX_TEXT_LENGTH + 500);
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-page-1", step(oversized, oversized), null);

        // Never silently shortened: the line says which fields were cut.
        assertTrue(line.contains("\"truncated\":\"expectedObservation,actualObservation\""), line);
        assertTrue(line.contains("…"), line);
    }

    @Test
    void aLineWithinTheCapCarriesNoTruncationMarker() {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-page-1", step("short", "also short"), null);
        assertFalse(line.contains("truncated"), line);
    }

    @Test
    void newlinesAreFlattenedSoOneRecordIsAlwaysOneLine() {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-page-1", step("e", "line one\nline two"), null);

        assertFalse(line.contains("\n"), line);
        assertTrue(line.contains("line one line two"), line);
    }

    // --- artifact separation and contract 1.0 safety --------------------------------------------

    @Test
    void contractOneEvidenceRemainsReadableWhenStepsAreRecordedAlongside() throws IOException {
        useRunDirectory();
        Path contractOne = runDirectory.resolve("evidence.jsonl");

        EvidenceRecorder.record(TestedItem.builder("SMOKE", TestedItem.ItemType.PAGE)
            .id("smoke-page-1").name("Submitted page").pageUrl("https://example.com/")
            .status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso()).build());
        ExecutionEvidenceRecorder.record("SMOKE", "smoke-page-1", step("e", "a"));

        // The 1.0 reader must see exactly its own record and produce no parse error, because a
        // parse error becomes ANALYZER_INTERNAL_ERROR and would fail an otherwise healthy run.
        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
        assertFalse(result.hasParseErrors());

        // And the two artifacts stay physically separate.
        assertEquals(1, Files.readAllLines(contractOne).size());
        assertEquals(1, stepLines().size());
    }

    @Test
    void aHistoricalEvidenceFileIsUnaffectedByThisSlice() throws IOException {
        useRunDirectory();
        Path contractOne = runDirectory.resolve("evidence.jsonl");
        String historical =
            "{\"id\":\"smoke-page-1\",\"testType\":\"SMOKE\",\"itemType\":\"PAGE\",\"status\":\"PASSED\"}";
        Files.writeString(contractOne, historical + System.lineSeparator());

        ExecutionEvidenceRecorder.record("SMOKE", "smoke-page-1", step("e", "a"));

        assertEquals(List.of(historical), Files.readAllLines(contractOne));
        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
    }

    @Test
    void theStepsFileSitsBesideTheEvidenceFileInTheSameRunDirectory() {
        useRunDirectory();
        Path steps = ExecutionEvidenceRecorder.executionEvidenceFile();

        assertEquals(runDirectory, steps.getParent());
        assertEquals(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME,
            steps.getFileName().toString());
    }

    // --- failure tolerance ---------------------------------------------------------------------

    @Test
    void recordingIsANoOpWithoutARunDirectory() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);

        ExecutionEvidenceRecorder.record("SMOKE", "smoke-page-1", step("e", "a"));

        assertEquals(List.of(), stepLines());
    }

    @Test
    void aNullStepIsIgnoredRatherThanThrowingIntoTheCheck() throws IOException {
        useRunDirectory();

        // A reporting problem must never replace the assertion the check was about to make.
        ExecutionEvidenceRecorder.record("SMOKE", "smoke-page-1", null);

        assertEquals(List.of(), stepLines());
    }

    @Test
    void appendingKeepsEveryEarlierStepSoAMidRunCrashLosesNothing() throws IOException {
        useRunDirectory();
        for (int sequence = 1; sequence <= 3; sequence++) {
            ExecutionEvidenceRecorder.record("SMOKE", "smoke-page-1", new ExecutionStep(
                sequence, Phase.ACTION, ExecutionMethod.NAVIGATE, "Step " + sequence,
                "", "", "", "", ExecutionStep.Status.PASSED, sequence, ""
            ));
        }

        List<String> lines = stepLines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("Step 1"));
        assertTrue(lines.get(2).contains("Step 3"));
    }
}
