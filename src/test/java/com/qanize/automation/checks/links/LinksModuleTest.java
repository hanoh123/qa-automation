package com.qanize.automation.checks.links;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Links behaviour, driven without a browser and without the network.
 *
 * <p>Links reads a page and then requests destinations, so the module takes two small seams: the
 * anchors it discovered, and the outcome of one request. Faking those two is enough to exercise
 * discovery, filtering, deduplication, classification, every status rule and the evidence — with no
 * WebDriver, no Chrome, no external site and no mocking dependency.
 */
@Tag("unit")
class LinksModuleTest {

    private static final String BASE = "https://example.com/";

    /** A page that is not the site root, so "the page we are on" is distinguishable from it. */
    private static final String PAGE = BASE + "inventory.html";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private LinkExecutionEvidence recording() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        return new LinkExecutionEvidence();
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

    private String itemWithId(String id) throws IOException {
        // Read once, into a local. The failure message wants the recorded items, but an
        // orElseThrow supplier cannot call a method that throws IOException: Supplier.get()
        // declares none, so the checked exception would have nowhere to go.
        List<String> recorded = items();
        return recorded.stream()
            .filter(line -> line.contains("\"id\":\"" + id + "\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no item recorded with id " + id + ": " + recorded));
    }

    /** Only baseUrl, maxLinks and the timeout matter to Links; the rest is inert. */
    private static WebsiteConfig config(int maxLinks) {
        return new WebsiteConfig(
            "1.0", "example", URI.create(BASE), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, 5, maxLinks, 10, null,
            new WebsiteTestSelectionConfig(false, false, true, false, false, false));
    }

    private LinksModule module(FakePage page, FakeProbe probe, int maxLinks,
                               LinkExecutionEvidence execution) {
        return module(page, probe, maxLinks, execution, BASE);
    }

    /** The same, for a page that is not the site root. */
    private LinksModule module(FakePage page, FakeProbe probe, int maxLinks,
                               LinkExecutionEvidence execution, String basePage) {
        return new LinksModule(page, probe, config(maxLinks), basePage, execution);
    }

    // --- discovery ----------------------------------------------------------------------------

    @Test
    void aPageWithNoLinksIsReportedAsNothingEligibleRatherThanAPass() throws IOException {
        FakeProbe probe = new FakeProbe();

        LinksModule.Result result = module(new FakePage(), probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
        assertFalse(result.passed(), "an unvalidated page must never read as a pass");
        assertTrue(result.nothingEligible());
        assertTrue(result.message().contains("No eligible same-origin links were discovered"),
            result.message());
        assertEquals(0, probe.requested.size(), "nothing may be requested");
        assertFalse(stepsFor(LinkExecutionEvidence.DISCOVERY_TEST_CASE_ID).isEmpty(),
            "the discovery diagnostic is still recorded");
        assertTrue(itemWithId("links-discovery").contains("\"testType\":\"LINKS\""),
            itemWithId("links-discovery"));
    }

    @Test
    void oneValidLinkIsRequestedOnceAndPasses() throws IOException {
        FakePage page = new FakePage().anchor("Pricing", BASE + "pricing");
        FakeProbe probe = new FakeProbe().respond(BASE + "pricing", 200);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertNull(result.message(), "a passing result carries no message");
        assertEquals(List.of(BASE + "pricing"), probe.requested, "exactly one request, to that link");
        String item = itemWithId("link-1");
        assertTrue(item.contains("\"httpStatus\":200"), item);
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
        assertTrue(item.contains("Responded with HTTP 200."), item);
        assertTrue(item.contains("HTTP GET"), item);
    }

    @Test
    void oneBrokenLinkFailsTheCheckAndNamesItsStatus() throws IOException {
        FakePage page = new FakePage().anchor("Gone", BASE + "gone");
        FakeProbe probe = new FakeProbe().respond(BASE + "gone", 404);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
        assertFalse(result.passed());
        assertTrue(result.message().startsWith("Internal links failed: "), result.message());
        assertTrue(result.message().contains("returned HTTP 404"), result.message());
        String item = itemWithId("link-1");
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
        assertTrue(item.contains("\"httpStatus\":404"), item);
    }

    // --- a destination that refuses is not a destination that is broken ------------------------

    @Test
    void anAuthenticationProtectedDestinationIsSkippedRatherThanReportedAsBroken()
        throws IOException {
        // The defect, reproduced from a real run of the-internet: /basic_auth answered 401 and was
        // reported as a link that did not resolve successfully. It resolves perfectly; it just
        // declines to serve a visitor who presented no credentials.
        FakePage page = new FakePage().anchor("Basic Auth", BASE + "basic_auth");
        FakeProbe probe = new FakeProbe().respond(BASE + "basic_auth", 401);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome(),
            "a protected destination is not a failing link");
        assertNull(result.message(), "and there is nothing to report against the website");
        String item = itemWithId("link-1");
        assertTrue(item.contains("\"status\":\"SKIPPED\""), item);
        assertTrue(item.contains("\"httpStatus\":401"), item);
        assertTrue(item.contains("AUTH_REQUIRED"), "the classification is published: " + item);
        assertTrue(item.contains("requires authentication"), "and said in words: " + item);
        // Requested, not excluded: the check did ask, and the report must not imply otherwise.
        assertEquals(List.of(BASE + "basic_auth"), probe.requested);
        assertTrue(item.contains("HTTP GET"), item);
    }

