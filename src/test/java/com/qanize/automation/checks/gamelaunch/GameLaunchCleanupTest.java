package com.qanize.automation.checks.gamelaunch;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import com.qanize.automation.checks.shared.SafePointerClick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting the catalogue back, and admitting it when that fails.
 *
 * <p>The failure this guards against is quiet: a launch that leaves an overlay open, cleanup that
 * assumes it closed, and every later game inspected through whatever is still on screen. So each
 * case here ends on the same question the production code asks — does the configured card list
 * match anything again — and the cases where it does not are the important ones.
 *
 * <p>The fake browser is the one {@link LaunchSurfaceDetectorTest} uses, and it fails the test if
 * anything calls {@code navigate()}. That is not a style preference: browser history on a gaming
 * site is not a reliable inverse of a launch.
 */
@Tag("unit")
class GameLaunchCleanupTest {

    private static final String LIST_URL = "https://example.test/lobby";

    // --- new window -------------------------------------------------------------------------------

    @Test
    void aLaunchedWindowIsClosedAndTheOriginalIsReturnedTo() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.openWindow("game-window");

        GameLaunchCleanup.Result result = cleanup(browser)
            .restore(browser, before, detection(
                LaunchSurfaceDetector.SurfaceType.NEW_WINDOW, "game-window"), LIST_URL);

