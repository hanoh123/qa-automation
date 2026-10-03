package com.qanize.automation.checks.search;

import com.qanize.automation.browser.GenericBaseSeleniumTest;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The JUnit face of the Search check.
 *
 * <p>The Search behaviour itself lives in {@link SearchModule}. What stays here is what only JUnit
 * and Surefire can provide: the class name {@code CheckId} classifies a run by, the tag the build
 * selects on, the assumption that skips an unselected check, and the conversion of the module's
 * verdict into a pass or a failure.
 *
 * <p>Opening the page is still done here, deliberately. A module must receive a page that is
 * already open, so that a future runner opens each page once for every module rather than each
 * module opening it again. Until that runner exists this wrapper is the thing that opens it.
 */
@Tag("website-analysis")
public class GenericSearchTest extends GenericBaseSeleniumTest {

    @Test
    void configuredSearchCanAcceptQueryWhenEnabled() {
        WebsiteConfig config = websiteConfig();
        assumeTrue(config.tests().search(), "Test is disabled by tests.search=false");
        WebsiteSearchConfig search = config.search();
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        String pageUrl = config.baseUrl().toString();
        // Contract 1.1 execution evidence, written as each action completes. One instance, shared
        // with the module below: step numbers run per test case, so the module's first search-input
        // step has to continue from the start-page step recorded here rather than restart at 1.
        SearchExecutionEvidence execution = new SearchExecutionEvidence();

        long stepStart = System.nanoTime();
        try {
            page.openUrl(pageUrl);
        } catch (RuntimeException navigationFailure) {
            // Record the real failure, then rethrow the same exception untouched: the check must
            // fail exactly as it did before, and never look like it simply produced no evidence.
            execution.startPageOpenFailed(pageUrl, navigationFailure, elapsedMs(stepStart));
            throw navigationFailure;
        }
        execution.openedStartPage(pageUrl, elapsedMs(stepStart));

        // The page is open; everything Search does to it belongs to the module.
        SearchModule.Result result = new SearchModule(page, search, pageUrl, execution).run();
        assertTrue(result.passed(), result.failureMessage());
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
