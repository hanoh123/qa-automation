package com.qanize.automation.config;

/**
 * A user-correctable run configuration problem. These errors must never be
 * reported as findings against the website under test.
 */
public final class RunConfigurationException extends IllegalArgumentException {

    public static final String CATEGORY = "RUN_CONFIGURATION";

    private final String code;
    private final String userMessage;
    private final String suggestedAction;

    public RunConfigurationException(
        String code,
        String userMessage,
        String suggestedAction
    ) {
        super(userMessage);
        this.code = requireText(code, "code");
        this.userMessage = requireText(userMessage, "userMessage");
        this.suggestedAction = requireText(suggestedAction, "suggestedAction");
    }

    public String category() {
        return CATEGORY;
    }

    public String code() {
        return code;
    }

    public String userMessage() {
        return userMessage;
    }

    public String suggestedAction() {
        return suggestedAction;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
