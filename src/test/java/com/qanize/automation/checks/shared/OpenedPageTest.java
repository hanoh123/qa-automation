package com.qanize.automation.checks.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-page orchestration boundary.
 *
 * <p>The bug this exists for was not in any module. Every module already did the right thing with
 * the base page it was given; the four single-page JUnit wrappers gave them the address the user had
 * typed, and the browser had been redirected away from it before a single link was read. The fix is
 * one production class, and these are its tests -- plus source guards over the four wrappers, because
 * the defect was a wiring decision and a wiring decision can only be pinned at the wiring.
 *
 * <p>No real browser. The fake driver below answers `getCurrentUrl()` however a test says, which is
 * exactly the variable that mattered.
 */
@Tag("unit")
class OpenedPageTest {

    private static final String REQUESTED = "https://site.test/dashboard/biz_ANON/websites/";
    private static final String LANDED = "https://site.test/";

    /** A driver that opens whatever it is told and then claims to be wherever a test says. */
    private static class FakeDriver implements WebDriver {

        private final String answer;
        private final RuntimeException refusal;
        private final List<String> opened = new java.util.ArrayList<>();

        private FakeDriver(String answer) {
            this(answer, null);
        }

        private FakeDriver(String answer, RuntimeException refusal) {
            this.answer = answer;
            this.refusal = refusal;
        }

        @Override public void get(String url) { opened.add(url); }

        @Override
        public String getCurrentUrl() {
            if (refusal != null) throw refusal;
            return answer;
        }

        @Override public String getTitle() { return "title"; }
        @Override public List<org.openqa.selenium.WebElement> findElements(org.openqa.selenium.By by) {
            return List.of();
        }
        @Override public org.openqa.selenium.WebElement findElement(org.openqa.selenium.By by) {
            throw new org.openqa.selenium.NotFoundException("none");
        }
        @Override public String getPageSource() { return "<html><body>x</body></html>"; }
        @Override public void close() { }
        @Override public void quit() { }
        @Override public java.util.Set<String> getWindowHandles() { return java.util.Set.of("w"); }
        @Override public String getWindowHandle() { return "w"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new UnsupportedOperationException(); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }

    private static BasePage pageOn(FakeDriver driver) {
        return new BasePage(driver, Duration.ofSeconds(1));
    }

    // --- what it reads ---------------------------------------------------------------------------

    @Test
    void aPageThatStayedWhereItWasSentReportsOneAddress() {
        FakeDriver driver = new FakeDriver(REQUESTED);

        OpenedPage opened = OpenedPage.open(pageOn(driver), REQUESTED);

        assertEquals(REQUESTED, opened.requested());
        assertEquals(REQUESTED, opened.landed());
        assertFalse(opened.redirected());
        assertEquals(List.of(REQUESTED), driver.opened, "it opened what it was asked to open");
    }

    @Test
    void aPageThatWasRedirectedReportsTheDocumentThatCameBack() {
        // The real run: a private dashboard was asked for and a public home page came back. The
        // requested address is kept; the landed one is what a discovery belongs to.
        OpenedPage opened = OpenedPage.open(pageOn(new FakeDriver(LANDED)), REQUESTED);

        assertEquals(REQUESTED, opened.requested());
        assertEquals(LANDED, opened.landed());
        assertTrue(opened.redirected());
    }

    @Test
    void aLandingAddressIsSanitizedBeforeItBecomesEveryItemsPage() {
        // A landing address is exactly where a return parameter or a session token shows up, and
        // this value is about to be written as the pageUrl of every item the check produces.
        OpenedPage opened = OpenedPage.open(
            pageOn(new FakeDriver("https://site.test/?next=%2Fdashboard%2F&token=SUPERSECRET")),
            REQUESTED);

        assertFalse(opened.landed().contains("SUPERSECRET"), opened.landed());
    }

    // --- what it refuses to invent ----------------------------------------------------------------

    @Test
    void aDriverThatWillNotSayWhereItIsLeavesTheRequestedAddressInPlace() {
        OpenedPage opened = OpenedPage.open(
            pageOn(new FakeDriver(null, new WebDriverException("no session"))), REQUESTED);

        assertEquals(REQUESTED, opened.landed(), "never null, and never a guess");
        assertFalse(opened.redirected());
    }

