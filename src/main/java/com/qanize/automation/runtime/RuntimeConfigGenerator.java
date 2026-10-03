package com.qanize.automation.runtime;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.config.RunConfigurationValidator;
import com.hanoh.support.JsonSupport;

public final class RuntimeConfigGenerator {

    /** Writes the internal properties adapter used by the JUnit/Selenium layer. */
    public Path generate(AutomationRunRequest request, Path outputFile) {
        RunConfigurationValidator.validate(request);
        Properties properties = toProperties(request);
        try {
            Path absolute = outputFile.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Output file must have a parent directory");
            }
            Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(absolute)) {
                properties.store(
                    writer,
                    "Derived Website Analyzer properties. Contains environment-variable names only."
                );
            }
            return absolute;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write runtime config file: " + outputFile, failure);
        }
    }

    public Path writeCanonicalJson(AutomationRunRequest request, Path outputFile) {
        RunConfigurationValidator.validate(request);
        return writeRequestSnapshot(request, outputFile);
    }

    /** Writes a safe request snapshot even when validation later rejects the run. */
    public Path writeRequestSnapshot(AutomationRunRequest request, Path outputFile) {
        if (request == null) {
            throw new IllegalArgumentException("AutomationRunRequest is required");
        }
        return JsonSupport.writeAtomically(outputFile, toJson(request));
    }

    /**
     * Writes the canonical run configuration only when the caller has not already supplied it.
     * QANIZE writes the validated configuration before starting this process, so that copy is
     * authoritative and must not be replaced by a second serializer.
     */
    public Path writeRequestSnapshotIfAbsent(AutomationRunRequest request, Path outputFile) {
        if (outputFile != null && Files.isRegularFile(outputFile)) {
            return outputFile.toAbsolutePath().normalize();
        }
        return writeRequestSnapshot(request, outputFile);
    }

    public Properties toProperties(AutomationRunRequest request) {
        RunConfigurationValidator.validate(request);
        Properties properties = new Properties();
        properties.setProperty("contract.version", request.schemaVersion());
        properties.setProperty("siteName", "QANIZE Website Analyzer Run");
        properties.setProperty("site.url", request.siteUrl().trim());
        properties.setProperty("importantPages", "/");
        properties.setProperty("navigationLabels", "");
        properties.setProperty("viewport.width", Integer.toString(request.viewportWidth()));
        properties.setProperty("viewport.height", Integer.toString(request.viewportHeight()));
        properties.setProperty("maxPages", Integer.toString(request.maxPages()));
        properties.setProperty("maxLinks", Integer.toString(request.maxLinks()));
        // Its own budget, written beside the other two and derived from neither. A run that said
        // nothing about it carries the documented default, so the key is never missing.
        properties.setProperty("maxGames", Integer.toString(request.gameLaunch().maxGames()));
        properties.setProperty("defaultTimeoutSeconds", "10");
        properties.setProperty("tests.smoke", Boolean.toString(request.smokeTest()));
        properties.setProperty("tests.navigation", Boolean.toString(request.navigationTest()));
        properties.setProperty("tests.links", Boolean.toString(request.linksTest()));
        properties.setProperty("tests.search", Boolean.toString(request.searchTest()));
        properties.setProperty("tests.forms", Boolean.toString(request.formsTest()));
        properties.setProperty("tests.login", Boolean.toString(request.loginTest()));
        // False on every run this build can execute: the validator above refuses an enabled block
        // before this line is reached. Written anyway, because a reader of the file should see
        // that the check was not selected rather than have to infer it from an absent key.
        properties.setProperty("tests.gameLaunch",
            Boolean.toString(request.gameLaunch().enabled()));
        properties.setProperty("search.enabled", Boolean.toString(request.searchEnabled()));
        properties.setProperty("search.inputSelector", safe(request.searchInputSelector()));
        properties.setProperty("search.submitSelector", safe(request.searchSubmitSelector()));
        properties.setProperty("search.resultsSelector", safe(request.searchResultsSelector()));
        properties.setProperty("search.query", effectiveQuery(request));
        // Written unconditionally and blank when unset, exactly like the search selectors, so the
        // properties file shape never varies between runs.
        properties.setProperty("menu.triggerSelector", safe(request.menuTriggerSelector()));
        properties.setProperty("menu.panelSelector", safe(request.menuPanelSelector()));
        properties.setProperty("menu.label", safe(request.menuLabel()));
        // Always written, blank when unconfigured: the file shape must not vary between runs, or a
        // reader has to guess whether a key vanished or the feature was off.
        properties.setProperty("form.selector", safe(request.formSelector()));
        properties.setProperty("form.fieldSelector", safe(request.formFieldSelector()));
        properties.setProperty("form.testValue", safe(request.formTestValue()));
        properties.setProperty("forms.enabled", Boolean.toString(request.formsTest()));
        properties.setProperty("linkChecks.enabled", Boolean.toString(request.linksTest()));
        properties.setProperty("login.enabled", Boolean.toString(request.loginTest()));
        properties.setProperty("login.url", safe(request.loginUrl()));
        properties.setProperty("login.triggerSelector", safe(request.loginTriggerSelector()));
        properties.setProperty("login.usernameEnv", safe(request.loginUsernameEnv()));
        properties.setProperty("login.passwordEnv", safe(request.loginPasswordEnv()));
        properties.setProperty("login.usernameSelector", safe(request.loginUsernameSelector()));
        properties.setProperty("login.passwordSelector", safe(request.loginPasswordSelector()));
        properties.setProperty("login.submitSelector", safe(request.loginSubmitSelector()));
        properties.setProperty("login.successUrlContains", safe(request.loginSuccessUrlContains()));
        properties.setProperty("login.successText", safe(request.loginSuccessText()));
        // Always written and blank when unset, exactly like the menu and form selectors, so the
        // file shape never varies between runs. Blank is absent: nothing here names a default
        // selector, because a selector guessed from one operator's site is wrong on every other.
        WebsiteGameLaunchConfig gameLaunch = request.gameLaunch();
        properties.setProperty("gameLaunch.cardSelector", safe(gameLaunch.cardSelector()));
        properties.setProperty("gameLaunch.nameSelector", safe(gameLaunch.nameSelector()));
        properties.setProperty("gameLaunch.providerSelector", safe(gameLaunch.providerSelector()));
        properties.setProperty("gameLaunch.launchTriggerSelector",
            safe(gameLaunch.launchTriggerSelector()));
        properties.setProperty("gameLaunch.gameSurfaceSelector",
            safe(gameLaunch.gameSurfaceSelector()));
        properties.setProperty("gameLaunch.errorSelector", safe(gameLaunch.errorSelector()));
        properties.setProperty("gameLaunch.closeSelector", safe(gameLaunch.closeSelector()));
        properties.setProperty("gameLaunch.authenticatedIndicatorSelector",
            safe(gameLaunch.authenticatedIndicatorSelector()));
        properties.setProperty("gameLaunch.blockingOverlayCloseSelector",
            safe(gameLaunch.blockingOverlayCloseSelector()));
        return properties;
    }

    String toJson(AutomationRunRequest request) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": ").append(JsonSupport.quote(request.schemaVersion())).append(",\n");
        json.append("  \"target\": {\n");
        json.append("    \"url\": ").append(JsonSupport.quote(safe(request.siteUrl()))).append("\n");
        json.append("  },\n");
        json.append("  \"viewport\": {\"preset\": ")
            .append(JsonSupport.quote(safe(request.viewportPreset())))
            .append(", \"width\": ").append(request.viewportWidth())
            .append(", \"height\": ").append(request.viewportHeight()).append("},\n");
        json.append("  \"limits\": {\"maxPages\": ").append(request.maxPages())
            .append(", \"maxLinks\": ").append(request.maxLinks()).append("},\n");
        json.append("  \"checks\": {\n");
        booleanField(json, "smoke", request.smokeTest(), true, 4);
        booleanField(json, "navigation", request.navigationTest(), true, 4);
        booleanField(json, "links", request.linksTest(), true, 4);
        booleanField(json, "search", request.searchTest(), true, 4);
        booleanField(json, "forms", request.formsTest(), true, 4);
        booleanField(json, "login", request.loginTest(), false, 4);
        json.append("  },\n");
        json.append("  \"search\": {\n");
        booleanField(json, "enabled", request.searchEnabled(), true, 4);
        stringField(json, "inputSelector", safe(request.searchInputSelector()), true, 4);
        stringField(json, "submitSelector", safe(request.searchSubmitSelector()), true, 4);
        stringField(json, "resultsSelector", safe(request.searchResultsSelector()), true, 4);
        stringField(json, "query", safe(request.searchQuery()), false, 4);
        json.append("  },\n");
        json.append("  \"menu\": {\n");
        stringField(json, "triggerSelector", safe(request.menuTriggerSelector()), true, 4);
        stringField(json, "panelSelector", safe(request.menuPanelSelector()), true, 4);
        stringField(json, "label", safe(request.menuLabel()), false, 4);
        json.append("  },\n");
        json.append("  \"formInteraction\": {\n");
        stringField(json, "selector", safe(request.formSelector()), true, 4);
        stringField(json, "fieldSelector", safe(request.formFieldSelector()), true, 4);
        stringField(json, "testValue", safe(request.formTestValue()), false, 4);
        json.append("  },\n");
        json.append("  \"login\": {\n");
        booleanField(json, "enabled", request.loginEnabled(), true, 4);
        stringField(json, "url", safe(request.loginUrl()), true, 4);
        stringField(json, "usernameEnv", safe(request.loginUsernameEnv()), true, 4);
        stringField(json, "passwordEnv", safe(request.loginPasswordEnv()), true, 4);
        stringField(json, "usernameSelector", safe(request.loginUsernameSelector()), true, 4);
        stringField(json, "passwordSelector", safe(request.loginPasswordSelector()), true, 4);
        stringField(json, "submitSelector", safe(request.loginSubmitSelector()), true, 4);
        stringField(json, "successUrlContains", safe(request.loginSuccessUrlContains()), true, 4);
        stringField(json, "successText", safe(request.loginSuccessText()), false, 4);
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static void stringField(
        StringBuilder json,
        String name,
        String value,
        boolean comma,
        int spaces
    ) {
        json.append(" ".repeat(spaces)).append(JsonSupport.quote(name)).append(": ")
            .append(JsonSupport.quote(value));
        json.append(comma ? ",\n" : "\n");
    }

    private static void booleanField(
        StringBuilder json,
        String name,
        boolean value,
        boolean comma,
        int spaces
    ) {
        json.append(" ".repeat(spaces)).append(JsonSupport.quote(name)).append(": ")
            .append(value);
        json.append(comma ? ",\n" : "\n");
    }

    private static String effectiveQuery(AutomationRunRequest request) {
        return request.searchQuery() == null || request.searchQuery().isBlank()
            ? "test"
            : request.searchQuery().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
