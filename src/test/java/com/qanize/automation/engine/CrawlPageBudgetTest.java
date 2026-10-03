package com.qanize.automation.engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.qanize.automation.checks.links.LinkSession;
import com.qanize.automation.checks.login.LoginModule;
import com.qanize.automation.checks.login.WebsiteLoginConfig;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Maximum pages setting, from the run's configuration to the last page actually opened.
 *
 * <p>A completed run configured for three pages produced ten. The value was not lost on the way in
 * — {@code run-config.json} said {@code "maxPages": 3} and {@code generated-run.properties} said
 * {@code maxPages=3} — and the crawler was not at fault either: it stopped exactly where its policy
 * told it to. What went wrong sat between them. The runner built the crawl's policy from
 * {@code CrawlPolicy.defaults()} and never read the number the run had been given, so every crawl
 * was budgeted at the default of ten regardless of what the customer asked for.
 *
 * <p>These tests therefore do not check the crawler's stopping condition, which was always right.
 * They check the one thing nothing checked: that the budget the crawl runs under is the budget the
 * run was configured with. The traversal underneath is the real {@link Crawler} on a scripted
 * graph, so "three pages" means three pages opened, not three pages intended.
 */
@Tag("unit")
class CrawlPageBudgetTest {

    private static final String BASE = "https://example.com/";

    /** More pages than any budget under test, so the budget is always the binding limit. */
    private static final int PAGES_AVAILABLE = 12;

    // --- the budget the crawl actually runs under -------------------------------------------------

    @Test
    void theCrawlIsBudgetedWithTheConfiguredMaximumPages() {
        for (int configured : List.of(1, 2, 3, 7, 10)) {
            SiteOperations operations = new SiteOperations();

            new CrawlRunner(operations, name -> "value").run(config(configured));

            assertEquals(configured, operations.policy.maxPages(),
                "a run configured for " + configured + " pages was budgeted at "
                    + operations.policy.maxPages());
        }
    }

    @Test
    void aRunConfiguredForThreePagesOpensExactlyThreeAndNeverAFourth() {
        // The reported defect, end to end: the configuration says three, the real crawler runs,
        // and page four is a page the browser never goes to.
        SiteOperations operations = new SiteOperations();

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> "value").run(config(3));

