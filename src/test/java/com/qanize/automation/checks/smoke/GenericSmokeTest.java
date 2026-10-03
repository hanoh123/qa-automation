package com.qanize.automation.checks.smoke;

import com.qanize.automation.browser.GenericBaseSeleniumTest;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.evidence.SensitiveDataFilter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The JUnit face of the Basic website check.
 *
 * <p>The page-health inspection itself lives in {@link PageHealthModule}. What stays here is what
 * only JUnit and Surefire can provide: the class name {@code CheckId} classifies a run by, the tag
 * the build selects on, the assumption that skips an unselected check, and the conversion of the
 * module's verdict into a pass or a failure.
 *
 * <p>Opening the page is still done here, deliberately. The module inspects a page that is already
 * open, so that a future runner opens each page once and every module inspects that same page
 * rather than each opening it again.
 */
@Tag("website-analysis")
public class GenericSmokeTest extends GenericBaseSeleniumTest {

    @Test
    void configuredBaseUrlLoadsUsablePage() {
        WebsiteConfig config = websiteConfig();
        assumeTrue(config.tests().smoke(), "Test is disabled by tests.smoke=false");
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        String requested = config.baseUrl().toString();
        // Contract 1.1 execution evidence, written as each action completes. One instance, shared
        // with the module below: step numbers run per test case, so the module's first step has to
        // continue from the navigation step recorded here rather than restart at 1.
        SmokeExecutionEvidence execution = new SmokeExecutionEvidence();

        // Opening the page is the caller's job. A browser failure here is contained exactly as it
        // always was: it becomes this page's verdict, never a thrown check.
        String navigationFailure = null;
        long stepStart = System.nanoTime();
        try {
            page.openUrl(requested);
            execution.navigated(requested, elapsedMs(stepStart));
        } catch (RuntimeException browserFailure) {
            navigationFailure =
                browserFailure.getClass().getSimpleName() + ": " + browserFailure.getMessage();
            execution.browserFailed(
                requested, SensitiveDataFilter.sanitizeMessage(navigationFailure), elapsedMs(stepStart));
        }

        PageHealthModule.Result result = new PageHealthModule(page, execution).inspect(
            requested,
            navigationFailure,
            config.optionalExpectedTitleContains().orElse(null),
            config.optionalExpectedText().orElse(null));

        // A page the run could not look at — an authentication challenge, say — is not a failure
        // of the website. Reported the way every other wrapper reports "nothing to check".
        assumeTrue(!result.skipped(), result.message());
        assertTrue(result.passed(), result.message());
    }

    /** Wall-clock milliseconds around one real browser call. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
