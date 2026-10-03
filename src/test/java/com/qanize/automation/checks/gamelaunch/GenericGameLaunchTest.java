package com.qanize.automation.checks.gamelaunch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer-facing entry class for the Game Launch check.
 *
 * <p>One of the seven classes Surefire selects through {@code @Tag("website-analysis")}, and the
 * name {@code CheckId} maps to {@code GAME_LAUNCH}. Like the other six wrappers it holds no
 * behaviour: the check itself is {@link GameLaunchModule}, which the crawler runs once per run on
 * the run's own driver, and which is unit-tested without a browser.
 *
 * <p><strong>It does not open a game here.</strong> A game launch needs a signed-in session, a
 * discovered list and a real browser, none of which exist in a Surefire fork; opening one from
 * here would either do nothing useful or click Demo on a live operator's site outside the run that
 * was configured for it. So this wrapper asserts the one thing it can honestly assert — that a run
 * which selected the check carries a configuration the check can act on — and assumes its way out
 * of a run that did not select it, exactly as an unselected check does.
 */
@Tag("website-analysis")
class GenericGameLaunchTest {

    @Test
    void configuredGameLaunchCanBeExecutedByTheRun() {
        WebsiteConfig config = WebsiteConfigLoader.loadSelected();
        WebsiteGameLaunchConfig gameLaunch = config.gameLaunch();

        assumeTrue(gameLaunch.enabled(), "Game Launch was not selected for this run");

        assertTrue(gameLaunch.optionalCardSelector().isPresent(),
            "Game Launch is selected but no game-card selector was configured");
        assertTrue(gameLaunch.optionalLaunchTriggerSelector().isPresent(),
            "Game Launch is selected but no demo trigger selector was configured");
        assertTrue(gameLaunch.maxGames() >= WebsiteGameLaunchConfig.MIN_GAMES
                && gameLaunch.maxGames() <= WebsiteGameLaunchConfig.MAX_GAMES,
            "Game Launch is selected but its launch budget is outside the supported range");
    }
}
