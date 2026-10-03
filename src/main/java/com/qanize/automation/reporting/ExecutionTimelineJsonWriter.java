package com.qanize.automation.reporting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.hanoh.support.JsonSupport;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.evidence.ExecutionStepEnvelope;
import com.qanize.automation.evidence.ExecutionTimelineReader;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.runtime.AutomationRunPaths;

/**
 * Projects the persisted contract 1.1 execution steps into a separate run artifact.
 *
 * <p><strong>Additive by construction.</strong> This writes a new file and touches nothing else.
 * {@code summary.json} stays contract 1.0, the raw report is untouched, and the artifact is not
 * listed in the summary's manifest — adding it there would change contract 1.0 bytes, which is a
 * later decision, not this one.
 *
 * <p><strong>It reports only what was recorded.</strong> The timeline carries checks, cases and
 * steps in their persisted order and nothing else. There are deliberately no case names, statuses,
 * timestamps, durations or counts: the evidence file does not contain them, and a plausible guess
 * in a machine-readable artifact is worse than an absent field.
 *
 * <p><strong>It cannot fail a run.</strong> {@link #writeIfPossible} swallows every failure, the
 * same rule {@link ExecutionEvidenceRecorder} follows. A reporting problem must never turn a
 * healthy website into a failed analysis, and by the time this runs the contract 1.0 summary is
 * already written.
 */
public final class ExecutionTimelineJsonWriter {

    /** Sits beside {@code summary.json} and {@code execution-evidence.jsonl} in the run directory. */
    public static final String EXECUTION_TIMELINE_FILE_NAME = "execution-timeline.json";

    /** The contract this projection belongs to. Independent of the 1.0 summary version. */
    public static final String SCHEMA_VERSION = "1.1";

