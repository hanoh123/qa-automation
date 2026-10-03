package com.qanize.automation.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import com.qanize.automation.runtime.AutomationRunRequest;
import com.qanize.automation.checks.forms.WebsiteFormInteractionConfig;
import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.engine.CrawlPolicy;
import com.qanize.automation.checks.login.WebsiteLoginConfig;
import com.qanize.automation.checks.navigation.WebsiteMenuConfig;
import com.qanize.automation.checks.search.WebsiteSearchConfig;

public final class WebsiteConfigLoader {

    public static final String SITE_CONFIG_PROPERTY = "siteConfig";
    public static final String DEFAULT_SITE_CONFIG = "sites/example.properties";

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private WebsiteConfigLoader() {
    }

    public static WebsiteConfig loadDefault() {
        return loadFromResource(DEFAULT_SITE_CONFIG);
    }

    public static WebsiteConfig loadSelected() {
        return loadSelected(System.getProperty(SITE_CONFIG_PROPERTY));
    }

    static WebsiteConfig loadSelected(String siteConfig) {
        String selected = siteConfig == null || siteConfig.isBlank()
            ? DEFAULT_SITE_CONFIG
            : siteConfig.trim();
        Path filePath = Path.of(selected);
        if (Files.isRegularFile(filePath)) {
            return load(filePath);
        }
        return loadFromResource(selected);
    }

