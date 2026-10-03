package com.qanize.automation.checks.search;

import java.util.Optional;


public record WebsiteSearchConfig(
    boolean enabled,
    String inputSelector,
    String submitSelector,
    String resultsSelector,
    String query
) {

    public Optional<String> optionalSubmitSelector() {
        return optional(submitSelector);
    }

    public Optional<String> optionalResultsSelector() {
        return optional(resultsSelector);
    }

    public String effectiveQuery() {
        return optional(query).orElse("test");
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value == null || value.isBlank() ? null : value.trim());
    }
}