    @Test
    void anAnswerThatIsNotADocumentIsNotAnAddress() {
        for (String notAPage : List.of("", "   ", "about:blank", "data:,", "chrome-error://x")) {
            OpenedPage opened = OpenedPage.open(pageOn(new FakeDriver(notAPage)), REQUESTED);
            assertEquals(REQUESTED, opened.landed(), notAPage);
        }
    }

    @Test
    void aFailureToOpenIsStillTheCallersToHandle() {
        // Checks that contain their own navigation failure must keep doing so: this must not
        // swallow the open.
        FakeDriver refusing = new FakeDriver(REQUESTED) {
            @Override public void get(String url) { throw new WebDriverException("refused"); }
        };
        BasePage page = new BasePage(refusing, Duration.ofSeconds(1));

        assertThrows(WebDriverException.class, () -> OpenedPage.open(page, REQUESTED));
        assertEquals(List.of(), refusing.opened, "nothing was recorded as opened");
    }

    @Test
    void whereReadsWithoutOpeningAnything() {
        // The crawler opens the page itself, so it can time the open and contain its failures.
        FakeDriver driver = new FakeDriver(LANDED);

        OpenedPage opened = OpenedPage.where(pageOn(driver), REQUESTED);

        assertEquals(LANDED, opened.landed());
        assertEquals(List.of(), driver.opened, "where() must never navigate");
    }

    // --- the wiring, pinned at the four single-page wrappers ---------------------------------------

    private static String wrapper(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @Test
    void everySinglePageWrapperHandsItsModuleTheLandedPageAndNotTheRequest() throws IOException {
        // This is the test that would have caught the miss. The fix that shipped before this one
        // corrected CrawlerPageProcessor, which a single-page run never executes -- so every
        // assertion below is over the path that actually runs when the crawler is off.
        for (String path : List.of(
            "src/test/java/com/qanize/automation/checks/links/GenericLinkValidationTest.java",
            "src/test/java/com/qanize/automation/checks/forms/GenericFormDetectionTest.java",
            "src/test/java/com/qanize/automation/checks/navigation/GenericNavigationTest.java")) {
            String source = wrapper(path);
            assertTrue(source.contains("OpenedPage.open(page,"),
                path + " must read where the browser landed");
            assertTrue(source.contains("opened.landed()"),
                path + " must hand the landed page to its module");
            // The old wiring, in every spelling it had.
            for (String old : List.of(
                "config, basePage, execution)",
                "driver, pageUrl, execution)",
                "page.openUrl(basePage);",
                "page.openUrl(pageUrl);")) {
                assertFalse(source.contains(old), path + " still has the old wiring: " + old);
            }
        }
    }

    @Test
    void theBasicWebsiteCheckStillReportsTheAddressThatWasSubmitted() throws IOException {
        // Deliberately NOT changed. "Submitted page" is the item whose identity is the request, and
        // PageHealthModule already publishes where the browser ended up as its finalUrl metadata --
        // which is the evidence the requested-versus-reached warning is derived from.
        String source = wrapper("src/test/java/com/qanize/automation/checks/smoke/GenericSmokeTest.java");

        assertTrue(source.contains("page.openUrl(requested);"), source);
        assertTrue(source.contains(".inspect(\n            requested,"), source);
        assertFalse(source.contains("opened.landed()"),
            "the submitted page's own item must keep the submitted address");
    }

    @Test
    void thereIsExactlyOneDefinitionOfWhereDidWeLand() throws IOException {
        // One mechanism for the crawler and the wrappers both, so a future check cannot invent a
        // second answer to the same question.
        String crawler = wrapper("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java");

        assertTrue(crawler.contains("OpenedPage.where(page, context.url()).landed()"), crawler);
        assertFalse(crawler.contains("driver.getCurrentUrl()"),
            "the crawler must go through OpenedPage rather than reading the driver itself");
    }

    @Test
    void theBoundaryIsNamedAndIsTheOnlyOneInThisClass() throws IOException {
        String source = wrapper("src/main/java/com/qanize/automation/checks/shared/OpenedPage.java");

        assertEquals(1, source.split("catch \\(", -1).length - 1,
            "one contained boundary: the driver that will not answer");
        assertTrue(source.contains("catch (RuntimeException unreadable)"), source);
        assertFalse(source.contains("catch (Exception"), source);
        assertFalse(source.contains("catch (Throwable"), source);
    }
}
