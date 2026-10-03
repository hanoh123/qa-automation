package com.qanize.automation.model;

public record Evidence(
    String id,
    String type,
    String label,
    String description,
    String artifactId,
    String pageUrl
) {
}