    @Test
    void aForbiddenDestinationIsTreatedTheSameWayAsAnUnauthenticatedOne() throws IOException {
        FakePage page = new FakePage().anchor("Members", BASE + "members");
        FakeProbe probe = new FakeProbe().respond(BASE + "members", 403);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertTrue(itemWithId("link-1").contains("\"status\":\"SKIPPED\""));
    }

    @Test
    void aProtectedDestinationDoesNotHideARealBrokenOneOnTheSamePage() throws IOException {
        // Both are requested, both are recorded, and only the broken one reaches the verdict.
        FakePage page = new FakePage()
            .anchor("Basic Auth", BASE + "basic_auth")
            .anchor("Gone", BASE + "gone");
        FakeProbe probe = new FakeProbe()
            .respond(BASE + "basic_auth", 401)
            .respond(BASE + "gone", 404);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
        assertTrue(result.message().contains(BASE + "gone"), result.message());
        assertFalse(result.message().contains("basic_auth"),
            "the protected destination must not appear in the failure: " + result.message());
        assertTrue(itemWithId("link-1").contains("\"status\":\"SKIPPED\""));
        assertTrue(itemWithId("link-2").contains("\"status\":\"FAILED\""));
    }

    @Test
    void theStepRecordedForAProtectedDestinationIsNotAWebsiteFinding() throws IOException {
        FakePage page = new FakePage().anchor("Basic Auth", BASE + "basic_auth");
        FakeProbe probe = new FakeProbe().respond(BASE + "basic_auth", 401);

        module(page, probe, 25, recording()).run();

        List<String> recorded = stepsFor("link-1");
        assertFalse(recorded.isEmpty(), "the request was still recorded");
        for (String line : recorded) {
            assertFalse(line.contains("WEBSITE_FINDING"),
                "a protected destination is not a defect on the customer's site: " + line);
        }
        assertTrue(
            recorded.stream().anyMatch(line -> line.contains("NOT_APPLICABLE")),
            "it is recorded as something that could not be checked: " + recorded);
    }

    @Test
    void beingRateLimitedIsNotReportedAsABrokenLinkEither() throws IOException {
        FakePage page = new FakePage().anchor("Search", BASE + "search");
        FakeProbe probe = new FakeProbe().respond(BASE + "search", 429);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        String item = itemWithId("link-1");
        assertTrue(item.contains("\"status\":\"SKIPPED\""), item);
        assertTrue(item.contains("RATE_LIMITED"), item);
    }

