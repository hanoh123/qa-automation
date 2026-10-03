package com.qanize.automation.checks.shared;


import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;


/**
 * The one safe way this project clicks a control it has already resolved.
 *
 * <p>Extracted from {@code GenericLoginHelper} so that the Login check and the configured menu
 * check share a single implementation. A second copy of a click that is allowed to fire on a real
 * customer website is exactly the thing that later drifts, so there is deliberately one click
 * command in the whole codebase and it lives here.
 *
 * <p><strong>Why a pointer sequence rather than {@code WebElement.click()}.</strong> The W3C
 * Element Click algorithm scrolls the element into view itself before dispatching, bottom-aligning
 * it — which is what moved a prepared control back to the viewport edge and produced an
 * interception. {@link Actions} moves the pointer to the element's in-view centre and only scrolls
 * when the element is not already visible, so the position just verified is the position clicked.
 *
 * <p><strong>The element click was tried here, and reverted.</strong> A launch that reported one
 * click, an unchanged address and no surface looked like a click that was never delivered, so this
 * method was briefly changed to {@code element.click()}. Three runs settled it: a run at 20:54
 * passed with this pointer sequence and a witnessed, trusted click; a run at 21:10 failed with the
 * same code and byte-identical configuration; a run at 21:43 failed again on the element click,
 * whose bytecode was verified as loaded. Identical code, identical configuration, opposite
 * results — so the click mechanism was never the variable, and the element click bought nothing
 * while reintroducing the re-scroll and interception risk above. What remains unexplained is which
 * node received the click, which is what {@code LaunchClickWitness} now measures rather than
 * guesses at.
 *
 * <p><strong>Why not a JavaScript click.</strong> Unchanged, and not up for revisiting. A scripted
 * click bypasses the very obstruction this class exists to detect, so it would report success for
 * a control a person could not reach. The only JavaScript here scrolls, and asks one non-mutating
 * hit-test question that returns a single boolean.
 *
 * <p><strong>No retry, ever.</strong> One command per interaction, by every caller, and a failure
 * travels out of here untouched. A second click on an unknown website can submit, toggle or
 * navigate a second time, and the analyzer would no longer be able to say what it did.
 */
public final class SafePointerClick {

    private final WebDriver driver;

    public SafePointerClick(WebDriver driver) {
        this.driver = driver;
    }

    /**
     * Scrolls the already-resolved control to the middle of the viewport.
     *
     * <p>JavaScript is used for scrolling only. It reads nothing, changes no value and clicks
     * nothing: {@code scrollIntoView} moves the viewport, and centring rather than the default
     * edge alignment is the point — a control resting on the bottom edge is exactly what produced
     * the interception. Any smooth-scroll animation is absorbed by the clickability wait that
     * follows, so no sleep is needed.
     */
    public void centreInViewport(WebElement element) {
        if (driver instanceof JavascriptExecutor javascript) {
            javascript.executeScript(
                "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
        }
    }

    /**
     * Whether the control is displayed, enabled, centred inside the viewport, and the thing a
     * pointer would actually hit at its centre.
     *
     * <p>Re-requests centring on every poll, because a page that is still laying out can move the
     * control after the first scroll. The hit-test asks the browser one question and receives one
     * boolean: it reads no text, no HTML, no attribute, and never identifies whatever is in the
     * way. Knowing that something obstructs the control is enough to keep waiting; knowing what it
     * is would only create something to leak.
     */
    public boolean ownsItsClickPoint(WebElement element) {
        try {
            if (!element.isDisplayed() || !element.isEnabled()) return false;
            centreInViewport(element);
            if (!(driver instanceof JavascriptExecutor javascript)) return true;
            Object owned = javascript.executeScript(CLICK_POINT_OWNERSHIP, element);
            return Boolean.TRUE.equals(owned);
        } catch (RuntimeException stillSettling) {
            // A detached or re-rendered element is simply not ready yet; the bounded wait decides.
            return false;
        }
    }

    /**
     * One pointer click on the element whose click point was just verified.
     *
     * <p>One sequence and one statement. Nothing is caught here: an interception, a stale element
     * or any other refusal is the caller's to report, and swallowing it is how a run comes to say
     * it clicked something it did not. Nothing is retried here either, and no caller may add a
     * retry: a second click on an unknown website can submit, toggle or navigate a second time,
     * and the analyzer would no longer be able to say what it did.
     *
     * @throws org.openqa.selenium.StaleElementReferenceException when the page replaced the
     *     control between the guard and the click
     */
    public void clickOnce(WebElement element) {
        new Actions(driver).moveToElement(element).click().perform();
    }

    /** Returns a boolean and nothing else. Non-mutating: it reads geometry and hit-tests a point. */
    private static final String CLICK_POINT_OWNERSHIP = """
        const element = arguments[0];
        const box = element.getBoundingClientRect();
        if (box.width === 0 || box.height === 0) { return false; }
        const x = box.left + box.width / 2;
        const y = box.top + box.height / 2;
        const width = window.innerWidth || document.documentElement.clientWidth;
        const height = window.innerHeight || document.documentElement.clientHeight;
        if (x < 0 || y < 0 || x >= width || y >= height) { return false; }
        const atPoint = document.elementFromPoint(x, y);
        if (!atPoint) { return false; }
        return atPoint === element || element.contains(atPoint);
        """;
}