        assertTrue(result.restored());
        assertEquals(1, browser.closes, "the launched window was closed exactly once");
        assertEquals("lobby", browser.getWindowHandle(), "and the original is active again");
        assertFalse(result.returnUrlUsed(), "the list survived, so no navigation was needed");
    }

    @Test
    void anOriginalWindowThatDisappearedIsReportedRatherThanGuessedAt() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.openWindow("game-window");
        // The lobby window is gone. Switching to the remaining one would report on a page the
        // check never chose.
        browser.handles.remove("lobby");

        GameLaunchCleanup.Result result = cleanup(browser)
            .restore(browser, before, detection(
                LaunchSurfaceDetector.SurfaceType.NEW_WINDOW, "game-window"), LIST_URL);

        assertFalse(result.restored());
        assertEquals(GameLaunchCleanup.Outcome.ORIGINAL_WINDOW_LOST, result.outcome());
    }

    @Test
    void aClosedLaunchWindowThatLeftAnEmptyListFallsBackToTheReturnAddress() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.openWindow("game-window");
        browser.cards.clear();

        GameLaunchCleanup cleanup = cleanup(browser);
        // Loading the return address brings the list back, as a real reload would.
        browser.onNavigate(() -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));
        GameLaunchCleanup.Result result = cleanup.restore(browser, before, detection(
            LaunchSurfaceDetector.SurfaceType.NEW_WINDOW, "game-window"), LIST_URL);

        assertTrue(result.restored());
        assertTrue(result.returnUrlUsed());
        assertEquals(LIST_URL, browser.url);
    }

    // --- same tab ----------------------------------------------------------------------------------

    @Test
    void aNavigatedTabIsReturnedByTheExplicitAddressAndNeverByHistory() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.url = "https://example.test/play";

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION, null), LIST_URL);

        assertTrue(result.restored());
        assertEquals(LIST_URL, browser.url);
        assertEquals(1, browser.navigations);
        // browser.navigate() throws; reaching this line proves history was never used.
    }

    @Test
    void aTabWithNoReturnAddressIsReportedAsNotRestored() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION, null), "  ");

        assertFalse(result.restored());
        assertEquals(GameLaunchCleanup.Outcome.LIST_NOT_RESTORED, result.outcome());
        assertEquals(0, browser.navigations);
    }

    // --- iframe ------------------------------------------------------------------------------------

    @Test
    void aFrameSurfaceIsLeftByReturningToDefaultContent() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertTrue(result.restored());
        assertEquals(1, browser.frameSwitches);
        assertEquals(0, browser.navigations, "the list was still there, so nothing was loaded");
    }

    @Test
    void aFrameThatReplacedTheListIsRecoveredWithTheReturnAddress() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.cards.clear();
        browser.onNavigate(() -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertTrue(result.restored());
        assertEquals(1, browser.frameSwitches);
        assertTrue(result.returnUrlUsed());
    }

    // --- modal / overlay -------------------------------------------------------------------------------

    @Test
    void anOverlayIsClosedThroughTheConfiguredControlExactlyOnce() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.closeControls.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "a"));
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.MODAL_OVERLAY, null), LIST_URL);

        assertTrue(result.restored());
        assertTrue(result.closeControlClicked());
        assertEquals(1, browser.pointerClicks, "one close click, never a second");
        assertEquals(0, browser.navigations);
    }

    @Test
    void anOverlayWithNoConfiguredControlGoesStraightToTheReturnAddress() {
        WebsiteGameLaunchConfig noClose = new WebsiteGameLaunchConfig(
            false, 3, "app-game-item", null, null, "button.demo", ".game-surface", null, null);
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before =
            new LaunchSurfaceDetector(noClose).snapshot(browser);

        GameLaunchCleanup.Result result =
            new GameLaunchCleanup(noClose, new SafePointerClick(browser), 1, () -> { })
                .restore(browser, before,
                    detection(LaunchSurfaceDetector.SurfaceType.MODAL_OVERLAY, null), LIST_URL);

        assertTrue(result.restored());
        assertFalse(result.closeControlClicked());
        assertTrue(result.returnUrlUsed());
    }

    @Test
    void aCloseClickThatDidNotRestoreTheListIsNotRepeated() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.closeControls.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "a"));
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.cards.clear();
        browser.onNavigate(() -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.MODAL_OVERLAY, null), LIST_URL);

        assertTrue(result.restored());
        assertEquals(1, browser.pointerClicks, "the close control is clicked at most once, ever");
        assertTrue(result.returnUrlUsed(), "the return address settled it instead of a second click");
    }

    @Test
    void anUnclickableCloseControlIsNotForcedAndTheAddressIsUsedInstead() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetectorTest.FakeNode control =
            new LaunchSurfaceDetectorTest.FakeNode(browser, "a");
        control.displayed = false;
        browser.closeControls.add(control);
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.cards.clear();
        browser.onNavigate(() -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.MODAL_OVERLAY, null), LIST_URL);

        assertEquals(0, browser.pointerClicks);
        assertTrue(result.restored());
    }

    // --- the list simply will not come back ---------------------------------------------------------------

    @Test
    void aListThatNeverReturnsIsReportedAsNotRestoredRatherThanIgnored() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.cards.clear();

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION, null), LIST_URL);

        assertFalse(result.restored());
        assertEquals(GameLaunchCleanup.Outcome.LIST_NOT_RESTORED, result.outcome());
        assertTrue(result.returnUrlUsed());
    }

    @Test
    void aBrowserThatRefusesACleanupActionIsReportedRatherThanRetried() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.refuseNavigation = true;

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION, null), LIST_URL);

        assertFalse(result.restored());
        assertEquals(GameLaunchCleanup.Outcome.BROWSER_REFUSED, result.outcome());
        assertEquals(1, browser.navigationAttempts, "refused once, not retried");
    }

    // --- nothing opened ------------------------------------------------------------------------------------

    @Test
    void whenNothingOpenedAnIntactListNeedsNoAction() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.NONE, null), LIST_URL);

        assertTrue(result.restored());
        assertEquals(0, browser.navigations);
        assertEquals(0, browser.pointerClicks);
        assertEquals(0, browser.closes);
    }

    @Test
    void anAmbiguousLaunchStillEndsWithAConfirmedList() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);
        browser.cards.clear();
        browser.onNavigate(() -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));

        GameLaunchCleanup.Result result = cleanup(browser).restore(browser, before,
            detection(LaunchSurfaceDetector.SurfaceType.AMBIGUOUS, null), LIST_URL);

        assertTrue(result.restored());
        assertTrue(result.returnUrlUsed());
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    // --- the readiness budget after a navigation ------------------------------------------------

    /** Counts its own calls, so a test can assert exactly how many pauses were taken. */
    private static final class CountingPause implements Runnable {

        private int pauses;
        private final Runnable effect;

        private CountingPause() { this(() -> { }); }

        private CountingPause(Runnable effect) { this.effect = effect; }

        @Override public void run() {
            pauses++;
            effect.run();
        }
    }

    @Test
    void aListThatArrivesAfterTheNavigationIsWaitedFor() {
        // The single-page-application shape. The app is still starting when navigation returns, so the
        // first query finds nothing and the grid appears a moment later.
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.cards.clear();
        CountingPause pause = new CountingPause();
        CountingPause waiting = new CountingPause(
            () -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser, 20, waiting).restore(
            browser, before, detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertTrue(result.restored(), "the list came back within the budget");
        assertEquals(GameLaunchCleanup.Outcome.RESTORED, result.outcome());
        assertTrue(result.returnUrlUsed());
        assertEquals(1, waiting.pauses, "one pause, taken between the two post-navigation queries");
        assertEquals(0, pause.pauses, "and the unused pause was never called");
    }

    @Test
    void aListThatNeverComesBackIsReportedAfterTheWholeBudgetAndNoLonger() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.cards.clear();
        CountingPause pause = new CountingPause();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser, 4, pause).restore(
            browser, before, detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertEquals(GameLaunchCleanup.Outcome.LIST_NOT_RESTORED, result.outcome(),
            "the existing outcome is preserved");
        assertEquals("the list was still unavailable after returning to the supplied address",
            result.detail(), "and so is the existing wording");
        assertTrue(result.returnUrlUsed());
        assertEquals(3, pause.pauses, "at most attempts - 1 pauses, and never one after the last");
        assertEquals(5, browser.cardQueries,
            "one read before the navigation, then at most attempts reads after it");
        assertEquals(1, browser.navigations, "one navigation, not one per attempt");
    }

    @Test
    void aListThatIsAlreadyThereAfterTheNavigationCostsNoPauseAtAll() {
        // The happy path must not become slower. One query, no waiting.
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.cards.clear();
        CountingPause pause = new CountingPause(
            () -> { throw new AssertionError("no pause may be taken when the list is present"); });
        browser.onNavigate(
            () -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser, 20, pause).restore(
            browser, before, detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertTrue(result.restored());
        assertEquals(0, pause.pauses, "zero pauses when the first post-navigation query answers");
    }

    @Test
    void theCheckBeforeTheNavigationIsStillASingleRead() {
        // Nothing has just been reloaded at that point, so there is nothing to wait for.
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.cards.clear();
        CountingPause waiting = new CountingPause(
            () -> browser.cards.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "app-game-item")));
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        cleanup(browser, 20, waiting).restore(
            browser, before, detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertEquals(3, browser.cardQueries,
            "one read before the navigation and two after it, not a poll on both sides");
        assertEquals(List.of("frame", "cards", "navigate", "cards", "cards"), browser.timeline,
            "and the frame was left before the one navigation: " + browser.timeline);
    }

    @Test
    void theSuppliedAddressIsLoadedOnceAndUnchangedIncludingItsQuery() {
        String withQuery = "https://example.test/lobby?pageId=Casino&tab=Top-Games";
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.cards.clear();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        cleanup(browser, 2, () -> { }).restore(
            browser, before, detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), withQuery);

        assertEquals(1, browser.navigations, "exactly one navigation");
        assertEquals(withQuery, browser.url, "the address is loaded exactly as supplied");
    }

    @Test
    void theConfiguredCloseControlIsNeverTouchedOnTheFrameePath() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();
        browser.closeControls.add(new LaunchSurfaceDetectorTest.FakeNode(browser, "a"));
        browser.cards.clear();
        LaunchSurfaceDetector.Snapshot before = snapshot(browser);

        GameLaunchCleanup.Result result = cleanup(browser, 2, () -> { }).restore(
            browser, before, detection(LaunchSurfaceDetector.SurfaceType.IFRAME, null), LIST_URL);

        assertFalse(result.closeControlClicked(), "the frame path never clicks the close control");
        assertEquals(0, browser.pointerClicks, "and no pointer sequence was issued at all");
    }

    @Test
    void aBudgetSmallerThanOneAttemptIsRefusedRatherThanSilentlySkippingTheCheck() {
        LaunchSurfaceDetectorTest.FakeBrowser browser = lobby();

        assertThrows(IllegalArgumentException.class, () -> new GameLaunchCleanup(
            LaunchSurfaceDetectorTest.config(), new SafePointerClick(browser), 0, () -> { }));
    }

    private static LaunchSurfaceDetectorTest.FakeBrowser lobby() {
        return new LaunchSurfaceDetectorTest.FakeBrowser(LIST_URL);
    }

    private static LaunchSurfaceDetector.Snapshot snapshot(WebDriver browser) {
        return new LaunchSurfaceDetector(LaunchSurfaceDetectorTest.config()).snapshot(browser);
    }

    private static GameLaunchCleanup cleanup(LaunchSurfaceDetectorTest.FakeBrowser browser) {
        return cleanup(browser, 1, () -> { });
    }

    /** The same cleanup, with the caller's readiness budget and pause made explicit. */
    private static GameLaunchCleanup cleanup(
        LaunchSurfaceDetectorTest.FakeBrowser browser, int surfaceAttempts, Runnable pause
    ) {
        return new GameLaunchCleanup(
            LaunchSurfaceDetectorTest.config(), new SafePointerClick(browser),
            surfaceAttempts, pause);
    }

    private static LaunchSurfaceDetector.Detection detection(
        LaunchSurfaceDetector.SurfaceType type, String handle
    ) {
        return new LaunchSurfaceDetector.Detection(
            type, Optional.ofNullable(handle),
            new LaunchSurfaceDetector.SafeLocation(LIST_URL, false), 1, "test");
    }
}
