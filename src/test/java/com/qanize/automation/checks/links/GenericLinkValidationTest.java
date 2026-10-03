package com.qanize.automation.checks.links;

import com.qanize.automation.browser.GenericBaseSeleniumTest;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.OpenedPage;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The JUnit face of the Links check.
 *
 * <p>The Links behaviour itself lives in {@link LinksModule}. What stays here is what only JUnit
 * and Surefire can provide: the class name {@code CheckId} classifies a run by, the tag the build
 * selects on, the assumption that skips an unselected check, and the conversion of the module's
 * verdict into a pass, a failure or a skip.
 *
 * <p>Opening the page is still done here, deliberately. A module must receive a page that is
 * already open, so that a future runner opens each page once for every module rather than each
 * module opening it again.
 */
@Tag("website-analysis")
public class GenericLinkValidationTest extends GenericBaseSeleniumTest {

    @Test
    void internalLinksOnBasePageHaveValidHttpResponsesWithinConfiguredLimit() {
        WebsiteConfig config = websiteConfig();
        assumeTrue(config.tests().links(), "Test is disabled by tests.links=false");
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        String requested = config.baseUrl().toString();
        // Contract 1.1 execution evidence, written as each action completes. One instance, shared
        // with the module below: step numbers run per test case, so the module's first step has to
        // continue from the start-page step recorded here rather than restart at 1.
        LinkExecutionEvidence execution = new LinkExecutionEvidence();

        long stepStart = System.nanoTime();
        // Opens the requested address and reads where the browser actually landed. The links on
        // this page belong to the document that came back, not to the address that was asked for:
        // attributing them to the request is what made a redirected run report links it had never
        // seen. The requested address is kept, unchanged, for everything that means "you asked for".
        OpenedPage opened = OpenedPage.open(page, requested);
        execution.openedStartPage(opened.landed(), elapsedMs(stepStart));

        // The page is open; everything Links does with it belongs to the module.
        LinksModule.Result result = new LinksModule(page, config, opened.landed(), execution).run();

        // A page with nothing safe to request has always been reported as a skip rather than a
        // pass or a failure, and that difference reaches the customer's summary.
        assumeTrue(!result.nothingEligible(), result.message());
        assertTrue(result.passed(), result.message());
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
