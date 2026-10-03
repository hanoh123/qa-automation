package com.qanize.automation.reporting;

import java.util.List;

public record TestRunSummary(
    int total,
    int passed,
    int failed,
    int skipped,
    int errors,
    OverallStatus overallStatus,
    List<FailedTestDetail> failedTests,
    List<SuiteResult> suites
) {

    public TestRunSummary {
        failedTests = List.copyOf(failedTests);
        suites = List.copyOf(suites);
    }

    /** Compatibility constructor for callers that do not supply per-suite detail. */
    public TestRunSummary(
        int total,
        int passed,
        int failed,
        int skipped,
        int errors,
        OverallStatus overallStatus,
        List<FailedTestDetail> failedTests
    ) {
        this(total, passed, failed, skipped, errors, overallStatus, failedTests, List.of());
    }

    /**
     * One Surefire test class. This is the only place the engine records which individual
     * checks actually ran, so it is the evidence behind "executed" in the run summary.
     */
    public record SuiteResult(
        String className,
        int tests,
        int failures,
        int errors,
        int skipped,
        String skipReason
    ) {
        public int executed() {
            return Math.max(0, tests - skipped);
        }
    }

    public enum OverallStatus {
        PASS,
        FAIL,
        NO_RESULTS
    }

    public enum ProblemType {
        FAILURE,
        ERROR
    }

    public record FailedTestDetail(
        String className,
        String testName,
        ProblemType type,
        String message
    ) {
        public FailedTestDetail(String className, String testName, String message) {
            this(className, testName, ProblemType.FAILURE, message);
        }
    }
}
