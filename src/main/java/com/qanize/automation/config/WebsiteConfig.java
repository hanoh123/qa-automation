package com.qanize.automation.config;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import com.qanize.automation.checks.forms.WebsiteFormInteractionConfig;
import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.checks.login.WebsiteLoginConfig;
import com.qanize.automation.checks.navigation.WebsiteMenuConfig;
import com.qanize.automation.checks.search.WebsiteSearchConfig;

public record WebsiteConfig(
    String contractVersion,
    String siteName,
    URI baseUrl,
    List<WebsitePageConfig> importantPages,
    List<String> navigationLabels,
    String expectedTitleContains,
    String expectedText,
    WebsiteSearchConfig search,
    WebsiteMenuConfig menu,
    WebsiteFormInteractionConfig formInteraction,
    int viewportWidth,
    int viewportHeight,
    int maxPages,
    int maxLinks,
    int defaultTimeoutSeconds,
    WebsiteLoginConfig login,
    WebsiteTestSelectionConfig tests,

    /**
     * Configuration for a check that does not exist yet. Never null; absent is
     * {@link WebsiteGameLaunchConfig#disabled()}.
     */
    WebsiteGameLaunchConfig gameLaunch
) {

    public WebsiteConfig {
        importantPages = List.copyOf(importantPages);
        navigationLabels = List.copyOf(navigationLabels);
        gameLaunch = gameLaunch == null ? WebsiteGameLaunchConfig.disabled() : gameLaunch;
    }

    /**
     * The pre-game-launch shape, kept so every existing caller compiles and behaves unchanged.
     *
     * <p>A configuration built the old way names no game selectors and does not select the check,
     * which is exactly what it described before this field existed.
     */
    public WebsiteConfig(
        String contractVersion,
        String siteName,
        URI baseUrl,
        List<WebsitePageConfig> importantPages,
        List<String> navigationLabels,
        String expectedTitleContains,
        String expectedText,
        WebsiteSearchConfig search,
        WebsiteMenuConfig menu,
        WebsiteFormInteractionConfig formInteraction,
        int viewportWidth,
        int viewportHeight,
        int maxPages,
        int maxLinks,
        int defaultTimeoutSeconds,
        WebsiteLoginConfig login,
        WebsiteTestSelectionConfig tests
    ) {
        this(contractVersion, siteName, baseUrl, importantPages, navigationLabels,
            expectedTitleContains, expectedText, search, menu, formInteraction, viewportWidth,
            viewportHeight, maxPages, maxLinks, defaultTimeoutSeconds, login, tests,
            WebsiteGameLaunchConfig.disabled());
    }

    public Optional<String> optionalExpectedTitleContains() {
        return Optional.ofNullable(blankToNull(expectedTitleContains));
    }

    public Optional<String> optionalExpectedText() {
        return Optional.ofNullable(blankToNull(expectedText));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
