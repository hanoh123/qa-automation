package com.qanize.automation.checks.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one click in the project.
 *
 * <p>It builds one pointer sequence: move to the element's in-view centre, then click. The W3C
 * Element Click algorithm was tried instead, on the theory that a sequence returning normally
 * without delivering an event explained a launch that did nothing. Three runs disproved it — a
 * pass and a failure on identical code and configuration, then a third failure on the element
 * click whose bytecode was verified as loaded — so the mechanism was never the variable and the
 * revert restored the sequence rather than keeping the re-scroll risk for nothing.
 *
 * <p>The properties guarded here are the ones that survived both directions: one click, no retry,
 * and failures that travel outwards instead of being absorbed.
 */
@Tag("unit")
class SafePointerClickTest {

    private static final Path SHARED = Path.of(
        "src/main/java/com/qanize/automation/checks/shared/SafePointerClick.java");

    // --- the click itself ---------------------------------------------------------------------

    @Test
    void clickOnceIssuesExactlyOnePointerSequenceNamingTheControl() {
        FakeBrowser browser = new FakeBrowser();
        FakeControl control = new FakeControl(browser);

        new SafePointerClick(browser).clickOnce(control);

        assertEquals(1, browser.sequences, "one pointer sequence, performed once");
        assertEquals(List.of(control), browser.clicked, "and it named the control it was given");
        assertEquals(0, control.clicks, "the element's own click method is never called");
    }

    @Test
    void theElementsOwnClickIsNeverUsed() {
        // It re-scrolls the element itself, undoing the centring the guard just verified. Tried
        // once, reverted, and pinned here so it is a decision rather than a drift.
        FakeBrowser browser = new FakeBrowser();
        FakeControl control = new FakeControl(browser);

        new SafePointerClick(browser).clickOnce(control);

        assertEquals(0, control.clicks, "WebElement.click() is not how this project clicks");
    }

    @Test
    void anInterceptedClickTravelsOutwardsAndIsNeverRetried() {
        // Loud beats silent. An obstruction that the guard did not catch must reach the caller as
        // a failure rather than becoming a click nobody can account for.
        FakeBrowser browser = new FakeBrowser();
        ElementClickInterceptedException intercepted =
            new ElementClickInterceptedException("something is in the way");
        FakeControl control = new FakeControl(browser);
        browser.refuseWith(intercepted);

        ElementClickInterceptedException thrown = assertThrows(
            ElementClickInterceptedException.class,
            () -> new SafePointerClick(browser).clickOnce(control));

        assertSame(intercepted, thrown, "the browser's own failure, not a rewritten one");
        assertEquals(1, browser.sequences, "attempted once, and never attempted again");
    }

    @Test
    void aStaleClickTravelsOutwardsAndIsNeverRetried() {
        FakeBrowser browser = new FakeBrowser();
        StaleElementReferenceException stale =
            new StaleElementReferenceException("the page replaced it");
        FakeControl control = new FakeControl(browser);
        browser.refuseWith(stale);

        StaleElementReferenceException thrown = assertThrows(
            StaleElementReferenceException.class,
            () -> new SafePointerClick(browser).clickOnce(control));

        assertSame(stale, thrown);
        assertEquals(1, browser.sequences);
    }

    // --- the guard that still runs first --------------------------------------------------------

    @Test
    void theOwnershipGuardStillRefusesAnUnsafeControlBeforeAnythingIsClicked() {
        // Replacing the click did not remove the guard in front of it. A control something else
        // owns is still refused, and refusing means nothing is clicked.
        FakeBrowser browser = new FakeBrowser();
        browser.ownsItsPoint = false;
        FakeControl control = new FakeControl(browser);
        SafePointerClick pointer = new SafePointerClick(browser);

        assertFalse(pointer.ownsItsClickPoint(control), "something else owns the click point");
        assertEquals(0, browser.sequences, "and a guard that says no clicks nothing");
    }

    @Test
    void aControlThatOwnsItsPointIsAllowedThroughAndThenClickedOnce() {
        FakeBrowser browser = new FakeBrowser();
        FakeControl control = new FakeControl(browser);
        SafePointerClick pointer = new SafePointerClick(browser);

        assertTrue(pointer.ownsItsClickPoint(control));
        pointer.clickOnce(control);

        assertEquals(1, browser.sequences);
        assertTrue(browser.centred, "the control is still centred before the guard answers");
    }

    @Test
    void aHiddenOrDisabledControlIsRefusedWithoutAskingTheBrowserAnything() {
        FakeBrowser browser = new FakeBrowser();
        SafePointerClick pointer = new SafePointerClick(browser);

        assertFalse(pointer.ownsItsClickPoint(new FakeControl(browser).hidden()));
        assertFalse(pointer.ownsItsClickPoint(new FakeControl(browser).disabled()));
        assertFalse(browser.centred, "a control that cannot be clicked is not even scrolled to");
    }

