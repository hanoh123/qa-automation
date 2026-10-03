package com.qanize.automation.model;

public record ArtifactMetadata(
    String id,
    String type,
    String label,
    String mediaType,
    String relativePath
) {
}
