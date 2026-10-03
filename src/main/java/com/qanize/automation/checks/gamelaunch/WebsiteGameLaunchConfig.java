package com.qanize.automation.checks.gamelaunch;

import java.util.Optional;

/**
 * The configuration a future Game Launch check will read.
 *
 * <p>Configuration only. Nothing in this slice locates an element, hovers, clicks or opens a game:
 * this record carries values from the request to the generated properties and back out again, and
 * the check that would use them does not exist yet. {@link #enabled()} being true is rejected by
 * {@code RunConfigurationValidator} before a browser is created, so a caller cannot ask for a
 * Game Launch result that nothing could have produced.
 *
 * <p>Every selector is optional and independent, and blank means absent. That differs from
 * {@code WebsiteMenuConfig} and {@code WebsiteFormInteractionConfig}, which are all-or-nothing
 * because a trigger with no panel cannot prove anything. Here each selector answers a different
 * question — which cards exist, what one is called, who provides it, how a game is opened, what the
 * opened game looks like, what an error looks like, how to close it — and a run may legitimately
 * know some of them and not others.
 *
 * <p><strong>One launch control, named by the caller.</strong> The only control this record can
 * name is the one that opens a game, and the caller names it: this file contains no selector for
 * any particular operator's control and no second launch mode to choose between. Opening a game
 * is a page load; it is not a wager, and there is no field here for a stake, a bet, a deposit or
 * anything else that could move money.
 *
 * <p>Selector defaults are deliberately absent. A default copied from one operator's site would be
 * a guess applied to every other operator's, and clicking a guessed control is exactly the mistake
 * the menu and form configurations were written to avoid.
 */
public record WebsiteGameLaunchConfig(
    boolean enabled,
    int maxGames,
    String cardSelector,
    String nameSelector,
    String providerSelector,
    String launchTriggerSelector,
    String gameSurfaceSelector,
    String errorSelector,
    String closeSelector,
    /**
     * A control the site shows only to a signed-in visitor.
     *
     * <p>The one honest way this check can say a session survived a launch. It replaces an earlier
     * heuristic that watched the sign-in field disappear, which proved nothing: a login form that
     * is merely closed or hidden looks exactly the same as one that was never needed. A positive,
     * visible element is a statement; an absence is not. Required when Login and Game Launch are
     * both selected, and supplied by the caller — no operator's balance or account control is
     * named anywhere in this build.
     */
    String authenticatedIndicatorSelector,
    /**
     * The close control of something that covers the catalogue, such as a promotional overlay.
     *
     * <p>Optional, and absent is the normal case. Its only purpose is to let the run dismiss one
     * named control before a card is hovered, because a full-viewport overlay intercepts every
     * pointer event and would otherwise turn every game into a false failure. It names a close
     * control and nothing else: the overlay body is never clicked, no alternative is tried, and a
     * blocker that cannot be dismissed stops the run instead of being clicked past.
     */
    String blockingOverlayCloseSelector
) {

    /** A selector long enough to express any real control, and short enough to bound the artifact. */
    public static final int MAX_SELECTOR_LENGTH = 512;

    /** Fewer than one launch attempt is not a run of this check. */
    public static final int MIN_GAMES = 1;

    /** How many games a run attempts when the request says nothing about it. */
    public static final int DEFAULT_MAX_GAMES = 3;

    /**
     * The ceiling on launch attempts.
     *
     * <p>Deliberately small, and deliberately unrelated to {@code maxPages} and {@code maxLinks}:
     * a launch attempt opens a third party's game, which costs more on their side than fetching a
     * page does, so the budget for it is its own number rather than a share of somebody else's.
     */
    public static final int MAX_GAMES = 10;

    /** Absent configuration: the check is off and carries the documented default budget. */
    public static WebsiteGameLaunchConfig disabled() {
        return new WebsiteGameLaunchConfig(
            false, DEFAULT_MAX_GAMES, null, null, null, null, null, null, null, null, null);
    }

    /**
     * The shape every caller used before the two safety selectors existed.
     *
     * <p>Kept so that configurations written without them describe exactly the run they always
     * described: both absent, which is what "not configured" has always meant here.
     */
    public WebsiteGameLaunchConfig(
        boolean enabled,
        int maxGames,
        String cardSelector,
        String nameSelector,
        String providerSelector,
        String launchTriggerSelector,
        String gameSurfaceSelector,
        String errorSelector,
        String closeSelector
    ) {
        this(enabled, maxGames, cardSelector, nameSelector, providerSelector, launchTriggerSelector,
            gameSurfaceSelector, errorSelector, closeSelector, null, null);
    }

    public WebsiteGameLaunchConfig {
        cardSelector = trimToNull(cardSelector);
        nameSelector = trimToNull(nameSelector);
        providerSelector = trimToNull(providerSelector);
        launchTriggerSelector = trimToNull(launchTriggerSelector);
        gameSurfaceSelector = trimToNull(gameSurfaceSelector);
        errorSelector = trimToNull(errorSelector);
        closeSelector = trimToNull(closeSelector);
        authenticatedIndicatorSelector = trimToNull(authenticatedIndicatorSelector);
        blockingOverlayCloseSelector = trimToNull(blockingOverlayCloseSelector);
    }

    /** Whether any selector was supplied at all, regardless of whether the check was selected. */
    public boolean anySelectorConfigured() {
        return cardSelector != null
            || nameSelector != null
            || providerSelector != null
            || launchTriggerSelector != null
            || gameSurfaceSelector != null
            || errorSelector != null
            || closeSelector != null
            || authenticatedIndicatorSelector != null
            || blockingOverlayCloseSelector != null;
    }

    public Optional<String> optionalCardSelector() {
        return Optional.ofNullable(cardSelector);
    }

    public Optional<String> optionalNameSelector() {
        return Optional.ofNullable(nameSelector);
    }

    public Optional<String> optionalProviderSelector() {
        return Optional.ofNullable(providerSelector);
    }

    public Optional<String> optionalLaunchTriggerSelector() {
        return Optional.ofNullable(launchTriggerSelector);
    }

    public Optional<String> optionalGameSurfaceSelector() {
        return Optional.ofNullable(gameSurfaceSelector);
    }

    public Optional<String> optionalErrorSelector() {
        return Optional.ofNullable(errorSelector);
    }

    public Optional<String> optionalCloseSelector() {
        return Optional.ofNullable(closeSelector);
    }

    public Optional<String> optionalAuthenticatedIndicatorSelector() {
        return Optional.ofNullable(authenticatedIndicatorSelector);
    }

    public Optional<String> optionalBlockingOverlayCloseSelector() {
        return Optional.ofNullable(blockingOverlayCloseSelector);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
