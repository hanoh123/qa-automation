package com.qanize.automation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One real thing a check acted on: one link, one form, one login attempt.
 *
 * <p>Contract 1.1, section 5. Named {@code ConcreteTestCase} rather than {@code TestCase} because
 * a type called {@code TestCase} inside a JUnit project reads as the framework's own concept; this
 * is a customer-facing record of work performed against a website, not a unit of the test runner.
 *
 * <p>{@code steps} is optional and an empty list means <em>not recorded by this contract
 * version</em> — never "no steps occurred" (section 8.2). A case whose check crashed before
 * instrumentation is still a valid record.
 */
public record ConcreteTestCase(
    String testCaseId,
    CheckId checkId,
    String name,
    Status status,
    String sourceUrl,
    String target,
    ExecutionMethod executionMethod,
    Instant startedAt,
    Instant completedAt,
    long durationMs,
    String expected,
    String actual,
    List<ExecutionStep> steps,
    List<String> evidenceRefs,
    ErrorCategory errorCategory
) {

    /** What happened to this one item. A case is never WARNING or NOT_TESTED; its check may be. */
    public enum Status {
        PASSED,
        FAILED,
        SKIPPED
    }

    public ConcreteTestCase {
        testCaseId = Invariants.requireText(testCaseId, "ConcreteTestCase.testCaseId");
        checkId = Invariants.requireField(checkId, "ConcreteTestCase.checkId");
        name = Invariants.requireText(name, "ConcreteTestCase.name");
        status = Invariants.requireField(status, "ConcreteTestCase.status");
        durationMs = Invariants.requireNonNegative(durationMs, "ConcreteTestCase.durationMs");

        sourceUrl = Invariants.optionalText(sourceUrl);
        target = Invariants.optionalText(target);
        expected = Invariants.optionalText(expected);
        actual = Invariants.optionalText(actual);
        steps = Invariants.immutableList(steps, "ConcreteTestCase.steps");
        evidenceRefs = Invariants.immutableList(evidenceRefs, "ConcreteTestCase.evidenceRefs");

        // Invariant 7, at step scope: this record owns the steps list, so it is the only place
        // that can prove the timeline has no duplicate and no hole.
        List<Integer> sequences = new ArrayList<>(steps.size());
        for (ExecutionStep step : steps) {
            sequences.add(step.sequence());
        }
        Invariants.requireGaplessSequence(sequences, "ConcreteTestCase.steps[].sequence");

        // Invariant 5: a passing case has nothing to categorize. Allowing an error category here
        // would let a report say a case passed and describe why it failed in the same breath.
        if (status == Status.PASSED && errorCategory != null) {
            throw new IllegalArgumentException(
                "ConcreteTestCase.errorCategory must be null when status is PASSED, but was "
                    + errorCategory);
        }
    }
}
