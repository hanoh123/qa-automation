package com.qanize.automation.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;
import com.qanize.automation.runtime.AutomationRunPaths;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence file is produced by the real recorder and read by the committed reader, so these
 * tests exercise the whole chain rather than a hand-built fixture that only resembles it.
 */
@Tag("unit")
class ExecutionTimelineJsonWriterTest {

    private final ExecutionTimelineJsonWriter writer = new ExecutionTimelineJsonWriter();

    // ---------------------------------------------------------------- artifact production

    @Test
    void aCompletedRunProducesTheTimelineArtifact() throws IOException {
        AutomationRunPaths paths = runWithEvidence("produced",
            line(CheckId.SMOKE, "smoke-page-1", step(1)));

        Path artifact = writer.writeIfPossible(paths).orElseThrow();

        assertEquals(ExecutionTimelineJsonWriter.EXECUTION_TIMELINE_FILE_NAME,
            artifact.getFileName().toString());
        assertTrue(Files.isRegularFile(artifact));
        String json = Files.readString(artifact, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": \"1.1\""));
        assertTrue(json.contains("\"runId\": \"" + paths.runId() + "\""));
        assertTrue(json.contains("\"sourcePresent\": true"));
    }

    @Test
    void theArtifactSitsBesideTheOtherRunFiles() throws IOException {
        AutomationRunPaths paths = runWithEvidence("beside", line(CheckId.SMOKE, "smoke-page-1", step(1)));
        Path artifact = writer.writeIfPossible(paths).orElseThrow();
        assertEquals(paths.runDirectory().toAbsolutePath().normalize(), artifact.getParent());
    }

    @Test
    void everyReportedCheckIdCanBeSerialized() throws IOException {
        List<String> lines = List.of(
            line(CheckId.SMOKE, "smoke-page-1", step(1)),
            line(CheckId.NAVIGATION, "nav-1", step(1)),
            line(CheckId.LINKS, "link-1", step(1)),
            line(CheckId.FORMS, "form-1", step(1)),
            line(CheckId.SEARCH, "search-input", step(1)),
            line(CheckId.LOGIN, "login-1", step(1)),
            line(CheckId.GAME_LAUNCH, "game-launch", step(1)));

        String json = json(runWithEvidence("all-six", lines.toArray(String[]::new)));

        for (CheckId checkId : CheckId.productChecks()) {
            assertTrue(json.contains("\"checkId\": \"" + checkId.name() + "\""),
                checkId + " is missing from the timeline");
        }
    }

    // ---------------------------------------------------------------- order

    @Test
    void checkCaseAndStepOrderIsPreserved() throws IOException {
        String json = json(runWithEvidence("order",
            line(CheckId.LOGIN, "login-1", step(1, "first")),
            line(CheckId.SMOKE, "smoke-page-1", step(1, "third")),
            line(CheckId.LOGIN, "login-1", step(2, "second"))));

        assertTrue(json.indexOf("\"LOGIN\"") < json.indexOf("\"SMOKE\""),
            "Checks must keep the order they first appeared in");
        assertTrue(json.indexOf("first") < json.indexOf("second"), "Steps must keep persisted order");
        assertTrue(json.indexOf("second") < json.indexOf("third"));
    }

    @Test
    void caseOrderWithinACheckIsPreserved() throws IOException {
        String json = json(runWithEvidence("case-order",
            line(CheckId.LINKS, "link-9", step(1)),
            line(CheckId.LINKS, "link-2", step(1))));
        assertTrue(json.indexOf("link-9") < json.indexOf("link-2"));
    }

    // ---------------------------------------------------------------- evidence states

    @Test
    void missingEvidenceProducesSourcePresentFalse() throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(uniqueRunId("missing"));
        Files.createDirectories(paths.runDirectory());

        String json = Files.readString(writer.writeIfPossible(paths).orElseThrow(), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"sourcePresent\": false"));
        assertTrue(json.contains("\"checks\": []"));
        assertTrue(json.contains("\"errors\": []"));
    }

    @Test
    void anEmptyEvidenceFileStaysDifferentFromAMissingOne() throws IOException {
        AutomationRunPaths empty = runWithEvidence("empty");
        AutomationRunPaths absent = AutomationRunPaths.forRunId(uniqueRunId("absent"));
        Files.createDirectories(absent.runDirectory());

        assertTrue(json(empty).contains("\"sourcePresent\": true"),
            "A file that exists but recorded nothing is not the same as no file at all");
        assertTrue(Files.readString(writer.writeIfPossible(absent).orElseThrow(), StandardCharsets.UTF_8)
            .contains("\"sourcePresent\": false"));
    }

    @Test
    void anEmptyEvidenceFileYieldsNoChecksAndNoErrors() throws IOException {
        String json = json(runWithEvidence("empty-clean"));
        assertTrue(json.contains("\"checks\": []"));
        assertTrue(json.contains("\"errors\": []"));
    }

    // ---------------------------------------------------------------- partial and invalid

    @Test
    void aRejectedCaseDoesNotRemoveItsValidSiblings() throws IOException {
        String json = json(runWithEvidence("siblings",
            line(CheckId.LINKS, "link-1", step(1)),
            line(CheckId.LINKS, "link-1", step(3)),
            line(CheckId.LINKS, "link-2", step(1)),
            line(CheckId.LINKS, "link-2", step(2))));

        assertTrue(json.contains("link-2"), "The sound case must survive its broken sibling");
        assertFalse(json.contains("\"testCaseId\": \"link-1\""), "The broken case must not be presented");
        assertTrue(json.contains("SEQUENCE_GAP"));
    }

    @Test
    void aSequenceGapIsReportedAsAnError() throws IOException {
        String json = json(runWithEvidence("gap",
            line(CheckId.SMOKE, "smoke-page-1", step(1)),
            line(CheckId.SMOKE, "smoke-page-1", step(3))));
        assertTrue(json.contains("\"code\": \"SEQUENCE_GAP\""));
        assertTrue(json.contains("\"lineNumber\": 0"), "A case-level problem spans lines, so it carries none");
    }

    @Test
    void stepsOutOfOrderAreReported() throws IOException {
        String json = json(runWithEvidence("descending",
            line(CheckId.SMOKE, "smoke-page-1", step(1)),
            line(CheckId.SMOKE, "smoke-page-1", step(2)),
            line(CheckId.SMOKE, "smoke-page-1", step(1))));
        assertTrue(json.contains("\"code\": \"SEQUENCE_OUT_OF_ORDER\""));
    }

    @Test
    void aMalformedLineIsReportedWithItsLineNumber() throws IOException {
        String json = json(runWithEvidence("malformed",
            line(CheckId.SMOKE, "smoke-page-1", step(1)),
            "{\"record\":\"EXECUTION_STEP\","));
        assertTrue(json.contains("\"code\": \"MALFORMED_LINE\""));
        assertTrue(json.contains("\"lineNumber\": 2"));
    }

    // ---------------------------------------------------------------- field fidelity

    @Test
    void everyStepFieldRoundTripsIntoTheArtifact() throws IOException {
        ExecutionStep full = new ExecutionStep(1, Phase.ACTION, ExecutionMethod.CLICK,
            "clicked the primary navigation link", "Products", "css=nav a.products",
            "the products page opens", "the products page opened",
            ExecutionStep.Status.PASSED, 742, "screenshot-3.png");

        String json = json(runWithEvidence("fields",
            ExecutionEvidenceRecorder.toJsonLine("NAVIGATION", "nav-1", full, null)));

        assertTrue(json.contains("\"sequence\": 1"));
        assertTrue(json.contains("\"phase\": \"ACTION\""));
        assertTrue(json.contains("\"method\": \"CLICK\""));
        assertTrue(json.contains("\"action\": \"clicked the primary navigation link\""));
        assertTrue(json.contains("\"targetLabel\": \"Products\""));
        assertTrue(json.contains("\"sanitizedLocator\": \"css=nav a.products\""));
        assertTrue(json.contains("\"expectedObservation\": \"the products page opens\""));
        assertTrue(json.contains("\"actualObservation\": \"the products page opened\""));
        assertTrue(json.contains("\"status\": \"PASSED\""));
        assertTrue(json.contains("\"durationMs\": 742"));
        assertTrue(json.contains("\"evidenceRef\": \"screenshot-3.png\""));
    }

    @Test
    void absentOptionalFieldsAreOmittedRatherThanWrittenEmpty() throws IOException {
        ExecutionStep sparse = new ExecutionStep(1, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "opened the start page", null, null, null, null, ExecutionStep.Status.PASSED, 0, null);

        String json = json(runWithEvidence("sparse",
            ExecutionEvidenceRecorder.toJsonLine("SMOKE", "smoke-page-1", sparse, null)));

        assertFalse(json.contains("\"targetLabel\""), "An unrecorded field must be absent, not empty");
        assertFalse(json.contains("\"sanitizedLocator\""));
        assertFalse(json.contains("\"evidenceRef\""));
        assertTrue(json.contains("\"durationMs\": 0"), "A real zero is still a recorded value");
    }

    @Test
    void anErrorCategoryRoundTripsOnAFailedStep() throws IOException {
        String json = json(runWithEvidence("category", ExecutionEvidenceRecorder.toJsonLine(
            "LINKS", "link-4", step(1, ExecutionStep.Status.FAILED), ErrorCategory.WEBSITE_FINDING)));
        assertTrue(json.contains("\"errorCategory\": \"WEBSITE_FINDING\""));
    }

    @Test
    void aPassingStepCarriesNoErrorCategory() throws IOException {
        assertFalse(json(runWithEvidence("no-category", line(CheckId.SMOKE, "smoke-page-1", step(1))))
            .contains("errorCategory"));
    }

    @Test
    void aTruncationNoteRoundTripsAsAnArrayOfFieldNames() throws IOException {
        // Flatly larger than any plausible field limit. The size is deliberately not derived from
        // the recorder's internal cap: that constant is package-private on purpose, and a test
        // that restates it would keep passing if the real limit ever changed underneath it.
        // Truncation is instead proven from what the recorder actually did.
        String enormous = "y".repeat(10_000);
        ExecutionStep long_ = new ExecutionStep(1, Phase.ACTION, ExecutionMethod.TYPE, enormous,
            null, null, null, null, ExecutionStep.Status.PASSED, 5, null);

        String persisted = ExecutionEvidenceRecorder.toJsonLine("SEARCH", "search-input", long_, null);
        assertTrue(persisted.length() < enormous.length(),
            "The recorder must shorten an oversized field instead of persisting it whole");
        assertTrue(persisted.contains("\"truncated\":\"action\""),
            "The recorder must name the field it shortened");

        String json = json(runWithEvidence("truncated", persisted));

        assertTrue(json.contains("\"truncated\": [\"action\"]"),
            "The timeline must preserve which field was shortened");
        assertFalse(json.contains(enormous),
            "The full oversized value must not reappear in the artifact");
    }

    // ---------------------------------------------------------------- nothing invented

    @Test
    void noCaseLevelStatusNameTimestampOrCountIsInvented() throws IOException {
        String json = json(runWithEvidence("no-invention",
            line(CheckId.LOGIN, "login-1", step(1)),
            line(CheckId.LOGIN, "login-1", step(2, ExecutionStep.Status.FAILED))));

        for (String invented : List.of("\"name\"", "\"startedAt\"", "\"completedAt\"",
            "\"discoveryUnit\"", "\"discoveredCount\"", "\"testedCount\"", "\"primaryMethod\"",
            "\"passed\"", "\"failed\"", "\"skipped\"", "\"selected\"", "\"executed\"")) {
            assertFalse(json.contains(invented), "The timeline invented " + invented);
        }
        // A case exposes exactly its identifier and its steps, nothing more.
        String caseBlock = json.substring(json.indexOf("\"testCaseId\""), json.indexOf("\"steps\""));
        assertFalse(caseBlock.contains("\"status\""), "A case has no recorded status to report");
    }

    // ---------------------------------------------------------------- safety

    @Test
    void aRejectedRawLineNeverReachesTheArtifact() throws IOException {
        String sentinel = "Zq7-RAW-LINE-SENTINEL-Xf2";
        String json = json(runWithEvidence("raw-line",
            "{\"record\":\"EXECUTION_STEP\",\"actualObservation\":\"" + sentinel + "\""));

        assertTrue(json.contains("MALFORMED_LINE"), "The problem must still be reported");
        assertFalse(json.contains(sentinel), "A rejected line must never be republished");
    }

    @Test
    void aRejectedFieldValueNeverReachesTheArtifact() throws IOException {
        String sentinel = "SENTINEL_Kp91_STATUS";
        String damaged = line(CheckId.SMOKE, "smoke-page-1", step(1))
            .replace("\"status\":\"PASSED\"", "\"status\":\"" + sentinel + "\"");

        String json = json(runWithEvidence("rejected-value", damaged));

        assertTrue(json.contains("UNKNOWN_STATUS"));
        assertFalse(json.contains(sentinel), "An unparseable value is untrusted and must not be echoed");
    }

    @Test
    void syntheticCredentialsInTheEvidenceNeverReachTheArtifact() throws IOException {
        // Nothing should carry a secret this far, so this asserts the last line of defence: even
        // if one were present on a rejected line, the artifact must not repeat it.
        String password = "Pw-SENTINEL-9f31-NEVER-PERSIST";
        String username = "user-SENTINEL-4a02@example.test";
        String json = json(runWithEvidence("credentials",
            "{\"record\":\"EXECUTION_STEP\",\"action\":\"" + username + " / " + password + "\"",
            line(CheckId.LOGIN, "login-1", step(1))));

        assertFalse(json.contains(password), "A credential must never appear in the timeline");
        assertFalse(json.contains(username));
        assertFalse(json.contains("SENTINEL"));
    }

    @Test
    void stringsAreEscapedWithTheExistingJsonMechanism() throws IOException {
        ExecutionStep awkward = new ExecutionStep(1, Phase.ACTION, ExecutionMethod.ASSERT,
            "checked \"quoted\" text with a backslash \\ and a tab\there",
            null, null, null, null, ExecutionStep.Status.PASSED, 1, null);

        String json = json(runWithEvidence("escaping",
            ExecutionEvidenceRecorder.toJsonLine("SMOKE", "smoke-page-1", awkward, null)));

        assertTrue(json.contains("\\\"quoted\\\""), "Quotes must be escaped, not dropped");
        assertTrue(json.contains("\\\\"), "Backslashes must be escaped");
        assertTrue(json.contains("\\t"), "Control characters must be escaped");
        // Newlines are flattened by the recorder before persistence, so none can reach here.
        long braces = json.chars().filter(character -> character == '{').count();
        assertEquals(braces, json.chars().filter(character -> character == '}').count(),
            "Escaping must not corrupt the object structure");
    }

    // ---------------------------------------------------------------- failure isolation

    @Test
    void aWriteFailureIsSwallowedRatherThanThrown() throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(uniqueRunId("unwritable"));
        // A regular file where the run directory belongs: the artifact cannot be created.
        Files.createDirectories(paths.runDirectory().getParent());
        Files.writeString(paths.runDirectory(), "not a directory");

        assertTrue(writer.writeIfPossible(paths).isEmpty(),
            "A timeline problem must be reported as an absent artifact, never as an exception");
    }

    @Test
    void theReaderIsUsedRatherThanASecondParser() {
        // Guards the reuse decision: the writer must not grow its own JSON parsing.
        assertFalse(sourceOf().contains("charAt"), "The writer must not parse evidence itself");
        assertFalse(sourceOf().contains("parseFlatObject"), "Parsing belongs to the reader alone");
        assertTrue(sourceOf().contains("new ExecutionTimelineReader()"));
    }

    // ---------------------------------------------------------------- helpers

    private static String sourceOf() {
        try {
            return Files.readString(Path.of(
                "src/main/java/com/qanize/automation/reporting/ExecutionTimelineJsonWriter.java"));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String json(AutomationRunPaths paths) throws IOException {
        return Files.readString(writer.writeIfPossible(paths).orElseThrow(), StandardCharsets.UTF_8);
    }

    /** Builds a run directory holding exactly the given evidence lines. */
    private static AutomationRunPaths runWithEvidence(String label, String... lines) throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(uniqueRunId(label));
        Files.createDirectories(paths.runDirectory());
        Files.writeString(
            paths.resolveArtifact(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME),
            String.join(System.lineSeparator(), lines),
            StandardCharsets.UTF_8);
        return paths;
    }

    private static String uniqueRunId(String label) {
        return "timeline-" + label + "-" + System.nanoTime();
    }

    private static ExecutionStep step(int sequence) {
        return step(sequence, "performed a recorded action");
    }

    private static ExecutionStep step(int sequence, String action) {
        return new ExecutionStep(sequence, Phase.ACTION, ExecutionMethod.CLICK, action,
            "Target", "css=a", "something happens", "something happened",
            ExecutionStep.Status.PASSED, 12, null);
    }

    private static ExecutionStep step(int sequence, ExecutionStep.Status status) {
        return new ExecutionStep(sequence, Phase.ACTION, ExecutionMethod.CLICK,
            "performed a recorded action", "Target", "css=a", "something happens",
            "something happened", status, 12, null);
    }

    private static String line(CheckId checkId, String testCaseId, ExecutionStep step) {
        ErrorCategory category = step.status() == ExecutionStep.Status.PASSED
            ? null
            : ErrorCategory.WEBSITE_FINDING;
        return ExecutionEvidenceRecorder.toJsonLine(checkId.name(), testCaseId, step, category);
    }
}
