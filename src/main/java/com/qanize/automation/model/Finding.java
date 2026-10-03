package com.qanize.automation.model;

import java.time.Instant;
import java.util.List;

public record Finding(
    String id,
    String runId,
    String category,
    String severity,
    String title,
    String description,
    String pageUrl,
    String expected,
    String actual,
    String whyItFailed,
    List<String> reproductionSteps,
    List<String> evidence,
    String recommendation,
    Instant timestamp
) {

    public Finding {
        reproductionSteps = List.copyOf(reproductionSteps);
        evidence = List.copyOf(evidence);
    }
}
