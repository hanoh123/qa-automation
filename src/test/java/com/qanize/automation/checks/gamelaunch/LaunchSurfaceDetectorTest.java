package com.qanize.automation.checks.gamelaunch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a launch apart from the page it happened on.
 *
 * <p>Every case here is a before-and-after pair, because that is the only honest way to answer the
 * question: a catalogue is full of overlays and frames before anything is clicked, so presence
 * proves nothing and only change proves a launch. The pre-existing-surface cases are the ones that
 * matter most — they are the shape that would otherwise report a promotional panel as a game.
 *
 * <p>No browser, no network, no UAT. The fake below counts polls, so "gave up after the attempts
 * ran out" is measured rather than waited for, and nothing sleeps.
 */
@Tag("unit")
class LaunchSurfaceDetectorTest {

    private static final String CARD = "app-game-item";
    private static final String SURFACE = ".game-surface";
    private static final String LIST_URL = "https://example.test/lobby";

    private static final Runnable NO_PAUSE = () -> { };

    // --- the four surfaces --------------------------------------------------------------------

    @Test
    void aNewWindowIsReportedWithTheHandleCleanupWillNeed() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.openWindow("game-window");
        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.NEW_WINDOW, detection.type());
        assertEquals("game-window", detection.newWindowHandle().orElseThrow());
        assertTrue(detection.surfaceOpened());
    }

    @Test
    void aTabThatNavigatedAwayIsReportedAsSameTabNavigation() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.url = "https://example.test/play";
        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION, detection.type());
        assertTrue(detection.newWindowHandle().isEmpty());
        assertEquals("https://example.test/play", detection.location().value());
    }

    @Test
    void aNewFrameMatchingTheConfiguredSurfaceIsReportedAsAnIframe() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.addSurface("iframe");
        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.IFRAME, detection.type());
        assertEquals(0, browser.srcReads, "a launch address is signed; src is never read");
    }

    @Test
    void aNewNonFrameSurfaceIsReportedAsAnOverlay() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.addSurface("div");
        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.MODAL_OVERLAY, detection.type());
    }

    // --- nothing happened ------------------------------------------------------------------------

    @Test
    void nothingAppearingBeforeTheAttemptsRunOutIsReportedAsNoSurface() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 4, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.NONE, detection.type());
        assertEquals(4, detection.attempts(), "every attempt was used before giving up");
        assertFalse(detection.surfaceOpened());
    }

    @Test
    void aSurfaceThatArrivesLateIsStillFoundAndTheRemainingPollsAreNotSpent() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);
        // Passes are counted across the whole fake, and taking the snapshot spends the first one,
        // so pass 4 is the third poll of the detection loop.
        browser.afterPass(4, () -> browser.addSurface("iframe"));

        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 6, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.IFRAME, detection.type());
        assertEquals(3, detection.attempts(), "two empty polls, then the surface");
    }

    @Test
    void thePauseRunsBetweenPollsAndNeverAfterTheLastOne() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);
        int[] pauses = {0};

        detector.detect(browser, before, 3, () -> pauses[0]++);

        assertEquals(2, pauses[0]);
    }

    // --- a surface that was already there ---------------------------------------------------------

    @Test
    void anOverlayThatWasOpenBeforeTheClickIsNeverReportedAsALaunch() {
        // The promotional panel this catalogue shows on arrival. It was there first, so it is not
        // what the click opened, and calling it a launch would report an untested game as tested.
        FakeBrowser browser = lobby();
        browser.addSurface("div");
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.NONE, detection.type());
    }

    @Test
    void aFrameThatWasAlreadyEmbeddedIsNeverReportedAsALaunch() {
        FakeBrowser browser = lobby();
        browser.addSurface("iframe");
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        assertEquals(LaunchSurfaceDetector.SurfaceType.NONE,
            detector.detect(browser, before, 3, NO_PAUSE).type());
    }

    @Test
    void aSecondSurfaceAppearingBesideAPreExistingOneStillCounts() {
        FakeBrowser browser = lobby();
        browser.addSurface("div");
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.addSurface("iframe");

        assertEquals(LaunchSurfaceDetector.SurfaceType.IFRAME,
            detector.detect(browser, before, 3, NO_PAUSE).type());
    }

    // --- priority and ambiguity ---------------------------------------------------------------------

    @Test
    void aWindowAndANavigationTogetherIsAmbiguousRatherThanAGuess() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.openWindow("game-window");
        browser.url = "https://example.test/play";
        LaunchSurfaceDetector.Detection detection = detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(LaunchSurfaceDetector.SurfaceType.AMBIGUOUS, detection.type());
        assertFalse(detection.surfaceOpened());
    }

    @Test
    void twoNewWindowsAreAmbiguousBecauseNeitherCanBeCalledTheGame() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.openWindow("one");
        browser.openWindow("two");

        assertEquals(LaunchSurfaceDetector.SurfaceType.AMBIGUOUS,
            detector.detect(browser, before, 3, NO_PAUSE).type());
    }

    @Test
    void aNewWindowOutranksAFrameThatAppearedAtTheSameTime() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.openWindow("game-window");
        browser.addSurface("iframe");

        assertEquals(LaunchSurfaceDetector.SurfaceType.NEW_WINDOW,
            detector.detect(browser, before, 3, NO_PAUSE).type());
    }

    @Test
    void aNavigationOutranksAnOverlayThatAppearedAtTheSameTime() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.url = "https://example.test/play";
        browser.addSurface("div");

        assertEquals(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION,
            detector.detect(browser, before, 3, NO_PAUSE).type());
    }

    @Test
    void aFrameInsideANewOverlayIsReportedAsAFrame() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.addSurface("div");
        browser.addSurface("iframe");

        assertEquals(LaunchSurfaceDetector.SurfaceType.IFRAME,
            detector.detect(browser, before, 3, NO_PAUSE).type());
    }

    // --- addresses -----------------------------------------------------------------------------------

    @Test
    void aSignedLaunchAddressIsReducedToSchemeHostAndPath() {
        LaunchSurfaceDetector.SafeLocation safe = LaunchSurfaceDetector.safeLocation(
            "https://games.example.test/launch/aviator?token=abc123&sig=deadbeef&sid=42");

        assertEquals("https://games.example.test/launch/aviator", safe.value());
        assertTrue(safe.queryOmitted());
        assertFalse(safe.value().contains("token"));
        assertFalse(safe.value().contains("abc123"));
        assertFalse(safe.value().contains("?"));
    }

    @Test
    void aFragmentIsDroppedWholeRatherThanFiltered() {
        LaunchSurfaceDetector.SafeLocation safe = LaunchSurfaceDetector.safeLocation(
            "https://games.example.test/play#access_token=secret");

        assertEquals("https://games.example.test/play", safe.value());
        assertTrue(safe.queryOmitted());
    }

    @Test
    void credentialsInTheAddressAreRemovedWithIt() {
        LaunchSurfaceDetector.SafeLocation safe = LaunchSurfaceDetector.safeLocation(
            "https://user:secret@games.example.test/play?x=1");

        assertEquals("https://games.example.test/play", safe.value());
        assertFalse(safe.value().contains("secret"));
        assertFalse(safe.value().contains("@"));
    }

    @Test
    void anOrdinaryAddressWithNoQueryReportsNothingOmitted() {
        LaunchSurfaceDetector.SafeLocation safe =
            LaunchSurfaceDetector.safeLocation("https://example.test/lobby");

        assertEquals("https://example.test/lobby", safe.value());
        assertFalse(safe.queryOmitted());
    }

    @Test
    void anAddressThatCannotBeSplitSafelyIsNotReportedAtAll() {
        assertNull(LaunchSurfaceDetector.safeLocation("about:blank?x=1").value());
        assertNull(LaunchSurfaceDetector.safeLocation("").value());
        assertNull(LaunchSurfaceDetector.safeLocation(null).value());
    }

    @Test
    void aQueryOnTheListAddressDoesNotMakeANavigationLookLikeALaunch() {
        // Same page, different tracking parameters. Reduced identically, so nothing moved.
        FakeBrowser browser = lobby();
        browser.url = LIST_URL + "?utm=a";
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.url = LIST_URL + "?utm=b";

        assertEquals(LaunchSurfaceDetector.SurfaceType.NONE,
            detector.detect(browser, before, 2, NO_PAUSE).type());
    }

    // --- detection changes nothing ---------------------------------------------------------------------

    @Test
    void detectionNeverClicksNavigatesRefreshesOrClosesAnything() {
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = detector();
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);
        browser.addSurface("iframe");

        detector.detect(browser, before, 3, NO_PAUSE);

        assertEquals(0, browser.navigations);
        assertEquals(0, browser.closes);
        assertEquals(0, browser.frameSwitches);
        assertEquals(0, browser.clicks);
    }

    @Test
    void withNoSurfaceSelectorOnlyWindowAndNavigationSignalsAreAvailable() {
        WebsiteGameLaunchConfig noSurface = new WebsiteGameLaunchConfig(
            false, 3, CARD, null, null, "button.demo", null, null, null);
        FakeBrowser browser = lobby();
        LaunchSurfaceDetector detector = new LaunchSurfaceDetector(noSurface);
        LaunchSurfaceDetector.Snapshot before = detector.snapshot(browser);

        browser.addSurface("iframe");

        assertEquals(LaunchSurfaceDetector.SurfaceType.NONE,
            detector.detect(browser, before, 2, NO_PAUSE).type(),
            "an unconfigured surface cannot be detected, and is not guessed at");
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private static LaunchSurfaceDetector detector() {
        return new LaunchSurfaceDetector(config());
    }

    static WebsiteGameLaunchConfig config() {
        return new WebsiteGameLaunchConfig(
            false, 3, CARD, ".name", ".provider", "button.demo", SURFACE, ".error", "a.close");
    }

    private static FakeBrowser lobby() {
        return new FakeBrowser(LIST_URL);
    }

    /** A browser that only answers questions, and counts every action it was never asked to take. */
    static final class FakeBrowser implements WebDriver, Interactive {

        String url;
        final Set<String> handles = new LinkedHashSet<>();
        String active = "lobby";
        final List<FakeNode> surfaces = new ArrayList<>();
        final List<FakeNode> cards = new ArrayList<>();
        final List<FakeNode> closeControls = new ArrayList<>();
        int navigations;
        int navigationAttempts;
        /** How many times the card selector was asked for. Counts readiness queries exactly. */
        int cardQueries;
        /** What happened, in order, so a test can prove a frame switch preceded a navigation. */
        final List<String> timeline = new ArrayList<>();
        int closes;
        int frameSwitches;
        int clicks;
        int pointerClicks;
        int srcReads;
        boolean refuseNavigation;
        private Runnable onNavigate = () -> { };
        private int passes;
        private final Map<Integer, Runnable> afterPass = new LinkedHashMap<>();

        FakeBrowser(String url) {
            this.url = url;
            handles.add(active);
            cards.add(new FakeNode(this, "app-game-item"));
        }

        void openWindow(String handle) { handles.add(handle); }
        void addSurface(String tag) { surfaces.add(new FakeNode(this, tag)); }
        void afterPass(int pass, Runnable mutation) { afterPass.put(pass, mutation); }
        void onNavigate(Runnable effect) { this.onNavigate = effect; }

        @Override
        public Set<String> getWindowHandles() {
            passes++;
            Runnable mutation = afterPass.remove(passes);
            if (mutation != null) mutation.run();
            return Set.copyOf(handles);
        }

        @Override public String getWindowHandle() { return active; }
        @Override public String getCurrentUrl() { return url; }

        @Override
        public List<WebElement> findElements(By by) {
            String selector = by.toString().replace("By.cssSelector: ", "");
            if (SURFACE.equals(selector)) return List.copyOf(surfaces);
            if (CARD.equals(selector)) {
                cardQueries++;
                timeline.add("cards");
                return List.copyOf(cards);
            }
            if ("a.close".equals(selector)) return List.copyOf(closeControls);
            return List.of();
        }

        @Override public WebElement findElement(By by) {
            List<WebElement> matches = findElements(by);
            if (matches.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return matches.getFirst();
        }
        @Override
        public void get(String destination) {
            navigationAttempts++;
            if (refuseNavigation) {
                throw new org.openqa.selenium.WebDriverException("the browser refused to navigate");
            }
            navigations++;
            timeline.add("navigate");
            url = destination;
            onNavigate.run();
        }

        // Interactive, so the project's one pointer click can complete without a browser.
        @Override public void perform(java.util.Collection<Sequence> actions) { pointerClicks++; }
        @Override public void resetInputState() { }
        @Override public String getPageSource() { return "<html></html>"; }
        @Override public String getTitle() { return "lobby"; }
        @Override public void close() { closes++; handles.remove(active); }
        @Override public void quit() { throw new AssertionError("nothing may quit the browser"); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }

        @Override
        public Navigation navigate() {
            throw new AssertionError("navigate() is never used; the return URL is explicit");
        }

        @Override
        public TargetLocator switchTo() {
            return new TargetLocator() {
                @Override public WebDriver defaultContent() {
                    frameSwitches++;
                    timeline.add("frame");
                    return FakeBrowser.this;
                }
                @Override public WebDriver window(String handle) { active = handle; return FakeBrowser.this; }
                @Override public WebDriver frame(int index) { frameSwitches++; return FakeBrowser.this; }
                @Override public WebDriver frame(String nameOrId) { frameSwitches++; return FakeBrowser.this; }
                @Override public WebDriver frame(WebElement element) { frameSwitches++; return FakeBrowser.this; }
                @Override public WebDriver parentFrame() { frameSwitches++; return FakeBrowser.this; }
                @Override public WebDriver newWindow(org.openqa.selenium.WindowType type) {
                    throw new UnsupportedOperationException();
                }
                @Override public org.openqa.selenium.Alert alert() { throw new UnsupportedOperationException(); }
                @Override public WebElement activeElement() { throw new UnsupportedOperationException(); }
            };
        }
    }

    /** One element. Reading its tag is allowed; reading its address is not. */
    static final class FakeNode implements WebElement {

        private static final Point ORIGIN = new Point(0, 0);
        private static final Dimension SIZE = new Dimension(100, 40);

        private final FakeBrowser browser;
        private final String tag;
        boolean enabled = true;
        boolean displayed = true;

        FakeNode(FakeBrowser browser, String tag) {
            this.browser = browser;
            this.tag = tag;
        }

        @Override public String getTagName() { return tag; }

        @Override
        public String getAttribute(String name) {
            if ("src".equals(name)) {
                browser.srcReads++;
                throw new AssertionError("an iframe's src must never be read");
            }
            return null;
        }

        @Override public void click() { browser.clicks++; }
        @Override public boolean isDisplayed() { return displayed; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public String getText() { return ""; }
        @Override public boolean isSelected() { return false; }
        @Override public void clear() { throw new AssertionError("nothing is cleared"); }
        @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("nothing is typed"); }
        @Override public void submit() { throw new AssertionError("nothing is submitted"); }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) {
            throw new org.openqa.selenium.NotFoundException("none");
        }
        @Override public String getCssValue(String propertyName) { return ""; }
        @Override public Point getLocation() { return ORIGIN; }
        @Override public Dimension getSize() { return SIZE; }
        @Override public Rectangle getRect() { return new Rectangle(ORIGIN, SIZE); }
        @Override public <X> X getScreenshotAs(OutputType<X> target) {
            throw new AssertionError("detection takes no screenshot");
        }
    }
}
