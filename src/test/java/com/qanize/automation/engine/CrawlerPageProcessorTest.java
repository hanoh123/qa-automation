package com.qanize.automation.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qanize.automation.checks.forms.FormsModule;
import com.qanize.automation.checks.links.LinksModule;
import com.qanize.automation.checks.navigation.NavigationModule;
import com.qanize.automation.checks.search.SearchModule;
import com.qanize.automation.checks.smoke.PageHealthModule;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;
import com.qanize.automation.evidence.EvidenceScope;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The bridge between the crawl queue and the page checks.
 *
 * <p>No WebDriver, no Chrome, no network and no real website: the page work is supplied through
 * the processor's own seam, which is what lets the ordering, the selection rules and the
 * open-failure behaviour be proved exactly rather than approximately.
 */
@Tag("unit")
class CrawlerPageProcessorTest {

    private static final String START = "https://example.com/";

    // --- helpers -------------------------------------------------------------------------------

    private static WebsiteConfig config(boolean smoke, boolean links) {
        return config(smoke, links, false, false, false);
    }

    private static WebsiteConfig config(
        boolean smoke, boolean links, boolean forms, boolean navigation, boolean search
    ) {
        return new WebsiteConfig(
            "1.0", "example", URI.create(START), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, 5, 25, 10, null,
            new WebsiteTestSelectionConfig(smoke, navigation, links, search, forms, false));
    }

    private static CrawlerPageProcessor processor(ScriptedAudit audit, boolean smoke, boolean links) {
        return new CrawlerPageProcessor(audit, config(smoke, links));
    }

    private static CrawlerPageProcessor allChecks(ScriptedAudit audit) {
        return new CrawlerPageProcessor(audit, config(true, true, true, true, true));
    }

    private static Crawler.Page page(String url, int depth, String parent) {
        return new Crawler.Page(url, depth, parent);
    }

    // --- opening the page ----------------------------------------------------------------------

    @Test
    void aPageIsOpenedExactlyOnceAndBothChecksSeeThatSameOpenPage() {
        ScriptedAudit audit = new ScriptedAudit();

        processor(audit, true, true).process(page(START, 0, null));

        assertEquals(1, audit.opens(START), "one open per page, and no silent retry");
        assertEquals(List.of("open:" + START, "health:" + START, "links:" + START), audit.calls,
            "both checks ran against the page that was just opened: " + audit.calls);
    }

    @Test
    void pageHealthRunsBeforeLinks() {
        ScriptedAudit audit = new ScriptedAudit();

        processor(audit, true, true).process(page(START, 0, null));

        assertTrue(audit.calls.indexOf("health:" + START) < audit.calls.indexOf("links:" + START),
            "health frames every other result, so it goes first: " + audit.calls);
    }

