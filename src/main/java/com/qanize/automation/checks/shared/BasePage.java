package com.qanize.automation.checks.shared;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;


public class BasePage {

    protected final WebDriver driver;
    protected final Duration timeout;

    public BasePage(WebDriver driver, Duration timeout) {
        this.driver = driver;
        this.timeout = timeout;
    }

    public void openUrl(String url) {
        driver.get(url);
    }

    public String getTitle() {
        return driver.getTitle();
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public WebElement findVisibleElement(By locator) {
        return WaitUtils.visible(driver, locator, timeout);
    }

    public boolean clickIfVisible(String cssSelector) {
        if (!SelectorUtils.hasUsableSelector(cssSelector)) {
            return false;
        }
        List<WebElement> elements = driver.findElements(SelectorUtils.css(cssSelector));
        if (elements.isEmpty() || !elements.getFirst().isDisplayed()) {
            return false;
        }
        elements.getFirst().click();
        return true;
    }

    public boolean typeIfVisible(String cssSelector, String value) {
        if (!SelectorUtils.hasUsableSelector(cssSelector)) {
            return false;
        }
        List<WebElement> elements = driver.findElements(SelectorUtils.css(cssSelector));
        if (elements.isEmpty() || !elements.getFirst().isDisplayed()) {
            return false;
        }
        elements.getFirst().clear();
        elements.getFirst().sendKeys(value);
        return true;
    }

    public List<WebElement> getLinks() {
        return driver.findElements(By.cssSelector("a[href]"));
    }

    /** How many elements currently match, without waiting. Used for discovery diagnostics. */
    public int count(String cssSelector) {
        return driver.findElements(By.cssSelector(cssSelector)).size();
    }

    /**
     * Waits until the page has rendered something worth inspecting.
     *
     * <p>{@code driver.get} only guarantees the HTML document; a JavaScript-rendered application
     * fills in its links and forms afterwards. Without this wait the discovery step runs against
     * the empty shell and every check reports "nothing found" on a page that is full of content.
     */
    public PageContentReadiness.Result awaitRenderedContent() {
        int attempts = (int) Math.max(1, timeout.toMillis() / CONTENT_POLL_MILLIS);
        return PageContentReadiness.awaitNonEmpty(
            () -> count(CONTENT_SELECTOR),
            attempts,
            BasePage::pauseBetweenContentPolls
        );
    }

    private static final String CONTENT_SELECTOR = "a[href], form";
    private static final long CONTENT_POLL_MILLIS = 250;

    private static void pauseBetweenContentPolls() {
        try {
            Thread.sleep(CONTENT_POLL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isTextPresent(String expectedText) {
        return expectedText != null
            && !expectedText.isBlank()
            && driver.getPageSource().contains(expectedText);
    }
}
