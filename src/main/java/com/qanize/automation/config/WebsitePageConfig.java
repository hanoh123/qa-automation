package com.qanize.automation.config;

public record WebsitePageConfig(
    String path,
    String expectedTitleContains,
    String expectedText
) {
}
