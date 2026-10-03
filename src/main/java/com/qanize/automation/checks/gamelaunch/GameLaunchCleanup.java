package com.qanize.automation.checks.gamelaunch;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriverException;

import com.qanize.automation.checks.shared.SafePointerClick;
import com.qanize.automation.checks.shared.SelectorUtils;

/**
 * Puts the browser back where the check found it, and says plainly whether it managed to.
 *
 * <p>Cleanup is where an automated launch check earns the right to test a second game. If the
 * catalogue is not genuinely back, every later game is inspecting whatever the previous one left
 * behind, and the run reports results for pages it never saw. So this class ends by asking the one
 * question that matters — does the configured card list match anything again — and returns that
 * answer rather than assuming it.
 *
 * <p><strong>Never {@code navigate().back()}.</strong> History on a gaming site is not a reliable
 * inverse of a launch: a launch may have replaced the entry, pushed several, or opened a context
 * with no history at all, and going back can re-trigger the very thing that was just closed. The
 * caller supplies an explicit return URL instead, and that is the only way this class navigates.
 *
 * <p><strong>The close control is clicked at most once.</strong> A second click on an unknown page
 * is a second action nobody asked for; if one close attempt did not restore the list, the return
 * URL settles it.
 *
 * <p>Nothing here dismisses a promotional popup, and nothing here holds a card element: the list
 * is confirmed by counting a fresh query, never by re-checking something discovery captured.
 *
 * <p><strong>The query after a navigation is given time; the ones before it are not.</strong> A
 * run reported the list unavailable immediately after returning to the very address it had loaded
 * the list from minutes earlier. The reason was here: the load before the launch is followed by a
 * bounded poll, and the load during cleanup was followed by a single query taken the instant
 * {@code driver.get} returned. On a single-page application that instant is before the application
 * has rendered anything, so the check was losing a race this class had set up against itself. The
 * post-navigation check now gets the same budget the first load always had — the caller's own
 * attempts and pause, not a second timeout model. Every check before a navigation stays a single
 * read, because nothing has just been reloaded at that point.
 */
public final class GameLaunchCleanup {

    /** Why cleanup ended the way it did. One value, chosen before anything else is reported. */
    public enum Outcome {

        /** The catalogue is available again and the browser is back in the original context. */
        RESTORED,

        /** The original browsing context is gone, so there is nothing to return to. */
        ORIGINAL_WINDOW_LOST,

        /** Everything reasonable was tried and the card list still matches nothing. */
        LIST_NOT_RESTORED,

        /** The browser refused an action cleanup needed. */
        BROWSER_REFUSED
    }

    /**
     * What cleanup did and whether it worked.
     *
     * @param closeControlClicked whether the configured close control was clicked — at most once
     * @param returnUrlUsed       whether the explicit return URL had to be loaded
     */
    public record Result(
        Outcome outcome,
        boolean closeControlClicked,
        boolean returnUrlUsed,
        String detail
    ) {

        public boolean restored() {
            return outcome == Outcome.RESTORED;
        }
    }

    private final WebsiteGameLaunchConfig config;
    private final SafePointerClick pointer;
    /** How many times the list may be asked for after a navigation. The caller's own budget. */
    private final int surfaceAttempts;
    /** What to do between those attempts. The caller's own pause, never a sleep written here. */
    private final Runnable pause;

    /**
     * @param surfaceAttempts the caller's attempt budget, shared with every other wait in this
     *     check so there is one waiting idiom in the project rather than two
     * @param pause           what the caller does between attempts
     */
    public GameLaunchCleanup(
        WebsiteGameLaunchConfig config,
        SafePointerClick pointer,
        int surfaceAttempts,
        Runnable pause
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.pointer = Objects.requireNonNull(pointer, "pointer must not be null");
        if (surfaceAttempts < 1) {
            throw new IllegalArgumentException("surfaceAttempts must be at least 1");
        }
        this.surfaceAttempts = surfaceAttempts;
        this.pause = Objects.requireNonNull(pause, "pause must not be null");
    }

