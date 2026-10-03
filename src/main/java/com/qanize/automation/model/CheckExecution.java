package com.qanize.automation.model;

import java.time.Instant;
import java.util.List;

/**
 * What actually happened for one customer-facing check.
 *
 * <p>Contract 1.1, section 4. The counting invariants enforced here are the machine-checkable form
 * of the rule this project has held by hand since the SPA discovery defect: <strong>a check that
 * did not test anything is never reported as a pass.</strong> In contract 1.0 those relationships
 * were maintained by the summary factory and could only be verified by reading it.
 *
 * <p>Test cases are nested rather than published in a flat list, so a case can never be orphaned
 * from its check or counted twice across two checks.
 */
public record CheckExecution(
    CheckId checkId,
    boolean selected,
    boolean executed,
    Status status,
    Instant startedAt,
    Instant completedAt,
    long durationMs,
    DiscoveryUnit discoveryUnit,
    int discoveredCount,
    int testedCount,
    int passed,
    int failed,
    int skipped,
    String skipReason,
    List<ConcreteTestCase> testCases,
    List<String> evidenceRefs
) {

    /** Mirrors the contract 1.0 check states so a 1.1 reader loses no distinction. */
    public enum Status {
        PASSED,
        FAILED,
        WARNING,
        SKIPPED,
        NOT_TESTED
    }

    public CheckExecution {
        checkId = Invariants.requireField(checkId, "CheckExecution.checkId");
        status = Invariants.requireField(status, "CheckExecution.status");
        durationMs = Invariants.requireNonNegative(durationMs, "CheckExecution.durationMs");

        discoveredCount = Invariants.requireNonNegativeCount(discoveredCount, "CheckExecution.discoveredCount");
        testedCount = Invariants.requireNonNegativeCount(testedCount, "CheckExecution.testedCount");
        passed = Invariants.requireNonNegativeCount(passed, "CheckExecution.passed");
        failed = Invariants.requireNonNegativeCount(failed, "CheckExecution.failed");
        skipped = Invariants.requireNonNegativeCount(skipped, "CheckExecution.skipped");

        skipReason = Invariants.optionalText(skipReason);
        testCases = Invariants.immutableList(testCases, "CheckExecution.testCases");
        evidenceRefs = Invariants.immutableList(evidenceRefs, "CheckExecution.evidenceRefs");

        // Invariant 1.
        if (testedCount != passed + failed) {
            throw new IllegalArgumentException(
                "CheckExecution.testedCount must equal passed + failed, but " + testedCount
                    + " != " + passed + " + " + failed);
        }

        // Invariant 2.
        if (discoveredCount != testedCount + skipped) {
            throw new IllegalArgumentException(
                "CheckExecution.discoveredCount must equal testedCount + skipped, but "
                    + discoveredCount + " != " + testedCount + " + " + skipped);
        }

        // Invariant 3. This is the one that makes a fabricated pass impossible to construct.
        if (status == Status.PASSED) {
            if (!executed) {
                throw new IllegalArgumentException(
                    "CheckExecution.status PASSED requires executed=true for " + checkId);
            }
            if (failed != 0) {
                throw new IllegalArgumentException(
                    "CheckExecution.status PASSED requires failed=0, but was " + failed
                        + " for " + checkId);
            }
            if (testedCount <= 0) {
                throw new IllegalArgumentException(
                    "CheckExecution.status PASSED requires testedCount > 0, but was " + testedCount
                        + " for " + checkId);
            }
        }

        // Invariant 4: a skipped check that cannot say why is indistinguishable from a silent
        // failure, which is the reporting defect this contract exists to remove.
        if (status == Status.SKIPPED && skipReason.isBlank()) {
            throw new IllegalArgumentException(
                "CheckExecution.skipReason is required when status is SKIPPED for " + checkId);
        }

        for (ConcreteTestCase testCase : testCases) {
            // The nesting is the ownership claim; a foreign case would silently break it.
            if (testCase.checkId() != checkId) {
                throw new IllegalArgumentException(
                    "CheckExecution.testCases contains " + testCase.testCaseId() + " belonging to "
                        + testCase.checkId() + ", but this check is " + checkId);
            }
            // Invariant 6: a website defect can only be observed by a check that ran.
            if (!executed && testCase.errorCategory() == ErrorCategory.WEBSITE_FINDING) {
                throw new IllegalArgumentException(
                    "CheckExecution.testCases contains a WEBSITE_FINDING (" + testCase.testCaseId()
                        + ") but " + checkId + " did not execute");
            }
        }
    }
}
