package com.qanize.automation.evidence;

import java.util.HashMap;
import java.util.Map;

import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;

/**
 * The step-numbering and step-writing mechanism shared by every check's execution evidence.
 *
 * <p>Each of the six {@code *ExecutionEvidence} classes previously carried its own private copy of
 * the same twelve lines: take the next sequence number for a test case, build an
 * {@link ExecutionStep}, and hand it to {@link ExecutionEvidenceRecorder}. This class is that
 * mechanism, extracted once. The per-check classes keep their own vocabulary — the sentences a
 * reader sees, and which steps exist at all — because that is the part which genuinely differs.
 *
 * <p><strong>Sequence numbers are per test case.</strong> They start at 1 and are counted
 * independently for each {@code testCaseId}, because contract 1.1 numbers steps within a case
 * rather than within a check. A check that records against a single case passes that one constant
 * id and gets the same numbering it had when it counted with a plain field.
 *
 * <p><strong>The number is taken before the step is built.</strong> A step that fails validation
 * has therefore already consumed its sequence number. That is the behaviour the guarded callers
 * had, and it is preserved deliberately: renumbering after a failure would silently close the gap
 * and make a lost step invisible.
 *
 * <p><strong>Guarded and unguarded are both real.</strong> Four checks contain reporting failures
 * so that a problem writing evidence can never be mistaken for a finding about the website; two do
 * not. Which mode a check uses is its own decision and is passed in at construction, so this
 * extraction changes no check's behaviour. Reconciling that difference is a separate question from
 * removing the duplication.
 *
 * <p>Not thread-safe, and deliberately so: the analyzer runs one check at a time under Surefire,
 * which configures no parallelism. A parallel runner would need to revisit this.
 */
public final class CheckStepRecorder {

    private final String checkId;

    /** The log prefix used when a reporting failure is swallowed, or null when it propagates. */
    private final String failureLogPrefix;

    private final Map<String, Integer> sequences = new HashMap<>();

    private CheckStepRecorder(String checkId, String failureLogPrefix) {
        this.checkId = checkId;
        this.failureLogPrefix = failureLogPrefix;
    }

    /**
     * A recorder that lets a reporting failure propagate to the caller.
     *
     * <p>Used by the checks that do not contain it themselves.
     */
    public static CheckStepRecorder unguarded(String checkId) {
        return new CheckStepRecorder(checkId, null);
    }

    /**
     * A recorder that swallows a reporting failure, logs it and returns null.
     *
     * @param failureLogPrefix the exact diagnostic label the check already used, so the console
     *                         output of an existing run is unchanged
     */
    public static CheckStepRecorder guarded(String checkId, String failureLogPrefix) {
        return new CheckStepRecorder(checkId, failureLogPrefix);
    }

    /**
     * Numbers, builds and records one step.
     *
     * @return the recorded step, or null when a guarded recorder swallowed a reporting failure
     */
    public ExecutionStep append(
        String testCaseId, Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        if (failureLogPrefix == null) {
            return write(
                testCaseId, phase, method, action, targetLabel,
                expected, actual, status, durationMs, errorCategory);
        }
        try {
            return write(
                testCaseId, phase, method, action, targetLabel,
                expected, actual, status, durationMs, errorCategory);
        } catch (RuntimeException reportingFailure) {
            System.out.println("QA Evidence | " + failureLogPrefix + " | "
                + EvidenceRecorder.safeLine(reportingFailure.toString()));
            return null;
        }
    }

    private ExecutionStep write(
        String testCaseId, Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        int sequence = sequences.merge(testCaseId, 1, Integer::sum);
        ExecutionStep step = new ExecutionStep(
            sequence, phase, method, action, targetLabel, "", expected, actual, status,
            Math.max(0, durationMs), ""
        );
        ExecutionEvidenceRecorder.record(checkId, testCaseId, step, errorCategory);
        return step;
    }

    /** How many steps have been numbered for one test case. */
    public int recordedSteps(String testCaseId) {
        return sequences.getOrDefault(testCaseId, 0);
    }
}
