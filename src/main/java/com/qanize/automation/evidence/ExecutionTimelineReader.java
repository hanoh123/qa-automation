package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;

/**
 * Reads {@code execution-evidence.jsonl} and groups the persisted steps into a timeline.
 *
 * <p><strong>What this is not.</strong> It does not build {@code ConcreteTestCase} or
 * {@code CheckExecution}. Those records need a name, a status, timestamps, a discovery unit and
 * counts, and none of that is in this file. Producing them would mean guessing, so this reader
 * stops at what was actually written: which check, which case, and the steps in the order they
 * happened. Lifecycle events are not read either, because none are ever written.
 *
 * <p><strong>Nothing is repaired.</strong> The file is opened read-only and never rewritten. A
 * line that cannot be trusted becomes an explicit error rather than a silent omission, and a case
 * whose sequence is broken is dropped whole so a partial timeline cannot be mistaken for the
 * complete one — other cases in the same file are unaffected.
 *
 * <p><strong>Errors are safe to publish.</strong> They carry a line number, a code and a sentence
 * built from those two plus the correlation keys. The offending line, the value that failed to
 * parse, and anything the browser saw are never included.
 */
public final class ExecutionTimelineReader {

    /** Why a line or a case could not be trusted. Stable identifiers, safe to publish. */
    public enum ErrorCode {
        SOURCE_UNREADABLE,
        MALFORMED_LINE,
        UNSUPPORTED_RECORD,
        MISSING_FIELD,
        BLANK_TEST_CASE_ID,
        UNKNOWN_CHECK_ID,
        UNKNOWN_PHASE,
        UNKNOWN_METHOD,
        UNKNOWN_STATUS,
        UNKNOWN_ERROR_CATEGORY,
        INVALID_STEP,
        STATUS_AND_CATEGORY_DISAGREE,
        SEQUENCE_DOES_NOT_START_AT_ONE,
        DUPLICATE_SEQUENCE,
        SEQUENCE_GAP,
        SEQUENCE_OUT_OF_ORDER
    }

    /**
     * One problem, described without quoting anything the file contained.
     *
     * @param lineNumber 1-based line, or 0 when the problem belongs to a whole case or the file
     */
    public record ReadError(int lineNumber, ErrorCode code, String explanation) {
        public ReadError {
            explanation = explanation == null ? "" : explanation;
        }
    }

    /** The steps recorded for one {@code testCaseId}, in the order they were persisted. */
    public record CaseTimeline(CheckId checkId, String testCaseId, List<ExecutionStepEnvelope> steps) {
        public CaseTimeline {
            steps = List.copyOf(steps);
        }
    }

    /** Every case one check recorded, in the order the cases first appeared. */
    public record CheckTimeline(CheckId checkId, List<CaseTimeline> cases) {
        public CheckTimeline {
            cases = List.copyOf(cases);
        }
    }

    /**
     * The outcome of a read.
     *
     * <p>{@code sourcePresent} separates "the analyzer recorded no execution evidence" from "the
     * check did nothing" — a distinction the caller must be able to make, because a missing file
     * only ever means the former.
     */
    public record Result(boolean sourcePresent, List<CheckTimeline> checks, List<ReadError> errors) {
        public Result {
            checks = List.copyOf(checks);
            errors = List.copyOf(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /** True when the file existed but yielded no usable case. */
        public boolean isEmpty() {
            return checks.isEmpty();
        }
    }

    public Result read(Path executionEvidenceFile) {
        if (executionEvidenceFile == null || !Files.isRegularFile(executionEvidenceFile)) {
            return new Result(false, List.of(), List.of());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(executionEvidenceFile, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            // The reason is deliberately not echoed: a path or OS message is not ours to publish.
            return new Result(true, List.of(), List.of(new ReadError(
                0, ErrorCode.SOURCE_UNREADABLE,
                "The execution-evidence file could not be read.")));
        }

        List<ReadError> errors = new ArrayList<>();
        // Insertion-ordered so first-seen check order and first-seen case order are preserved.
        Map<CheckId, Map<String, List<ExecutionStepEnvelope>>> grouped = new LinkedHashMap<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty()) continue;
            int lineNumber = index + 1;

            Map<String, String> values;
            try {
                values = EvidenceFileReader.parseFlatObject(line);
            } catch (RuntimeException malformed) {
                errors.add(new ReadError(lineNumber, ErrorCode.MALFORMED_LINE,
                    "Line " + lineNumber + " is not a readable evidence record."));
                continue;
            }

            ExecutionStepEnvelope envelope = parse(values, lineNumber, errors);
            if (envelope == null) continue;

            grouped
                .computeIfAbsent(envelope.checkId(), unused -> new LinkedHashMap<>())
                .computeIfAbsent(envelope.testCaseId(), unused -> new ArrayList<>())
                .add(envelope);
        }

        return new Result(true, validated(grouped, errors), errors);
    }

