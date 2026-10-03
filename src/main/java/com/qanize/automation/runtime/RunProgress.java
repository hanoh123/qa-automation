package com.qanize.automation.runtime;

import java.time.Duration;
import java.time.Instant;

public record RunProgress(
    String schemaVersion,
    String runId,
    State state,
    String phase,
    String currentPage,
    int completedChecks,
    int totalChecks,
    Instant startedAt,
    Instant updatedAt,
    long elapsedMs,
    boolean cancellable,
    boolean cancelRequested,
    String message
) {

    public enum State {
        QUEUED,
        CONFIGURING,
        RUNNING,
        FINALIZING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static RunProgress of(
        String runId,
        State state,
        String phase,
        String currentPage,
        int completedChecks,
        int totalChecks,
        Instant startedAt,
        String message
    ) {
        Instant updatedAt = Instant.now();
        return new RunProgress(
            AutomationRunRequest.CONTRACT_VERSION,
            runId,
            state,
            phase,
            currentPage,
            completedChecks,
            totalChecks,
            startedAt,
            updatedAt,
            Math.max(0, Duration.between(startedAt, updatedAt).toMillis()),
            false,
            false,
            message == null ? "" : message
        );
    }
}
