package com.qanize.automation.runtime;

import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;

public record AutomationRunRequest(
    String schemaVersion,
    String siteUrl,
    String viewportPreset,
    int viewportWidth,
    int viewportHeight,
    int maxPages,
    int maxLinks,
    boolean loginEnabled,
    String loginUrl,
    String loginUsernameEnv,
    String loginPasswordEnv,
    String loginUsernameSelector,
    String loginPasswordSelector,
    String loginSubmitSelector,
    String loginSuccessUrlContains,
    String loginSuccessText,
    boolean smokeTest,
    boolean navigationTest,
    boolean linksTest,
    boolean searchTest,
    boolean formsTest,
    boolean loginTest,
    boolean searchEnabled,
    String searchInputSelector,
    String searchSubmitSelector,
    String searchResultsSelector,
    String searchQuery,
    /** Optional click-to-open menu. All three blank means the menu check does not exist. */
    String menuTriggerSelector,
    String menuPanelSelector,
    String menuLabel,
    /**
     * Optional safe form interaction. All three blank means the Forms check stays discovery-only,
     * exactly as it behaved before this feature existed.
     */
    String formSelector,
    String formFieldSelector,
    String formTestValue,
    /**
     * Which execution path this run takes. False — including absent, which is how every request
     * written before this field existed arrives — is the Maven/Surefire path exactly as before.
     *
     * <p>A boolean rather than a mode enum because there are two paths and the contract has no
     * execution-mode structure to extend.
     *
     * <p><strong>The page budget is {@link #maxPages()}, and the crawl runs under it.</strong>
     * This field chooses an engine; it carries no budget of its own. Where this note once said the
     * crawl's limits "are not configurable from a request", that described a defect rather than a
     * design: the configured number reached the engine intact and was then discarded there in
     * favour of {@code CrawlPolicy.defaults()}, so a run asking for three pages opened ten. Depth
     * genuinely is not configurable — it stays at
     * {@link com.qanize.automation.engine.CrawlPolicy#DEFAULT_MAX_DEPTH}, because no control sets
     * it.
     */
    boolean crawlerEnabled,

    /**
     * Configuration for a check that does not exist yet.
     *
     * <p>Never null: an absent block is {@link WebsiteGameLaunchConfig#disabled()}, which carries
     * the documented launch budget and no selectors, so every request written before this field
     * existed describes exactly the run it always described.
     *
     * <p>Carried on the request so the values can travel to the generated properties and be read
     * back, and no further. {@code RunConfigurationValidator} rejects an enabled block before a
     * browser exists, so this field cannot select a check.
     */
    WebsiteGameLaunchConfig gameLaunch,

    /**
     * An optional control that reveals the sign-in form, such as a header button opening a modal.
     *
     * <p>Blank on almost every site. It travels to {@code WebsiteLoginConfig} and is clicked once
     * before any field is looked for; it is not a second sign-in mechanism and cannot become one.
     */
    String loginTriggerSelector
) {

    public AutomationRunRequest {
        gameLaunch = gameLaunch == null ? WebsiteGameLaunchConfig.disabled() : gameLaunch;
    }

    /**
     * The pre-game-launch shape, kept so every existing caller compiles and behaves unchanged.
     *
     * <p>Defaulting to {@link WebsiteGameLaunchConfig#disabled()} is the backward-compatibility
     * guarantee itself: a request built the old way carries no selectors and does not select the
     * check, and there is no arrangement of the old arguments that can.
     */
    public AutomationRunRequest(
        String schemaVersion,
        String siteUrl,
        String viewportPreset,
        int viewportWidth,
        int viewportHeight,
        int maxPages,
        int maxLinks,
        boolean loginEnabled,
        String loginUrl,
        String loginUsernameEnv,
        String loginPasswordEnv,
        String loginUsernameSelector,
        String loginPasswordSelector,
        String loginSubmitSelector,
        String loginSuccessUrlContains,
        String loginSuccessText,
        boolean smokeTest,
        boolean navigationTest,
        boolean linksTest,
        boolean searchTest,
        boolean formsTest,
        boolean loginTest,
        boolean searchEnabled,
        String searchInputSelector,
        String searchSubmitSelector,
        String searchResultsSelector,
        String searchQuery,
        String menuTriggerSelector,
        String menuPanelSelector,
        String menuLabel,
        String formSelector,
        String formFieldSelector,
        String formTestValue,
        boolean crawlerEnabled
    ) {
        this(schemaVersion, siteUrl, viewportPreset, viewportWidth, viewportHeight, maxPages,
            maxLinks, loginEnabled, loginUrl, loginUsernameEnv, loginPasswordEnv,
            loginUsernameSelector, loginPasswordSelector, loginSubmitSelector,
            loginSuccessUrlContains, loginSuccessText, smokeTest, navigationTest, linksTest,
            searchTest, formsTest, loginTest, searchEnabled, searchInputSelector,
            searchSubmitSelector, searchResultsSelector, searchQuery, menuTriggerSelector,
            menuPanelSelector, menuLabel, formSelector, formFieldSelector, formTestValue,
            crawlerEnabled, WebsiteGameLaunchConfig.disabled(), null);
    }

    /**
     * The pre-crawler shape, kept so every existing caller compiles and behaves unchanged.
     *
     * <p>Defaulting to {@code false} here is the backward-compatibility guarantee itself: a
     * request built the old way selects the legacy path, and there is no arrangement of the old
     * arguments that can select the crawler.
     */
    public AutomationRunRequest(
        String schemaVersion,
        String siteUrl,
        String viewportPreset,
        int viewportWidth,
        int viewportHeight,
        int maxPages,
        int maxLinks,
        boolean loginEnabled,
        String loginUrl,
        String loginUsernameEnv,
        String loginPasswordEnv,
        String loginUsernameSelector,
        String loginPasswordSelector,
        String loginSubmitSelector,
        String loginSuccessUrlContains,
        String loginSuccessText,
        boolean smokeTest,
        boolean navigationTest,
        boolean linksTest,
        boolean searchTest,
        boolean formsTest,
        boolean loginTest,
        boolean searchEnabled,
        String searchInputSelector,
        String searchSubmitSelector,
        String searchResultsSelector,
        String searchQuery,
        String menuTriggerSelector,
        String menuPanelSelector,
        String menuLabel,
        String formSelector,
        String formFieldSelector,
        String formTestValue
    ) {
        this(schemaVersion, siteUrl, viewportPreset, viewportWidth, viewportHeight, maxPages,
            maxLinks, loginEnabled, loginUrl, loginUsernameEnv, loginPasswordEnv,
            loginUsernameSelector, loginPasswordSelector, loginSubmitSelector,
            loginSuccessUrlContains, loginSuccessText, smokeTest, navigationTest, linksTest,
            searchTest, formsTest, loginTest, searchEnabled, searchInputSelector,
            searchSubmitSelector, searchResultsSelector, searchQuery, menuTriggerSelector,
            menuPanelSelector, menuLabel, formSelector, formFieldSelector, formTestValue,
            false);
    }

    /** The shape every caller used before the optional sign-in trigger existed. */
    public AutomationRunRequest(
        String schemaVersion,
        String siteUrl,
        String viewportPreset,
        int viewportWidth,
        int viewportHeight,
        int maxPages,
        int maxLinks,
        boolean loginEnabled,
        String loginUrl,
        String loginUsernameEnv,
        String loginPasswordEnv,
        String loginUsernameSelector,
        String loginPasswordSelector,
        String loginSubmitSelector,
        String loginSuccessUrlContains,
        String loginSuccessText,
        boolean smokeTest,
        boolean navigationTest,
        boolean linksTest,
        boolean searchTest,
        boolean formsTest,
        boolean loginTest,
        boolean searchEnabled,
        String searchInputSelector,
        String searchSubmitSelector,
        String searchResultsSelector,
        String searchQuery,
        String menuTriggerSelector,
        String menuPanelSelector,
        String menuLabel,
        String formSelector,
        String formFieldSelector,
        String formTestValue,
        boolean crawlerEnabled,
        WebsiteGameLaunchConfig gameLaunch
    ) {
        this(schemaVersion, siteUrl, viewportPreset, viewportWidth, viewportHeight, maxPages,
            maxLinks, loginEnabled, loginUrl, loginUsernameEnv, loginPasswordEnv,
            loginUsernameSelector, loginPasswordSelector, loginSubmitSelector,
            loginSuccessUrlContains, loginSuccessText, smokeTest, navigationTest, linksTest,
            searchTest, formsTest, loginTest, searchEnabled, searchInputSelector,
            searchSubmitSelector, searchResultsSelector, searchQuery, menuTriggerSelector,
            menuPanelSelector, menuLabel, formSelector, formFieldSelector, formTestValue,
            crawlerEnabled, gameLaunch, null);
    }

    public static final String CONTRACT_VERSION = "1.0";
    public static final String DEFAULT_VIEWPORT_PRESET = "desktop";
    public static final int DEFAULT_VIEWPORT_WIDTH = 1440;
    public static final int DEFAULT_VIEWPORT_HEIGHT = 900;
    public static final int DEFAULT_MAX_PAGES = 5;
    public static final int DEFAULT_MAX_LINKS = 25;

    /** A default configuration: every check except Search and Login. */
    public static AutomationRunRequest simple(String siteUrl) {
        return new AutomationRunRequest(
            CONTRACT_VERSION,
            siteUrl,
            DEFAULT_VIEWPORT_PRESET,
            DEFAULT_VIEWPORT_WIDTH,
            DEFAULT_VIEWPORT_HEIGHT,
            DEFAULT_MAX_PAGES,
            DEFAULT_MAX_LINKS,
            false,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            "",
            "",
            "",
            "test",
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
