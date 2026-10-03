package com.qanize.automation.checks.navigation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.qanize.automation.checks.shared.PageStatusProbe;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Navigation behaviour, driven without a browser.
 *
 * <p>Navigation both reads a page and opens destinations on it, so the module takes one small seam
 * covering exactly the page work it performs. Faking that is enough to exercise discovery, the
 * destination loop, baseline restoration and every menu path — with no WebDriver, no Chrome, no
 * external site and no mocking dependency.
 */
@Tag("unit")
class NavigationModuleTest {

    private static final String BASE = "https://example.com/";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private NavigationExecutionEvidence recording() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        return new NavigationExecutionEvidence();
    }

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> stepsFor(String testCaseId) throws IOException {
        return steps().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    private List<String> items() throws IOException {
        Path file = runDirectory.resolve("evidence.jsonl");
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** Read once into a local: an orElseThrow supplier cannot carry a checked exception. */
    private String itemWithId(String id) throws IOException {
        List<String> recorded = items();
        for (String line : recorded) {
            if (line.contains("\"id\":\"" + id + "\"")) return line;
        }
        throw new AssertionError("no item recorded with id " + id + ": " + recorded);
    }

    /** Only baseUrl and maxPages matter to Navigation; the rest is inert. */
    private static WebsiteConfig config(int maxPages) {
        return new WebsiteConfig(
            "1.0", "example", URI.create(BASE), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, maxPages, 25, 10, null,
            new WebsiteTestSelectionConfig(false, true, false, false, false, false));
    }

    private NavigationModule module(FakePage page, int maxPages, NavigationExecutionEvidence e) {
        return new NavigationModule(page, config(maxPages), BASE, e);
    }

    /** The same module, told what a titleless destination's address answers. */
    private NavigationModule module(
        FakePage page, int maxPages, NavigationExecutionEvidence e, PageStatusProbe probe
    ) {
        return new NavigationModule(page, config(maxPages), BASE, e, EvidenceScope.legacy(), probe);
    }

    /**
     * A probe with scripted answers, counting how often it was asked.
     *
     * <p>Per address as well as a fallback, because a page can hold a protected destination and a
     * broken one at once, and a probe that answered the same status for both could not tell the
     * two verdicts apart.
     */
    private static final class FakeStatus implements PageStatusProbe {

        private final Map<String, Integer> byUrl = new LinkedHashMap<>();
        private final Integer fallback;
        private int asked;

        FakeStatus(int status) { this.fallback = status; }

        FakeStatus() { this.fallback = null; }

        FakeStatus answering(String url, int status) {
            byUrl.put(url, status);
            return this;
        }

        @Override
        public OptionalInt statusOf(String url) {
            asked++;
            Integer status = byUrl.containsKey(url) ? byUrl.get(url) : fallback;
            return status == null ? OptionalInt.empty() : OptionalInt.of(status);
        }
    }

    // --- discovery ----------------------------------------------------------------------------

    @Test
    void aPageWithNoNavigationLinksIsReportedAsNothingEligibleRatherThanAPass() throws IOException {
        FakePage page = new FakePage();

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
        assertFalse(result.passed(), "an untested page must never read as a pass");
        assertTrue(result.nothingEligible());
        assertTrue(result.message().contains("No eligible navigation links were discovered"),
            result.message());
        assertEquals(List.of(), page.opened, "nothing may be opened");
        assertTrue(itemWithId("navigation-discovery").contains("\"testType\":\"NAVIGATION\""),
            itemWithId("navigation-discovery"));
    }

    @Test
    void aDestinationThatLoadsAUsablePagePasses() throws IOException {
        FakePage page = new FakePage().anchor("Pricing", BASE + "pricing")
            .destination(BASE + "pricing", BASE + "pricing", "Pricing | Example", true);

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertNull(result.message(), "a passing result carries no message");
        String item = itemWithId("nav-1");
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
        assertTrue(item.contains("The destination loaded."), item);
        assertTrue(item.contains("\"metadata.pageTitle\":\"Pricing | Example\""), item);
        assertTrue(item.contains("\"metadata.redirected\":\"false\""), item);
    }

    @Test
    void aDestinationThatDoesNotReturnAUsablePageFails() throws IOException {
        FakePage page = new FakePage().anchor("Broken", BASE + "broken")
            .destination(BASE + "broken", BASE + "broken", "", false);

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().startsWith("Navigation destinations failed: "), result.message());
        assertTrue(result.message().contains("did not load a usable page"), result.message());
        String item = itemWithId("nav-1");
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
        assertTrue(item.contains("The destination did not return a usable page."), item);
    }

    // --- a destination that is protected rather than usable -------------------------------------

    @Test
    void anAuthenticationProtectedDestinationIsSkippedRatherThanPassed() throws IOException {
        // The defect, reproduced from run qanize-1790766054652-8e65e2e3: /basic_auth rendered the
        // empty document a browser shows after dismissing a 401 prompt. The page source was
        // non-blank, so "is this a usable page" said yes, and Navigation reported PASSED on the
        // same address the Links check had already classified AUTH_REQUIRED in that very run.
        FakePage page = new FakePage().anchor("Basic Auth", BASE + "basic_auth")
            .destination(BASE + "basic_auth", BASE + "basic_auth", "", true);

        NavigationModule.Result result =
            module(page, 5, recording(), new FakeStatus(401)).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.NOTHING_TO_CHECK, result.outcome());
        assertTrue(result.skipped());
        assertFalse(result.passed(), "nothing about the destination was verified");
        String item = itemWithId("nav-1");
        assertTrue(item.contains("\"status\":\"SKIPPED\""), item);
        assertTrue(item.contains("\"httpStatus\":401"), item);
        assertTrue(item.contains("AUTH_REQUIRED"), "the classification is published: " + item);
        assertTrue(item.contains("requires authentication"), "and said in words: " + item);
        assertFalse(item.contains("The destination loaded."),
            "it did not load a usable page: " + item);
    }

    @Test
    void aProtectedDestinationRecordsNoWebsiteFindingInItsSteps() throws IOException {
        FakePage page = new FakePage().anchor("Members", BASE + "members")
            .destination(BASE + "members", BASE + "members", "", true);

        module(page, 5, recording(), new FakeStatus(403)).followDiscoveredLinks();

        List<String> verdicts = stepsFor("nav-1").stream()
            .filter(line -> line.contains("loaded a usable page"))
            .filter(line -> line.contains("ASSERTION"))
            .toList();
        assertEquals(1, verdicts.size(), stepsFor("nav-1").toString());
        assertFalse(verdicts.get(0).contains("WEBSITE_FINDING"), verdicts.get(0));
        assertTrue(verdicts.get(0).contains("NOT_APPLICABLE"), verdicts.get(0));
        assertTrue(verdicts.get(0).contains("\"status\":\"SKIPPED\""), verdicts.get(0));
    }

    @Test
    void aTitlelessDestinationThatSimplyHasNoTitleStillPasses() throws IOException {
        // Navigation does not require a title and must not start: the recorded expectation is
        // "the destination reports its title, if it has one". A titleless page that answers
        // normally is a valid page and keeps the verdict it has always had.
        FakePage page = new FakePage().anchor("Plain", BASE + "plain")
            .destination(BASE + "plain", BASE + "plain", "", true);

        NavigationModule.Result result =
            module(page, 5, recording(), new FakeStatus(200)).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        String item = itemWithId("nav-1");
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
        assertTrue(item.contains("The destination loaded."), item);
    }

    @Test
    void withoutAProbeATitlelessDestinationKeepsExactlyTheVerdictItAlwaysHad() throws IOException {
        // Every caller that supplies no probe -- crawler mode, and any signed-in run whose session
        // refuses one -- behaves precisely as before.
        FakePage page = new FakePage().anchor("Basic Auth", BASE + "basic_auth")
            .destination(BASE + "basic_auth", BASE + "basic_auth", "", true);

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertTrue(itemWithId("nav-1").contains("\"status\":\"PASSED\""));
    }

    @Test
    void aDestinationThatReportedATitleIsNeverProbed() throws IOException {
        FakeStatus probe = new FakeStatus(200);
        FakePage page = new FakePage().anchor("Pricing", BASE + "pricing")
            .destination(BASE + "pricing", BASE + "pricing", "Pricing | Example", true);

        NavigationModule.Result result = module(page, 5, recording(), probe).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertEquals(0, probe.asked, "an ordinary destination must not cost an extra request");
    }

    @Test
    void aProtectedDestinationDoesNotHideARealFailureOnTheSamePage() throws IOException {
        FakePage page = new FakePage()
            .anchor("Basic Auth", BASE + "basic_auth")
            .anchor("Broken", BASE + "broken")
            .destination(BASE + "basic_auth", BASE + "basic_auth", "", true)
            .destination(BASE + "broken", BASE + "broken", "", false);

        NavigationModule.Result result = module(page, 5, recording(),
            new FakeStatus()
                .answering(BASE + "basic_auth", 401)
                .answering(BASE + "broken", 500))
            .followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains(BASE + "broken"), result.message());
        assertFalse(result.message().contains("basic_auth"),
            "the protected destination must not appear in the failure: " + result.message());
        assertTrue(itemWithId("nav-1").contains("\"status\":\"SKIPPED\""));
        assertTrue(itemWithId("nav-2").contains("\"status\":\"FAILED\""));
    }

    @Test
    void oneProtectedDestinationBesideARealPassIsStillAPassForTheCheck() throws IOException {
        // Something WAS verified, so the check has a verdict to report.
        FakePage page = new FakePage()
            .anchor("Basic Auth", BASE + "basic_auth")
            .anchor("Pricing", BASE + "pricing")
            .destination(BASE + "basic_auth", BASE + "basic_auth", "", true)
            .destination(BASE + "pricing", BASE + "pricing", "Pricing | Example", true);

        NavigationModule.Result result =
            module(page, 5, recording(), new FakeStatus(401)).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertTrue(itemWithId("nav-1").contains("\"status\":\"SKIPPED\""));
        assertTrue(itemWithId("nav-2").contains("\"status\":\"PASSED\""));
    }

    @Test
    void aFinalAddressThatDiffersFromTheDestinationIsRecordedAsARedirect() throws IOException {
        FakePage page = new FakePage().anchor("Old", BASE + "old")
            .destination(BASE + "old", BASE + "new", "New page", true);

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome(), "a redirect is not a failure");
        String item = itemWithId("nav-1");
        assertTrue(item.contains("\"metadata.redirected\":\"true\""), item);
        assertTrue(item.contains("redirecting to " + BASE + "new"), item);
        assertTrue(item.contains("\"metadata.finalUrl\":\"" + BASE + "new\""), item);
    }

    @Test
    void aBrowserFailureWhileOpeningADestinationIsThatDestinationsVerdict() throws IOException {
        FakePage page = new FakePage().anchor("Broken", BASE + "broken")
            .openFailure(BASE + "broken", new IllegalStateException("net::ERR_NAME_NOT_RESOLVED"));

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        String item = itemWithId("nav-1");
        assertTrue(item.contains("The browser could not open the destination."), item);
        assertTrue(item.contains("IllegalStateException"), item);
        assertFalse(item.contains("\"metadata.finalUrl\""), "no address may be invented: " + item);
    }

    @Test
    void unsupportedAndOffSiteDestinationsKeepTheirClassificationAndAreNeverOpened() throws IOException {
        FakePage page = new FakePage()
            .anchor("Email us", "mailto:hello@example.com")
            .anchor("Top", "#top")
            .anchor("Partner", "https://other.example.org/page")
            .anchor("Sign out", BASE + "logout");

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
        assertEquals(List.of(), page.opened, "none of these may be opened in the browser");
        Map<String, String> byExclusion = new LinkedHashMap<>();
        for (String line : items()) {
            for (String kind : List.of("MAIL", "FRAGMENT", "EXTERNAL", "STATE_CHANGING")) {
                if (line.contains("\"metadata.exclusion\":\"" + kind + "\"")) byExclusion.put(kind, line);
            }
        }
        for (String kind : List.of("MAIL", "FRAGMENT", "EXTERNAL", "STATE_CHANGING")) {
            assertTrue(byExclusion.containsKey(kind),
                kind + " must still be excluded; recorded were " + byExclusion.keySet());
            assertTrue(byExclusion.get(kind).contains("\"status\":\"SKIPPED\""), byExclusion.get(kind));
            assertTrue(byExclusion.get(kind).contains("Reviewed the navigation link and did not follow it."),
                byExclusion.get(kind));
        }
        assertTrue(byExclusion.get("STATE_CHANGING")
            .contains("The link may sign out, delete or otherwise change data."),
            "a sign-out link must never be followed: " + byExclusion.get("STATE_CHANGING"));
    }

    // --- baseline restoration and case isolation ----------------------------------------------

    @Test
    void theStartPageIsReopenedAfterEveryDestinationSoEachCaseBeginsWhereTheLastDid()
        throws IOException {
        FakePage page = new FakePage()
            .anchor("One", BASE + "a").anchor("Two", BASE + "b")
            .destination(BASE + "a", BASE + "a", "A", true)
            .destination(BASE + "b", BASE + "b", "B", true);

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        // Destination, back to base, destination, back to base — in that exact order.
        assertEquals(List.of(BASE + "a", BASE, BASE + "b", BASE), page.opened);
        assertFalse(stepsFor("nav-1").isEmpty());
        assertTrue(stepsFor("nav-1").stream().anyMatch(line -> line.contains("\"sequence\":5")),
            "the return step is recorded against the case that caused it: " + stepsFor("nav-1"));
    }

    @Test
    void theStartPageIsStillReopenedAfterAFailedDestination() throws IOException {
        FakePage page = new FakePage()
            .anchor("Broken", BASE + "broken").anchor("Good", BASE + "good")
            .destination(BASE + "broken", BASE + "broken", "", false)
            .destination(BASE + "good", BASE + "good", "Good", true);

        NavigationModule.Result result = module(page, 5, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        // A failure does not stop the run and does not skip the restore.
        assertEquals(List.of(BASE + "broken", BASE, BASE + "good", BASE), page.opened);
        assertTrue(itemWithId("nav-1").contains("\"status\":\"FAILED\""), itemWithId("nav-1"));
        assertTrue(itemWithId("nav-2").contains("\"status\":\"PASSED\""), itemWithId("nav-2"));
        assertFalse(itemWithId("nav-2").contains("could not open"),
            "one case's failure must not leak into another's evidence");
        assertTrue(result.message().contains("broken"), result.message());
        assertFalse(result.message().contains("good"), result.message());
    }

    @Test
    void aFailureWhileReturningToTheStartPagePropagatesRatherThanBeingReportedAsAPass() {
        RuntimeException restoreFailure = new IllegalStateException("browser window closed");
        FakePage page = new FakePage()
            .anchor("One", BASE + "a")
            .destination(BASE + "a", BASE + "a", "A", true)
            .openFailure(BASE, restoreFailure);

        // The restore is deliberately outside the per-destination try: losing the baseline means
        // every later case would run against an unknown page, so it stops the run.
        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class, () -> module(page, 5, recording()).followDiscoveredLinks());
        org.junit.jupiter.api.Assertions.assertSame(restoreFailure, thrown);
    }

    @Test
    void theConfiguredPageLimitStillCapsHowManyDestinationsAreOpened() throws IOException {
        FakePage page = new FakePage()
            .anchor("One", BASE + "a").anchor("Two", BASE + "b").anchor("Three", BASE + "c")
            .destination(BASE + "a", BASE + "a", "A", true)
            .destination(BASE + "b", BASE + "b", "B", true)
            .destination(BASE + "c", BASE + "c", "C", true);

        NavigationModule.Result result = module(page, 2, recording()).followDiscoveredLinks();

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertEquals(List.of(BASE + "a", BASE, BASE + "b", BASE), page.opened,
            "the third destination is over the limit");
    }

    // --- the configured menu ------------------------------------------------------------------

    private static WebsiteMenuConfig menu() {
        return new WebsiteMenuConfig("#burger", "#panel", "Main menu");
    }

    @Test
    void aMenuPanelHiddenBeforeTheClickAndVisibleAfterItPasses() throws IOException {
        FakePage page = new FakePage().trigger(1, true, true).panelVisibleAfterClick(true);

        NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertEquals(1, page.clicks, "exactly one click, and only one");
        String item = itemWithId("nav-menu-1");
        assertTrue(item.contains("\"name\":\"Main menu\""), item);
        assertTrue(item.contains("hidden before the click and visible after it"), item);
        assertFalse(item.contains("#burger"), "a selector is a locator, never persisted: " + item);
        assertFalse(item.contains("#panel"), item);
    }

    @Test
    void aPanelThatNeverAppearsIsAWebsiteFindingAndNotAnAutomationFault() throws IOException {
        FakePage page = new FakePage().trigger(1, true, true).panelVisibleAfterClick(false);

        NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured menu panel did not become visible after the click.",
            result.message());
        assertTrue(stepsFor("nav-menu-1").stream()
            .anyMatch(line -> line.contains("\"errorCategory\":\"WEBSITE_FINDING\"")),
            stepsFor("nav-menu-1").toString());
    }

    @Test
    void aTriggerThatIsMissingOrAmbiguousIsAConfigurationErrorAndIsNeverClicked() throws IOException {
        for (int matches : new int[] { 0, 2 }) {
            runDirectory.resolve("evidence.jsonl").toFile().delete();
            runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME).toFile().delete();
            FakePage page = new FakePage().trigger(matches, true, true);

            NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

            assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
            assertEquals(0, page.clicks, "nothing may be clicked when the trigger is not exactly one");
            assertEquals(matches == 0
                    ? "The configured menu button was not found on the page."
                    : "The configured menu button matched more than one element.",
                result.message());
            assertTrue(stepsFor("nav-menu-1").stream()
                .anyMatch(line -> line.contains("\"errorCategory\":\"CONFIGURATION_ERROR\"")),
                stepsFor("nav-menu-1").toString());
        }
    }

    @Test
    void anUnusableSelectorIsAConfigurationErrorAndIsNeverClicked() throws IOException {
        FakePage page = new FakePage().unusableTriggerSelector();

        NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured menu button selector could not be used.", result.message());
        assertEquals(0, page.clicks);
    }

    @Test
    void aPanelAlreadyVisibleBeforeTheClickStopsTheCheckWithoutClicking() throws IOException {
        FakePage page = new FakePage().trigger(1, true, true).panelVisibleBefore(true);

        NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("already visible before the click"), result.message());
        assertEquals(0, page.clicks, "an unobservable transition must not be clicked at all");
        assertTrue(itemWithId("nav-menu-1").contains("\"metadata.panelVisibleBefore\":\"true\""),
            itemWithId("nav-menu-1"));
    }

    @Test
    void aTriggerThatNeverBecomesClickableIsAnInfrastructureErrorNotAWebsiteFinding()
        throws IOException {
        FakePage page = new FakePage().trigger(1, true, true).clickPointOwned(false);

        NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("never became safely clickable"), result.message());
        assertEquals(0, page.clicks);
        assertTrue(stepsFor("nav-menu-1").stream()
            .anyMatch(line -> line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\"")),
            stepsFor("nav-menu-1").toString());
    }

    @Test
    void aClickTheBrowserCouldNotDeliverIsAnInfrastructureError() throws IOException {
        FakePage page = new FakePage().trigger(1, true, true).clickSucceeds(false);

        NavigationModule.Result result = module(page, 5, recording()).openConfiguredMenu(menu());

        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("could not deliver the click"), result.message());
        assertTrue(itemWithId("nav-menu-1").contains("\"metadata.clicked\":\"false\""),
            itemWithId("nav-menu-1"));
    }

    // --- evidence continuity and ownership -----------------------------------------------------

    @Test
    void theModuleContinuesTheSequenceTheWrapperAlreadyStarted() throws IOException {
        NavigationExecutionEvidence execution = recording();
        // Exactly what the wrapper records before handing over.
        execution.openedStartPage(BASE, 12);

        module(new FakePage().anchor("Pricing", BASE + "pricing")
            .destination(BASE + "pricing", BASE + "pricing", "P", true), 5, execution)
            .followDiscoveredLinks();

        List<String> discovery = stepsFor(NavigationExecutionEvidence.DISCOVERY_TEST_CASE_ID);
        assertTrue(discovery.get(0).contains("\"sequence\":1"), discovery.get(0));
        // The module must not restart at 1: numbering is per test case, not per object.
        assertTrue(discovery.get(1).contains("\"sequence\":2"), discovery.get(1));
        for (String line : steps()) {
            assertTrue(line.contains("\"checkId\":\"NAVIGATION\""), line);
        }
    }


    // --- crawler mode: report the open, never repeat it -----------------------------------------

    @Test
    void crawlerModeReportsTheStartPageWithoutTouchingTheBrowser() throws IOException {
        FakePage page = new FakePage();
        NavigationModule.Result result = module(page, 5, recording())
            .recordCrawlerOpenedPage("https://example.com/", null, 42, null);

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        assertNull(result.message(), "a passing result carries no message");
        assertTrue(page.opened.isEmpty(),
            "crawler mode must open nothing at all: " + page.opened);

        String item = itemWithId("nav-page-reached");
        assertTrue(item.contains("The submitted start page was opened."), item);
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
        assertTrue(item.contains("\"metadata.linkFollowed\":\"false\""), item);
        assertTrue(item.contains("\"metadata.reachedBy\":\"crawler-open\""), item);
        assertFalse(item.contains("discoveredFrom"), "the start page has no parent: " + item);
    }

    @Test
    void crawlerModeNamesTheSourceAndDestinationOfADiscoveredPage() throws IOException {
        FakePage page = new FakePage();
        NavigationModule.Result result = module(page, 5, recording()).recordCrawlerOpenedPage(
            "https://example.com/about", "https://example.com/", 31, null);

        assertEquals(NavigationModule.Outcome.PASSED, result.outcome());
        String item = itemWithId("nav-page-reached");
        assertTrue(item.contains("Reached from https://example.com/."), item);
        assertTrue(item.contains("\"metadata.discoveredFrom\":\"https://example.com/\""), item);
        assertTrue(item.contains("https://example.com/about"), item);
        assertTrue(page.opened.isEmpty(), page.opened.toString());
    }

    @Test
    void crawlerModeFailsWhenTheCrawlerCouldNotOpenThePage() throws IOException {
        FakePage page = new FakePage();
        NavigationModule.Result result = module(page, 5, recording()).recordCrawlerOpenedPage(
            "https://example.com/about", "https://example.com/", 9,
            "IllegalStateException: gateway timeout");

        // Never a pass merely because no navigation work ran.
        assertEquals(NavigationModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("could not be opened"), result.message());
        String item = itemWithId("nav-page-reached");
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
        assertTrue(item.contains("gateway timeout"), item);
        assertTrue(page.opened.isEmpty(), "a failed open is still not re-attempted here");
    }

    @Test
    void crawlerModeSanitizesBothAddressesAndTheFailure() throws IOException {
        String secret = "abcdef0123456789abcdef";
        FakePage page = new FakePage();
        module(page, 5, recording()).recordCrawlerOpenedPage(
            "https://example.com/about?session_token=" + secret,
            "https://example.com/hub?api_key=" + secret,
            5, "login failed for password=" + secret);

        String item = itemWithId("nav-page-reached");
        assertFalse(item.contains(secret), "no raw token may reach the item: " + item);
        assertTrue(item.contains("session_token=[redacted]"), item);
        assertTrue(item.contains("api_key=[redacted]"), item);
    }

    @Test
    void crawlerModeAndTheStandaloneFlowUseDifferentItemIdsSoNeitherOverwritesTheOther() {
        // nav-page-reached is new; nav-1, nav-skipped-N and nav-menu-1 are the legacy ids.
        assertEquals("nav-page-reached",
            NavigationExecutionEvidence.CRAWLER_PAGE_TEST_CASE_ID);
        assertNotEquals(NavigationExecutionEvidence.CRAWLER_PAGE_TEST_CASE_ID,
            NavigationExecutionEvidence.MENU_TEST_CASE_ID);
        assertNotEquals(NavigationExecutionEvidence.CRAWLER_PAGE_TEST_CASE_ID,
            NavigationExecutionEvidence.DISCOVERY_TEST_CASE_ID);
    }

    @Test
    void theModuleOwnsNoBrowserLifecycleNoCrawlQueueAndMakesNoAssertion() throws IOException {
        // Executable code only. Prose describing what the module deliberately does not do — "records
        // no visited pages" — must not be mistaken for the thing it disclaims.
        String source = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationModule.java")));

        for (String forbidden : List.of(
            "org.junit", "assertTrue(", "assertFalse(", "assumeTrue(",
            "new ChromeDriver", "driver.quit", "driver.close",
            "Keys.ENTER", "Keys.RETURN", ".submit()", "executeScript", "Thread.sleep",
            "visited", "frontier", "queue",
            // The blunt substring "Crawl" used to stand in for "owns no crawl queue". It cannot
            // any more: recordCrawlerOpenedPage reports the open the Crawler already performed.
            // These replace it and are strictly stronger -- they forbid the dependency itself
            // rather than the word, so the module still cannot reach a queue, a policy or a page
            // it did not receive as a plain argument.
            "com.qanize.automation.engine", "CrawlPolicy", "Crawler.", "new Crawler",
            "PageAuditContext", "EvidenceScope.forPage")) {
            assertFalse(source.contains(forbidden), "NavigationModule must not contain " + forbidden);
        }
        // And crawler mode is a report, not a traversal: it opens nothing and holds nothing.
        String crawlerMode = source.substring(
            source.indexOf("public Result recordCrawlerOpenedPage("),
            source.indexOf("public Result followDiscoveredLinks()"));
        assertFalse(crawlerMode.contains("openUrl"), "crawler mode must open nothing: " + crawlerMode);
        assertFalse(crawlerMode.contains("clickTriggerOnce"), crawlerMode);
        assertFalse(crawlerMode.contains("navigation."),
            "crawler mode must not touch the page seam at all: " + crawlerMode);
        // Exactly one place asks for the click, and exactly one place performs it through the
        // shared helper. A second call site would be a retry, and a retry could toggle a real
        // menu shut again so the analyzer could no longer say what it did.
        assertEquals(1, source.split("navigation\\.clickTriggerOnce\\(\\)", -1).length - 1,
            "one delegated click, and only one");
        assertEquals(1, source.split("safeClick\\.clickOnce\\(", -1).length - 1,
            "the click goes through the shared SafePointerClick helper, exactly once");
        assertEquals(0, source.split("\\.click\\(\\)", -1).length - 1,
            "the module must never click an element directly");
    }

    /** Executable code only: a comment must neither satisfy a guard nor trip one. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * The page work Navigation performs, scripted.
     *
     * <p>Small on purpose: replacing a real {@code WebDriver} would take a large stub of methods
     * Navigation never calls, and would prove less than this does.
     */
    private static final class FakePage implements NavigationModule.PageNavigation {

        private final List<NavigationModule.Anchor> anchors = new ArrayList<>();
        private final Map<String, String[]> destinations = new LinkedHashMap<>();
        private final Map<String, RuntimeException> openFailures = new LinkedHashMap<>();
        private final List<String> opened = new ArrayList<>();

        private String currentUrl = BASE;
        private String title = "Start";
        private boolean loaded = true;

        private boolean selectorUsable = true;
        private int triggerMatches;
        private boolean triggerDisplayed;
        private boolean triggerEnabled;
        private boolean panelVisibleBefore;
        private boolean panelVisibleAfter;
        private boolean clickPointOwned = true;
        private boolean clickSucceeds = true;
        private int clicks;
        private boolean clicked;

        FakePage anchor(String text, String href) {
            anchors.add(new NavigationModule.Anchor(text, href));
            return this;
        }

        /** finalUrl, title and whether the document loaded, for one destination. */
        FakePage destination(String url, String finalUrl, String title, boolean loaded) {
            destinations.put(url, new String[] { finalUrl, title, Boolean.toString(loaded) });
            return this;
        }

        FakePage openFailure(String url, RuntimeException failure) {
            openFailures.put(url, failure);
            return this;
        }

        FakePage trigger(int matches, boolean displayed, boolean enabled) {
            this.triggerMatches = matches;
            this.triggerDisplayed = displayed;
            this.triggerEnabled = enabled;
            return this;
        }

        FakePage unusableTriggerSelector() {
            this.selectorUsable = false;
            return this;
        }

        FakePage panelVisibleBefore(boolean visible) {
            this.panelVisibleBefore = visible;
            return this;
        }

        FakePage panelVisibleAfterClick(boolean visible) {
            this.panelVisibleAfter = visible;
            return this;
        }

        FakePage clickPointOwned(boolean owned) {
            this.clickPointOwned = owned;
            return this;
        }

        FakePage clickSucceeds(boolean succeeds) {
            this.clickSucceeds = succeeds;
            return this;
        }

        @Override
        public boolean awaitRenderedContentTimedOut() {
            return false;
        }

        @Override
        public List<NavigationModule.Anchor> discoverAnchors() {
            return List.copyOf(anchors);
        }

        @Override
        public int count(String cssSelector) {
            return cssSelector.startsWith("a[href]") ? anchors.size() : 0;
        }

        @Override
        public void openUrl(String url) {
            RuntimeException failure = openFailures.get(url);
            if (failure != null) {
                opened.add(url);
                throw failure;
            }
            opened.add(url);
            String[] scripted = destinations.get(url);
            if (scripted == null) {
                currentUrl = url;
                title = "Start";
                loaded = true;
            } else {
                currentUrl = scripted[0];
                title = scripted[1];
                loaded = Boolean.parseBoolean(scripted[2]);
            }
        }

        @Override
        public String currentUrl() {
            return currentUrl;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public boolean hasLoadedDocument() {
            return loaded;
        }

        @Override
        public NavigationModule.TriggerInspection inspectTrigger(String triggerSelector) {
            return selectorUsable
                ? new NavigationModule.TriggerInspection(
                    true, triggerMatches, triggerDisplayed, triggerEnabled)
                : new NavigationModule.TriggerInspection(false, 0, false, false);
        }

        @Override
        public boolean anyVisible(String panelSelector) {
            return clicked ? panelVisibleAfter : panelVisibleBefore;
        }

        @Override
        public boolean awaitTriggerOwnsClickPoint() {
            return clickPointOwned;
        }

        @Override
        public boolean clickTriggerOnce() {
            clicks++;
            clicked = clickSucceeds;
            return clickSucceeds;
        }

        @Override
        public boolean awaitPanelVisible(String panelSelector) {
            return panelVisibleAfter;
        }
    }
}
