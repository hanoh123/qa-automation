package com.qanize.automation.checks.login;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

import com.qanize.automation.config.RunConfigurationException;


public record WebsiteLoginConfig(
    boolean enabled,
    URI loginUrl,
    String usernameEnv,
    String passwordEnv,
    String usernameSelector,
    String passwordSelector,
    String submitSelector,
    String successUrlContains,
    String successText,
    /**
     * An optional control that reveals the sign-in form, such as a header button opening a modal.
     *
     * <p>Blank on almost every site, and blank means the form is already on the configured page —
     * which is what this check has always assumed. Where it is not, this names the one control
     * that puts it there. It opens a form and nothing else: it is clicked once, before anything is
     * typed, and it is not a second way to sign in.
     */
    String triggerSelector
) {

    private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*"
    );

    public Optional<URI> optionalLoginUrl() {
        return Optional.ofNullable(loginUrl);
    }

    public Optional<String> optionalSuccessUrlContains() {
        return Optional.ofNullable(blankToNull(successUrlContains));
    }

    public Optional<String> optionalSuccessText() {
        return Optional.ofNullable(blankToNull(successText));
    }

    /** The shape every caller used before the optional trigger existed. */
    public WebsiteLoginConfig(
        boolean enabled,
        URI loginUrl,
        String usernameEnv,
        String passwordEnv,
        String usernameSelector,
        String passwordSelector,
        String submitSelector,
        String successUrlContains,
        String successText
    ) {
        this(enabled, loginUrl, usernameEnv, passwordEnv, usernameSelector, passwordSelector,
            submitSelector, successUrlContains, successText, null);
    }

    public Optional<String> optionalTriggerSelector() {
        return Optional.ofNullable(blankToNull(triggerSelector));
    }

    public void validateReadyForLogin() {
        if (!enabled) {
            return;
        }
        if (loginUrl == null) {
            throw error(
                "LOGIN_URL_REQUIRED",
                "Login testing was enabled, but no login URL was configured.",
                "Configure the login URL or disable Login testing."
            );
        }
        requireEnvironmentReference(usernameEnv, "LOGIN_USERNAME_ENV_REQUIRED", "username");
        requireEnvironmentReference(passwordEnv, "LOGIN_PASSWORD_ENV_REQUIRED", "password");
        // Selectors are intentionally optional: when one is absent the engine detects that
        // element on the page instead of rejecting the run. A supplied selector is still used
        // verbatim and never silently replaced by detection.
    }

    public Optional<String> optionalUsernameSelector() {
        return Optional.ofNullable(blankToNull(usernameSelector));
    }

    public Optional<String> optionalPasswordSelector() {
        return Optional.ofNullable(blankToNull(passwordSelector));
    }

    public Optional<String> optionalSubmitSelector() {
        return Optional.ofNullable(blankToNull(submitSelector));
    }

    private static void requireText(String value, String code, String label) {
        if (value == null || value.isBlank()) {
            throw error(
                code,
                "Login testing was enabled, but no " + label + " was configured.",
                "Configure the " + label + " or disable Login testing."
            );
        }
    }

    private static void requireEnvironmentReference(String value, String code, String label) {
        if (value == null || value.isBlank()) {
            throw error(
                code,
                "Login testing was enabled, but no " + label + " environment-variable reference was configured.",
                "Configure the " + label + " environment-variable name or disable Login testing."
            );
        }
        if (!ENVIRONMENT_REFERENCE.matcher(value.trim()).matches()) {
            throw error(
                "INVALID_ENVIRONMENT_VARIABLE_NAME",
                "login." + label + "Env must contain an environment-variable name, not a credential value.",
                "Use a name such as QANIZE_TEST_USERNAME and configure its value in the Java process environment."
            );
        }
    }

    private static RunConfigurationException error(String code, String message, String action) {
        return new RunConfigurationException(code, message, action);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
