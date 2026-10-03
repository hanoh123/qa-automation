package com.qanize.automation.config;

public record WebsiteTestSelectionConfig(
    boolean smoke,
    boolean navigation,
    boolean links,
    boolean search,
    boolean forms,
    boolean login
) {

    public static WebsiteTestSelectionConfig defaults() {
        return new WebsiteTestSelectionConfig(true, true, true, false, false, false);
    }
}
