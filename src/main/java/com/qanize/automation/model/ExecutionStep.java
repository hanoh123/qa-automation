package com.qanize.automation.model;

/**
 * One thing the analyzer actually did.
 *
 * <p>Contract 1.1, section 6. This is the atomic evidence record: a step exists because an action
 * was performed, and it is written at the moment the action completes. Nothing assembles steps
 * afterwards from a test result, because a test result cannot know what the test did.
 *
 * <p>Optional text fields are normalized to {@code ""} rather than left null, so a reader never
 * has to distinguish "absent" from "empty" — both mean the same thing here: not recorded.
 *
 * <p>This record holds no credential values. Redaction happens before construction, at the
 * recorder boundary (section 8.6); the model cannot verify that and does not pretend to.
 */
public record ExecutionStep(
    int sequence,
    Phase phase,
    ExecutionMethod method,
    String action,
    String targetLabel,
    String sanitizedLocator,
    String expectedObservation,
    String actualObservation,
    Status status,
    long durationMs,
    String evidenceRef
) {

    /** The outcome of a single action. A step never carries the richer check-level states. */
    public enum Status {
        PASSED,
        FAILED,
        SKIPPED
    }

    public ExecutionStep {
        sequence = Invariants.requirePositiveSequence(sequence, "ExecutionStep.sequence");
        phase = Invariants.requireField(phase, "ExecutionStep.phase");
        method = Invariants.requireField(method, "ExecutionStep.method");
        action = Invariants.requireText(action, "ExecutionStep.action");
        status = Invariants.requireField(status, "ExecutionStep.status");
        durationMs = Invariants.requireNonNegative(durationMs, "ExecutionStep.durationMs");
        targetLabel = Invariants.optionalText(targetLabel);
        sanitizedLocator = Invariants.optionalText(sanitizedLocator);
        expectedObservation = Invariants.optionalText(expectedObservation);
        actualObservation = Invariants.optionalText(actualObservation);
        evidenceRef = Invariants.optionalText(evidenceRef);
    }
}