    // --- the shape of the implementation --------------------------------------------------------

    @Test
    void thereIsExactlyOneClickCommandAndNoRetryAndNoScriptedClick() throws IOException {
        String code = Files.readString(SHARED)
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)//.*$", " ");

        assertEquals(1, code.split("new Actions\\(driver\\)", -1).length - 1,
            "one pointer click in the whole project");
        assertEquals(0, code.split("element\\.click\\(\\);", -1).length - 1,
            "and it is not the element's own click");
        assertFalse(code.contains("arguments[0].click()"), "and it is not a scripted click");
        for (String loop : List.of("while (", "for (", "do {")) {
            assertFalse(code.contains(loop), "no loop may re-enter the click: " + loop);
        }
        // The click method's own body, not the guard above it, which legitimately catches.
        int start = code.indexOf("public void clickOnce");
        String clickBody = code.substring(start, code.indexOf('}', start));
        assertFalse(clickBody.contains("catch"),
            "the click method swallows nothing: a refusal is the caller's to report");
        assertFalse(clickBody.contains("try"), clickBody);
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** A browser that answers the guard and records what it was asked to do. */
    private static final class FakeBrowser implements WebDriver, JavascriptExecutor, Interactive {

        private final List<WebElement> clicked = new ArrayList<>();
        private int sequences;
        private boolean centred;
        private boolean ownsItsPoint = true;

        @Override
        public Object executeScript(String script, Object... args) {
            if (script.contains("scrollIntoView")) {
                centred = true;
                return null;
            }
            return ownsItsPoint;
        }

        /** Where the project's one click lands, and what the browser may refuse. */
        @Override
        public void perform(java.util.Collection<Sequence> actions) {
            sequences++;
            for (Sequence sequence : actions) {
                collect(sequence.encode(), clicked);
            }
            if (refusal != null) throw refusal;
        }

        private RuntimeException refusal;

        private void refuseWith(RuntimeException failure) {
            this.refusal = failure;
        }

        private static void collect(Object encoded, List<WebElement> into) {
            if (encoded instanceof WebElement element) {
                if (!into.contains(element)) into.add(element);
                return;
            }
            if (encoded instanceof java.util.Map<?, ?> map) {
                map.values().forEach(value -> collect(value, into));
                return;
            }
            if (encoded instanceof Iterable<?> many) {
                many.forEach(value -> collect(value, into));
            }
        }

        @Override public void resetInputState() { }
        @Override public Object executeAsyncScript(String script, Object... args) {
            throw new UnsupportedOperationException("nothing asynchronous here");
        }
        @Override public void get(String url) { throw new AssertionError("never navigates"); }
        @Override public String getCurrentUrl() { return "https://example.test/page"; }
        @Override public String getTitle() { return ""; }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) { throw new UnsupportedOperationException(); }
        @Override public String getPageSource() { throw new AssertionError("never read"); }
        @Override public void close() { }
        @Override public void quit() { }
        @Override public Set<String> getWindowHandles() { return Set.of("main"); }
        @Override public String getWindowHandle() { return "main"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new AssertionError("never navigates"); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }

    /** A control that counts its clicks and can refuse one. */
    private static final class FakeControl implements WebElement {

        private final FakeBrowser browser;
        private boolean displayed = true;
        private boolean enabled = true;
        private int clicks;

        private FakeControl(FakeBrowser browser) {
            this.browser = browser;
        }

            private FakeControl hidden() {
            this.displayed = false;
            return this;
        }

        private FakeControl disabled() {
            this.enabled = false;
            return this;
        }

        @Override
        public void click() {
            clicks++;
        }

        @Override public boolean isDisplayed() { return displayed; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void submit() { throw new AssertionError("nothing is submitted"); }
        @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("nothing is typed"); }
        @Override public void clear() { throw new AssertionError("nothing is cleared"); }
        @Override public String getTagName() { return "a"; }
        @Override public String getAttribute(String name) { return null; }
        @Override public boolean isSelected() { return false; }
        @Override public String getText() { throw new AssertionError("no text is read"); }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) { throw new UnsupportedOperationException(); }
        @Override public String getCssValue(String propertyName) { return ""; }
        @Override public Point getLocation() { return new Point(0, 0); }
        @Override public Dimension getSize() { return new Dimension(10, 10); }
        @Override public Rectangle getRect() { return new Rectangle(getLocation(), getSize()); }
        @Override public <X> X getScreenshotAs(OutputType<X> target) {
            throw new AssertionError("no screenshot here");
        }
    }
}