    private static ExecutionStepEnvelope parse(
        Map<String, String> values, int lineNumber, List<ReadError> errors
    ) {
        String record = values.get("record");
        if (!ExecutionEvidenceRecorder.EXECUTION_STEP_RECORD.equals(record)) {
            errors.add(new ReadError(lineNumber, ErrorCode.UNSUPPORTED_RECORD,
                "Line " + lineNumber + " is not an execution-step record."));
            return null;
        }

        String testCaseId = values.get("testCaseId");
        if (testCaseId == null || testCaseId.isBlank()) {
            errors.add(new ReadError(lineNumber, ErrorCode.BLANK_TEST_CASE_ID,
                "Line " + lineNumber + " has no test case id, so its step cannot be correlated."));
            return null;
        }

        CheckId checkId = enumValue(CheckId.class, values.get("checkId"));
        if (checkId == null) {
            errors.add(new ReadError(lineNumber, ErrorCode.UNKNOWN_CHECK_ID,
                "Line " + lineNumber + " names a check this version does not recognise."));
            return null;
        }
        Phase phase = enumValue(Phase.class, values.get("phase"));
        if (phase == null) {
            errors.add(new ReadError(lineNumber, ErrorCode.UNKNOWN_PHASE,
                "Line " + lineNumber + " names a phase this version does not recognise."));
            return null;
        }
        ExecutionMethod method = enumValue(ExecutionMethod.class, values.get("method"));
        if (method == null) {
            errors.add(new ReadError(lineNumber, ErrorCode.UNKNOWN_METHOD,
                "Line " + lineNumber + " names an execution method this version does not recognise."));
            return null;
        }
        ExecutionStep.Status status = enumValue(ExecutionStep.Status.class, values.get("status"));
        if (status == null) {
            errors.add(new ReadError(lineNumber, ErrorCode.UNKNOWN_STATUS,
                "Line " + lineNumber + " names a status this version does not recognise."));
            return null;
        }

        ErrorCategory errorCategory = null;
        String rawCategory = values.get("errorCategory");
        if (rawCategory != null && !rawCategory.isBlank()) {
            errorCategory = enumValue(ErrorCategory.class, rawCategory);
            if (errorCategory == null) {
                errors.add(new ReadError(lineNumber, ErrorCode.UNKNOWN_ERROR_CATEGORY,
                    "Line " + lineNumber + " names an error category this version does not recognise."));
                return null;
            }
        }

        // The producer rule, not an invented one: every recorder pairs PASSED with no category,
        // and pairs FAILED or SKIPPED with one. A line that disagrees was not written by this
        // engine, and its status cannot be trusted.
        boolean passed = status == ExecutionStep.Status.PASSED;
        if (passed && errorCategory != null) {
            errors.add(new ReadError(lineNumber, ErrorCode.STATUS_AND_CATEGORY_DISAGREE,
                "Line " + lineNumber + " is passing but carries an error category."));
            return null;
        }
        if (!passed && errorCategory == null) {
            errors.add(new ReadError(lineNumber, ErrorCode.STATUS_AND_CATEGORY_DISAGREE,
                "Line " + lineNumber + " is not passing but carries no error category."));
            return null;
        }

        Integer sequence = integer(values.get("sequence"));
        Long durationMs = longValue(values.get("durationMs"));
        if (sequence == null || durationMs == null) {
            errors.add(new ReadError(lineNumber, ErrorCode.MISSING_FIELD,
                "Line " + lineNumber + " is missing a required numeric field."));
            return null;
        }

        ExecutionStep step;
        try {
            // Absent optional text is normalized to "" by the record itself, which is exactly how
            // the writer omitted it.
            step = new ExecutionStep(
                sequence, phase, method,
                values.get("action"),
                values.get("targetLabel"),
                values.get("sanitizedLocator"),
                values.get("expectedObservation"),
                values.get("actualObservation"),
                status, durationMs,
                values.get("evidenceRef")
            );
        } catch (RuntimeException rejected) {
            // The model's own invariants refused it; the reason is not echoed.
            errors.add(new ReadError(lineNumber, ErrorCode.INVALID_STEP,
                "Line " + lineNumber + " does not satisfy the execution-step rules."));
            return null;
        }

        return new ExecutionStepEnvelope(
            record, checkId, testCaseId, step, errorCategory, truncatedFields(values.get("truncated")));
    }

