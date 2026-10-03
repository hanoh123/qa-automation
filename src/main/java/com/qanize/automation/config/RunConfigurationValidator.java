package com.qanize.automation.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import com.qanize.automation.runtime.AutomationRunRequest;
import com.qanize.automation.checks.forms.WebsiteFormInteractionConfig;
import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.checks.login.WebsiteLoginConfig;
import com.qanize.automation.checks.navigation.WebsiteMenuConfig;

public final class RunConfigurationValidator {

    private static final Set<String> VIEWPORT_PRESETS = Set.of("desktop", "tablet", "mobile");

    private RunConfigurationValidator() {
    }

    public static void validate(AutomationRunRequest request) {
        if (request == null) {
            throw error(
                "RUN_CONFIGURATION_REQUIRED",
                "A run configuration is required.",
                "Submit a complete Website Analyzer run configuration."
            );
        }
        if (!AutomationRunRequest.CONTRACT_VERSION.equals(request.schemaVersion())) {
            throw error(
                "UNSUPPORTED_SCHEMA_VERSION",
                "schemaVersion must be " + AutomationRunRequest.CONTRACT_VERSION + ".",
                "Refresh the page and submit the current run-configuration format."
            );
        }
        requirePublicHttpUrl(request.siteUrl(), "target.url", true);
        if (!VIEWPORT_PRESETS.contains(safe(request.viewportPreset()).toLowerCase(Locale.ROOT))) {
            throw error(
                "INVALID_VIEWPORT_PRESET",
                "viewport.preset must be desktop, tablet, or mobile.",
                "Choose one of the supported viewport presets."
            );
        }
        requireRange(request.viewportWidth(), 320, 3840, "viewport.width");
        requireRange(request.viewportHeight(), 320, 2160, "viewport.height");
        requireRange(request.maxPages(), 1, 25, "limits.maxPages");
        requireRange(request.maxLinks(), 1, 500, "limits.maxLinks");

        if (request.searchEnabled() && isBlank(request.searchInputSelector())) {
            throw error(
                "SEARCH_INPUT_SELECTOR_REQUIRED",
                "Search testing was enabled, but no search input selector was configured.",
                "Configure the search-field selector or disable Search testing."
            );
        }
        if (request.searchEnabled() && isBlank(request.searchQuery())) {
            throw error(
                "SEARCH_QUERY_REQUIRED",
                "search.query is required when this feature is enabled.",
                "Configure search.query or disable this feature."
            );
        }
        if (request.searchTest() != request.searchEnabled()) {
            throw error(
                "SEARCH_CONFIGURATION_MISMATCH",
                "checks.search and search.enabled must have the same value.",
                "Enable or disable Search consistently in the run configuration."
            );
        }
        validateMenu(request);
        validateFormInteraction(request);
        validateGameLaunch(request);
        if (request.loginTest() != request.loginEnabled()) {
            throw error(
                "LOGIN_CONFIGURATION_MISMATCH",
                "checks.login and login.enabled must have the same value.",
                "Enable or disable Login consistently in the run configuration."
            );
        }
        if (request.loginEnabled()) {
            URI loginUrl = requirePublicHttpUrl(request.loginUrl(), "login.url", true);
            WebsiteLoginConfig loginConfig = new WebsiteLoginConfig(
                true,
                loginUrl,
                request.loginUsernameEnv(),
                request.loginPasswordEnv(),
                request.loginUsernameSelector(),
                request.loginPasswordSelector(),
                request.loginSubmitSelector(),
                request.loginSuccessUrlContains(),
                request.loginSuccessText(),
                request.loginTriggerSelector()
            );
            requireWithin(request.loginTriggerSelector(), WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH,
                "LOGIN_TRIGGER_SELECTOR_TOO_LONG", "The sign-in trigger selector is too long.");
            loginConfig.validateReadyForLogin();
            requireEnvironmentValue(loginConfig.usernameEnv(), "login.usernameEnv");
            requireEnvironmentValue(loginConfig.passwordEnv(), "login.passwordEnv");
        }
    }

