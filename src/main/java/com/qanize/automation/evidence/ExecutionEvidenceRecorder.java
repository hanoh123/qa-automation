package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionStep;
import com.hanoh.support.JsonSupport;

/**
 * Persists contract 1.1 execution steps as JSON Lines, at the moment each action completes.
 *
 * <p><strong>Why a second file.</strong> {@code evidence.jsonl} is read by the contract 1.0
 * {@link EvidenceFileReader}, which requires {@code testType}, {@code itemType} and {@code status}
 * on every line. A step record has none of those, so every step written there would become a parse
 * error, and {@code RunSummaryFactory} turns any parse error into an {@code ANALYZER_INTERNAL_ERROR}
 * — a successful run would report as failed. Steps therefore go to a sibling file, and the 1.0
 * reader and every historical run are left exactly as they are.
 *
 * <p><strong>Envelope.</strong> A flattened step cannot say which case it belongs to, because in
 * the summary that correlation comes from nesting. Each line therefore carries {@code record},
 * {@code checkId} and {@code testCaseId} alongside the step's own fields; the immutable
 * {@link ExecutionStep} model is untouched. The summary assembles the nesting later by grouping on
 * {@code testCaseId}.
 *
 * <p>Recording never throws into a check. A reporting problem must not turn a healthy website into
 * a failure, and must never replace the assertion a test was about to make.
 */
public final class ExecutionEvidenceRecorder {

    /** Sits beside {@code evidence.jsonl} in the same run directory. */
    public static final String EXECUTION_EVIDENCE_FILE_NAME = "execution-evidence.jsonl";

    /** Discriminates the line type, so later record kinds can share this file. */
    public static final String EXECUTION_STEP_RECORD = "EXECUTION_STEP";

    /**
     * Contract 1.1, section 8.7. Applied here rather than in the model: the model describes what
     * is true, the recorder decides what fits on disk.
     */
    static final int MAX_TEXT_LENGTH = 2000;

    private ExecutionEvidenceRecorder() {
    }

    /** Appends one step. Absent run directory means no-op, which yields no steps, never fake ones. */
    public static void record(String checkId, String testCaseId, ExecutionStep step) {
        record(checkId, testCaseId, step, null);
    }

    /**
     * Appends one step, optionally categorising why it failed.
     *
     * <p>{@link ErrorCategory} is an envelope field, not part of {@link ExecutionStep}: in the
     * contract it belongs to the concrete test case, which is assembled later. Carrying it on the
     * line means a failed step already says whether the website misbehaved or the analyzer did,
     * without amending a frozen model.
     */
    public static void record(
        String checkId, String testCaseId, ExecutionStep step, ErrorCategory errorCategory
    ) {
        if (step == null) return;
        Path target = executionEvidenceFile();
        if (target == null) return;
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            synchronized (ExecutionEvidenceRecorder.class) {
                Files.writeString(
                    target,
                    toJsonLine(checkId, testCaseId, step, errorCategory) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
                );
            }
        } catch (IOException | RuntimeException failure) {
            // Deliberately swallowed: see the class comment. The console line is a diagnostic,
            // never the machine-readable source of truth.
            System.out.println("QA Evidence | execution-step-write-failed | "
                + EvidenceRecorder.safeLine(failure.toString()));
        }
    }

    /**
     * The steps file, derived as a sibling of the configured evidence file.
     *
     * <p>Deriving rather than adding a second system property keeps the Maven command, the CLI and
     * {@code AutomationRunPaths} untouched: the run directory is already known through
     * {@code qanize.evidenceFile}.
     */
    static Path executionEvidenceFile() {
        Path evidenceFile = EvidenceRecorder.evidenceFile();
        return evidenceFile == null ? null : evidenceFile.resolveSibling(EXECUTION_EVIDENCE_FILE_NAME);
    }

    /**
     * One flat JSON object per line: envelope first, then the step.
     *
     * <p>When any field is truncated the line gains a {@code truncated} entry naming exactly which
     * ones, so a shortened observation is visible as a fact rather than mistaken for the whole
     * story. Nothing is ever dropped silently.
     */
    public static String toJsonLine(
        String checkId, String testCaseId, ExecutionStep step, ErrorCategory errorCategory
    ) {
        List<String> truncated = new ArrayList<>();
        StringBuilder json = new StringBuilder("{");
        boolean[] first = { true };

        text(json, first, "record", EXECUTION_STEP_RECORD, truncated);
        text(json, first, "checkId", checkId, truncated);
        text(json, first, "testCaseId", testCaseId, truncated);
        number(json, first, "sequence", step.sequence());
        text(json, first, "phase", step.phase().name(), truncated);
        text(json, first, "method", step.method().name(), truncated);
        text(json, first, "action", step.action(), truncated);
        text(json, first, "targetLabel", step.targetLabel(), truncated);
        text(json, first, "sanitizedLocator", step.sanitizedLocator(), truncated);
        text(json, first, "expectedObservation", step.expectedObservation(), truncated);
        text(json, first, "actualObservation", step.actualObservation(), truncated);
        text(json, first, "status", step.status().name(), truncated);
        number(json, first, "durationMs", step.durationMs());
        text(json, first, "evidenceRef", step.evidenceRef(), truncated);
        text(json, first, "errorCategory", errorCategory == null ? null : errorCategory.name(), truncated);
        if (!truncated.isEmpty()) {
            text(json, first, "truncated", String.join(",", truncated), new ArrayList<>());
        }
        return json.append("}").toString();
    }

    /** Deterministic: the same input always produces the same truncation at the same boundary. */
    static String cap(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) return value;
        return value.substring(0, MAX_TEXT_LENGTH) + "…";
    }

    private static void text(
        StringBuilder json, boolean[] first, String key, String value, List<String> truncated
    ) {
        if (value == null || value.isBlank()) return;
        String flattened = EvidenceRecorder.safeLine(value);
        String capped = cap(flattened);
        if (!capped.equals(flattened)) truncated.add(key);
        separator(json, first);
        json.append(JsonSupport.quote(key)).append(":").append(JsonSupport.quote(capped));
    }

    private static void number(StringBuilder json, boolean[] first, String key, long value) {
        separator(json, first);
        json.append(JsonSupport.quote(key)).append(":").append(value);
    }

    private static void separator(StringBuilder json, boolean[] first) {
        if (first[0]) { first[0] = false; } else { json.append(","); }
    }
}
