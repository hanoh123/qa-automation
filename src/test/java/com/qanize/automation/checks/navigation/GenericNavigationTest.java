package com.qanize.automation.checks.navigation;

import java.util.stream.Stream;

import com.qanize.automation.browser.GenericBaseSeleniumTest;
import com.qanize.automation.checks.links.LinkSession;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.OpenedPage;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.evidence.EvidenceScope;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The JUnit face of the Navigation check.
 *
 * <p>The Navigation behaviour itself lives in {@link NavigationModule}. What stays here is what
 * only JUnit and Surefire can provide: the class name {@code CheckId} classifies a run by, the tag
 * the build selects on, the assumption that skips an unselected check, and the conversion of the
 * module's verdict into a pass, a failure or a skip.
 *
 * <p>Opening the start page is still done here, deliberately. A module must receive a page that is
 * already open, so that a future runner opens each page once for every module rather than each
 * module opening it again.
 */
@Tag("website-analysis")
public class GenericNavigationTest extends GenericBaseSeleniumTest {

    @Test
    void discoveredNavigationLinksReachUsablePages() {
        WebsiteConfig config = websiteConfig();
        assumeTrue(config.tests().navigation(), "Test is disabled by tests.navigation=false");
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        String basePage = config.baseUrl().toString();
        // Contract 1.1 execution evidence, written as each action completes. One instance, shared
        // with the module below: step numbers run per test case, so the module's first step has to
        // continue from the start-page step recorded here rather than restart at 1.
        NavigationExecutionEvidence execution = new NavigationExecutionEvidence();

        long stepStart = System.nanoTime();
        // The navigation candidates below are read from the document that came back, so that is
        // their source page. Each destination the module then opens keeps its own address and its
        // own final address after its own redirects -- three separate facts, and they stay so.
        OpenedPage opened = OpenedPage.open(page, basePage);
        execution.openedStartPage(opened.landed(), elapsedMs(stepStart));

        // What a destination that rendered no title may be asked. The session decides whether such
        // a request may be made at all, and the single-page check has always spoken as an
        // anonymous visitor -- the same session LinksModule uses by default on this path -- so it
        // is named here rather than assumed further in.
        NavigationModule.Result result = new NavigationModule(
            page, driver, config, opened.landed(), execution, EvidenceScope.legacy(),
            LinkSession.anonymous().pageStatusProbe(WebsiteConfigLoader.timeout(config)))
            .followDiscoveredLinks();

        // A page with no eligible destination has always been reported as a skip rather than a
        // pass or a failure, and that difference reaches the customer's summary.
        assumeTrue(!result.nothingEligible(), result.message());
        // A destination the run could not check -- an authentication challenge -- is reported the
        // same way: not a pass, and not a failure of the website.
        assumeTrue(!result.skipped(), result.message());
        assertTrue(result.passed(), result.message());
    }

    /**
     * The configured click-to-open menu, or nothing at all.
     *
     * <p><strong>Absent means absent.</strong> When no menu is configured this returns an empty
     * stream, which JUnit reports as a container with no children and Surefire counts as zero
     * tests. That was verified against Surefire 3.5.6 before this was written: an empty factory
     * leaves {@code tests} and {@code skipped} untouched, so a run without a menu is byte-for-byte
     * the run it was before this feature existed. An {@code assumeTrue(false)} would instead have
     * added a skipped test and moved NAVIGATION's counts on every existing run.
     *
     * <p>The decision is made here, from configuration alone, before any browser work happens.
     *
     * <p>It is a separate factory rather than part of the link check because the two must not
     * block each other: the link check abandons itself when a site has no eligible links, and a
     * menu can still be worth testing on such a site.
     */
    @TestFactory
    Stream<DynamicTest> configuredMenuOpensItsPanel() {
        WebsiteConfig config = websiteConfig();
        WebsiteMenuConfig menu = config.menu();
        if (!config.tests().navigation() || menu == null || !menu.enabled()) {
            return Stream.empty();
        }
        // A stable display name. Never the selector, and never anything read from the site.
        return Stream.of(DynamicTest.dynamicTest(
            "configured menu opens its panel", () -> runConfiguredMenuCheck(config, menu)));
    }

    /** Opens the start page, then hands the one configured menu to the module. */
    private void runConfiguredMenuCheck(WebsiteConfig config, WebsiteMenuConfig menu) {
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        NavigationExecutionEvidence execution = new NavigationExecutionEvidence();
        String basePage = config.baseUrl().toString();

        long step = System.nanoTime();
        OpenedPage opened = OpenedPage.open(page, basePage);
        execution.openedMenuStartPage(opened.landed(), elapsedMs(step));

        NavigationModule.Result result =
            new NavigationModule(page, driver, config, opened.landed(), execution)
                .openConfiguredMenu(menu);
        assertTrue(result.passed(), result.message());
    }

    /** Wall-clock milliseconds around one real browser call. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