    @Test
    void theBelowFourHundredRuleIsUnchangedAtItsBoundary() throws IOException {
        FakePage page = new FakePage()
            .anchor("Redirected", BASE + "moved")
            .anchor("Client error", BASE + "bad");
        FakeProbe probe = new FakeProbe()
            .respond(BASE + "moved", 399)
            .respond(BASE + "bad", 400);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
        assertTrue(itemWithId("link-1").contains("\"status\":\"PASSED\""), "399 is still acceptable");
        assertTrue(itemWithId("link-2").contains("\"status\":\"FAILED\""), "400 is still a failure");
    }

    // --- filtering, deduplication and classification -------------------------------------------

    @Test
    void aDestinationAlreadySeenIsSkippedAsADuplicateAndNeverRequestedTwice() throws IOException {
        FakePage page = new FakePage()
            .anchor("Pricing", BASE + "pricing")
            .anchor("Our pricing", BASE + "pricing");
        FakeProbe probe = new FakeProbe().respond(BASE + "pricing", 200);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertEquals(List.of(BASE + "pricing"), probe.requested, "the duplicate must not be requested");
        String skipped = itemWithId("link-skipped-1");
        assertTrue(skipped.contains("\"status\":\"SKIPPED\""), skipped);
        assertTrue(skipped.contains("DUPLICATE"), skipped);
        assertTrue(skipped.contains("The destination was already tested."), skipped);
    }

    @Test
    void unsupportedAndOffSiteDestinationsKeepTheirExistingClassificationAndAreNeverRequested()
        throws IOException {
        FakePage page = new FakePage()
            .anchor("Email us", "mailto:hello@example.com")
            .anchor("Call us", "tel:+15550000")
            // A bare fragment, as it appears in markup. The browser-resolved shape of the same
            // thing has its own tests below.
            .anchor("Top", "#top")
            .anchor("Partner", "https://other.example.org/page")
            .anchor("Brochure", BASE + "brochure.pdf")
            .anchor("Run", "javascript:void(0)");
        FakeProbe probe = new FakeProbe();

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
        assertEquals(List.of(), probe.requested, "none of these may be requested");

        // Metadata is flattened by the recorder, so the key is "metadata.linkType".
        Map<String, String> byType = new LinkedHashMap<>();
        for (String line : items()) {
            for (String type : List.of("email", "telephone", "anchor", "external", "download", "script")) {
                if (line.contains("\"metadata.linkType\":\"" + type + "\"")) byType.put(type, line);
            }
        }
        for (String type : List.of("email", "telephone", "anchor", "external", "download", "script")) {
            assertTrue(byType.containsKey(type),
                type + " must still be classified; recorded types were " + byType.keySet());
            assertTrue(byType.get(type).contains("\"status\":\"SKIPPED\""), byType.get(type));
            assertTrue(byType.get(type).contains("Reviewed the link and did not request it."),
                byType.get(type));
        }
        assertTrue(byType.get("external").contains("The link leaves the tested website."),
            byType.get("external"));
        assertTrue(byType.get("anchor").contains("The link only jumps within the same page."),
            byType.get("anchor"));
    }

    @Test
    void theConfiguredLinkLimitStillCapsHowManyDestinationsAreRequested() throws IOException {
        FakePage page = new FakePage()
            .anchor("One", BASE + "a").anchor("Two", BASE + "b").anchor("Three", BASE + "c");
        FakeProbe probe = new FakeProbe()
            .respond(BASE + "a", 200).respond(BASE + "b", 200).respond(BASE + "c", 200);

        LinksModule.Result result = module(page, probe, 2, recording()).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertEquals(List.of(BASE + "a", BASE + "b"), probe.requested, "the third is over the limit");
    }