    private static void requireEnvironmentValue(String variableName, String field) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            throw error(
                "LOGIN_ENVIRONMENT_VARIABLE_MISSING",
                field + " references an environment variable that is not available to the analyzer.",
                "Define " + variableName + " in the QANIZE server environment or disable Login testing."
            );
        }
    }

    /**
     * A menu is either fully configured or absent.
     *
     * <p>Rejected here as well as in the loader so a half-configured menu is reported while the
     * person is still looking at the form, rather than after a browser has already started.
     */
    private static void validateMenu(AutomationRunRequest request) {
        boolean hasTrigger = !isBlank(request.menuTriggerSelector());
        boolean hasPanel = !isBlank(request.menuPanelSelector());
        if (hasTrigger != hasPanel) {
            throw error(
                "MENU_SELECTORS_INCOMPLETE",
                "Menu testing needs both the menu button and the menu panel.",
                "Enter both selectors, or clear both to skip the menu test."
            );
        }
        requireWithin(request.menuTriggerSelector(), WebsiteMenuConfig.MAX_SELECTOR_LENGTH,
            "MENU_TRIGGER_SELECTOR_TOO_LONG", "The menu button selector is too long.");
        requireWithin(request.menuPanelSelector(), WebsiteMenuConfig.MAX_SELECTOR_LENGTH,
            "MENU_PANEL_SELECTOR_TOO_LONG", "The menu panel selector is too long.");
        requireWithin(request.menuLabel(), WebsiteMenuConfig.MAX_LABEL_LENGTH,
            "MENU_LABEL_TOO_LONG", "The menu name is too long.");
    }

    /**
     * Form interaction is either fully configured or absent.
     *
     * <p>Rejected here as well as in the loader so a half-configured interaction is reported while
     * the person is still looking at the form, rather than after a browser has already started and
     * a field has already been typed into.
     */
    private static void validateFormInteraction(AutomationRunRequest request) {
        int supplied = (isBlank(request.formSelector()) ? 0 : 1)
            + (isBlank(request.formFieldSelector()) ? 0 : 1)
            + (isBlank(request.formTestValue()) ? 0 : 1);
        if (supplied != 0 && supplied != 3) {
            throw error(
                "FORM_INTERACTION_INCOMPLETE",
                "Filling in a form field needs the form, the field and the value to type.",
                "Enter all three, or clear all three to skip the form interaction."
            );
        }
        requireWithin(request.formSelector(), WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH,
            "FORM_SELECTOR_TOO_LONG", "The form selector is too long.");
        requireWithin(request.formFieldSelector(), WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH,
            "FORM_FIELD_SELECTOR_TOO_LONG", "The form field selector is too long.");
        requireWithin(request.formTestValue(), WebsiteFormInteractionConfig.MAX_TEST_VALUE_LENGTH,
            "FORM_TEST_VALUE_TOO_LONG", "The test value is too long.");
    }

    /**
     * Game Launch configuration is checked here.
     *
     * <p>The check is executable now, so selecting it is no longer refused — the temporary
     * rejection that stood here while the runtime was being built has been removed, and nothing
     * else about this method changed.
     *
     * <p>The selectors and the budget are still validated whether or not the check is selected,
     * because they are configuration in their own right: a run may carry them, write them to the
     * generated properties and load them back, and that path has to be correct however the check
     * is later selected.
     */
    private static void validateGameLaunch(AutomationRunRequest request) {
        WebsiteGameLaunchConfig gameLaunch = request.gameLaunch();
        requireRange(gameLaunch.maxGames(), WebsiteGameLaunchConfig.MIN_GAMES,
            WebsiteGameLaunchConfig.MAX_GAMES, "limits.maxGames");
        requireGameSelectorWithin(gameLaunch.cardSelector(),
            "GAME_CARD_SELECTOR_TOO_LONG", "The game card selector is too long.");
        requireGameSelectorWithin(gameLaunch.nameSelector(),
            "GAME_NAME_SELECTOR_TOO_LONG", "The game name selector is too long.");
        requireGameSelectorWithin(gameLaunch.providerSelector(),
            "GAME_PROVIDER_SELECTOR_TOO_LONG", "The game provider selector is too long.");
        requireGameSelectorWithin(gameLaunch.launchTriggerSelector(),
            "GAME_LAUNCH_TRIGGER_SELECTOR_TOO_LONG", "The game launch button selector is too long.");
        requireGameSelectorWithin(gameLaunch.gameSurfaceSelector(),
            "GAME_SURFACE_SELECTOR_TOO_LONG", "The game surface selector is too long.");
        requireGameSelectorWithin(gameLaunch.errorSelector(),
            "GAME_ERROR_SELECTOR_TOO_LONG", "The game error selector is too long.");
        requireGameSelectorWithin(gameLaunch.closeSelector(),
            "GAME_CLOSE_SELECTOR_TOO_LONG", "The game close selector is too long.");
        requireGameSelectorWithin(gameLaunch.authenticatedIndicatorSelector(),
            "GAME_AUTHENTICATED_INDICATOR_SELECTOR_TOO_LONG",
            "The signed-in indicator selector is too long.");
        requireGameSelectorWithin(gameLaunch.blockingOverlayCloseSelector(),
            "GAME_BLOCKING_OVERLAY_CLOSE_SELECTOR_TOO_LONG",
            "The blocking popup close selector is too long.");

        if (!gameLaunch.enabled()) return;

        // Required only once the check is actually selected, and required in the same three
        // places the form marks required, so a configuration the form accepted is one the engine
        // accepts and the other way round. Each names something the check cannot proceed without:
        // what a game card is, how a game is opened, and what an opened game looks like. Without
        // the third there is nothing to wait for, and "the game opened" would be a guess.
        requireGameSelector(gameLaunch.cardSelector(), "GAME_CARD_SELECTOR_REQUIRED",
            "A game card selector is required when Game launch is selected.",
            "Add the selector that matches one game card in the list.");
        requireGameSelector(gameLaunch.launchTriggerSelector(), "GAME_LAUNCH_TRIGGER_SELECTOR_REQUIRED",
            "A game launch button selector is required when Game launch is selected.",
            "Add the selector for the control that opens a game from its card.");
        requireGameSelector(gameLaunch.gameSurfaceSelector(), "GAME_SURFACE_SELECTOR_REQUIRED",
            "A game surface selector is required when Game launch is selected.",
            "Add the selector for the element an opened game appears in.");

        // The one cross-check between two checks, and it exists because the alternative is a
        // false claim. A run that signs in and then opens games has to be able to say whether the
        // session survived; without a positive signal the honest answer is "not verified", and a
        // run that cannot verify what it set out to verify should be refused while the person is
        // still looking at the form rather than reported afterwards.
        if (request.loginTest() && isBlank(gameLaunch.authenticatedIndicatorSelector())) {
            throw error(
                "GAME_AUTHENTICATED_INDICATOR_REQUIRED",
                "A signed-in indicator selector is required when Login and Game launch are both "
                    + "selected.",
                "Add a selector for something the site shows only to a signed-in visitor, or turn "
                    + "off Login or Game launch."
            );
        }
    }

    private static void requireGameSelector(
        String value, String code, String message, String action
    ) {
        if (isBlank(value)) {
            throw error(code, message, action);
        }
    }

    private static void requireGameSelectorWithin(String value, String code, String message) {
        requireWithin(value, WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH, code, message);
    }

    /** The offending value is never echoed back: only the field and its limit are reported. */
    private static void requireWithin(String value, int maximum, String code, String message) {
        if (value != null && value.trim().length() > maximum) {
            throw error(code, message, "Shorten it to " + maximum + " characters or fewer.");
        }
    }

    public static URI requirePublicHttpUrl(String value, String field, boolean required) {
        if (isBlank(value)) {
            if (!required) return null;
            throw error(
                "TARGET_URL_REQUIRED",
                field + " is required.",
                "Enter an absolute public http or https URL."
            );
        }

        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException failure) {
            throw error(
                "INVALID_TARGET_URL",
                field + " must be a valid absolute URL.",
                "Enter an absolute public http or https URL."
            );
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            throw error(
                "UNSUPPORTED_TARGET_PROTOCOL",
                field + " must use http or https.",
                "Enter a public URL beginning with http:// or https://."
            );
        }
        if (uri.getRawUserInfo() != null) {
            throw error(
                "URL_CREDENTIALS_NOT_ALLOWED",
                field + " must not contain credentials.",
                "Remove the username and password from the URL and use environment references for login."
            );
        }
        String hostname = uri.getHost();
        if (hostname == null || hostname.isBlank()) {
            throw error(
                "INVALID_TARGET_URL",
                field + " must be a valid absolute URL.",
                "Enter an absolute public http or https URL."
            );
        }
        if (isBlockedHostname(hostname)) {
            throw error(
                "PRIVATE_TARGET_NOT_ALLOWED",
                field + " must not target localhost, a private network, or a link-local address.",
                "Use a publicly reachable test environment URL."
            );
        }
        return uri;
    }

    static boolean isBlockedHostname(String rawHostname) {
        String hostname = rawHostname.toLowerCase(Locale.ROOT)
            .replaceFirst("^\\[", "")
            .replaceFirst("\\]$", "")
            .replaceFirst("\\.$", "");
        if (hostname.equals("localhost") || hostname.endsWith(".localhost")
            || hostname.equals("::") || hostname.equals("::1")
            || hostname.equals("0:0:0:0:0:0:0:0")
            || hostname.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (blockedIpv4(hostname)) return true;
        String mappedIpv4 = hostname.startsWith("::ffff:") ? hostname.substring(7) : hostname;
        if (!mappedIpv4.equals(hostname) && blockedIpv4(mappedIpv4)) return true;
        return hostname.matches("^(?:fc|fd)[0-9a-f]{2}:.*")
            || hostname.matches("^fe[89ab][0-9a-f]:.*");
    }

    private static boolean blockedIpv4(String hostname) {
        String[] parts = hostname.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
        } catch (NumberFormatException failure) {
            return false;
        }
        int first = octets[0];
        int second = octets[1];
        return first == 0 || first == 10 || first == 127
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || first >= 224;
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw error(
                "VALUE_OUT_OF_RANGE",
                field + " must be between " + minimum + " and " + maximum + ".",
                "Choose a value from " + minimum + " to " + maximum + "."
            );
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static RunConfigurationException error(String code, String message, String action) {
        return new RunConfigurationException(code, message, action);
    }
}