    public static WebsiteConfig loadFromResource(String resourceName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new RunConfigurationException(
                    "RUN_CONFIG_NOT_FOUND",
                    "The generated run configuration could not be found.",
                    "Generate the run configuration again and retry the analysis."
                );
            }
            return load(input, resourceName);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read website config resource: " + resourceName, failure);
        }
    }

    public static WebsiteConfig load(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.getFileName().toString());
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read website config file: " + path, failure);
        }
    }

    static WebsiteConfig load(InputStream input, String sourceName) throws IOException {
        Properties properties = new Properties();
        properties.load(input);

        String contractVersion = stringValue(
            properties,
            "contract.version",
            AutomationRunRequest.CONTRACT_VERSION
        );
        if (!AutomationRunRequest.CONTRACT_VERSION.equals(contractVersion)) {
            throw new RunConfigurationException(
                "UNSUPPORTED_CONTRACT_VERSION",
                "Run configuration version " + contractVersion + " is not supported.",
                "Generate a version " + AutomationRunRequest.CONTRACT_VERSION + " run configuration."
            );
        }

        URI baseUrl = requiredHttpUri(properties, sourceName, "site.url", "baseUrl");
        WebsiteTestSelectionConfig tests = testSelection(properties);
        WebsiteSearchConfig search = new WebsiteSearchConfig(
            tests.search(),
            optionalString(properties, "search.inputSelector"),
            optionalString(properties, "search.submitSelector"),
            optionalString(properties, "search.resultsSelector"),
            stringValue(properties, "search.query", "test")
        );
        validateSearch(search);

        WebsiteMenuConfig menu = new WebsiteMenuConfig(
            optionalString(properties, "menu.triggerSelector"),
            optionalString(properties, "menu.panelSelector"),
            optionalString(properties, "menu.label")
        );
        validateMenu(menu);

        WebsiteFormInteractionConfig formInteraction = new WebsiteFormInteractionConfig(
            optionalString(properties, "form.selector"),
            optionalString(properties, "form.fieldSelector"),
            optionalString(properties, "form.testValue")
        );
        validateFormInteraction(formInteraction);

        WebsiteGameLaunchConfig gameLaunch = gameLaunchConfig(properties);
        validateGameLaunch(gameLaunch);

        WebsiteLoginConfig login = loginConfig(properties, sourceName, tests.login());
        int viewportWidth = positiveInt(
            properties,
            "viewport.width",
            AutomationRunRequest.DEFAULT_VIEWPORT_WIDTH
        );
        int viewportHeight = positiveInt(
            properties,
            "viewport.height",
            AutomationRunRequest.DEFAULT_VIEWPORT_HEIGHT
        );

        return new WebsiteConfig(
            contractVersion,
            stringValue(properties, "siteName", "Runtime Website"),
            baseUrl,
            importantPages(properties),
            csvValues(properties, "navigationLabels"),
            optionalString(properties, "expected.titleContains"),
            optionalString(properties, "expected.text"),
            search,
            menu,
            formInteraction,
            viewportWidth,
            viewportHeight,
            // The last point at which "this run said nothing about a page budget" is still
            // distinguishable from "this run asked for exactly this many". Below here it is an int
            // like any other, so the documented default has to be applied here or not at all. It
            // is the crawl's own published default rather than a second number that could drift
            // away from it.
            positiveInt(properties, "maxPages", CrawlPolicy.DEFAULT_MAX_PAGES),
            positiveInt(properties, "maxLinks", AutomationRunRequest.DEFAULT_MAX_LINKS),
            positiveInt(properties, "defaultTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS),
            login,
            tests,
            gameLaunch
        );
    }

    /**
     * Reads the Game Launch block, applying the documented default where the file says nothing.
     *
     * <p>This is the last point at which "this run said nothing about a launch budget" is still
     * distinguishable from "this run asked for exactly this many", so the default is applied here
     * or not at all — the same reasoning, and the same shape, as {@code maxPages} below.
     *
     * <p>A properties file written before Game Launch existed has none of these keys, so it loads
     * as the disabled block with the default budget and behaves exactly as it always did.
     */
    private static WebsiteGameLaunchConfig gameLaunchConfig(Properties properties) {
        return new WebsiteGameLaunchConfig(
            booleanValue(properties, "tests.gameLaunch", false),
            positiveInt(properties, "maxGames", WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES),
            optionalString(properties, "gameLaunch.cardSelector"),
            optionalString(properties, "gameLaunch.nameSelector"),
            optionalString(properties, "gameLaunch.providerSelector"),
            optionalString(properties, "gameLaunch.launchTriggerSelector"),
            optionalString(properties, "gameLaunch.gameSurfaceSelector"),
            optionalString(properties, "gameLaunch.errorSelector"),
            optionalString(properties, "gameLaunch.closeSelector"),
            optionalString(properties, "gameLaunch.authenticatedIndicatorSelector"),
            optionalString(properties, "gameLaunch.blockingOverlayCloseSelector")
        );
    }

    /**
     * Bounds and length checks only. Each selector stands alone, so there is no partial state to
     * reject the way a half-configured menu or form interaction is rejected.
     *
     * <p>Checked here as well as in the request validator so a run started from a properties file
     * is rejected on the same terms as one started from the web form. Whether the check may run at
     * all is not decided here: that is the request validator's single gate, which refuses an
     * enabled block before a browser exists.
     */
    private static void validateGameLaunch(WebsiteGameLaunchConfig gameLaunch) {
        if (gameLaunch.maxGames() < WebsiteGameLaunchConfig.MIN_GAMES
            || gameLaunch.maxGames() > WebsiteGameLaunchConfig.MAX_GAMES) {
            throw new RunConfigurationException(
                "VALUE_OUT_OF_RANGE",
                "limits.maxGames must be between " + WebsiteGameLaunchConfig.MIN_GAMES
                    + " and " + WebsiteGameLaunchConfig.MAX_GAMES + ".",
                "Choose a value from " + WebsiteGameLaunchConfig.MIN_GAMES
                    + " to " + WebsiteGameLaunchConfig.MAX_GAMES + "."
            );
        }
        requireWithin(gameLaunch.cardSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_CARD_SELECTOR_TOO_LONG", "The game card selector is too long.");
        requireWithin(gameLaunch.nameSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_NAME_SELECTOR_TOO_LONG", "The game name selector is too long.");
        requireWithin(gameLaunch.providerSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_PROVIDER_SELECTOR_TOO_LONG", "The game provider selector is too long.");
        requireWithin(gameLaunch.launchTriggerSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_LAUNCH_TRIGGER_SELECTOR_TOO_LONG", "The game launch button selector is too long.");
        requireWithin(gameLaunch.gameSurfaceSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_SURFACE_SELECTOR_TOO_LONG", "The game surface selector is too long.");
        requireWithin(gameLaunch.errorSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_ERROR_SELECTOR_TOO_LONG", "The game error selector is too long.");
        requireWithin(gameLaunch.closeSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_CLOSE_SELECTOR_TOO_LONG", "The game close selector is too long.");
        requireWithin(gameLaunch.authenticatedIndicatorSelector(),
            WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_AUTHENTICATED_INDICATOR_SELECTOR_TOO_LONG",
            "The signed-in indicator selector is too long.");
        requireWithin(gameLaunch.blockingOverlayCloseSelector(),
            WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
            "GAME_BLOCKING_OVERLAY_CLOSE_SELECTOR_TOO_LONG",
            "The blocking popup close selector is too long.");
    }

    public static Duration timeout(WebsiteConfig config) {
        return Duration.ofSeconds(config.defaultTimeoutSeconds());
    }

    private static void validateSearch(WebsiteSearchConfig search) {
        if (search.enabled() && (search.inputSelector() == null || search.inputSelector().isBlank())) {
            throw new RunConfigurationException(
                "SEARCH_INPUT_SELECTOR_REQUIRED",
                "Search testing was enabled, but no search input selector was configured.",
                "Configure the search-field selector or disable Search testing."
            );
        }
    }

    /**
     * A menu is either fully configured or absent. There is no partial state.
     *
     * <p>A trigger with no panel could be clicked but never verified, so it would produce a click
     * on a customer's website with nothing to prove it was safe or useful. That is a configuration
     * mistake worth reporting, not a degraded mode worth running.
     */
    private static void validateMenu(WebsiteMenuConfig menu) {
        boolean hasTrigger = menu.triggerSelector() != null;
        boolean hasPanel = menu.panelSelector() != null;
        if (hasTrigger != hasPanel) {
            throw new RunConfigurationException(
                "MENU_SELECTORS_INCOMPLETE",
                "Menu testing needs both the menu button and the menu panel.",
                "Enter both selectors, or clear both to skip the menu test."
            );
        }
        requireWithin(menu.triggerSelector(), WebsiteMenuConfig.MAX_SELECTOR_LENGTH,
            "MENU_TRIGGER_SELECTOR_TOO_LONG", "The menu button selector is too long.");
        requireWithin(menu.panelSelector(), WebsiteMenuConfig.MAX_SELECTOR_LENGTH,
            "MENU_PANEL_SELECTOR_TOO_LONG", "The menu panel selector is too long.");
        requireWithin(menu.label(), WebsiteMenuConfig.MAX_LABEL_LENGTH,
            "MENU_LABEL_TOO_LONG", "The menu name is too long.");
    }

    /**
     * Form interaction is either fully configured or absent. There is no partial state.
     *
     * <p>Checked here as well as in the request validator so a run started from a properties file
     * is rejected on the same terms as one started from the web form.
     */
    private static void validateFormInteraction(WebsiteFormInteractionConfig form) {
        int supplied = (form.formSelector() == null ? 0 : 1)
            + (form.fieldSelector() == null ? 0 : 1)
            + (form.testValue() == null ? 0 : 1);
        if (supplied != 0 && supplied != 3) {
            throw new RunConfigurationException(
                "FORM_INTERACTION_INCOMPLETE",
                "Filling in a form field needs the form, the field and the value to type.",
                "Enter all three, or clear all three to skip the form interaction."
            );
        }
        requireWithin(form.formSelector(), WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH,
            "FORM_SELECTOR_TOO_LONG", "The form selector is too long.");
        requireWithin(form.fieldSelector(), WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH,
            "FORM_FIELD_SELECTOR_TOO_LONG", "The form field selector is too long.");
        requireWithin(form.testValue(), WebsiteFormInteractionConfig.MAX_TEST_VALUE_LENGTH,
            "FORM_TEST_VALUE_TOO_LONG", "The test value is too long.");
    }

    /** The offending value is never echoed: only its field is named. */
    private static void requireWithin(String value, int maximum, String code, String message) {
        if (value != null && value.length() > maximum) {
            throw new RunConfigurationException(
                code, message, "Shorten it to " + maximum + " characters or fewer.");
        }
    }

    private static WebsiteLoginConfig loginConfig(
        Properties properties,
        String sourceName,
        boolean enabled
    ) {
        WebsiteLoginConfig config = new WebsiteLoginConfig(
            enabled,
            optionalHttpUri(properties, "login.url", sourceName),
            optionalString(properties, "login.usernameEnv"),
            optionalString(properties, "login.passwordEnv"),
            optionalString(properties, "login.usernameSelector"),
            optionalString(properties, "login.passwordSelector"),
            optionalString(properties, "login.submitSelector"),
            optionalString(properties, "login.successUrlContains"),
            optionalString(properties, "login.successText"),
            optionalString(properties, "login.triggerSelector")
        );
        config.validateReadyForLogin();
        return config;
    }

    private static WebsiteTestSelectionConfig testSelection(Properties properties) {
        WebsiteTestSelectionConfig defaults = WebsiteTestSelectionConfig.defaults();
        return new WebsiteTestSelectionConfig(
            booleanValue(properties, "tests.smoke", defaults.smoke()),
            booleanValue(properties, "tests.navigation", defaults.navigation()),
            booleanValue(properties, "tests.links", defaults.links()),
            booleanValue(
                properties,
                "tests.search",
                booleanValue(properties, "search.enabled", defaults.search())
            ),
            booleanValue(
                properties,
                "tests.forms",
                booleanValue(properties, "forms.enabled", defaults.forms())
            ),
            booleanValue(
                properties,
                "tests.login",
                booleanValue(properties, "login.enabled", defaults.login())
            )
        );
    }

    private static List<WebsitePageConfig> importantPages(Properties properties) {
        List<String> paths = csvValues(properties, "importantPages");
        if (paths.isEmpty()) {
            paths = List.of("/");
        }
        List<WebsitePageConfig> pages = new ArrayList<>();
        for (String path : paths) {
            pages.add(new WebsitePageConfig(path, "", ""));
        }
        return pages;
    }

    private static URI requiredHttpUri(Properties properties, String sourceName, String... keys) {
        for (String key : keys) {
            String value = optionalString(properties, key);
            if (value != null) {
                return parseHttpUri(value, key, sourceName);
            }
        }
        throw new RunConfigurationException(
            "TARGET_URL_REQUIRED",
            "A target website URL is required.",
            "Enter an absolute http:// or https:// website URL."
        );
    }

    private static URI optionalHttpUri(Properties properties, String key, String sourceName) {
        String value = optionalString(properties, key);
        return value == null ? null : parseHttpUri(value, key, sourceName);
    }

    private static URI parseHttpUri(String value, String key, String sourceName) {
        String field = key.startsWith("login") ? "login.url" : "target.url";
        return RunConfigurationValidator.requirePublicHttpUrl(value, field, true);
    }

    private static String stringValue(Properties properties, String key, String defaultValue) {
        String value = optionalString(properties, key);
        return value == null ? defaultValue : value;
    }

    private static String optionalString(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean booleanValue(Properties properties, String key, boolean defaultValue) {
        String value = optionalString(properties, key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int positiveInt(Properties properties, String key, int defaultValue) {
        String value = optionalString(properties, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new RunConfigurationException(
                "INVALID_" + key.toUpperCase(Locale.ROOT).replace('.', '_'),
                "The configured " + key + " must be a positive whole number.",
                "Choose a positive value for " + key + "."
            );
        }
    }

    private static List<String> csvValues(Properties properties, String key) {
        String value = optionalString(properties, key);
        if (value == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