        CrawlRunner.CrawlRunResult.Crawled crawled = (CrawlRunner.CrawlRunResult.Crawled) result;
        assertEquals(3, crawled.crawl().attempted().size(),
            "opened: " + operations.opened);
        assertEquals(List.of(page(1), page(2), page(3)), operations.opened);
        assertFalse(operations.opened.contains(page(4)),
            "page 4 was opened even though the run was configured for three pages");
    }

    @Test
    void noPageBeyondTheBudgetProducesAnyRecordAtAll() {
        // Not merely "fewer results": the fourth page must produce no page result, no attempt and
        // no evidence, because the browser never went there. A run that visited ten pages and
        // reported three would still have crawled a customer's site seven times over.
        SiteOperations operations = new SiteOperations();

        CrawlRunner.CrawlRunResult.Crawled crawled = (CrawlRunner.CrawlRunResult.Crawled)
            new CrawlRunner(operations, name -> "value").run(config(3));

        List<String> reported = crawled.crawl().attempted().stream()
            .map(Crawler.PageResult::url)
            .toList();
        assertEquals(3, reported.size(), reported.toString());
        for (int beyond = 4; beyond <= PAGES_AVAILABLE; beyond++) {
            String url = page(beyond);
            assertFalse(reported.contains(url), "page " + beyond + " was reported: " + reported);
            assertFalse(operations.opened.contains(url), "page " + beyond + " was opened");
        }
    }

    @Test
    void aBudgetOfOneVisitsOnlyTheStartPage() {
        SiteOperations operations = new SiteOperations();

        CrawlRunner.CrawlRunResult.Crawled crawled = (CrawlRunner.CrawlRunResult.Crawled)
            new CrawlRunner(operations, name -> "value").run(config(1));

        assertEquals(List.of(page(1)), operations.opened);
        assertEquals(1, crawled.crawl().attempted().size());
    }

    @Test
    void aBudgetOfTenStillVisitsTenWhenTheSiteHasMore() {
        // The previous default, now reached because it was asked for rather than because it was
        // the only number the runner knew.
        SiteOperations operations = new SiteOperations();

        CrawlRunner.CrawlRunResult.Crawled crawled = (CrawlRunner.CrawlRunResult.Crawled)
            new CrawlRunner(operations, name -> "value").run(config(10));

        assertEquals(10, operations.opened.size(), operations.opened.toString());
        assertEquals(10, crawled.crawl().attempted().size());
        assertTrue(crawled.crawl().limits().pageLimitReached(),
            "the site had more to offer, so the budget is what stopped the crawl");
    }

    // --- what the budget must not disturb ---------------------------------------------------------

    @Test
    void theDepthBudgetIsUntouchedByThePageBudget() {
        // The UI has no depth control, so depth keeps the verified default whatever the page
        // budget says. A page budget that quietly widened depth would crawl deeper than anyone
        // asked, in a direction nobody can see from the form.
        for (int configured : List.of(1, 3, 10)) {
            SiteOperations operations = new SiteOperations();

            new CrawlRunner(operations, name -> "value").run(config(configured));

            assertEquals(CrawlPolicy.DEFAULT_MAX_DEPTH, operations.policy.maxDepth(),
                "depth must stay at the default when only the page budget was configured");
        }
    }

    @Test
    void maximumLinksIsADifferentSettingAndDoesNotBecomeThePageBudget() {
        // The two are adjacent in the form and in the properties file, and confusing them would
        // produce a crawl budgeted at 25 that still looked configured.
        SiteOperations operations = new SiteOperations();

        new CrawlRunner(operations, name -> "value").run(config(3, 25));

        assertEquals(3, operations.policy.maxPages(), "the page budget is maxPages");
        assertEquals(3, operations.opened.size());
    }

    @Test
    void theStartUrlAndTheBridgeAreUnchangedByThisFix() {
        SiteOperations operations = new SiteOperations();

        new CrawlRunner(operations, name -> "value").run(config(3));

        assertEquals(BASE, operations.startUrl);
        assertTrue(operations.processor instanceof CrawlerPageProcessor,
            "the per-page bridge is still the existing one");
    }

    @Test
    void breadthFirstOrderSurvivesTheBudget() {
        // The budget truncates the queue; it must not reorder it. Page 2 and page 3 are the start
        // page's own children, which is what breadth-first means here.
        SiteOperations operations = new SiteOperations();

        new CrawlRunner(operations, name -> "value").run(config(3));

        assertEquals(List.of(page(1), page(2), page(3)), operations.opened,
            "the first three pages in breadth-first order, not an arbitrary three");
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static String page(int number) {
        return number == 1 ? BASE : BASE + "page-" + number;
    }

    private static WebsiteConfig config(int maxPages) {
        return config(maxPages, 25);
    }

    private static WebsiteConfig config(int maxPages, int maxLinks) {
        return new WebsiteConfig(
            "1.0", "example", URI.create(BASE), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, maxPages, maxLinks, 10, null,
            new WebsiteTestSelectionConfig(true, false, true, false, false, false));
    }

    /**
     * The runner's operations, with a real crawl over a scripted site.
     *
     * <p>The browser and the sign-in are stubbed, because neither is what is under test. The
     * traversal is not: {@link #crawl} runs the actual {@link Crawler} under exactly the policy the
     * runner chose, so what these tests count is pages the crawler really decided to open.
     */
    private static final class SiteOperations implements CrawlRunner.RunOperations {

        private final List<String> opened = new ArrayList<>();
        private CrawlPolicy policy;
        private String startUrl;
        private CrawlerPageProcessor processor;

        @Override
        public WebDriver startBrowser(WebsiteConfig config) {
            return new IdleDriver();
        }

        @Override
        public LoginModule.Result signIn(
            WebDriver driver, WebsiteConfig config, WebsiteLoginConfig login,
            String username, String password
        ) {
            return new LoginModule.Result(LoginModule.Outcome.PASSED, null);
        }

        @Override
        public LinkSession captureLinkSession(WebDriver driver, WebsiteConfig config) {
            return LinkSession.anonymous();
        }

        @Override
        public Crawler.CrawlResult crawl(
            CrawlPolicy policy, String startUrl, CrawlerPageProcessor processor
        ) {
            this.policy = policy;
            this.startUrl = startUrl;
            this.processor = processor;
            // The real traversal, under the policy the runner just built. Substituting a scripted
            // result here would prove only what this test already assumed.
            return new Crawler(policy).crawl(startUrl, this::openScriptedPage);
        }

        /** A flat site: the start page links to every other page, so the budget is the only brake. */
        private List<String> openScriptedPage(Crawler.Page page) {
            opened.add(page.url());
            if (!page.isStartPage()) {
                return List.of();
            }
            List<String> links = new ArrayList<>();
            for (int number = 2; number <= PAGES_AVAILABLE; number++) {
                links.add(page(number));
            }
            return links;
        }
    }

    /** A browser that is started and quit and asked nothing else. */
    private static final class IdleDriver implements WebDriver {

        @Override public void get(String url) { }
        @Override public String getCurrentUrl() { return BASE; }
        @Override public String getTitle() { return ""; }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) { throw new UnsupportedOperationException(); }
        @Override public String getPageSource() { return ""; }
        @Override public void close() { }
        @Override public void quit() { }
        @Override public java.util.Set<String> getWindowHandles() { return java.util.Set.of("main"); }
        @Override public String getWindowHandle() { return "main"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new UnsupportedOperationException(); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }
}