    /**
     * Returns the browser to the catalogue after one launch.
     *
     * @param before    the snapshot taken before the launch click, which names the original window
     * @param detection what the launch opened
     * @param returnUrl the explicit address of the list; the only address this class will load
     */
    public Result restore(
        WebDriver driver,
        LaunchSurfaceDetector.Snapshot before,
        LaunchSurfaceDetector.Detection detection,
        String returnUrl
    ) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(before, "before-click snapshot must not be null");
        Objects.requireNonNull(detection, "detection must not be null");
        try {
            return switch (detection.type()) {
                case NEW_WINDOW -> closeLaunchedWindow(driver, before, detection, returnUrl);
                case SAME_TAB_NAVIGATION -> returnByUrl(driver, returnUrl, false);
                case IFRAME -> leaveFrame(driver, returnUrl);
                case MODAL_OVERLAY -> closeOverlay(driver, returnUrl);
                // Nothing opened, or nothing that can be described. The list is still expected to
                // be there, so it is confirmed rather than assumed, and the return URL is the
                // remedy if it is not.
                case NONE, AMBIGUOUS -> listAvailable(driver)
                    ? restored(false, false, "no surface to close; the list is still present")
                    : returnByUrl(driver, returnUrl, false);
            };
        } catch (WebDriverException refused) {
            return new Result(Outcome.BROWSER_REFUSED, false, false,
                "the browser refused a cleanup action: " + refused.getClass().getSimpleName());
        }
    }

    private Result closeLaunchedWindow(
        WebDriver driver,
        LaunchSurfaceDetector.Snapshot before,
        LaunchSurfaceDetector.Detection detection,
        String returnUrl
    ) {
        Optional<String> launched = detection.newWindowHandle();
        if (launched.isPresent()) {
            driver.switchTo().window(launched.get());
            driver.close();
        }
        Set<String> remaining = driver.getWindowHandles();
        if (!remaining.contains(before.activeHandle())) {
            // The window the catalogue was in has gone. Switching to some other window would be a
            // guess about where the list lives, and the check would report on the wrong page.
            return new Result(Outcome.ORIGINAL_WINDOW_LOST, false, false,
                "the original window is no longer open");
        }
        driver.switchTo().window(before.activeHandle());
        return listAvailable(driver)
            ? restored(false, false, "closed the launched window and returned to the list")
            : returnByUrl(driver, returnUrl, false);
    }

    private Result leaveFrame(WebDriver driver, String returnUrl) {
        driver.switchTo().defaultContent();
        return listAvailable(driver)
            ? restored(false, false, "left the frame and the list was still there")
            : returnByUrl(driver, returnUrl, false);
    }

    private Result closeOverlay(WebDriver driver, String returnUrl) {
        boolean clicked = clickCloseControlOnce(driver);
        if (clicked && listAvailable(driver)) {
            return restored(true, false, "closed the overlay through the configured control");
        }
        return returnByUrl(driver, returnUrl, clicked);
    }

    /**
     * Clicks the configured close control, once, or reports that it could not.
     *
     * <p>No retry and no second candidate: the first match is the control the configuration names,
     * and if it is not usable the return URL is the answer rather than another click.
     */
    private boolean clickCloseControlOnce(WebDriver driver) {
        if (!SelectorUtils.hasUsableSelector(config.closeSelector())) return false;
        List<WebElement> controls = driver.findElements(SelectorUtils.css(config.closeSelector()));
        if (controls.isEmpty()) return false;
        WebElement control = controls.getFirst();
        if (!pointer.ownsItsClickPoint(control)) return false;
        pointer.clickOnce(control);
        return true;
    }

    /** Loads the explicit return address. The only navigation cleanup ever performs. */
    private Result returnByUrl(WebDriver driver, String returnUrl, boolean closeClicked) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return new Result(Outcome.LIST_NOT_RESTORED, closeClicked, false,
                "no return address was supplied, so the list could not be restored");
        }
        driver.get(returnUrl);
        return listAvailableWithinBudget(driver)
            ? restored(closeClicked, true, "returned to the list by the supplied address")
            : new Result(Outcome.LIST_NOT_RESTORED, closeClicked, true,
                "the list was still unavailable after returning to the supplied address");
    }

    /**
     * The same question as {@link #listAvailable}, asked until the budget runs out.
     *
     * <p>Used only after a navigation, which is the only moment the application is guaranteed to
     * be starting from nothing. The first query happens immediately, so a list that is already
     * there costs nothing at all and no pause is taken; a list that is not there is asked for
     * again, with one pause between attempts and never one after the last.
     */
    private boolean listAvailableWithinBudget(WebDriver driver) {
        for (int attempt = 0; attempt < surfaceAttempts; attempt++) {
            if (attempt > 0) pause.run();
            if (listAvailable(driver)) return true;
        }
        return false;
    }

    /**
     * Whether the catalogue is answering right now.
     *
     * <p>A fresh query every time, and only a count is kept. Holding on to a card here would be
     * holding a reference that the next launch invalidates.
     *
     * <p>One read, no budget. Every caller of this method asks before a navigation, when the page
     * has had whatever time it was always going to have; the post-navigation caller is
     * {@link #listAvailableWithinBudget} instead.
     */
    private boolean listAvailable(WebDriver driver) {
        if (!SelectorUtils.hasUsableSelector(config.cardSelector())) return false;
        return !driver.findElements(LaunchSurfaceDetector.cardLocator(config)).isEmpty();
    }

    private static Result restored(boolean closeClicked, boolean returnUrlUsed, String detail) {
        return new Result(Outcome.RESTORED, closeClicked, returnUrlUsed, detail);
    }
}