    @Test
    void theProcessorNeverCreatesOrQuitsABrowser() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // The caller owns the browser lifecycle exactly as the single-page checks already assume.
        assertFalse(source.contains("ChromeDriver"), "the processor must not create a driver");
        assertFalse(source.contains("ChromeOptions"), source);
        assertFalse(source.contains(".quit()"), "the processor must not close the browser");
        assertFalse(source.contains(".close()"), source);
        assertFalse(source.contains("new CommonPage("), "it borrows a page, it does not build one");
        assertEquals(1, source.split("\\.openUrl\\(", -1).length - 1,
            "exactly one place opens a page");
    }

    // --- which checks run ----------------------------------------------------------------------

    @Test
    void anUnselectedCheckDoesNotRunAndIsNeverReportedAsAPass() {
        ScriptedAudit healthOnly = new ScriptedAudit();
        CrawlerPageProcessor.PageAuditResult health =
            resultOf(processor(healthOnly, true, false), healthOnly);
        assertEquals(List.of("open:" + START, "health:" + START), healthOnly.calls);
        assertEquals(CrawlerPageProcessor.Status.PASSED, health.pageHealth().status());
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, health.links().status(),
            "an unselected check must say so rather than claim a pass");
        assertFalse(health.links().ran());

        ScriptedAudit linksOnly = new ScriptedAudit();
        CrawlerPageProcessor.PageAuditResult links =
            resultOf(processor(linksOnly, false, true), linksOnly);
        assertEquals(List.of("open:" + START, "links:" + START), linksOnly.calls);
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, links.pageHealth().status());
        assertEquals(CrawlerPageProcessor.Status.PASSED, links.links().status());

        ScriptedAudit neither = new ScriptedAudit();
        CrawlerPageProcessor.PageAuditResult none = resultOf(processor(neither, false, false), neither);
        assertEquals(List.of("open:" + START), neither.calls, "the page still opens");
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, none.pageHealth().status());
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, none.links().status());
    }

    private static CrawlerPageProcessor.PageAuditResult resultOf(
        CrawlerPageProcessor processor, ScriptedAudit audit
    ) {
        processor.process(page(START, 0, null));
        return processor.results().get(0);
    }

    // --- failures ------------------------------------------------------------------------------

    @Test
    void aPageThatWillNotOpenRunsNoCheckAndOffersNoCandidates() {
        ScriptedAudit audit = new ScriptedAudit()
            .openFails(START, new IllegalStateException("the page never became ready"));

        CrawlerPageProcessor processor = processor(audit, true, true);
        List<String> candidates = processor.process(page(START, 0, null));

        assertEquals(List.of("open:" + START), audit.calls, "no check may run on a page that is not there");
        assertTrue(candidates.isEmpty());

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertFalse(result.opened());
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.pageOpen().status());
        assertTrue(result.pageOpen().message().contains("the page never became ready"),
            result.pageOpen().message());
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, result.pageHealth().status(),
            "a check that could not run must not read as skipped-by-choice or as a pass");
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, result.links().status());
        assertTrue(result.discoveredCandidates().isEmpty());
    }

    @Test
    void aFailingPageHealthDoesNotStopLinksFromInspectingAPageThatOpened() {
        ScriptedAudit audit = new ScriptedAudit()
            .health(START, new PageHealthModule.Result(
                PageHealthModule.Outcome.FAILED, "Page title should not be blank"))
            .links(START, "/a", "/b");

        CrawlerPageProcessor processor = processor(audit, true, true);
        List<String> candidates = processor.process(page(START, 0, null));

        // A blank title is a real finding, but it says nothing about whether the links work.
        assertEquals(List.of("open:" + START, "health:" + START, "links:" + START), audit.calls);
        assertEquals(List.of("/a", "/b"), candidates);

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.pageHealth().status());
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.links().status());
        assertTrue(result.opened());
    }

    // --- the handoff to the crawler ------------------------------------------------------------

    @Test
    void theCandidatesReachTheCrawlerInTheExactOrderLinksReportedThem() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/c", "https://example.com/a", "https://example.com/b");

        List<String> candidates = processor(audit, true, true).process(page(START, 0, null));

        assertEquals(List.of(
            "https://example.com/c",
            "https://example.com/a",
            "https://example.com/b"), candidates, "DOM discovery order, not sorted");
    }

    @Test
    void whatLinksAlreadyExcludedNeverReachesTheCrawler() throws IOException {
        // The processor does not re-filter, and must not: the exclusion rules live in the
        // classifier that Links already applied. This proves the seam carries the module's list
        // through untouched rather than the processor inventing a second set of rules.
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));
        assertFalse(source.contains("mailto"), "exclusion rules must not be duplicated here");
        assertFalse(source.contains("javascript:"), source);
        assertFalse(source.contains("NavigationLinkInspector"), source);
        assertTrue(source.contains("linksResult.internalCandidates()"),
            "the module's own list is what travels");

        // And end to end: a Links result that already excluded them yields exactly what it kept.
        ScriptedAudit audit = new ScriptedAudit().links(START, "https://example.com/kept");
        assertEquals(List.of("https://example.com/kept"),
            processor(audit, true, true).process(page(START, 0, null)));
    }

    @Test
    void candidatesAreStillHandedOverWhenAnIndividualLinkFailedItsHttpCheck() {
        ScriptedAudit audit = new ScriptedAudit().linksResult(START, new LinksModule.Result(
            LinksModule.Outcome.LINKS_FAILED,
            "Internal links failed: https://example.com/gone returned HTTP 404",
            List.of("https://example.com/gone", "https://example.com/fine")));

        CrawlerPageProcessor processor = processor(audit, true, true);
        List<String> candidates = processor.process(page(START, 0, null));

        // A 404 is a finding about that destination, not a reason to stop crawling the site.
        assertEquals(List.of("https://example.com/gone", "https://example.com/fine"), candidates);
        assertEquals(CrawlerPageProcessor.Status.FAILED,
            processor.results().get(0).links().status());
    }

    // --- driven by the real crawler ------------------------------------------------------------

    @Test
    void aTenPageTraversalOpensExactlyTenUniqueInternalPagesWithCorrectIdentity() {
        ScriptedAudit audit = new ScriptedAudit();
        // A hub linking to twelve pages, each of which links back to the hub and to a duplicate.
        List<String> hub = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            hub.add("https://example.com/p" + index);
            audit.links("https://example.com/p" + index, START, "https://example.com/p1");
        }
        audit.linksList(START, hub);

        CrawlerPageProcessor processor = processor(audit, true, true);
        Crawler.CrawlResult crawl = new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        assertEquals(10, crawl.attempted().size(), "the default budget, start page included");
        assertEquals(10, processor.results().size());

        List<String> opened = audit.calls.stream().filter(call -> call.startsWith("open:")).toList();
        assertEquals(10, opened.size(), "exactly ten opens");
        assertEquals(10, opened.stream().distinct().count(), "and ten distinct pages");

        // Exact identity, not just counts: page 1 is the start page, then p1..p9 at depth 1.
        List<String> identity = processor.results().stream()
            .map(result -> result.page().pageNumber() + "|" + result.page().displayUrl()
                + "|d" + result.page().depth() + "|from=" + result.page().discoveredFrom())
            .toList();
        assertEquals(List.of(
            "1|https://example.com/|d0|from=null",
            "2|https://example.com/p1|d1|from=https://example.com/",
            "3|https://example.com/p2|d1|from=https://example.com/",
            "4|https://example.com/p3|d1|from=https://example.com/",
            "5|https://example.com/p4|d1|from=https://example.com/",
            "6|https://example.com/p5|d1|from=https://example.com/",
            "7|https://example.com/p6|d1|from=https://example.com/",
            "8|https://example.com/p7|d1|from=https://example.com/",
            "9|https://example.com/p8|d1|from=https://example.com/",
            "10|https://example.com/p9|d1|from=https://example.com/"), identity);
    }

    @Test
    void oneUnopenablePageDoesNotStopThePagesAlreadyQueued() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/a", "https://example.com/b", "https://example.com/c")
            .openFails("https://example.com/b", new IllegalStateException("gateway timeout"));

        CrawlerPageProcessor processor = processor(audit, true, true);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        assertEquals(4, processor.results().size(), "the pages after the failure still ran");
        assertEquals(1, audit.opens("https://example.com/b"), "a failed open is never retried");

        CrawlerPageProcessor.PageAuditResult failed = processor.results().get(2);
        assertEquals("https://example.com/b", failed.page().displayUrl());
        assertFalse(failed.opened());
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, failed.pageHealth().status());
        assertTrue(processor.results().get(3).opened(), "page c still ran");
        assertEquals(3, audit.calls.stream().filter(c -> c.startsWith("health:")).count(),
            "health ran on the three pages that opened, and not on the one that did not");
    }

    // --- nothing sensitive, nothing that expires -----------------------------------------------

    @Test
    void rawSecretsDoNotAppearInAnyPublishedPartOfAPageResult() {
        String secret = "abcdef0123456789abcdef";
        String leaky = "https://example.com/dash?session_token=" + secret;
        ScriptedAudit audit = new ScriptedAudit().linksList(START, List.of(leaky));

        CrawlerPageProcessor processor = processor(audit, true, true);
        List<String> toCrawler = processor.process(page(START, 0, null));

        // Raw to the crawler, because it has to open the page it was given.
        assertEquals(List.of(leaky), toCrawler);

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(1, result.discoveredCandidates().size());
        String published = result.discoveredCandidates().get(0);
        assertFalse(published.contains(secret), "the published candidate must be sanitized: " + published);
        assertTrue(published.contains("session_token=[redacted]"), published);
    }

    @Test
    void aPageOpenFailureMessageIsSanitizedBeforeItIsPublished() {
        String secret = "zyxwvu9876543210zyxwvu";
        ScriptedAudit audit = new ScriptedAudit().openFails(START,
            new IllegalStateException("could not reach the page: password=" + secret));

        CrawlerPageProcessor processor = processor(audit, true, true);
        processor.process(page(START, 0, null));

        String message = processor.results().get(0).pageOpen().message();
        assertFalse(message.contains(secret), "the failure message must not leak it: " + message);
    }

    @Test
    void noPublishedResultTypeCanHoldABrowserHandle() {
        for (Class<?> type : List.of(
            CrawlerPageProcessor.PageAuditResult.class,
            CrawlerPageProcessor.ModuleOutcome.class,
            PageAuditContext.class)) {
            Arrays.stream(type.getRecordComponents()).forEach(component -> {
                String name = component.getType().getName();
                assertFalse(name.startsWith("org.openqa.selenium"),
                    type.getSimpleName() + "." + component.getName() + " is a Selenium type");
                assertFalse(java.util.Map.class.isAssignableFrom(component.getType()),
                    type.getSimpleName() + "." + component.getName() + " is a loose state map");
            });
        }
    }

    // --- the wrappers and the reporting contract are untouched ---------------------------------

    @Test
    void theSinglePageJUnitWrappersAreUnchangedByThisBridge() throws IOException {
        String smoke = Files.readString(
            Path.of("src/test/java/com/qanize/automation/checks/smoke/GenericSmokeTest.java"));
        String links = Files.readString(
            Path.of("src/test/java/com/qanize/automation/checks/links/GenericLinkValidationTest.java"));

        // Still their own entry points, still opening their own page, still unaware of a crawler.
        assertTrue(smoke.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertTrue(smoke.contains("new PageHealthModule(page, execution).inspect("), smoke);
        assertFalse(smoke.contains("Crawler"), "the wrapper must not learn about the engine");
        // Still its own entry point, and still the one that opens its own page -- what changed
        // is that it hands the module the document that came back rather than the request.
        assertTrue(links.contains("new LinksModule(page, config, opened.landed(), execution).run()"),
            links);
        assertTrue(links.contains("assertTrue(result.passed(), result.message())"),
            "the wrapper still reads only the verdict, so a third result field cannot disturb it");
        assertFalse(links.contains("Crawler"), links);
    }

    @Test
    void everyIdentityTheTwoChecksWriteIsQualifiedByPage() throws IOException {
        assertTrue(CrawlerPageProcessor.EVIDENCE_SCOPING.contains("page-scoped"),
            CrawlerPageProcessor.EVIDENCE_SCOPING);

        // The checks keep their own fixed names; what changed is that every one of them now goes
        // through the scope on its way to an id. A new .id( that skipped the scope would show up
        // here as an unqualified literal.
        String health = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/smoke/PageHealthModule.java"));
        String links = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/links/LinksModule.java"));
        for (String source : List.of(health, links)) {
            for (String line : source.split("\n")) {
                if (!line.contains(".id(")) continue;
                assertTrue(line.contains("scope.itemId("),
                    "every tested-item id must be page-qualified: " + line.trim());
            }
        }
        assertTrue(links.contains("scope.itemId(\"links-discovery\")"),
            "including the discovery item, which is built by a shared helper");
    }

    @Test
    void theCrawlerGivesEachPageItsOwnFreshScope() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // Built from the context each time and handed over explicitly: no field, no cache, no
        // ambient current page that a later page could inherit.
        assertTrue(source.contains("EvidenceScope.forPage("), source);
        // A field is what would outlive a page. `private EvidenceScope scopeFor(context)` builds
        // one per call from the context it was handed, which is the rule rather than a breach of it.
        assertFalse(source.matches("(?s).*private\\s+EvidenceScope\\s+\\w+\\s*(=|;).*"),
            "a stored scope field would outlive a page");
        assertFalse(source.contains("ThreadLocal"), source);
        assertTrue(source.contains("new SmokeExecutionEvidence(scope)"), source);
        assertTrue(source.contains("new LinkExecutionEvidence(scope)"), source);
    }


    // --- the three modules wired in this slice -------------------------------------------------

    @Test
    void everySelectedCheckRunsInAnOrderThatReadOnlyWorkComesFirst() {
        ScriptedAudit audit = new ScriptedAudit();

        allChecks(audit).process(page(START, 0, null));

        // Health and Links read the document; Forms reads it too. Navigation drives the browser
        // away and back, and Search leaves it on a results page, so both must come after every
        // check that still needs this document.
        assertEquals(List.of(
            "open:" + START,
            "health:" + START,
            "links:" + START,
            "forms:" + START,
            "navigation:" + START,
            "search:" + START), audit.calls);
    }

    @Test
    void theNavigatingChecksRunAfterTheCandidatesHaveAlreadyBeenCollected() {
        ScriptedAudit audit = new ScriptedAudit().links(START, "https://example.com/a");

        CrawlerPageProcessor processor = allChecks(audit);
        List<String> candidates = processor.process(page(START, 0, null));

        // If Search or Navigation ran first, Links would have read whatever page they landed on.
        assertTrue(audit.calls.indexOf("links:" + START) < audit.calls.indexOf("navigation:" + START));
        assertTrue(audit.calls.indexOf("links:" + START) < audit.calls.indexOf("search:" + START));
        assertEquals(List.of("https://example.com/a"), candidates,
            "the handoff still describes this page, not a results page");
    }

    @Test
    void eachOfTheThreeNewChecksIsSelectedIndependentlyAndSaysSoWhenItIsNot() {
        ScriptedAudit formsOnly = new ScriptedAudit();
        new CrawlerPageProcessor(formsOnly, config(false, false, true, false, false))
            .process(page(START, 0, null));
        assertEquals(List.of("open:" + START, "forms:" + START), formsOnly.calls);

        ScriptedAudit navOnly = new ScriptedAudit();
        new CrawlerPageProcessor(navOnly, config(false, false, false, true, false))
            .process(page(START, 0, null));
        assertEquals(List.of("open:" + START, "navigation:" + START), navOnly.calls);

        ScriptedAudit searchOnly = new ScriptedAudit();
        CrawlerPageProcessor processor =
            new CrawlerPageProcessor(searchOnly, config(false, false, false, false, true));
        processor.process(page(START, 0, null));
        assertEquals(List.of("open:" + START, "search:" + START), searchOnly.calls);

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.search().status());
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, result.forms().status(),
            "an unselected check must say so rather than claim a pass");
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, result.navigation().status());
        assertFalse(result.forms().ran());
    }

    @Test
    void aPageThatWillNotOpenRunsNoBrowserCheckButNavigationStillReportsTheFailedOpen() {
        ScriptedAudit audit = new ScriptedAudit()
            .openFails(START, new IllegalStateException("the page never became ready"));

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));

        // Navigation is consulted, but only to describe the open that just failed -- it inspects
        // no document and opens nothing, which is why it can speak about a page that never loaded.
        // Forms and Search need a document, so they genuinely could not run.
        assertEquals(List.of("open:" + START, "navigation:" + START), audit.calls);
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, result.forms().status());
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.navigation().status(),
            "a page that would not open is a navigation failure, not an absence of navigation");
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, result.search().status());
    }

    @Test
    void nothingEligibleIsReportedAsSuchRatherThanAsAPassOrAFailure() {
        ScriptedAudit audit = new ScriptedAudit()
            .forms(START, new FormsModule.Result(FormsModule.Outcome.SKIPPED, "No forms were discovered."))
            .navigation(START, new NavigationModule.Result(
                NavigationModule.Outcome.NOTHING_ELIGIBLE, "No eligible navigation links."))
            .search(START, new SearchModule.Result(
                SearchModule.Stage.INPUT, false, "The search field was not found."));

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);

        // Nothing to check is neither a pass nor a finding against the site.
        assertEquals(CrawlerPageProcessor.Status.NOTHING_TO_CHECK, result.forms().status());
        assertEquals(CrawlerPageProcessor.Status.NOTHING_TO_CHECK, result.navigation().status());
        assertTrue(result.forms().ran(), "it did run; it simply found nothing of its kind");
        assertTrue(result.forms().message().contains("No forms were discovered."));

        // A search that could not find its field is a real finding, not an absence.
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.search().status());
        assertTrue(result.search().message().contains("The search field was not found."));
    }

    @Test
    void oneFailingCheckDoesNotStopTheChecksThatFollowItOnTheSamePage() {
        ScriptedAudit audit = new ScriptedAudit()
            .forms(START, new FormsModule.Result(FormsModule.Outcome.FAILED, "A control had no label."));

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));

        assertEquals(List.of(
            "open:" + START, "health:" + START, "links:" + START,
            "forms:" + START, "navigation:" + START, "search:" + START), audit.calls,
            "a Forms finding says nothing about navigation or search");
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.forms().status());
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.navigation().status());
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.search().status());
    }

    // --- the point of the slice: isolated scoped evidence per page -----------------------------

    @Test
    void twoCrawledPagesGiveNavigationFormsAndSearchDifferentScopedIdentities() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/about")
            .links("https://example.com/about");

        CrawlerPageProcessor processor = allChecks(audit);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        assertEquals(2, processor.results().size());
        // The scope the Forms seam actually received on each page, captured by the fake.
        assertEquals(List.of("page-001", "page-002"),
            audit.scopesSeen.stream().map(EvidenceScope::pageId).toList());

        // And that scope qualifies every identity the three checks write.
        EvidenceScope first = audit.scopesSeen.get(0);
        EvidenceScope second = audit.scopesSeen.get(1);
        assertEquals("page-001/forms-1", first.itemId("forms-1"));
        assertEquals("page-002/forms-1", second.itemId("forms-1"));
        assertEquals("page-001/nav-1", first.itemId("nav-1"));
        assertEquals("page-002/nav-1", second.itemId("nav-1"));
        assertEquals("page-001/search-input", first.testCaseId("search-input"));
        assertEquals("page-002/search-input", second.testCaseId("search-input"));
        assertFalse(first.equals(second), "two pages, two scopes");
    }

    @Test
    void eachPagesScopeCarriesThatPagesOwnIdentityAndNothingInherited() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/about")
            .links("https://example.com/about");

        new Crawler(CrawlPolicy.defaults()).crawl(START, allChecks(audit));

        EvidenceScope second = audit.scopesSeen.get(1);
        assertEquals(2, second.pageNumber());
        assertEquals("https://example.com/about", second.pageUrl());
        assertEquals(1, second.depth());
        assertEquals(START, second.discoveredFrom(),
            "the parent travels into the evidence, not just into the crawl result");
    }

    @Test
    void everyProductionSeamMethodBuildsItsModuleWithBothTheRecorderAndTheScope()
        throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // One mechanism, five checks: each is handed the same freshly built scope twice — once so
        // the recorder qualifies case ids, once so the module qualifies tested-item ids. A module
        // constructed without it would silently reuse another page's identities.
        //
        // The trailing bracket is not part of the rule and is no longer asserted: page health
        // takes a third collaborator (the status probe below), and pinning the closing bracket
        // would have made "this module takes one more argument" indistinguishable from "this
        // module lost its scope". What is asserted is what the rule always meant — recorder and
        // scope, together, on every one of the five.
        for (String wiring : List.of(
            "new SmokeExecutionEvidence(scope), scope",
            "new LinkExecutionEvidence(scope), scope",
            "new FormExecutionEvidence(scope), scope",
            "new NavigationExecutionEvidence(scope), scope",
            "new SearchExecutionEvidence(scope), scope")) {
            assertTrue(source.contains(wiring), "missing scope wiring: " + wiring);
        }
        // And the collaborator that arrived with it, wired from the session rather than built here.
        assertTrue(source.contains("scope, pageStatusProbe())"),
            "page health is handed the status probe");
        assertTrue(source.contains("session.pageStatusProbe("),
            "the session decides whether a page may be probed at all");
        assertEquals(5, source.split("EvidenceScope scope = scopeFor\\(context\\);", -1).length - 1,
            "each seam method builds its own scope from the context it was given");
        // A FIELD would outlive the page it describes; `private EvidenceScope scopeFor(context)`
        // is the opposite -- it builds one per call from the context it was handed. The rule was
        // always about stored state, so it is stated that way rather than relaxed.
        assertFalse(source.matches("(?s).*private\\s+EvidenceScope\\s+\\w+\\s*(=|;).*"),
            "a stored scope field would outlive the page it describes");
        assertFalse(source.contains("static EvidenceScope scope"),
            "and a static one would outlive the run");

        // Forms is wired to the read-only entry point only.
        assertTrue(source.contains(".inspectDiscoveredForms()"), source);
        assertFalse(source.contains("fillConfiguredField"),
            "filling a configured field is a once-per-run interaction, not a per-page one");
    }

    @Test
    void loginIsNotWiredIntoThePerPageBridgeAtAll() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // Login establishes a session once per run, so it has no place in a per-page audit. That
        // is still true now that a session travels through here: what arrives is the outcome of a
        // sign-in someone else performed, not the sign-in itself.
        assertFalse(source.contains("LoginModule"), source);
        assertFalse(source.contains("LoginExecutionEvidence"), source);
    }

    @Test
    void theRunsSessionReachesTheLinkCheckAndIsOnlyEverAskedThingsByTheOthers() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // Handed to Links, which is the only check that leaves the browser to ask a server
        // something of its own. Forms, Navigation and Search all work through the already-open
        // session, so a session argument on any of them would be noise at best and a second place
        // to get this wrong at worst.
        assertTrue(
            source.contains("landedUrl(context), session, new LinkExecutionEvidence(scope), scope)"),
            source);
        for (String otherModule : List.of(
            "new FormExecutionEvidence(scope), scope)",
            "new NavigationExecutionEvidence(scope), scope)",
            "new SearchExecutionEvidence(scope), scope)")) {
            assertTrue(source.contains(otherModule),
                "the other three are wired exactly as before: " + otherModule);
        }

        // Page health is the one that moved, and it moved without weakening the rule below. It
        // never receives the session; it receives a probe the session produced. Whether an
        // anonymous request may be made at all is decided inside LinkSession, which is where
        // "what may this run present to a server" has always lived.
        assertFalse(source.contains("session, new SmokeExecutionEvidence"),
            "page health is never handed the session itself");
        assertTrue(source.contains("session.pageStatusProbe("),
            "it is handed a probe the session decided on");

        // It passes the session on and does nothing else with it. A bridge that read a cookie out
        // of it, rendered it, or decided anything from it would be exactly the sprawl this design
        // is meant to avoid.
        for (String forbidden : List.of(
            "cookieHeaderFor", "SessionCookie", "cookieCount", "capturedFrom",
            "session.authenticated()", "session.mode()", "Cookie")) {
            assertFalse(source.contains(forbidden),
                "the bridge must not inspect the session: " + forbidden);
        }
    }

    @Test
    void aBridgeBuiltWithoutASessionIsAnonymousRatherThanUndecided() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // Every pre-existing caller keeps working and keeps behaving exactly as it did. The default
        // is written down rather than left to a null check somewhere further in, so there is no
        // state in which a page is audited before the bridge has decided what it may present.
        assertTrue(source.contains("LinkSession.anonymous()"), source);
        assertFalse(source.contains("private LinkSession session;"),
            "the session is final, established once for the run");
    }


    // --- the duplicate-navigation fix ----------------------------------------------------------

    @Test
    void aDestinationDiscoveredOnPageOneIsOpenedExactlyOnceInTheWholeCrawl() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/about", "https://example.com/pricing")
            .links("https://example.com/about")
            .links("https://example.com/pricing");

        CrawlerPageProcessor processor = allChecks(audit);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        // The whole point: Navigation used to open each of these too, so every destination was
        // reached twice -- once inside NavigationModule and again when the queue dequeued it.
        assertEquals(1, audit.opens("https://example.com/about"));
        assertEquals(1, audit.opens("https://example.com/pricing"));
        assertEquals(1, audit.opens(START));
        assertEquals(3, audit.calls.stream().filter(c -> c.startsWith("open:")).count(),
            "three pages, three opens: " + audit.calls);
        assertEquals(3, audit.calls.stream().filter(c -> c.startsWith("open:")).distinct().count());
    }

    @Test
    void navigationOpensNothingInsideTheProcessor() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));

        // followDiscoveredLinks opens every destination and then reopens the baseline. It survives
        // for the standalone check; the crawler must never reach it. The single mention left here
        // is the comment saying so, which is why the assertion is on the call shape.
        assertFalse(source.contains(".followDiscoveredLinks()"),
            "the crawler must not ask Navigation to reopen candidates");
        assertTrue(source.contains(".recordCrawlerOpenedPage("),
            "it reports the open it already performed instead");
        assertEquals(1, source.split("\\.openUrl\\(", -1).length - 1,
            "still exactly one place opens a page");
    }

    @Test
    void navigationReportsTheOpenRatherThanPerformingASecondOne() {
        ScriptedAudit audit = new ScriptedAudit().links(START, "https://example.com/about")
            .links("https://example.com/about");

        CrawlerPageProcessor processor = allChecks(audit);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        // Navigation is consulted once per page, and only ever after that page's single open.
        for (String url : List.of(START, "https://example.com/about")) {
            assertEquals(1, audit.calls.stream().filter(c -> c.equals("navigation:" + url)).count());
            assertTrue(audit.calls.indexOf("open:" + url) < audit.calls.indexOf("navigation:" + url),
                "the open comes first, and Navigation merely reports it: " + audit.calls);
        }
    }

    @Test
    void aSuccessfulOpenGivesNavigationATruthfulPassedResult() {
        ScriptedAudit audit = new ScriptedAudit();

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.navigation().status());
        assertTrue(result.navigation().ran());
    }

    @Test
    void aFailedOpenMakesNavigationFailedRatherThanPassedOrAbsent() {
        ScriptedAudit audit = new ScriptedAudit()
            .openFails("https://example.com/about", new IllegalStateException("gateway timeout"))
            .links(START, "https://example.com/about");

        CrawlerPageProcessor processor = allChecks(audit);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        CrawlerPageProcessor.PageAuditResult failed = processor.results().get(1);
        assertFalse(failed.opened());
        assertEquals(CrawlerPageProcessor.Status.FAILED, failed.navigation().status(),
            "a page that would not open is a navigation failure, never a pass");
        // The other checks still could not run; only Navigation has something truthful to say.
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, failed.pageHealth().status());
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN, failed.links().status());
    }

    @Test
    void anUnselectedNavigationIsNotSelectedEvenWhenThePageFailsToOpen() {
        ScriptedAudit audit = new ScriptedAudit()
            .openFails(START, new IllegalStateException("gateway timeout"));

        CrawlerPageProcessor processor =
            new CrawlerPageProcessor(audit, config(true, true, false, false, false));
        processor.process(page(START, 0, null));

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.NOT_SELECTED, result.navigation().status());
        assertEquals(List.of("open:" + START), audit.calls,
            "an unselected check is never consulted, not even to report a failure");
    }

    @Test
    void navigationIsHandedTheSanitizedSourceAndDestinationForADiscoveredPage() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/about")
            .links("https://example.com/about");

        CrawlerPageProcessor processor = allChecks(audit);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        // The start page was reached from nowhere; the discovered page names its parent.
        assertNull(audit.reachedFrom.get(START));
        assertEquals(START, audit.reachedFrom.get("https://example.com/about"));

        CrawlerPageProcessor.PageAuditResult discovered = processor.results().get(1);
        assertEquals("https://example.com/about", discovered.page().displayUrl());
        assertEquals(START, discovered.page().discoveredFrom());
    }

    @Test
    void nothingInTheCrawlerNavigationPathClaimsALinkWasClicked() throws IOException {
        String module = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationModule.java"));
        String evidence = Files.readString(Path.of(
            "src/main/java/com/qanize/automation/checks/navigation/NavigationExecutionEvidence.java"));

        // The crawler-mode method performs no browser action, so no wording of its may imply one.
        String crawlerMethod = module.substring(
            module.indexOf("public Result recordCrawlerOpenedPage("),
            module.indexOf("// --- the discovered-destination check"));
        assertFalse(crawlerMethod.contains("openUrl"), "it must open nothing: " + crawlerMethod);
        assertFalse(crawlerMethod.toLowerCase().contains("click"), crawlerMethod);
        assertTrue(crawlerMethod.contains("\"linkFollowed\", \"false\""),
            "the item says plainly that no link was followed");

        int crawlerStep = evidence.indexOf("public ExecutionStep crawlerOpenedPage(");
        String stepWording = evidence.substring(crawlerStep, evidence.indexOf("}", crawlerStep));
        assertTrue(stepWording.contains("Navigation followed no link to reach it."), stepWording);
        assertFalse(stepWording.toLowerCase().contains("clicked"), stepWording);
    }

    @Test
    void theStandaloneNavigationCheckKeepsItsOwnLinkFollowingBehaviour() throws IOException {
        String wrapper = Files.readString(Path.of(
            "src/test/java/com/qanize/automation/checks/navigation/GenericNavigationTest.java"));
        String module = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationModule.java"));

        // The single-page check keeps its own entry points. Its base page is now the document the
        // browser landed on -- the fix this slice is for -- and nothing else about it moved.
        assertTrue(wrapper.contains("followDiscoveredLinks()"), wrapper);
        assertTrue(wrapper.contains("openConfiguredMenu(menu)"), wrapper);
        assertTrue(wrapper.contains("OpenedPage.open(page, basePage)"), wrapper);
        assertFalse(wrapper.contains("config, basePage, execution)"),
            "the requested address must never be handed to the module as its source page");
        assertFalse(wrapper.contains("recordCrawlerOpenedPage"),
            "the standalone check has no use for crawler mode");

        // And the flow it depends on still opens destinations and restores the baseline.
        assertTrue(module.contains("public Result followDiscoveredLinks()"), module);
        assertTrue(module.contains("navigation.openUrl(destination.toString())"),
            "the standalone flow still follows its destinations");
        assertTrue(module.contains("navigation.openUrl(basePage)"),
            "and still returns to the baseline afterwards");
    }


    // --- per-module failure isolation ----------------------------------------------------------

    /** Every module status on one page, in run order, as a readable line. */
    private static String statuses(CrawlerPageProcessor.PageAuditResult r) {
        return "health=" + r.pageHealth().status() + " links=" + r.links().status()
            + " forms=" + r.forms().status() + " navigation=" + r.navigation().status()
            + " search=" + r.search().status();
    }

    private static final List<String> ALL_MODULES = List.of(
        "open:" + START, "health:" + START, "links:" + START,
        "forms:" + START, "navigation:" + START, "search:" + START);

    @Test
    void aPageHealthExceptionFailsOnlyPageHealthAndLinksStillRuns() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("health", START, new IllegalStateException("stale element reference"))
            .links(START, "https://example.com/about");

        CrawlerPageProcessor processor = allChecks(audit);
        List<String> candidates = processor.process(page(START, 0, null));

        assertEquals(ALL_MODULES, audit.calls, "every later module still ran");
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals("health=FAILED links=PASSED forms=PASSED navigation=PASSED search=PASSED",
            statuses(result));
        assertTrue(result.pageHealth().message().contains("stale element reference"),
            result.pageHealth().message());
        assertEquals(List.of("https://example.com/about"), candidates,
            "Links still owned and produced the handoff");
    }

    @Test
    void aLinksExceptionFailsOnlyLinksProducesNoCandidatesAndLaterModulesStillRun() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("links", START, new IllegalStateException("the DOM went away"));

        CrawlerPageProcessor processor = allChecks(audit);
        List<String> candidates = processor.process(page(START, 0, null));

        assertEquals(ALL_MODULES, audit.calls);
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals("health=PASSED links=FAILED forms=PASSED navigation=PASSED search=PASSED",
            statuses(result));
        // Links threw before returning, so there is no trustworthy handoff at all.
        assertTrue(candidates.isEmpty(), "no candidates may be invented: " + candidates);
        assertTrue(result.discoveredCandidates().isEmpty());
    }

    @Test
    void aFormsExceptionPreservesTheEarlierResultsAndTheCandidates() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("forms", START, new IllegalStateException("a control detached"))
            .links(START, "https://example.com/a", "https://example.com/b");

        CrawlerPageProcessor processor = allChecks(audit);
        List<String> candidates = processor.process(page(START, 0, null));

        assertEquals(ALL_MODULES, audit.calls);
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals("health=PASSED links=PASSED forms=FAILED navigation=PASSED search=PASSED",
            statuses(result));
        assertEquals(List.of("https://example.com/a", "https://example.com/b"), candidates,
            "a Forms crash must not cost the crawler its handoff");
        assertTrue(result.opened(), "the page still processed rather than being discarded");
    }

    @Test
    void aNavigationReportingExceptionPreservesEarlierResultsAndSearchStillRuns() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("navigation", START, new IllegalStateException("evidence write failed"))
            .links(START, "https://example.com/a");

        CrawlerPageProcessor processor = allChecks(audit);
        List<String> candidates = processor.process(page(START, 0, null));

        assertEquals(ALL_MODULES, audit.calls, "Search is after Navigation and still ran");
        assertEquals("health=PASSED links=PASSED forms=PASSED navigation=FAILED search=PASSED",
            statuses(processor.results().get(0)));
        assertEquals(List.of("https://example.com/a"), candidates);
    }

    @Test
    void aSearchExceptionPreservesEveryEarlierResultAndAllCandidates() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("search", START, new IllegalStateException("the submit control vanished"))
            .links(START, "https://example.com/a", "https://example.com/b");

        CrawlerPageProcessor processor = allChecks(audit);
        List<String> candidates = processor.process(page(START, 0, null));

        assertEquals(ALL_MODULES, audit.calls);
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals("health=PASSED links=PASSED forms=PASSED navigation=PASSED search=FAILED",
            statuses(result));
        assertEquals(List.of("https://example.com/a", "https://example.com/b"), candidates,
            "the last module failing is exactly when everything before it must survive");
        assertEquals(2, result.discoveredCandidates().size());
    }

    @Test
    void unselectedModulesStayNotSelectedWhenAnotherModuleThrows() {
        // Only PageHealth and Links are selected, and PageHealth throws.
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("health", START, new IllegalStateException("boom"));

        CrawlerPageProcessor processor = processor(audit, true, true);
        processor.process(page(START, 0, null));

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals("health=FAILED links=PASSED forms=NOT_SELECTED "
            + "navigation=NOT_SELECTED search=NOT_SELECTED", statuses(result),
            "a boundary cannot turn \"nobody asked for this\" into a failure");
        assertEquals(List.of("open:" + START, "health:" + START, "links:" + START), audit.calls,
            "an unselected module is never invoked, failure or not");
    }

    @Test
    void everySelectedModuleRunsAtMostOnceEvenWhenOneThrows() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("forms", START, new IllegalStateException("a control detached"));

        allChecks(audit).process(page(START, 0, null));

        for (String module : List.of("open", "health", "links", "forms", "navigation", "search")) {
            assertEquals(1, audit.calls.stream().filter(c -> c.equals(module + ":" + START)).count(),
                module + " must run exactly once -- no retry, no reopen: " + audit.calls);
        }
    }

    @Test
    void searchStaysLastEvenWhenEveryModuleBeforeItThrows() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("health", START, new IllegalStateException("one"))
            .throwsIn("links", START, new IllegalStateException("two"))
            .throwsIn("forms", START, new IllegalStateException("three"))
            .throwsIn("navigation", START, new IllegalStateException("four"));

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));

        assertEquals(ALL_MODULES, audit.calls, "the order is fixed regardless of failures");
        assertEquals("health=FAILED links=FAILED forms=FAILED navigation=FAILED search=PASSED",
            statuses(processor.results().get(0)),
            "four independent boundaries, and the one healthy module still passes");
    }

    @Test
    void aModuleExceptionMessageIsSanitizedBeforeItIsPublished() {
        String secret = "abcdef0123456789abcdef";
        ScriptedAudit audit = new ScriptedAudit().throwsIn("forms", START,
            new IllegalStateException("could not read the field: password=" + secret));

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));

        String message = processor.results().get(0).forms().message();
        assertFalse(message.contains(secret), "the crash message must not leak it: " + message);
        assertTrue(message.contains("password=[redacted]"), message);
    }

    @Test
    void anErrorIsNotSwallowedByAModuleBoundary() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("forms", START, new StackOverflowError("unrecoverable"));

        CrawlerPageProcessor processor = allChecks(audit);

        // Only RuntimeException is contained. Carrying on after an Error would produce evidence
        // nobody should trust, so it propagates untouched.
        StackOverflowError escaped = assertThrows(StackOverflowError.class,
            () -> processor.process(page(START, 0, null)));
        assertEquals("unrecoverable", escaped.getMessage());
        assertEquals(List.of("open:" + START, "health:" + START, "links:" + START,
            "forms:" + START), audit.calls, "and nothing after it ran");
    }

    @Test
    void aLaterFailureNeverRewritesAnAlreadyRecordedModuleResult() {
        ScriptedAudit audit = new ScriptedAudit()
            .health(START, new PageHealthModule.Result(
                PageHealthModule.Outcome.FAILED, "Page title should not be blank"))
            .links(START, "https://example.com/a")
            .throwsIn("search", START, new IllegalStateException("late crash"));

        CrawlerPageProcessor processor = allChecks(audit);
        processor.process(page(START, 0, null));

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        // PageHealth's own FAILED verdict survives with its own message, not Search's.
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.pageHealth().status());
        assertTrue(result.pageHealth().message().contains("Page title should not be blank"),
            result.pageHealth().message());
        assertFalse(result.pageHealth().message().contains("late crash"),
            "one module's crash may not overwrite another's message");
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.links().status());
    }

    @Test
    void oneModuleFailureOnPageOneDoesNotStopTheCrawlerReachingPageTwo() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/about")
            .links("https://example.com/about")
            .throwsIn("forms", START, new IllegalStateException("a control detached"));

        CrawlerPageProcessor processor = allChecks(audit);
        Crawler.CrawlResult crawl = new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        // Before this slice the exception escaped, the Crawler failed the whole page, the
        // candidate was lost with it, and /about was never queued at all.
        assertEquals(List.of(START, "https://example.com/about"), crawl.attemptedUrls());
        assertEquals(2, processor.results().size(), "both pages produced a result");
        assertEquals(CrawlerPageProcessor.Status.FAILED,
            processor.results().get(0).forms().status());
        assertEquals(CrawlerPageProcessor.Status.PASSED,
            processor.results().get(1).forms().status(), "page 2 is unaffected by page 1");
        assertTrue(crawl.failed().isEmpty(),
            "a contained module failure is not a failed page: " + crawl.failed());
    }

    @Test
    void containingAModuleFailureDidNotReintroduceDuplicateNavigationTraversal() throws IOException {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, "https://example.com/about")
            .links("https://example.com/about")
            .throwsIn("health", START, new IllegalStateException("boom"));

        CrawlerPageProcessor processor = allChecks(audit);
        new Crawler(CrawlPolicy.defaults()).crawl(START, processor);

        assertEquals(1, audit.opens("https://example.com/about"),
            "still exactly one open per page: " + audit.calls);
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java"));
        assertFalse(source.contains(".followDiscoveredLinks()"),
            "the crawler must still never ask Navigation to reopen candidates");
        assertEquals(1, source.split("\\.openUrl\\(", -1).length - 1);
    }

    @Test
    void everyContainedBoundaryIsNamedAndNothingElseIsSwallowed() throws IOException {
        String code = processorCode();
        String flat = flat(code);

        assertFalse(code.contains("catch (Throwable"), "Error must never be caught");
        assertFalse(code.contains("catch (Error"), code);
        assertFalse(code.contains("catch (Exception "), "only RuntimeException is contained");

        // Four boundaries, each named and each in one known method. Naming them is the point:
        // a count alone would let a fifth appear anywhere and still pass by adjusting a number.
        assertEquals(1, count(methodBody(code, PROCESS), "catch (RuntimeException openFailure)"),
            "the page open boundary");
        assertEquals(1, count(methodBody(code, PROCESS), "catch (RuntimeException linksFailure)"),
            "the Links candidate-owning boundary");
        assertEquals(1, count(methodBody(code, GUARD), "catch (RuntimeException moduleFailure)"),
            "the shared module boundary");
        assertEquals(1, count(methodBody(flat, CAPTURE), "catch (RuntimeException ignored)"),
            "the screenshot boundary");
        // The landed-address read has its own boundary, and it is deliberately NOT here: it lives
        // in OpenedPage, which both the crawler and the four single-page wrappers go through, and
        // OpenedPageTest owns that rule. One definition of "where did we land", one boundary.
        assertEquals(0, count(code, "catch (RuntimeException unreadable)"),
            "the landed-address boundary belongs to OpenedPage, not to this file");
        assertTrue(code.contains("OpenedPage.where(page, context.url()).landed()"),
            "and this file delegates to it rather than reading the driver itself");

        assertEquals(4, count(code, "catch ("),
            "every boundary in this file must be one of the four named above");
        assertTrue(code.contains("private ModuleOutcome guarded("), code);
        assertTrue(code.contains("private static ModuleOutcome guardedWithoutCapture("), code);

        // The screenshot boundary is one method, declared once, with a typed second parameter,
        // and it is the only thing in this file that speaks to the recorder at all. Slice 7 made
        // that parameter a CheckTarget: a String there is a caller-supplied label, which is how a
        // URL, a selector or a credential reaches a file name that then gets printed everywhere.
        assertEquals(1, count(flat, CAPTURE + " {"),
            "exactly one screenshot boundary, typed with CheckTarget: " + flat);
        assertEquals(1, count(flat, "capture(PageAuditContext"),
            "no second capture overload may exist beside the typed one");
        assertEquals(0, count(flat, "String checkKey"),
            "the free-form String label Slice 7 removed must not come back");
        assertEquals(1, count(flat, CAPTURE_IF_FAILED + " {"),
            "its only caller is typed the same way: " + flat);
        assertEquals(1, count(code, "screenshots."),
            "only the screenshot boundary talks to the recorder: a second reference would be a "
                + "second way to take a picture, outside every guarantee that method makes");
    }

    @Test
    void theModuleBoundaryCatchesOnlyRuntimeExceptionAndCallsTheModuleOnce() throws IOException {
        String body = methodBody(processorCode(), GUARD);

        assertEquals(1, count(body, "catch (RuntimeException moduleFailure)"), body);
        assertEquals(0, count(body, "catch (Exception"), body);
        assertEquals(0, count(body, "catch (Throwable"), body);
        assertEquals(0, count(body, "catch (Error"), body);
        assertEquals(1, count(body, "module.get()"),
            "the module is invoked exactly once: no retry and no second verdict");
        assertTrue(body.contains("ModuleOutcome.crashed(moduleFailure)"),
            "a module that threw is reported as an analyzer crash, not a website finding");
    }

    @Test
    void theLinksBoundaryOwnsItsCandidatesAndInspectsExactlyOnce() throws IOException {
        String body = methodBody(processorCode(), PROCESS);

        assertEquals(1, count(body, "catch (RuntimeException linksFailure)"), body);
        assertEquals(1, count(body, "audit.inspectLinks("),
            "Links is asked once: a second call would be a retry with a different page state");
        assertTrue(body.contains("links = ModuleOutcome.crashed(linksFailure)"),
            "a Links that threw has no trustworthy handoff, so the candidates stay empty");
    }

    @Test
    void theOpenBoundaryNeverReopensThePageAndYieldsNoCandidates() throws IOException {
        String body = methodBody(processorCode(), PROCESS);

        assertEquals(1, count(body, "catch (RuntimeException openFailure)"), body);
        assertEquals(1, count(body, "audit.open("),
            "a page that did not load is a result about the website, not something to ask again");
        assertTrue(body.contains("guardedWithoutCapture(navigationSelected"),
            "the open failure is photographed once, so its Navigation report must not photograph "
                + "the same event a second time");
    }

    @Test
    void theScreenshotBoundaryCannotChangeAnOutcomeOrDiscardCandidates() throws IOException {
        String code = processorCode();
        String flat = flat(code);
        String body = methodBody(flat, CAPTURE);

        // Typed, and the typing is the security property: the only thing a caller can name is a
        // value of a fixed enum, so no URL, selector, message or credential can ride in on it.
        assertEquals(1, count(flat, CAPTURE + " {"),
            "the boundary must be declared with CheckTarget, not a free-form String: " + flat);
        assertEquals(0, count(flat, "String checkKey"),
            "the free-form label Slice 7 removed must not come back");
        assertEquals(1, count(flat, "capture(PageAuditContext"),
            "one screenshot boundary, not an overload pair: " + flat);
        assertEquals(1, count(flat, "private void capture(PageAuditContext"),
            "and it returns void, so a broken camera has nothing at all to hand back");
        assertEquals(1, count(flat, SCREENSHOT_SEAM),
            "the seam it calls through is typed the same way: " + flat);

        assertEquals(1, count(body, "catch (RuntimeException ignored)"), body);
        assertEquals(1, count(body, "catch ("), "exactly one catch in the boundary: " + body);
        assertEquals(0, count(body, "catch (Exception"), body);
        assertEquals(0, count(body, "catch (Throwable"), body);
        assertEquals(0, count(body, "catch (Error"), body);
        assertEquals(1, count(body, "screenshots.capture("),
            "one attempt per failure, never retried");
        assertEquals(1, count(code, "screenshots.capture("),
            "and it is the only call to the recorder anywhere in this file");
        assertTrue(body.contains("screenshots.capture(context.pageNumber(), target)"),
            "the page number and the typed target are handed over unchanged, never composed into "
                + "a label here: " + body);

        // It returns nothing and touches nothing that carries a verdict or a queue, so a broken
        // camera has no route to a module status, a page result or the crawler's candidates.
        assertEquals(0, count(body, "return"), "a void boundary cannot rewrite an answer");
        assertEquals(0, count(body, "throw"), "a capture problem may never escape this method");
        assertEquals(0, count(body, "ModuleOutcome"), body);
        assertEquals(0, count(body, "results"), body);
        assertEquals(0, count(body, "candidates"), body);

        // And it is only ever reached for a failure, through one caller typed the same way.
        String caller = methodBody(flat, CAPTURE_IF_FAILED);
        assertTrue(caller.contains("outcome.status() == Status.FAILED"),
            "a picture is taken for a failure and for nothing else");
        assertEquals(1, count(caller, "capture(context, target)"),
            "the caller forwards the target it was given rather than choosing one: " + caller);
    }

    private static final String PROCESS = "public List<String> process(Crawler.Page page)";
    private static final String GUARD = "private static ModuleOutcome guardedWithoutCapture(";

    /**
     * The exact current declaration of the screenshot boundary, typed and written out in full.
     *
     * <p>Written out rather than shortened to a prefix on purpose. {@link #methodBody} asserts
     * that the declaration it is handed exists and is unique, so this constant is itself the
     * assertion that the boundary still takes a {@code CrawlScreenshotRecorder.CheckTarget}: put
     * a free-form {@code String} back in that position and every test that reads this method's
     * body fails to find it, rather than quietly passing against some other method.
     *
     * <p>It is looked up in {@link #flat} source, so the same exact signature matches whether
     * production writes it on one line or wraps it across several. That is not a loosening: the
     * full typed parameter list still has to be there, character for character.
     */
    private static final String CAPTURE =
        "private void capture(PageAuditContext context, CrawlScreenshotRecorder.CheckTarget target)";

    /** Its only caller, typed the same way and looked up the same way. */
    private static final String CAPTURE_IF_FAILED =
        "private void captureIfFailed(PageAuditContext context, "
            + "CrawlScreenshotRecorder.CheckTarget target, ModuleOutcome outcome)";

    /** The one seam a picture is ever requested through, typed the same way again. */
    private static final String SCREENSHOT_SEAM =
        "void capture(int pageNumber, CrawlScreenshotRecorder.CheckTarget target);";

    private static String processorCode() throws IOException {
        return code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlerPageProcessor.java")));
    }

    /**
     * Source reduced to a single line, with the padding a wrapped parameter list leaves behind.
     *
     * <p>A declaration written on one line and the same declaration broken across several come
     * out identical, so a signature assertion can be exact about types without also being an
     * assertion about where the formatter chose to break the line.
     */
    private static String flat(String code) {
        return code.replaceAll("\\s+", " ").replaceAll("\\(\\s+", "(").replaceAll("\\s+\\)", ")");
    }

    /** Source with comments removed, so a guard can never match documentation. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** The body of one uniquely-declared method, matched brace to brace. */
    private static String methodBody(String code, String declaration) {
        int start = code.indexOf(declaration);
        assertTrue(start >= 0, "declaration not found: " + declaration);
        assertEquals(-1, code.indexOf(declaration, start + 1),
            "declaration is not unique: " + declaration);
        int open = code.indexOf('{', start);
        assertTrue(open > start, declaration);
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char character = code.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return code.substring(open + 1, index);
            }
        }
        throw new AssertionError("unbalanced braces after " + declaration);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
    }

    // --- the scripted page work ----------------------------------------------------------------

    /**
     * The page work, scripted: opening a page and the two checks, without a browser.
     *
     * <p>It records every call in order, so a test can prove not only what the results say but
     * exactly what the processor asked for and in which sequence.
     */
    private static final class ScriptedAudit implements CrawlerPageProcessor.PageAudit {

        private final List<String> calls = new ArrayList<>();
        private final Map<String, RuntimeException> openFailures = new LinkedHashMap<>();
        private final Map<String, PageHealthModule.Result> healths = new LinkedHashMap<>();
        private final Map<String, LinksModule.Result> links = new LinkedHashMap<>();
        private final Map<String, FormsModule.Result> formsResults = new LinkedHashMap<>();
        private final Map<String, NavigationModule.Result> navigationResults = new LinkedHashMap<>();
        private final Map<String, SearchModule.Result> searchResults = new LinkedHashMap<>();
        private final List<EvidenceScope> scopesSeen = new ArrayList<>();
        private final Map<String, String> reachedFrom = new LinkedHashMap<>();
        /** Keyed "<module>:<url>", so one page can be made to throw from exactly one module. */
        private final Map<String, Throwable> throwsFrom = new LinkedHashMap<>();

        ScriptedAudit throwsIn(String module, String url, Throwable failure) {
            throwsFrom.put(module + ":" + url, failure);
            return this;
        }

        /** Rethrows whatever was scripted, without wrapping: an Error must stay an Error. */
        private void failIfScripted(String module, String url) {
            Throwable failure = throwsFrom.get(module + ":" + url);
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        ScriptedAudit forms(String url, FormsModule.Result result) {
            formsResults.put(url, result);
            return this;
        }

        ScriptedAudit navigation(String url, NavigationModule.Result result) {
            navigationResults.put(url, result);
            return this;
        }

        ScriptedAudit search(String url, SearchModule.Result result) {
            searchResults.put(url, result);
            return this;
        }

        ScriptedAudit openFails(String url, RuntimeException failure) {
            openFailures.put(url, failure);
            return this;
        }

        ScriptedAudit health(String url, PageHealthModule.Result result) {
            healths.put(url, result);
            return this;
        }

        ScriptedAudit links(String url, String... candidates) {
            return linksList(url, Arrays.asList(candidates));
        }

        ScriptedAudit linksList(String url, List<String> candidates) {
            return linksResult(url,
                new LinksModule.Result(LinksModule.Outcome.PASSED, null, candidates));
        }

        ScriptedAudit linksResult(String url, LinksModule.Result result) {
            links.put(url, result);
            return this;
        }

        int opens(String url) {
            return (int) calls.stream().filter(call -> call.equals("open:" + url)).count();
        }

        @Override
        public void open(PageAuditContext page) {
            calls.add("open:" + page.url());
            RuntimeException failure = openFailures.get(page.url());
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public PageHealthModule.Result inspectPageHealth(PageAuditContext page) {
            calls.add("health:" + page.url());
            failIfScripted("health", page.url());
            return healths.getOrDefault(page.url(),
                new PageHealthModule.Result(PageHealthModule.Outcome.PASSED, null));
        }

        @Override
        public LinksModule.Result inspectLinks(PageAuditContext page) {
            calls.add("links:" + page.url());
            failIfScripted("links", page.url());
            return links.getOrDefault(page.url(),
                new LinksModule.Result(LinksModule.Outcome.PASSED, null, List.of()));
        }

        @Override
        public FormsModule.Result inspectForms(PageAuditContext page) {
            calls.add("forms:" + page.url());
            failIfScripted("forms", page.url());
            scopesSeen.add(EvidenceScope.forPage(
                page.pageNumber(), page.url(), page.depth(), page.discoveredFrom()));
            return formsResults.getOrDefault(page.url(),
                new FormsModule.Result(FormsModule.Outcome.PASSED, null));
        }

        @Override
        public NavigationModule.Result recordPageReached(
            PageAuditContext page, long openMillis, RuntimeException openFailure
        ) {
            calls.add("navigation:" + page.url());
            failIfScripted("navigation", page.url());
            reachedFrom.put(page.url(), page.discoveredFrom());
            if (openFailure != null) {
                return new NavigationModule.Result(NavigationModule.Outcome.FAILED,
                    "The page discovered on " + page.discoveredFrom() + " could not be opened.");
            }
            return navigationResults.getOrDefault(page.url(),
                new NavigationModule.Result(NavigationModule.Outcome.PASSED, null));
        }

        @Override
        public SearchModule.Result inspectSearch(PageAuditContext page) {
            calls.add("search:" + page.url());
            failIfScripted("search", page.url());
            return searchResults.getOrDefault(page.url(),
                new SearchModule.Result(SearchModule.Stage.SUBMIT, true, null));
        }
    }

    // --- Slice 6: one picture per crawler failure, and none otherwise --------------------------

    private static CrawlerPageProcessor withScreenshots(
        ScriptedAudit audit, RecordingScreenshots screenshots
    ) {
        return new CrawlerPageProcessor(audit, config(true, true, true, true, true), screenshots);
    }

    @Test
    void aCleanPageAsksForNoScreenshot() {
        RecordingScreenshots screenshots = new RecordingScreenshots();

        withScreenshots(new ScriptedAudit(), screenshots).process(page(START, 0, null));

        assertEquals(List.of(), screenshots.requests, "nothing failed, so nothing is photographed");
    }

    @Test
    void aPageThatWillNotOpenIsPhotographedExactlyOnce() {
        ScriptedAudit audit = new ScriptedAudit()
            .openFails(START, new RuntimeException("net::ERR_CONNECTION_REFUSED"));
        RecordingScreenshots screenshots = new RecordingScreenshots();

        withScreenshots(audit, screenshots).process(page(START, 0, null));

        // Once, for the open. The Navigation outcome built afterwards reports this same failed
        // open, so photographing it again would be one event pictured twice.
        assertEquals(List.of("1:open"), screenshots.requests);
    }

    @Test
    void everyFailedModuleIsPhotographedExactlyOnce() {
        ScriptedAudit audit = new ScriptedAudit()
            .health(START, new PageHealthModule.Result(PageHealthModule.Outcome.FAILED, "blank"))
            .linksResult(START, new LinksModule.Result(
                LinksModule.Outcome.LINKS_FAILED, "a link was broken", List.of()))
            .forms(START, new FormsModule.Result(FormsModule.Outcome.FAILED, "no label"))
            .navigation(START, new NavigationModule.Result(
                NavigationModule.Outcome.FAILED, "the menu did not open"))
            .search(START, new SearchModule.Result(SearchModule.Stage.SUBMIT, false, "no results"));
        RecordingScreenshots screenshots = new RecordingScreenshots();

        withScreenshots(audit, screenshots).process(page(START, 0, null));

        assertEquals(
            List.of("1:page-health", "1:links", "1:forms", "1:navigation", "1:search"),
            screenshots.requests,
            "one picture per failing check, in the order the checks ran");
    }

    @Test
    void aCrashedModuleIsPhotographedOnceAndStaysAnAnalyzerCrash() {
        ScriptedAudit audit = new ScriptedAudit()
            .throwsIn("forms", START, new IllegalStateException("the analyzer broke"));
        RecordingScreenshots screenshots = new RecordingScreenshots();

        CrawlerPageProcessor processor = withScreenshots(audit, screenshots);
        processor.process(page(START, 0, null));

        assertEquals(List.of("1:forms"), screenshots.requests);
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.forms().status());
        assertEquals(CrawlerPageProcessor.FailureOrigin.ANALYZER_CRASH,
            result.forms().failureOrigin(), "a picture must not reclassify whose fault it was");
        assertTrue(result.forms().crashed());
    }

    @Test
    void aScreenshotFailureLeavesTheModuleOutcomeUnchanged() {
        ScriptedAudit audit = new ScriptedAudit()
            .forms(START, new FormsModule.Result(FormsModule.Outcome.FAILED, "no label"));
        RecordingScreenshots screenshots =
            new RecordingScreenshots().failsWith(new IllegalStateException("the camera broke"));

        CrawlerPageProcessor processor = withScreenshots(audit, screenshots);
        processor.process(page(START, 0, null));

        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.FAILED, result.forms().status());
        assertEquals(CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT, result.forms().failureOrigin(),
            "a broken camera must not turn a website finding into an analyzer crash");
        assertTrue(result.forms().message().contains("no label"));
    }

    @Test
    void aScreenshotFailureDoesNotStopTheModulesThatFollow() {
        ScriptedAudit audit = new ScriptedAudit()
            .health(START, new PageHealthModule.Result(PageHealthModule.Outcome.FAILED, "blank"));
        RecordingScreenshots screenshots =
            new RecordingScreenshots().failsWith(new IllegalStateException("the camera broke"));

        CrawlerPageProcessor processor = withScreenshots(audit, screenshots);
        processor.process(page(START, 0, null));

        assertEquals(List.of(
            "open:" + START, "health:" + START, "links:" + START,
            "forms:" + START, "navigation:" + START, "search:" + START), audit.calls,
            "every later check still ran");
        CrawlerPageProcessor.PageAuditResult result = processor.results().get(0);
        assertEquals(CrawlerPageProcessor.Status.PASSED, result.search().status());
    }

    @Test
    void linksCandidatesSurviveAScreenshotFailureOnALaterModule() {
        ScriptedAudit audit = new ScriptedAudit()
            .links(START, START + "a", START + "b")
            .forms(START, new FormsModule.Result(FormsModule.Outcome.FAILED, "no label"));
        RecordingScreenshots screenshots =
            new RecordingScreenshots().failsWith(new IllegalStateException("the camera broke"));

        List<String> candidates =
            withScreenshots(audit, screenshots).process(page(START, 0, null));

        assertEquals(List.of(START + "a", START + "b"), candidates,
            "a later camera failure cannot cost the crawler its queue");
    }

    @Test
    void theScreenshotIsRequestedBeforeTheNextModuleRuns() {
        ScriptedAudit audit = new ScriptedAudit()
            .linksResult(START, new LinksModule.Result(
                LinksModule.Outcome.LINKS_FAILED, "a link was broken", List.of()));
        // One shared timeline, so the interleaving is observed rather than inferred.
        RecordingScreenshots screenshots = new RecordingScreenshots(audit.calls);

        withScreenshots(audit, screenshots).process(page(START, 0, null));

        assertEquals(List.of(
            "open:" + START,
            "health:" + START,
            "links:" + START,
            "screenshot:links",
            "forms:" + START,
            "navigation:" + START,
            "search:" + START), audit.calls,
            "the picture is taken while this page is still the page on screen");
    }

    @Test
    void searchStillRunsLastSoNothingIsPhotographedAfterItMovesTheBrowser() {
        ScriptedAudit audit = new ScriptedAudit()
            .search(START, new SearchModule.Result(SearchModule.Stage.SUBMIT, false, "no results"));
        RecordingScreenshots screenshots = new RecordingScreenshots(audit.calls);

        withScreenshots(audit, screenshots).process(page(START, 0, null));

        assertEquals("search:" + START, audit.calls.get(audit.calls.size() - 2));
        assertEquals("screenshot:search", audit.calls.get(audit.calls.size() - 1),
            "Search is last, and its own picture is the last thing that happens on this page");
    }

    @Test
    void notSelectedNotRunAndNothingToCheckAreNeverPhotographed() {
        // Nothing is selected, so every check is NOT_SELECTED.
        RecordingScreenshots unselected = new RecordingScreenshots();
        new CrawlerPageProcessor(new ScriptedAudit(), config(false, false, false, false, false),
            unselected).process(page(START, 0, null));
        assertEquals(List.of(), unselected.requests, "NOT_SELECTED is not a failure");

        // NOTHING_TO_CHECK: the check ran and found nothing of its kind.
        ScriptedAudit nothing = new ScriptedAudit()
            .forms(START, new FormsModule.Result(FormsModule.Outcome.SKIPPED, "no forms"))
            .navigation(START, new NavigationModule.Result(
                NavigationModule.Outcome.NOTHING_ELIGIBLE, "no menu"));
        RecordingScreenshots screenshots = new RecordingScreenshots();
        CrawlerPageProcessor processor = withScreenshots(nothing, screenshots);
        processor.process(page(START, 0, null));
        assertEquals(CrawlerPageProcessor.Status.NOTHING_TO_CHECK,
            processor.results().get(0).forms().status());
        assertEquals(List.of(), screenshots.requests, "the website did nothing wrong");

        // NOT_RUN: the page never opened, so the other checks could not run.
        ScriptedAudit neverOpened = new ScriptedAudit()
            .openFails(START, new RuntimeException("refused"));
        RecordingScreenshots openOnly = new RecordingScreenshots();
        CrawlerPageProcessor second = withScreenshots(neverOpened, openOnly);
        second.process(page(START, 0, null));
        assertEquals(CrawlerPageProcessor.Status.NOT_RUN,
            second.results().get(0).pageHealth().status());
        assertEquals(List.of("1:open"), openOnly.requests, "only the open itself");
    }

    @Test
    void screenshotLabelsCarryPageAndCheckIdentityButNoAddress() {
        assertEquals("page-002-links-failed",
            CrawlScreenshotRecorder.labelFor(2, CrawlScreenshotRecorder.CheckTarget.LINKS));
        assertEquals("page-001-open-failed",
            CrawlScreenshotRecorder.labelFor(1, CrawlScreenshotRecorder.CheckTarget.PAGE_OPEN));
        assertEquals("login-failed",
            CrawlScreenshotRecorder.labelFor(0, CrawlScreenshotRecorder.CheckTarget.LOGIN));
        assertEquals("crawler-unexpected-failure",
            CrawlScreenshotRecorder.labelFor(0, CrawlScreenshotRecorder.CheckTarget.UNEXPECTED));

        // A caller cannot supply anything but a fixed target, so no label can carry content: the
        // parameter that used to accept a free-form string no longer exists.
        for (CrawlScreenshotRecorder.CheckTarget target
            : CrawlScreenshotRecorder.CheckTarget.values()) {
            String label = CrawlScreenshotRecorder.labelFor(3, target);
            assertTrue(label.matches("[a-z0-9-]+"), label);
        }
    }

    // --- Slice 6: the boundaries this slice must not cross -------------------------------------

    @Test
    void noCheckModuleImportsTheScreenshotService() throws IOException {
        List<Path> checkSources = javaFilesUnder(Path.of("src/main/java/com/qanize/automation/checks"));

        assertFalse(checkSources.isEmpty(), "the scan did not reach the check packages");
        for (Path source : checkSources) {
            String text = Files.readString(source);
            assertFalse(text.contains("ScreenshotCaptureService"),
                source + " must not know how a screenshot is taken");
            assertFalse(text.contains("CrawlScreenshotRecorder"), source.toString());
            assertFalse(text.contains("PageScreenshots"), source.toString());
        }
    }

    @Test
    void noSecondScreenshotOrRedactionMechanismExists() throws IOException {
        List<Path> mainSources = javaFilesUnder(Path.of("src/main/java"));

        for (Path source : mainSources) {
            String name = source.getFileName().toString();
            String text = Files.readString(source);
            if (name.equals("ScreenshotCaptureService.java")) {
                continue;
            }
            assertFalse(text.contains("getScreenshotAs"),
                name + " must not take its own screenshot");
            assertFalse(text.contains("createTreeWalker"),
                name + " must not carry a second redaction script");
        }
    }

    /**
     * The crawler reaches the runtime through one class, and that class is named here.
     *
     * <p>Until the CLI integration this asserted that <em>nothing</em> under {@code runtime} named
     * {@code CrawlRunner}, because nothing was supposed to start a crawl yet. That slice happened,
     * so the blanket ban is no longer true — but the reason for it is. A second place that
     * constructs or calls the runner would be a second lifecycle: another browser, another
     * sign-in, another set of artifact destinations, none of it covered by the coordinator's
     * tests. So the guard is narrowed rather than dropped, and it is now stricter than a ban on a
     * word: exactly one file may name the runner, and it must be the one whose whole job is that.
     */
    @Test
    void theCrawlerIsWiredIntoTheRuntimeThroughExactlyOneClass() throws IOException {
        List<Path> wiring = new ArrayList<>();
        for (Path source : javaFilesUnder(Path.of("src/main/java/com/qanize/automation/runtime"))) {
            if (code(Files.readString(source)).contains("CrawlRunner")) {
                wiring.add(source.getFileName());
            }
        }

        assertEquals(List.of(Path.of("CrawlerRunCoordinator.java")), wiring,
            "one runtime class may reach the crawler, and it is the coordinator");

        String coordinator = code(Files.readString(Path.of(
            "src/main/java/com/qanize/automation/runtime/CrawlerRunCoordinator.java")));
        assertEquals(1, count(coordinator, "new CrawlRunner("),
            "and it constructs exactly one: a second would be a second browser and a second "
                + "sign-in, outside everything the coordinator's tests cover");

        // The CLI still reaches it only through that class, never directly.
        String cli = code(Files.readString(Path.of(
            "src/main/java/com/qanize/automation/runtime/AutomationRunOrchestratorCli.java")));
        assertEquals(0, count(cli, "CrawlRunner"),
            "the CLI never names the runner: it goes through the coordinator or not at all");
        assertEquals(1, count(cli, "new CrawlerRunCoordinator()"),
            "and builds exactly one production coordinator, in the overload that defaults it");
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        try (var found = Files.walk(root)) {
            return found.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    /** Records every screenshot request, and can be made to fail like a broken camera. */
    private static final class RecordingScreenshots
        implements CrawlerPageProcessor.PageScreenshots {

        private final List<String> timeline;
        private final List<String> requests = new ArrayList<>();
        private RuntimeException failure;

        RecordingScreenshots() {
            this(new ArrayList<>());
        }

        /** Shares the audit's call list, so ordering between the two is observed directly. */
        RecordingScreenshots(List<String> timeline) {
            this.timeline = timeline;
        }

        RecordingScreenshots failsWith(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        @Override
        public void capture(int pageNumber, CrawlScreenshotRecorder.CheckTarget target) {
            requests.add(pageNumber + ":" + target.labelKey());
            timeline.add("screenshot:" + target.labelKey());
            if (failure != null) {
                throw failure;
            }
        }
    }
}
