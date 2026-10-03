package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures are produced by the real writer, {@link ExecutionEvidenceRecorder#toJsonLine}, so these
 * tests prove the reader can read what the analyzer actually writes rather than what a hand-typed
 * JSON string in a test file happens to look like. Where a corrupt line is needed, a real line is
 * damaged in one specific place, so everything else about it stays authentic.
 */
@Tag("unit")
class ExecutionTimelineReaderTest {

    private final ExecutionTimelineReader reader = new ExecutionTimelineReader();

    // ---------------------------------------------------------------- source states

    @Test
    void anAbsentFileIsNotAnEmptyRun(@TempDir Path directory) {
        ExecutionTimelineReader.Result result = reader.read(directory.resolve("execution-evidence.jsonl"));
        assertFalse(result.sourcePresent(), "A missing file must not claim the analyzer recorded nothing");
        assertTrue(result.checks().isEmpty());
        assertTrue(result.errors().isEmpty(), "A missing file is a normal state, not an error");
    }

    @Test
    void aNullPathIsReportedAsAnAbsentSource() {
        ExecutionTimelineReader.Result result = reader.read(null);
        assertFalse(result.sourcePresent());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void aDirectoryIsNotMistakenForAnEvidenceFile(@TempDir Path directory) {
        assertFalse(reader.read(directory).sourcePresent());
    }

    @Test
    void anEmptyFileIsPresentButYieldsNoChecks(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory));
        assertTrue(result.sourcePresent(), "The file existed, and that is a different fact from being absent");
        assertTrue(result.isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void blankLinesAreIgnoredRatherThanReportedAsCorrupt(@TempDir Path directory) throws IOException {
        Path file = write(directory, "", line(CheckId.SMOKE, "smoke-1", step(1)), "   ", "");
        ExecutionTimelineReader.Result result = reader.read(file);
        assertTrue(result.errors().isEmpty());
        assertEquals(1, onlyCase(result).steps().size());
    }

    // ---------------------------------------------------------------- fidelity

    @Test
    void everyPersistedFieldSurvivesTheRoundTrip(@TempDir Path directory) throws IOException {
        ExecutionStep written = new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.CLICK, "clicked the primary navigation link",
            "Products", "css=nav a.products", "the products page opens", "the products page opened",
            ExecutionStep.Status.PASSED, 742, "screenshot-3.png");

        ExecutionStepEnvelope envelope = onlyStep(reader.read(
            write(directory, ExecutionEvidenceRecorder.toJsonLine("NAVIGATION", "nav-1", written, null))));

        assertEquals(ExecutionEvidenceRecorder.EXECUTION_STEP_RECORD, envelope.record());
        assertEquals(CheckId.NAVIGATION, envelope.checkId());
        assertEquals("nav-1", envelope.testCaseId());
        assertEquals(written, envelope.step(), "The reconstructed step must equal the one that was written");
        assertNull(envelope.errorCategory());
        assertTrue(envelope.truncatedFields().isEmpty());
    }

    @Test
    void anErrorCategoryOnAFailedStepIsPreserved(@TempDir Path directory) throws IOException {
        ExecutionStepEnvelope envelope = onlyStep(reader.read(write(directory,
            ExecutionEvidenceRecorder.toJsonLine(
                "LINKS", "link-4", step(1, ExecutionStep.Status.FAILED), ErrorCategory.WEBSITE_FINDING))));
        assertEquals(ErrorCategory.WEBSITE_FINDING, envelope.errorCategory());
    }

    @Test
    void omittedOptionalTextIsReadBackAsEmptyNotNull(@TempDir Path directory) throws IOException {
        // The writer skips blank optional fields entirely, so they are absent on the line.
        ExecutionStep sparse = new ExecutionStep(
            1, Phase.SETUP, ExecutionMethod.NAVIGATE, "opened the start page",
            null, null, null, null, ExecutionStep.Status.PASSED, 0, null);

        ExecutionStep readBack = onlyStep(reader.read(
            write(directory, ExecutionEvidenceRecorder.toJsonLine("SMOKE", "smoke-1", sparse, null)))).step();

        assertEquals("", readBack.targetLabel());
        assertEquals("", readBack.sanitizedLocator());
        assertEquals("", readBack.expectedObservation());
        assertEquals("", readBack.actualObservation());
        assertEquals("", readBack.evidenceRef());
        assertEquals(sparse, readBack);
    }

    @Test
    void aTruncationNoteIsPreservedAsFieldNames(@TempDir Path directory) throws IOException {
        String enormous = "x".repeat(ExecutionEvidenceRecorder.MAX_TEXT_LENGTH + 500);
        ExecutionStep long_ = new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.TYPE, enormous, null, null, null, enormous,
            ExecutionStep.Status.PASSED, 5, null);

        ExecutionStepEnvelope envelope = onlyStep(reader.read(
            write(directory, ExecutionEvidenceRecorder.toJsonLine("SEARCH", "search-1", long_, null))));

        assertTrue(envelope.truncated(), "A shortened line must stay visibly shortened after reading");
        assertTrue(envelope.truncatedFields().contains("action"));
        assertTrue(envelope.truncatedFields().contains("actualObservation"));
    }

    @Test
    void aLineWithNothingTruncatedHasNoTruncationNote(@TempDir Path directory) throws IOException {
        assertFalse(onlyStep(reader.read(write(directory, line(CheckId.SMOKE, "smoke-1", step(1))))).truncated());
    }

    // ---------------------------------------------------------------- grouping and order

    @Test
    void stepsKeepThePreciseOrderTheyWerePersistedIn(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.FORMS, "form-1", step(1, "first")),
            line(CheckId.FORMS, "form-1", step(2, "second")),
            line(CheckId.FORMS, "form-1", step(3, "third")));

        List<ExecutionStepEnvelope> steps = onlyCase(reader.read(file)).steps();
        assertEquals(List.of("first", "second", "third"),
            steps.stream().map(envelope -> envelope.step().action()).toList());
    }

    @Test
    void checksAppearInTheOrderTheyFirstAppeared(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.LOGIN, "login-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(1)),
            line(CheckId.LOGIN, "login-1", step(2)));

        assertEquals(List.of(CheckId.LOGIN, CheckId.SMOKE),
            reader.read(file).checks().stream().map(ExecutionTimelineReader.CheckTimeline::checkId).toList());
    }

    @Test
    void casesAppearInTheOrderTheyFirstAppearedWithinTheirCheck(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.LINKS, "link-9", step(1)),
            line(CheckId.LINKS, "link-2", step(1)),
            line(CheckId.LINKS, "link-9", step(2)));

        assertEquals(List.of("link-9", "link-2"),
            reader.read(file).checks().getFirst().cases().stream()
                .map(ExecutionTimelineReader.CaseTimeline::testCaseId).toList());
    }

    @Test
    void interleavedCasesAreSeparatedWithoutLosingOrder(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.LINKS, "link-1", step(1, "a1")),
            line(CheckId.LINKS, "link-2", step(1, "b1")),
            line(CheckId.LINKS, "link-1", step(2, "a2")),
            line(CheckId.LINKS, "link-2", step(2, "b2")));

        List<ExecutionTimelineReader.CaseTimeline> cases = reader.read(file).checks().getFirst().cases();
        assertEquals(2, cases.size());
        assertEquals(List.of("a1", "a2"), actions(cases.getFirst()));
        assertEquals(List.of("b1", "b2"), actions(cases.get(1)));
    }

    @Test
    void anIdenticalCaseIdUnderTwoChecksStaysTwoCases(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.FORMS, "item-1", step(1)),
            line(CheckId.SEARCH, "item-1", step(1)));

        ExecutionTimelineReader.Result result = reader.read(file);
        assertEquals(2, result.checks().size(), "The case id alone is not the key; the check is part of it");
        result.checks().forEach(check -> assertEquals(1, check.cases().size()));
    }

    // ---------------------------------------------------------------- rejected lines

    @Test
    void aMalformedLineIsReportedAndTheRestIsStillRead(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            "{\"record\":\"EXECUTION_STEP\",",
            line(CheckId.SMOKE, "smoke-1", step(1)));

        ExecutionTimelineReader.Result result = reader.read(file);
        assertEquals(ExecutionTimelineReader.ErrorCode.MALFORMED_LINE, onlyError(result).code());
        assertEquals(1, onlyCase(result).steps().size(), "One bad line must not discard a good one");
    }

    @Test
    void aNestedValueIsRejectedRatherThanFlattened(@TempDir Path directory) throws IOException {
        Path file = write(directory, "{\"record\":\"EXECUTION_STEP\",\"step\":{\"sequence\":1}}");
        assertEquals(ExecutionTimelineReader.ErrorCode.MALFORMED_LINE, onlyError(reader.read(file)).code());
    }

    @Test
    void aRecordThatIsNotAnExecutionStepIsSkipped(@TempDir Path directory) throws IOException {
        Path file = write(directory, damage(CheckId.SMOKE, "smoke-1",
            "\"record\":\"EXECUTION_STEP\"", "\"record\":\"RUN_LIFECYCLE_EVENT\""));
        ExecutionTimelineReader.Result result = reader.read(file);
        assertEquals(ExecutionTimelineReader.ErrorCode.UNSUPPORTED_RECORD, onlyError(result).code());
        assertTrue(result.isEmpty());
    }

    @Test
    void anUnknownCheckIdIsReportedRatherThanGuessed(@TempDir Path directory) throws IOException {
        assertEquals(ExecutionTimelineReader.ErrorCode.UNKNOWN_CHECK_ID, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"checkId\":\"SMOKE\"", "\"checkId\":\"ACCESSIBILITY\"")))).code());
    }

    @Test
    void anUnknownPhaseIsReported(@TempDir Path directory) throws IOException {
        assertEquals(ExecutionTimelineReader.ErrorCode.UNKNOWN_PHASE, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"phase\":\"ACTION\"", "\"phase\":\"TELEPORT\"")))).code());
    }

    @Test
    void anUnknownExecutionMethodIsReported(@TempDir Path directory) throws IOException {
        assertEquals(ExecutionTimelineReader.ErrorCode.UNKNOWN_METHOD, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"method\":\"CLICK\"", "\"method\":\"TELEKINESIS\"")))).code());
    }

    @Test
    void anUnknownStatusIsReportedRatherThanTreatedAsPassing(@TempDir Path directory) throws IOException {
        assertEquals(ExecutionTimelineReader.ErrorCode.UNKNOWN_STATUS, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"status\":\"PASSED\"", "\"status\":\"PROBABLY\"")))).code());
    }

    @Test
    void anUnknownErrorCategoryIsReported(@TempDir Path directory) throws IOException {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-1", step(1, ExecutionStep.Status.FAILED), ErrorCategory.WEBSITE_FINDING);
        assertEquals(ExecutionTimelineReader.ErrorCode.UNKNOWN_ERROR_CATEGORY, onlyError(reader.read(
            write(directory, line.replace("\"WEBSITE_FINDING\"", "\"COSMIC_RAY\"")))).code());
    }

    @Test
    void aLineWithoutATestCaseIdCannotBeCorrelatedAndIsReported(@TempDir Path directory) throws IOException {
        // The writer omits blank text, so a blank case id produces a line with no such field.
        String line = ExecutionEvidenceRecorder.toJsonLine("SMOKE", "  ", step(1), null);
        assertEquals(ExecutionTimelineReader.ErrorCode.BLANK_TEST_CASE_ID, onlyError(reader.read(write(directory, line))).code());
    }

    @Test
    void aMissingNumericFieldIsReported(@TempDir Path directory) throws IOException {
        assertEquals(ExecutionTimelineReader.ErrorCode.MISSING_FIELD, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"sequence\":1,", "")))).code());
    }

    @Test
    void anUnparseableDurationIsReported(@TempDir Path directory) throws IOException {
        assertEquals(ExecutionTimelineReader.ErrorCode.MISSING_FIELD, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"durationMs\":12", "\"durationMs\":\"soon\"")))).code());
    }

    @Test
    void aLineTheModelItselfRejectsIsReportedAsAnInvalidStep(@TempDir Path directory) throws IOException {
        // Sequence 0 is not a valid step; the model refuses it and the reader must not force it.
        assertEquals(ExecutionTimelineReader.ErrorCode.INVALID_STEP, onlyError(reader.read(
            write(directory, damage(CheckId.SMOKE, "smoke-1", "\"sequence\":1,", "\"sequence\":0,")))).code());
    }

    // ---------------------------------------------------------------- the producer rule

    @Test
    void aPassingStepMayNotCarryAnErrorCategory(@TempDir Path directory) throws IOException {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-1", step(1, ExecutionStep.Status.PASSED), ErrorCategory.WEBSITE_FINDING);
        ExecutionTimelineReader.Result result = reader.read(write(directory, line));
        assertEquals(ExecutionTimelineReader.ErrorCode.STATUS_AND_CATEGORY_DISAGREE, onlyError(result).code());
        assertTrue(result.isEmpty(), "A self-contradicting line is not usable evidence");
    }

    @Test
    void aFailingStepWithoutAnErrorCategoryIsRejected(@TempDir Path directory) throws IOException {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-1", step(1, ExecutionStep.Status.FAILED), null);
        assertEquals(ExecutionTimelineReader.ErrorCode.STATUS_AND_CATEGORY_DISAGREE,
            onlyError(reader.read(write(directory, line))).code());
    }

    @Test
    void aSkippedStepWithoutAnErrorCategoryIsRejected(@TempDir Path directory) throws IOException {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-1", step(1, ExecutionStep.Status.SKIPPED), null);
        assertEquals(ExecutionTimelineReader.ErrorCode.STATUS_AND_CATEGORY_DISAGREE,
            onlyError(reader.read(write(directory, line))).code());
    }

    @Test
    void aSkippedStepWithACategoryIsAccepted(@TempDir Path directory) throws IOException {
        String line = ExecutionEvidenceRecorder.toJsonLine(
            "SMOKE", "smoke-1", step(1, ExecutionStep.Status.SKIPPED), ErrorCategory.NOT_APPLICABLE);
        ExecutionTimelineReader.Result result = reader.read(write(directory, line));
        assertTrue(result.errors().isEmpty());
        assertEquals(ErrorCategory.NOT_APPLICABLE, onlyStep(result).errorCategory());
    }

    // ---------------------------------------------------------------- sequence integrity

    @Test
    void aCaseThatDoesNotStartAtOneIsDropped(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(2)),
            line(CheckId.SMOKE, "smoke-1", step(3))));
        assertEquals(ExecutionTimelineReader.ErrorCode.SEQUENCE_DOES_NOT_START_AT_ONE, onlyError(result).code());
        assertTrue(result.isEmpty());
    }

    @Test
    void aGapInTheSequenceDropsTheCase(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(3))));
        assertEquals(ExecutionTimelineReader.ErrorCode.SEQUENCE_GAP, onlyError(result).code());
        assertTrue(result.isEmpty(), "A timeline with a hole in it must not be presented as complete");
    }

    @Test
    void aRepeatedSequenceDropsTheCase(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(1))));
        assertEquals(ExecutionTimelineReader.ErrorCode.DUPLICATE_SEQUENCE, onlyError(result).code());
    }

    @Test
    void stepsOutOfOrderAreReportedAndNeverQuietlySorted(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(3)),
            line(CheckId.SMOKE, "smoke-1", step(2))));
        assertEquals(ExecutionTimelineReader.ErrorCode.SEQUENCE_GAP, onlyError(result).code());
        assertTrue(result.isEmpty(), "Sorting would hide that the analyzer recorded them out of order");
    }

    @Test
    void aDescendingSequenceIsReportedAsOutOfOrder(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(2)),
            line(CheckId.SMOKE, "smoke-1", step(1))));
        assertEquals(ExecutionTimelineReader.ErrorCode.SEQUENCE_OUT_OF_ORDER, onlyError(result).code());
    }

    @Test
    void oneBrokenCaseDoesNotRemoveTheSoundOnesBesideIt(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.LINKS, "link-1", step(1)),
            line(CheckId.LINKS, "link-1", step(3)),
            line(CheckId.LINKS, "link-2", step(1)),
            line(CheckId.LINKS, "link-2", step(2))));

        ExecutionTimelineReader.CheckTimeline check = result.checks().getFirst();
        assertEquals(1, check.cases().size());
        assertEquals("link-2", check.cases().getFirst().testCaseId());
        assertEquals(1, result.errors().size());
    }

    @Test
    void aCheckWhoseEveryCaseIsBrokenDisappearsRatherThanAppearingEmpty(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.LINKS, "link-1", step(2)),
            line(CheckId.SMOKE, "smoke-1", step(1))));

        assertEquals(List.of(CheckId.SMOKE),
            result.checks().stream().map(ExecutionTimelineReader.CheckTimeline::checkId).toList());
    }

    // ---------------------------------------------------------------- safety of the errors

    @Test
    void anErrorNeverQuotesTheLineThatCausedIt(@TempDir Path directory) throws IOException {
        String secret = "Zq7-UNIQUE-SENTINEL-Xf2";
        Path file = write(directory, "{\"record\":\"EXECUTION_STEP\",\"actualObservation\":\"" + secret + "\"");

        ExecutionTimelineReader.Result result = reader.read(file);
        assertFalse(result.errors().isEmpty());
        result.errors().forEach(error -> assertFalse(error.explanation().contains(secret),
            "A read error must describe the problem, never republish the content"));
    }

    @Test
    void anErrorNeverRepeatsAnUnrecognisedValue(@TempDir Path directory) throws IOException {
        String sentinel = "SENTINEL_Kp91_VALUE";
        Path file = write(directory,
            damage(CheckId.SMOKE, "smoke-1", "\"status\":\"PASSED\"", "\"status\":\"" + sentinel + "\""));

        assertFalse(onlyError(reader.read(file)).explanation().contains(sentinel),
            "An unparseable value is untrusted content and must not be echoed back");
    }

    @Test
    void lineNumbersInErrorsAreOneBased(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            "{ this is not json",
            line(CheckId.SMOKE, "smoke-1", step(2)));
        assertEquals(2, onlyError(reader.read(file)).lineNumber());
    }

    @Test
    void aCaseLevelErrorCarriesNoLineNumberBecauseItSpansSeveral(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(3))));
        assertEquals(0, onlyError(result).lineNumber());
    }

    // ---------------------------------------------------------------- API guarantees

    @Test
    void everyReturnedCollectionIsImmutable(@TempDir Path directory) throws IOException {
        ExecutionTimelineReader.Result result = reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1))));

        assertThrows(UnsupportedOperationException.class, () -> result.checks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.checks().getFirst().cases().clear());
        assertThrows(UnsupportedOperationException.class, () -> onlyCase(result).steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> onlyStep(result).truncatedFields().clear());
    }

    @Test
    void readingDoesNotModifyTheSourceFile(@TempDir Path directory) throws IOException, NoSuchAlgorithmException {
        Path file = write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)),
            "{ corrupt",
            line(CheckId.SMOKE, "smoke-1", step(2)));

        String before = checksum(file);
        reader.read(file);
        assertEquals(before, checksum(file), "The reader must never repair or rewrite the evidence");
    }

    @Test
    void readingTheSameFileTwiceGivesTheSameAnswer(@TempDir Path directory) throws IOException {
        Path file = write(directory,
            line(CheckId.LINKS, "link-1", step(1)),
            line(CheckId.SMOKE, "smoke-1", step(1)),
            "{ corrupt");
        assertEquals(reader.read(file), reader.read(file));
    }

    @Test
    void theEnvelopeDoesNotPretendToBeATestCaseOrACheck(@TempDir Path directory) throws IOException {
        // Guards the scope of this slice: the reader stops at steps, because the file has no case
        // name, no case status, no timestamps and no discovery counts to build the richer records.
        ExecutionStepEnvelope envelope = onlyStep(reader.read(write(directory,
            line(CheckId.SMOKE, "smoke-1", step(1)))));
        assertNotNull(envelope.step());
        assertFalse(List.of(ExecutionStepEnvelope.class.getRecordComponents()).stream()
                .anyMatch(component -> component.getType().getSimpleName().equals("ConcreteTestCase")
                    || component.getType().getSimpleName().equals("CheckExecution")
                    || component.getType().getSimpleName().equals("RunLifecycleEvent")),
            "This slice must not invent the models whose data does not exist yet");
    }

    @Test
    void everyRecorderWritesACheckIdThisReaderCanResolve() throws IOException {
        // The reader resolves checkId with CheckId.valueOf, and the six recorders each hold that
        // name as a plain string literal. Nothing in the compiler connects the two, so a rename on
        // either side would make the reader reject every real line while all other tests still
        // pass. This is the test that would catch it.
        // Each recorder now lives in its own check package, so this walks the check tree instead
        // of listing one flat directory. What it proves is unchanged: every recorder that exists
        // declares a CHECK_ID this reader can resolve, and every check is represented once.
        Path checks = Path.of("src/main/java/com/qanize/automation/checks");
        List<CheckId> found = new ArrayList<>();
        List<Path> recorders;
        try (var files = Files.walk(checks)) {
            recorders = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith("ExecutionEvidence.java"))
                .sorted()
                .toList();
        }
        for (Path file : recorders) {
            Matcher matcher = Pattern.compile("CHECK_ID\\s*=\\s*\"([^\"]*)\"").matcher(Files.readString(file));
            assertTrue(matcher.find(), file.getFileName() + " must declare a CHECK_ID literal");
            String literal = matcher.group(1);
            assertTrue(Arrays.stream(CheckId.values()).anyMatch(id -> id.name().equals(literal)),
                file.getFileName() + " writes a checkId that CheckId does not define");
            found.add(CheckId.valueOf(literal));
        }
        // Every reported check must have exactly one recorder, so none can go silently unreadable.
        // The reverse is deliberately not asserted: a recorder may exist for an identity that is
        // registered but not yet reported, which is the state a check is in while it is being
        // built. Demanding the two sets be equal would either force an unfinished check into the
        // product's reported set or force its recorder to be deleted and rewritten later.
        assertEquals(found.stream().distinct().sorted().toList(), found.stream().sorted().toList(),
            "No check may be represented twice");
        assertTrue(found.containsAll(CheckId.productChecks()),
            "Every reported check must have a recorder; missing: "
                + CheckId.productChecks().stream().filter(id -> !found.contains(id)).toList());
    }

    // ---------------------------------------------------------------- helpers

    private static ExecutionStep step(int sequence) {
        return step(sequence, "performed a recorded action");
    }

    private static ExecutionStep step(int sequence, String action) {
        return new ExecutionStep(sequence, Phase.ACTION, ExecutionMethod.CLICK, action,
            "Target", "css=a", "something happens", "something happened",
            ExecutionStep.Status.PASSED, 12, null);
    }

    private static ExecutionStep step(int sequence, ExecutionStep.Status status) {
        return new ExecutionStep(sequence, Phase.ACTION, ExecutionMethod.CLICK, "performed a recorded action",
            "Target", "css=a", "something happens", "something happened", status, 12, null);
    }

    private static String line(CheckId checkId, String testCaseId, ExecutionStep step) {
        return ExecutionEvidenceRecorder.toJsonLine(checkId.name(), testCaseId, step, null);
    }

    /** A real line with exactly one substitution, so everything except the defect stays authentic. */
    private static String damage(CheckId checkId, String testCaseId, String find, String replacement) {
        String original = line(checkId, testCaseId, step(1));
        String damaged = original.replace(find, replacement);
        assertFalse(original.equals(damaged), "The fixture did not actually change: " + find);
        return damaged;
    }

    private static List<String> actions(ExecutionTimelineReader.CaseTimeline testCase) {
        return testCase.steps().stream().map(envelope -> envelope.step().action()).toList();
    }

    private static ExecutionTimelineReader.CaseTimeline onlyCase(ExecutionTimelineReader.Result result) {
        assertEquals(1, result.checks().size(), "Expected exactly one check");
        assertEquals(1, result.checks().getFirst().cases().size(), "Expected exactly one case");
        return result.checks().getFirst().cases().getFirst();
    }

    private static ExecutionStepEnvelope onlyStep(ExecutionTimelineReader.Result result) {
        List<ExecutionStepEnvelope> steps = onlyCase(result).steps();
        assertEquals(1, steps.size(), "Expected exactly one step");
        return steps.getFirst();
    }

    private static ExecutionTimelineReader.ReadError onlyError(ExecutionTimelineReader.Result result) {
        assertEquals(1, result.errors().size(), "Expected exactly one error, got " + result.errors());
        return result.errors().getFirst();
    }

    private static Path write(Path directory, String... lines) throws IOException {
        Path file = directory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        Files.writeString(file, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String checksum(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
