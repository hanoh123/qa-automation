package com.qanize.automation.reporting;

import com.qanize.automation.model.ArtifactMetadata;
import com.qanize.automation.model.Evidence;
import com.qanize.automation.model.Finding;
import com.qanize.automation.model.RunError;
import com.qanize.automation.model.RunLifecycle;
import com.qanize.automation.model.RunOutcome;
import com.qanize.automation.model.TestedItem;

import java.time.Instant;
import java.util.List;

public record RunSummary(
    String schemaVersion,
    String runId,
    RunLifecycle lifecycle,
    RunOutcome outcome,
    String targetUrl,
    Instant startedAt,
    Instant completedAt,
    long durationMs,
    Totals totals,
    List<Finding> findings,
    List<RunError> errors,
    List<Evidence> evidence,
    List<ArtifactMetadata> artifacts,
    List<TestExecution> testExecution,
    List<TestedItem> testedItems
) {

    public RunSummary {
        findings = List.copyOf(findings);
        errors = List.copyOf(errors);
        evidence = List.copyOf(evidence);
        artifacts = List.copyOf(artifacts);
        testExecution = List.copyOf(testExecution);
        testedItems = List.copyOf(testedItems);
    }

    public record Totals(
        int total,
        int passed,
        int failed,
        int skipped,
        int errors
    ) {
    }
}