    // --- same-document references, as a browser reports them -----------------------------------
    //
    // These are the shape that produced a false Links failure on a real authenticated run: a
    // browser resolves an anchor's href before reporting it, so `<a href="#">` on /inventory.html
    // is discovered as "https://example.com/inventory.html#". Nothing here starts with '#', so
    // nothing here was caught by the rule that looks at the start of an href.

    @Test
    void aBrowserResolvedFragmentAnchorIsNeverRequested() throws IOException {
        FakePage page = new FakePage().anchor("Open menu", PAGE + "#");
        // A probe with no scripted response: if the module requests anything at all, the request
        // itself fails the test rather than being quietly answered.
        FakeProbe probe = new FakeProbe();

        LinksModule.Result result = module(page, probe, 25, recording(), PAGE).run();

        assertEquals(List.of(), probe.requested, "a jump within this page is not an HTTP request");
        assertEquals(LinksModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
        String skipped = itemWithId("link-skipped-1");
        assertTrue(skipped.contains("\"status\":\"SKIPPED\""), skipped);
        assertTrue(skipped.contains("\"metadata.exclusion\":\"FRAGMENT\""), skipped);
        assertTrue(skipped.contains("The link only jumps within the same page."), skipped);
        assertFalse(skipped.contains("\"status\":\"FAILED\""), skipped);
    }

    @Test
    void everyFragmentAnchorIsReportedAsSameDocumentAndNoneAsADuplicate() throws IOException {
        // Twenty `href="#"` anchors on one page, which is what the real run met. The failure this
        // guards had two halves: the first copy was requested and reported as a broken link, and
        // the other nineteen were reported as duplicates of a destination that should never have
        // been tested. Neither may happen again.
        FakePage page = new FakePage();
        for (int index = 1; index <= 20; index++) page.anchor("Item " + index, PAGE + "#");
        FakeProbe probe = new FakeProbe();

        LinksModule.Result result = module(page, probe, 25, recording(), PAGE).run();

        assertEquals(List.of(), probe.requested);
        assertEquals(LinksModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
        for (int index = 1; index <= 20; index++) {
            String skipped = itemWithId("link-skipped-" + index);
            assertTrue(skipped.contains("\"metadata.exclusion\":\"FRAGMENT\""), skipped);
            assertTrue(skipped.contains("\"metadata.linkType\":\"anchor\""), skipped);
            assertFalse(skipped.contains("DUPLICATE"),
                "no copy may be called a duplicate of a destination that was never tested: " + skipped);
        }
        for (String line : items()) {
            assertFalse(line.contains("\"status\":\"FAILED\""), "nothing here is a broken link: " + line);
        }
    }

    @Test
    void aFragmentAnchorDoesNotConsumeTheRealLinkBackToThisPage() throws IOException {
        // The second half of the defect. Once a "#" reference had claimed this page's identity,
        // the menu's genuine link back to it was dismissed as a duplicate — so the one link that
        // was worth testing was the one that never got tested.
        FakePage page = new FakePage()
            .anchor("Open menu", PAGE + "#")
            .anchor("All Items", PAGE);
        FakeProbe probe = new FakeProbe().respond(PAGE, 200);

        LinksModule.Result result = module(page, probe, 25, recording(), PAGE).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertEquals(List.of(PAGE), probe.requested, "the real link is requested, exactly once");
        assertTrue(itemWithId("link-1").contains("\"status\":\"PASSED\""), itemWithId("link-1"));
    }

    @Test
    void aDestinationOnAnotherPageIsRequestedWithoutItsFragment() throws IOException {
        FakePage page = new FakePage().anchor("Plans", BASE + "pricing#plans");
        // Scripted for the fragment-free address only: requesting the fragment form would hit the
        // probe's "unexpected destination" guard and fail here.
        FakeProbe probe = new FakeProbe().respond(BASE + "pricing", 200);

        LinksModule.Result result = module(page, probe, 25, recording(), PAGE).run();

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertEquals(List.of(BASE + "pricing"), probe.requested);
    }

    @Test
    void theRecordedTargetIsTheAddressThatWasActuallyRequested() throws IOException {
        FakePage page = new FakePage().anchor("Plans", BASE + "pricing#plans");
        FakeProbe probe = new FakeProbe().respond(BASE + "pricing", 200);

        module(page, probe, 25, recording(), PAGE).run();

        String item = itemWithId("link-1");
        assertTrue(item.contains("\"target\":\"" + BASE + "pricing\""), item);
        assertFalse(item.contains("pricing#plans"),
            "the report must not name a destination that was never requested: " + item);
        for (String line : stepsFor("link-1")) {
            assertFalse(line.contains("pricing#plans"), line);
        }
    }

    @Test
    void theCandidatesHandedToACrawlerCarryNoFragment() throws IOException {
        FakePage page = new FakePage()
            .anchor("Open menu", PAGE + "#")
            .anchor("Plans", BASE + "pricing#plans");
        FakeProbe probe = new FakeProbe().respond(BASE + "pricing", 200);

        LinksModule.Result result = module(page, probe, 25, recording(), PAGE).run();

        // One page to visit next, named once. A crawler records what it opens as a page, and
        // "/pricing#plans" would be "/pricing" arriving under a second name.
        assertEquals(List.of(BASE + "pricing"), result.internalCandidates());
    }

    // --- failures --------------------------------------------------------------------------------

    @Test
    void aRequestThatCouldNotBeCompletedFailsThatLinkWithNoInventedStatus() throws IOException {
        FakePage page = new FakePage().anchor("Slow", BASE + "slow");
        FakeProbe probe = new FakeProbe().fail(BASE + "slow",
            "java.net.http.HttpTimeoutException: request timed out");

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
        assertTrue(result.message().contains("could not be requested"), result.message());
        String item = itemWithId("link-1");
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
        assertFalse(item.contains("httpStatus"), "no status may be invented: " + item);
        assertTrue(item.contains("The request could not be completed."), item);
        // The cause is reported, having passed through the existing sanitizer on the way. That
        // sanitizer redacts long identifier-shaped runs, so the exception's class name may not
        // survive verbatim; the readable reason does, which is what a reader needs.
        assertTrue(item.contains("request timed out"), "the real cause is reported: " + item);
    }

    @Test
    void oneFailingLinkLeavesEveryOtherLinksEvidenceUntouched() throws IOException {
        FakePage page = new FakePage()
            .anchor("Good", BASE + "good")
            .anchor("Broken", BASE + "broken")
            .anchor("Also good", BASE + "also");
        FakeProbe probe = new FakeProbe()
            .respond(BASE + "good", 200)
            .fail(BASE + "broken", "java.io.IOException: connection reset")
            .respond(BASE + "also", 200);

        LinksModule.Result result = module(page, probe, 25, recording()).run();

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
        // Processing continues past a failure: the third link is still requested and still passes.
        assertEquals(List.of(BASE + "good", BASE + "broken", BASE + "also"), probe.requested);
        assertTrue(itemWithId("link-1").contains("\"status\":\"PASSED\""), itemWithId("link-1"));
        assertTrue(itemWithId("link-2").contains("\"status\":\"FAILED\""), itemWithId("link-2"));
        assertTrue(itemWithId("link-3").contains("\"status\":\"PASSED\""), itemWithId("link-3"));
        assertFalse(itemWithId("link-1").contains("connection reset"),
            "one link's failure must not leak into another's evidence");
        assertFalse(itemWithId("link-3").contains("connection reset"),
            "one link's failure must not leak into another's evidence");
        // Only the broken one is named in the verdict.
        assertTrue(result.message().contains("broken"), result.message());
        assertFalse(result.message().contains("also"), result.message());
    }

    @Test
    void credentialsInAFailureMessageAreSanitizedBeforeTheyAreRecorded() throws IOException {
        FakePage page = new FakePage().anchor("Portal", BASE + "portal");
        FakeProbe probe = new FakeProbe().fail(BASE + "portal",
            "java.io.IOException: refused by https://user:s3cret@example.com/portal?token=abc123");

        module(page, probe, 25, recording()).run();

        String item = itemWithId("link-1");
        assertFalse(item.contains("s3cret"), "a password must never reach the evidence: " + item);
        assertFalse(item.contains("abc123"), "a token must never reach the evidence: " + item);
        for (String line : stepsFor("link-1")) {
            assertFalse(line.contains("s3cret"), line);
            assertFalse(line.contains("abc123"), line);
        }
    }

    // --- evidence continuity and ownership -----------------------------------------------------

    @Test
    void theModuleContinuesTheSequenceTheWrapperAlreadyStarted() throws IOException {
        LinkExecutionEvidence execution = recording();
        // Exactly what the wrapper records before handing over.
        execution.openedStartPage(BASE, 12);

        module(new FakePage().anchor("Pricing", BASE + "pricing"),
            new FakeProbe().respond(BASE + "pricing", 200), 25, execution).run();

        List<String> discovery = stepsFor(LinkExecutionEvidence.DISCOVERY_TEST_CASE_ID);
        assertTrue(discovery.get(0).contains("\"sequence\":1"), discovery.get(0));
        // The module must not restart at 1: numbering is per test case, not per object.
        assertTrue(discovery.get(1).contains("\"sequence\":2"), discovery.get(1));
        assertTrue(discovery.size() >= 3, "start page, render wait and discovery: " + discovery);
        for (String line : steps()) {
            assertTrue(line.contains("\"checkId\":\"LINKS\""), line);
        }
    }

    @Test
    void theModuleNeitherOpensAPageNorNavigatesToAnyDestination() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/links/LinksModule.java"));

        for (String forbidden : List.of(
            "org.junit", "assertTrue(", "assertFalse(", "assumeTrue(",
            "openUrl", "driver.get", "new ChromeDriver", "driver.quit", "click(")) {
            assertFalse(source.contains(forbidden), "LinksModule must not contain " + forbidden);
        }
    }

    /** The anchors a page reports, and the diagnostic counts derived from them. */
    private static final class FakePage implements LinksModule.PageLinks {

        private final List<LinksModule.DiscoveredLink> anchors = new ArrayList<>();
        private boolean timedOut;

        FakePage anchor(String text, String href) {
            anchors.add(new LinksModule.DiscoveredLink(text, href));
            return this;
        }

        @Override
        public boolean awaitRenderedContentTimedOut() {
            return timedOut;
        }

        @Override
        public List<LinksModule.DiscoveredLink> discoverLinks() {
            return List.copyOf(anchors);
        }

        @Override
        public int count(String cssSelector) {
            return cssSelector.startsWith("a[href]") ? anchors.size() : 0;
        }
    }

    /** One scripted response per destination, and a record of what was actually requested. */
    private static final class FakeProbe implements LinksModule.LinkProbe {

        private final Map<String, LinksModule.Probe> responses = new LinkedHashMap<>();
        private final List<String> requested = new ArrayList<>();

        FakeProbe respond(String url, int statusCode) {
            responses.put(url, new LinksModule.Probe(statusCode, url, null));
            return this;
        }

        FakeProbe fail(String url, String rawFailure) {
            responses.put(url, new LinksModule.Probe(null, null, rawFailure));
            return this;
        }

        @Override
        public LinksModule.Probe request(URI link, Duration timeout) {
            requested.add(link.toString());
            LinksModule.Probe scripted = responses.get(link.toString());
            if (scripted == null) {
                throw new AssertionError("the module requested an unexpected destination: " + link);
            }
            return scripted;
        }
    }
}
