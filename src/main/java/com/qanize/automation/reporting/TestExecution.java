package com.qanize.automation.reporting;

/**
 * What actually happened for one selected test type during a run.
 *
 * <p>This record exists so the product can tell the user which checks really executed instead of
 * inferring "passed" from the absence of findings. Every field here is derived from evidence the
 * engine produced: {@code selected} comes from the validated run configuration, and
 * {@code executed} and the counts come from the Surefire results for that test class.
 *
 * <p>A test is {@link Status#PASSED} only when at least one check actually ran and none failed.
 * When a selected test produced no execution evidence it is {@link Status#SKIPPED} with a reason,
 * or {@link Status#NOT_TESTED}. It is never reported as passed.
 */
public record TestExecution(
    String testType,
    boolean selected,
    boolean executed,
    Status status,
    int checkCount,
    int passedCount,
    int failedCount,
    int warningCount,
    int skippedCount,
    String skippedReason,
    int itemsDiscovered,
    int itemsTested,
    int itemsPassed,
    int itemsFailed,
    int itemsSkipped,
    int itemsDetailed,
    int itemsOmitted
) {

    /** Compatibility constructor for records created before item-level counts existed. */
    public TestExecution(
        String testType, boolean selected, boolean executed, Status status,
        int checkCount, int passedCount, int failedCount, int warningCount,
        int skippedCount, String skippedReason
    ) {
        this(testType, selected, executed, status, checkCount, passedCount, failedCount,
             warningCount, skippedCount, skippedReason, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Returns a copy carrying the counts reconciled from the recorded tested items. */
    public TestExecution withItemCounts(
        int discovered, int tested, int passed, int failed, int skipped, int detailed, int omitted
    ) {
        return new TestExecution(
            testType, selected, executed, status, checkCount, passedCount, failedCount,
            warningCount, skippedCount, skippedReason,
            discovered, tested, passed, failed, skipped, detailed, omitted
        );
    }

    public enum Status {
        PASSED,
        FAILED,
        WARNING,
        SKIPPED,
        NOT_TESTED
    }

    public static TestExecution notSelected(String testType) {
        return new TestExecution(
            testType, false, false, Status.NOT_TESTED,
            0, 0, 0, 0, 0,
            "Not selected for this run."
        );
    }

    /** A selected test whose class produced no Surefire result at all. */
    public static TestExecution noEvidence(String testType) {
        return new TestExecution(
            testType, true, false, Status.NOT_TESTED,
            0, 0, 0, 0, 0,
            "The analyzer produced no execution record for this check."
        );
    }

    public static TestExecution fromSuite(String testType, TestRunSummary.SuiteResult suite) {
        int executedChecks = suite.executed();
        int failedChecks = suite.failures() + suite.errors();
        int passedChecks = Math.max(0, executedChecks - failedChecks);

        if (executedChecks == 0) {
            String reason = suite.skipReason() == null || suite.skipReason().isBlank()
                ? "The analyzer did not run this check for this configuration."
                : suite.skipReason();
            return new TestExecution(
                testType, true, false, Status.SKIPPED,
                suite.tests(), 0, 0, 0, suite.skipped(),
                reason
            );
        }

        Status status = failedChecks > 0 ? Status.FAILED : Status.PASSED;
        return new TestExecution(
            testType,
            true,
            true,
            status,
            suite.tests(),
            passedChecks,
            failedChecks,
            0,
            suite.skipped(),
            ""
        );
    }
}