    /**
     * Reads the run's execution evidence and writes the timeline. Never throws.
     *
     * @return the artifact path, or empty when the timeline could not be produced
     */
    public Optional<Path> writeIfPossible(AutomationRunPaths paths) {
        try {
            Path evidence = paths.resolveArtifact(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
            return Optional.of(write(paths, new ExecutionTimelineReader().read(evidence)));
        } catch (RuntimeException failure) {
            // Deliberately swallowed: see the class comment. A diagnostic line, never a verdict.
            System.out.println("QA Evidence | execution-timeline-write-failed | " + safeLine(failure.toString()));
            return Optional.empty();
        }
    }

    /** Writes an already-read timeline, using the same atomic convention as the summary. */
    public Path write(AutomationRunPaths paths, ExecutionTimelineReader.Result result) {
        return JsonSupport.writeAtomically(
            paths.resolveArtifact(EXECUTION_TIMELINE_FILE_NAME),
            toJson(paths.runId(), result)
        );
    }

    String toJson(String runId, ExecutionTimelineReader.Result result) {
        StringBuilder json = new StringBuilder("{\n");
        text(json, 2, "schemaVersion", SCHEMA_VERSION);
        json.append(",\n");
        text(json, 2, "runId", runId);
        json.append(",\n");
        // Distinguishes "no evidence file was produced" from "a file was produced with no steps".
        json.append("  \"sourcePresent\": ").append(result.sourcePresent()).append(",\n");
        checks(json, result.checks());
        json.append(",\n");
        errors(json, result.errors());
        json.append("\n}\n");
        return json.toString();
    }

    private static void checks(StringBuilder json, List<ExecutionTimelineReader.CheckTimeline> checks) {
        json.append("  \"checks\": [");
        if (checks.isEmpty()) { json.append(']'); return; }
        json.append('\n');
        for (int index = 0; index < checks.size(); index++) {
            ExecutionTimelineReader.CheckTimeline check = checks.get(index);
            json.append("    {\n");
            text(json, 6, "checkId", check.checkId().name());
            json.append(",\n");
            testCases(json, check.cases());
            json.append('\n').append("    }");
            if (index < checks.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void testCases(StringBuilder json, List<ExecutionTimelineReader.CaseTimeline> cases) {
        json.append("      \"testCases\": [");
        if (cases.isEmpty()) { json.append(']'); return; }
        json.append('\n');
        for (int index = 0; index < cases.size(); index++) {
            ExecutionTimelineReader.CaseTimeline testCase = cases.get(index);
            json.append("        {\n");
            text(json, 10, "testCaseId", testCase.testCaseId());
            json.append(",\n");
            steps(json, testCase.steps());
            json.append('\n').append("        }");
            if (index < cases.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("      ]");
    }

    private static void steps(StringBuilder json, List<ExecutionStepEnvelope> steps) {
        json.append("          \"steps\": [");
        if (steps.isEmpty()) { json.append(']'); return; }
        json.append('\n');
        for (int index = 0; index < steps.size(); index++) {
            ExecutionStepEnvelope envelope = steps.get(index);
            ExecutionStep step = envelope.step();
            String pad = " ".repeat(14);
            List<String> lines = new ArrayList<>();
            lines.add(pad + JsonSupport.quote("sequence") + ": " + step.sequence());
            lines.add(pad + JsonSupport.quote("phase") + ": " + JsonSupport.quote(step.phase().name()));
            lines.add(pad + JsonSupport.quote("method") + ": " + JsonSupport.quote(step.method().name()));
            lines.add(pad + JsonSupport.quote("action") + ": " + JsonSupport.quote(step.action()));
            // Optional text is omitted rather than written empty, matching how the recorder wrote
            // it and how the 1.0 summary writes its own optional fields.
            optional(lines, pad, "targetLabel", step.targetLabel());
            optional(lines, pad, "sanitizedLocator", step.sanitizedLocator());
            optional(lines, pad, "expectedObservation", step.expectedObservation());
            optional(lines, pad, "actualObservation", step.actualObservation());
            lines.add(pad + JsonSupport.quote("status") + ": " + JsonSupport.quote(step.status().name()));
            lines.add(pad + JsonSupport.quote("durationMs") + ": " + step.durationMs());
            optional(lines, pad, "evidenceRef", step.evidenceRef());
            if (envelope.errorCategory() != null) {
                optional(lines, pad, "errorCategory", envelope.errorCategory().name());
            }
            if (envelope.truncated()) {
                List<String> quoted = envelope.truncatedFields().stream().map(JsonSupport::quote).toList();
                lines.add(pad + JsonSupport.quote("truncated") + ": [" + String.join(", ", quoted) + "]");
            }
            json.append("            {\n").append(String.join(",\n", lines)).append('\n').append("            }");
            if (index < steps.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("          ]");
    }

    /**
     * Read errors, carrying only what the reader already decided is safe to publish: a line
     * number, a stable code and a generic sentence. The rejected line and the rejected value are
     * not available here and are not reconstructed.
     */
    private static void errors(StringBuilder json, List<ExecutionTimelineReader.ReadError> errors) {
        json.append("  \"errors\": [");
        if (errors.isEmpty()) { json.append(']'); return; }
        json.append('\n');
        for (int index = 0; index < errors.size(); index++) {
            ExecutionTimelineReader.ReadError error = errors.get(index);
            json.append("    {\n");
            json.append("      ").append(JsonSupport.quote("lineNumber")).append(": ")
                .append(error.lineNumber()).append(",\n");
            text(json, 6, "code", error.code().name());
            json.append(",\n");
            text(json, 6, "message", error.explanation());
            json.append('\n').append("    }");
            if (index < errors.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void optional(List<String> lines, String pad, String key, String value) {
        if (value == null || value.isBlank()) return;
        lines.add(pad + JsonSupport.quote(key) + ": " + JsonSupport.quote(value));
    }

    private static void text(StringBuilder json, int spaces, String key, String value) {
        json.append(" ".repeat(spaces)).append(JsonSupport.quote(key)).append(": ")
            .append(JsonSupport.quote(value == null ? "" : value));
    }

    private static String safeLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