    /** The writer joins the shortened field names with commas; they are kept as written. */
    private static List<String> truncatedFields(String truncated) {
        if (truncated == null || truncated.isBlank()) return List.of();
        List<String> fields = new ArrayList<>();
        for (String field : truncated.split(",")) {
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) fields.add(trimmed);
        }
        return List.copyOf(fields);
    }

    /**
     * Drops any case whose sequence is not 1, 2, 3… and keeps every other case.
     *
     * <p>Steps are never reordered: a case that arrived out of order is reported as such rather
     * than sorted into looking correct. Isolation is per case, so one broken timeline cannot make
     * a sound one disappear, and a check whose cases are all broken simply has no cases.
     */
    private static List<CheckTimeline> validated(
        Map<CheckId, Map<String, List<ExecutionStepEnvelope>>> grouped, List<ReadError> errors
    ) {
        List<CheckTimeline> checks = new ArrayList<>();
        for (Map.Entry<CheckId, Map<String, List<ExecutionStepEnvelope>>> check : grouped.entrySet()) {
            List<CaseTimeline> cases = new ArrayList<>();
            for (Map.Entry<String, List<ExecutionStepEnvelope>> testCase : check.getValue().entrySet()) {
                List<ExecutionStepEnvelope> steps = testCase.getValue();
                ReadError problem = sequenceProblem(check.getKey(), testCase.getKey(), steps);
                if (problem != null) {
                    errors.add(problem);
                    continue;
                }
                cases.add(new CaseTimeline(check.getKey(), testCase.getKey(), steps));
            }
            if (!cases.isEmpty()) checks.add(new CheckTimeline(check.getKey(), cases));
        }
        return List.copyOf(checks);
    }

    private static ReadError sequenceProblem(
        CheckId checkId, String testCaseId, List<ExecutionStepEnvelope> steps
    ) {
        String where = " for " + checkId.name() + " case " + testCaseId + ".";
        int first = steps.getFirst().step().sequence();
        if (first != 1) {
            return new ReadError(0, ErrorCode.SEQUENCE_DOES_NOT_START_AT_ONE,
                "The recorded steps do not start at sequence 1" + where);
        }
        for (int index = 1; index < steps.size(); index++) {
            int previous = steps.get(index - 1).step().sequence();
            int current = steps.get(index).step().sequence();
            if (current == previous) {
                return new ReadError(0, ErrorCode.DUPLICATE_SEQUENCE,
                    "A step sequence is repeated" + where);
            }
            if (current < previous) {
                return new ReadError(0, ErrorCode.SEQUENCE_OUT_OF_ORDER,
                    "The recorded steps are out of order" + where);
            }
            if (current > previous + 1) {
                return new ReadError(0, ErrorCode.SEQUENCE_GAP,
                    "A step sequence is missing" + where);
            }
        }
        return null;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value.trim()); } catch (NumberFormatException failure) { return null; }
    }

    private static Long longValue(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value.trim()); } catch (NumberFormatException failure) { return null; }
    }
}
