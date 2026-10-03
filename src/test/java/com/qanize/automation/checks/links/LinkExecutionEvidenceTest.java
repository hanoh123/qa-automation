package com.qanize.automation.checks.links;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.checks.navigation.NavigationExecutionEvidence;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceFileReader;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.testsupport.CheckSources;


/**
 * Proves the Links execution evidence without a live website.
 *
 * <p>Links is the check that never touches its subject with a browser, so most of what is pinned
 * here is that the report says so: an HTTP GET is described as an HTTP request, a status is the
 * real one, and a connection failure is never dressed up as a response from the site.
 */
@Tag("unit")
class LinkExecutionEvidenceTest {

    private static final String BASE = "https://example.com/";
    private static final String LINK_1 = "https://example.com/pricing";
    private static final String LINK_2 = "https://example.com/about";

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

    private List<String> lines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> linesFor(String testCaseId) throws IOException {
        return lines().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    /** Drives one link exactly as the check does on a healthy response. */
    private void requestLink(LinkExecutionEvidence execution, String id, String link, int status) {
        execution.requestedLink(id, link, status, link, 143);
        execution.assertedLinkUsable(id, link, status < 400, "Responded with HTTP " + status + ".");
    }

    // --- correlation -------------------------------------------------------------------------

    @Test
    void eachLinkGetsItsOwnExistingTestCaseId() throws IOException {
        LinkExecutionEvidence execution = recording();
        requestLink(execution, "link-1", LINK_1, 200);
        requestLink(execution, "link-2", LINK_2, 200);

        assertEquals(2, linesFor("link-1").size());
        assertEquals(2, linesFor("link-2").size());
        assertTrue(linesFor("link-1").stream().noneMatch(line -> line.contains("/about")));
        assertTrue(linesFor("link-2").stream().noneMatch(line -> line.contains("/pricing")));
    }

    @Test
    void sequenceIsDeterministicAndRestartsPerLink() throws IOException {
        LinkExecutionEvidence execution = recording();
        requestLink(execution, "link-1", LINK_1, 200);
        requestLink(execution, "link-2", LINK_2, 200);

        for (String id : List.of("link-1", "link-2")) {
            List<String> forCase = linesFor(id);
            assertTrue(forCase.get(0).contains("\"sequence\":1"), forCase.get(0));
            assertTrue(forCase.get(1).contains("\"sequence\":2"), forCase.get(1));
        }
        assertEquals(2, execution.recordedSteps("link-1"));
    }

    @Test
    void everyLineIsAttributedToTheLinksCheck() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.openedStartPage(BASE, 400);
        requestLink(execution, "link-1", LINK_1, 200);

        assertTrue(lines().stream().allMatch(line -> line.contains("\"checkId\":\"LINKS\"")));
    }

    // --- honest method attribution -------------------------------------------------------------

    @Test
    void theRequestIsRecordedAsAnHttpRequestAndNamesTheMethodActuallyUsed() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1", LINK_1, 200, LINK_1, 143);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"HTTP_REQUEST\""), line);
        assertTrue(line.contains("with a GET"), line);
        assertFalse(line.contains("NAVIGATE"), line);
    }

    @Test
    void aTestedLinkIsNeverDescribedAsBrowserNavigationOrAClick() throws IOException {
        LinkExecutionEvidence execution = recording();
        requestLink(execution, "link-1", LINK_1, 200);
        execution.requestFailed("link-2", LINK_2, "ConnectException", 10_000);

        for (String line : lines()) {
            assertFalse(line.contains("CLICK"), line);
            assertFalse(line.contains("\"method\":\"NAVIGATE\""), line);
        }
    }

    @Test
    void onlyTheStartPageIsRecordedAsBrowserNavigation() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.openedStartPage(BASE, 400);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"NAVIGATE\""), line);
        assertTrue(line.contains("\"phase\":\"SETUP\""), line);
        assertTrue(line.contains("collect its links"), line);
    }

    @Test
    void theRecorderHasNoEntryPointThatCouldIssueItsOwnRequest() throws IOException {
        List<String> methodNames = List.of(LinkExecutionEvidence.class.getDeclaredMethods())
            .stream().map(java.lang.reflect.Method::getName).toList();
        assertFalse(methodNames.contains("clicked"));

        // Nothing in the evidence layer may build or send a request: the check owns the one call.
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/links/LinkExecutionEvidence.java"));
        assertFalse(source.contains("HttpClient"), "evidence must not own an HTTP client");
        assertFalse(source.contains("HttpRequest"), "evidence must not build a request");
        assertFalse(source.contains(".send("), "evidence must never perform a request");
    }

    /** Where the Links request now lives; the wrapper only opens the page and reports. */
    private static final Path LINKS_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/links/LinksModule.java");

    @Test
    void theCheckStillMakesExactlyOneRequestPerLink() throws IOException {
        // The request moved into LinksModule with the rest of the check. Reading the wrapper would
        // now find no send call at all and the count would pass vacuously, so this reads the module
        // and separately proves the wrapper owns no request of its own.
        String module = Files.readString(LINKS_MODULE);
        String wrapper = Files.readString(CheckSources.entryClass("GenericLinkValidationTest"));

        // There are now two probes — the anonymous one, and the one a signed-in run uses — so
        // counting a single client's calls would still read 1 while saying nothing at all about
        // the other. Each is counted on its own, and the total is counted as well, so a third send
        // anywhere in the module fails here instead of hiding behind whichever count still passes.
        assertEquals(1, module.split("HTTP_CLIENT\\.send\\(", -1).length - 1,
            "the anonymous probe sends once");
        assertEquals(1, module.split("client\\.send\\(", -1).length - 1,
            "the signed-in probe sends from exactly one place");
        assertEquals(2, module.split("\\.send\\(", -1).length - 1,
            "two probes, one send each — evidence added no extra request to either");

        // The anonymous client's configuration is untouched, redirect policy included: a run that
        // did not sign in must behave exactly as it always has.
        assertTrue(module.contains("followRedirects(HttpClient.Redirect.NORMAL)"));
        assertTrue(module.contains(".GET()"));
        assertFalse(module.contains(".HEAD("));

        // The signed-in probe follows redirects itself, which is what makes "a cookie never
        // crosses an origin" a rule this code applies rather than one it hopes a client applies.
        assertTrue(module.contains("followRedirects(HttpClient.Redirect.NEVER)"),
            "the signed-in probe decides every hop for itself");
        assertEquals(1, module.split("session\\.cookieHeaderFor\\(", -1).length - 1,
            "one place decides whether a hop is served the session");

        // The wrapper must not have acquired a request path of its own.
        assertFalse(wrapper.contains("HttpClient"), "the wrapper owns no HTTP client");
        assertFalse(wrapper.contains(".send("), "the wrapper performs no request");
    }

    @Test
    void theRecorderNeverLearnsWhatASessionIs() throws IOException {
        // The evidence layer describes what happened to the website. A cookie is not one of those
        // things, and a recorder that could name one is a recorder that could write one down.
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/links/LinkExecutionEvidence.java"));

        for (String forbidden : List.of(
            "Cookie", "LinkSession", "SessionCookie", "cookieHeaderFor", "Authorization")) {
            assertFalse(source.contains(forbidden),
                "the recorder must not know about " + forbidden);
        }
    }

    // --- status and duration ----------------------------------------------------------------------

    @Test
    void theRealStatusAndDurationArePersisted() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1", LINK_1, 301, LINK_1, 143);

        String line = lines().getFirst();
        assertTrue(line.contains("Responded with HTTP 301."), line);
        assertTrue(line.contains("\"durationMs\":143"), line);
    }

    @Test
    void theExistingPassAndFailThresholdIsPreservedExactly() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1", LINK_1, 399, LINK_1, 5);
        execution.requestedLink("link-2", LINK_2, 400, LINK_2, 5);

        assertEquals(400, LinkExecutionEvidence.FAILURE_STATUS_THRESHOLD);
        assertTrue(linesFor("link-1").getFirst().contains("\"status\":\"PASSED\""));
        assertTrue(linesFor("link-2").getFirst().contains("\"status\":\"FAILED\""));
        // 3xx passes because the existing check follows redirects and only fails at 400.
        assertFalse(linesFor("link-1").getFirst().contains("errorCategory"));
    }

    @Test
    void aFailingStatusIsAWebsiteFindingAndAConnectionFailureIsNot() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1", LINK_1, 404, LINK_1, 12);
        execution.requestFailed("link-2", LINK_2, "HttpConnectTimeoutException: timed out", 10_000);

        assertTrue(linesFor("link-1").getFirst().contains("\"errorCategory\":\"WEBSITE_FINDING\""));
        String failed = linesFor("link-2").getFirst();
        assertTrue(failed.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), failed);
        assertFalse(failed.contains("WEBSITE_FINDING"), failed);
        // No response was received, so no status may be implied.
        assertFalse(failed.contains("Responded with HTTP"), failed);
    }

    // --- redirects -----------------------------------------------------------------------------------

    @Test
    void aRedirectIsReportedOnlyWhenTheFinalUrlDiffers() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1", LINK_1, 200, "https://example.com/pricing/", 20);
        execution.requestedLink("link-2", LINK_2, 200, LINK_2, 20);

        assertTrue(linesFor("link-1").getFirst().contains("redirected to"), "final URL differs");
        assertFalse(linesFor("link-2").getFirst().contains("redirected"), "final URL is the same");
    }

    @Test
    void anUnavailableFinalUrlIsNeverInvented() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1", LINK_1, 200, null, 20);
        execution.requestedLink("link-2", LINK_2, 200, "", 20);

        assertFalse(linesFor("link-1").getFirst().contains("redirected"));
        assertFalse(linesFor("link-2").getFirst().contains("redirected"));
    }

    // --- discovery, exclusion and skipping ------------------------------------------------------------

    @Test
    void discoveryReportsTheRealCountsAndTheConfiguredLimit() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.discoveredLinks(BASE, 40, 40, 25, 8, 15);

        String line = lines().getFirst();
        assertTrue(line.contains("Found 40 link element(s)"), line);
        assertTrue(line.contains("25 will be requested"), line);
        assertTrue(line.contains("8 further eligible link(s) were not requested"), line);
        assertTrue(line.contains("\"phase\":\"DISCOVERY\""), line);
    }

    @Test
    void noLimitNoteAppearsWhenNothingWasLeftOut() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.discoveredLinks(BASE, 4, 4, 4, 0, 5);

        assertFalse(lines().getFirst().contains("further eligible"), lines().getFirst());
    }

    @Test
    void anExcludedLinkSaysWhyAndClaimsNoRequest() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.excludedLink("link-skipped-1", "Email us", "mailto:hi@example.com",
            "email", "The link is not an HTTP destination.");

        String line = lines().getFirst();
        assertTrue(line.contains("did not request it"), line);
        assertTrue(line.contains("Classified as email"), line);
        assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"NOT_APPLICABLE\""), line);
        assertFalse(line.contains("HTTP_REQUEST"), line);
    }

    @Test
    void zeroEligibleLinksIsSkippedAndNotApplicableRatherThanPassed() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.discoveredLinks(BASE, 3, 3, 0, 0, 6);
        execution.nothingEligibleToRequest(BASE, "No eligible same-origin links were discovered.");

        for (String line : lines()) {
            assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
            assertTrue(line.contains("\"errorCategory\":\"NOT_APPLICABLE\""), line);
            assertFalse(line.contains("PASSED"), line);
        }
    }

    // --- sensitive data ------------------------------------------------------------------------------------

    @Test
    void credentialShapedTargetsAreNeverPersisted() throws IOException {
        LinkExecutionEvidence execution = recording();
        execution.requestedLink("link-1",
            "https://example.com/account?session=abc123&api_key=zzz&page=2", 200,
            "https://admin:hunter2@example.com/account", 10);

        String line = lines().getFirst();
        assertFalse(line.contains("abc123"), line);
        assertFalse(line.contains("zzz"), line);
        assertFalse(line.contains("hunter2"), line);
        assertTrue(line.contains("session=[redacted]"), line);
        assertTrue(line.contains("api_key=[redacted]"), line);
        assertTrue(line.contains("page=2"), line);
    }

    // --- the result never depends on reporting -----------------------------------------------------------------

    @Test
    void recordingIsANoOpWithoutARunDirectory() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);

        assertNotNull(new LinkExecutionEvidence().requestedLink("link-1", LINK_1, 200, LINK_1, 5));
        assertEquals(List.of(), lines());
    }

    @Test
    void aReportingFailureCannotEscapeIntoTheChecksCatchBlock() throws IOException {
        // The check records the HTTP outcome inside catch (Exception): anything thrown from here
        // would be caught there and reported as a failed request, inventing a finding from a bug.
        LinkExecutionEvidence execution = recording();

        // Pathological input must be contained, not propagated.
        execution.excludedLink("link-skipped-1", null, null, null, null);
        execution.requestedLink("link-1", null, 200, null, -5);
        assertNotNull(execution.requestedLink("link-2", LINK_2, 200, LINK_2, 5),
            "a healthy step still records afterwards");

        // Missing detail is omitted rather than written as the word "null".
        assertFalse(linesFor("link-skipped-1").getFirst().contains("null"),
            linesFor("link-skipped-1").getFirst());

        // The containment did not disappear when the recorder was extracted: it moved, once, into
        // the shared CheckStepRecorder. Three facts replace the old "this file contains the text
        // catch (RuntimeException reportingFailure)" assertion, which proved none of them — not the
        // check id, not the diagnostic label, and nothing at all about run-time behaviour.

        // 1. This class records through the guarded factory, with its own identity and its own
        //    label. Switching to the unguarded factory, or changing the label, fails here.
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/links/LinkExecutionEvidence.java"));
        assertTrue(
            source.contains("CheckStepRecorder.guarded(CHECK_ID, \"link-step-record-failed\")"),
            "Links must record through the guarded shared recorder under its own failure label");
        assertFalse(source.contains("CheckStepRecorder.unguarded("),
            "Links must not become an unguarded recorder");
        assertFalse(source.contains("ExecutionEvidenceRecorder.record("),
            "no Links step may bypass the guarded recorder and write directly");

        // 2. CHECK_ID really is LINKS — read from what was just written, not from the source.
        assertTrue(linesFor("link-2").getFirst().contains("\"checkId\":\"LINKS\""),
            linesFor("link-2").getFirst());

        // 3. That exact recorder configuration contains a reporting failure at run time. A step the
        //    model refuses to build is swallowed, logged under this check's label and reported as
        //    null, so nothing can reach the check's own catch (Exception) block and invent a
        //    finding. Without this the assertions above would only pin spelling.
        CheckStepRecorder guarded = CheckStepRecorder.guarded("LINKS", "link-step-record-failed");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        ExecutionStep swallowed;
        try {
            swallowed = guarded.append(
                "link-1", null, ExecutionMethod.HTTP_REQUEST, "Requested the link destination.",
                LINK_1, "expected", "actual", ExecutionStep.Status.PASSED, 5,
                ErrorCategory.INFRASTRUCTURE_ERROR);
        } finally {
            System.setOut(originalOut);
        }
        assertNull(swallowed, "a contained reporting failure yields no step and no exception");
        assertTrue(
            captured.toString(StandardCharsets.UTF_8)
                .contains("QA Evidence | link-step-record-failed | "),
            captured.toString(StandardCharsets.UTF_8));
    }

    // --- isolation and structure ---------------------------------------------------------------------------------

    @Test
    void linksSharesTheFileWithSmokeAndNavigationWithoutColliding() throws IOException {
        LinkExecutionEvidence links = recording();
        new SmokeExecutionEvidence().navigated(BASE, 10);
        new NavigationExecutionEvidence().navigatedToDestination("nav-1", LINK_1, 20);
        links.requestedLink("link-1", LINK_1, 200, LINK_1, 30);

        List<String> all = lines();
        assertEquals(3, all.size());
        assertTrue(all.get(0).contains("\"checkId\":\"SMOKE\""), all.get(0));
        assertTrue(all.get(1).contains("\"checkId\":\"NAVIGATION\""), all.get(1));
        assertTrue(all.get(2).contains("\"checkId\":\"LINKS\""), all.get(2));
    }

    @Test
    void contractOneEvidenceStaysReadableAlongsideLinkSteps() throws IOException {
        LinkExecutionEvidence execution = recording();
        Path contractOne = runDirectory.resolve("evidence.jsonl");

        EvidenceRecorder.record(TestedItem.builder("LINKS", TestedItem.ItemType.LINK)
            .id("link-1").name("Pricing").pageUrl(BASE).target(LINK_1)
            .status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso()).build());
        execution.requestedLink("link-1", LINK_1, 200, LINK_1, 5);

        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
        assertEquals(1, Files.readAllLines(contractOne).size());
    }

    /**
     * The Links recorder stays bound to the Links check.
     *
     * <p>Which checks are instrumented is deliberately not asserted here — that boundary moves
     * with each approved slice and is owned by
     * {@code NavigationExecutionEvidenceTest#onlyApprovedChecksAreInstrumented}. Restating it in
     * every evidence test is what made the Navigation guard fail when Links was approved.
     */
    @Test
    void noOtherCheckUsesTheLinksRecorder() throws IOException {
        for (String other : List.of(
            "GenericSmokeTest", "GenericNavigationTest", "GenericFormDetectionTest",
            "GenericSearchTest", "GenericLoginTest"
        )) {
            assertFalse(
                Files.readString(CheckSources.entryClass(other)).contains("LinkExecutionEvidence"),
                other + " must not record through the Links check's recorder"
            );
        }
        assertTrue(Files.readString(CheckSources.entryClass("GenericLinkValidationTest"))
            .contains("LinkExecutionEvidence"));
    }

    @Test
    void thereIsExactlyOneLinksCheckAndNoNewDirectory() throws IOException {
        {
            List<String> names = CheckSources.entryClassFileNames();
            assertEquals(7, names.size(), "the seven customer-facing checks, and nothing else: " + names);
            assertEquals(1, names.stream().filter(name -> name.contains("Link")).count());
        }
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/links")));
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/http")));
    }

    @Test
    void everyRecordedActionFollowsTheCallThatPerformedIt() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericLinkValidationTest"));
        String module = Files.readString(LINKS_MODULE);

        // The wrapper opens the page, then records that it did.
        // The open now goes through OpenedPage. The ordering rule is unchanged.
        assertOrdered(wrapper, "OpenedPage.open(page, requested);", "execution.openedStartPage(");

        // Every read and every request is performed first and described afterwards.
        assertOrdered(module, "pageLinks.awaitRenderedContentTimedOut();", "execution.waitedForRenderedContent(");
        assertOrdered(module, "pageLinks.discoverLinks()", "execution.discoveredLinks(");
        assertOrdered(module, "probe.request(link,", "execution.requestedLink(");
        // Each link's verdict is on disk before the run's verdict is produced after the loop.
        assertOrdered(module, "execution.assertedLinkUsable(", "return failures.isEmpty()");
        // ... and only then does the wrapper turn that result into JUnit.
        assertOrdered(wrapper, "new LinksModule(", "assertTrue(result.passed(), result.message());");
    }

    @Test
    void theWrapperStaysAThinJUnitFaceOverTheModule() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericLinkValidationTest"));

        // Surefire classification depends on all three of these staying exactly as they are.
        assertTrue(wrapper.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertEquals(1, wrapper.split("@Test", -1).length - 1,
            "exactly one @Test, so the reported test count for LINKS is unchanged");
        assertTrue(wrapper.contains("assumeTrue(config.tests().links()"),
            "the selected-check gate cannot move into a module");

        // A page with nothing safe to request is still a skip, not a pass and not a failure.
        assertTrue(wrapper.contains("assumeTrue(!result.nothingEligible(), result.message());"),
            "nothing-eligible must still abort the test as a skip");

        // It opens the page and hands both the page and its recorder over.
        assertTrue(wrapper.contains("OpenedPage.open(page, requested);"),
            "the wrapper still owns baseline navigation until a runner exists");
        assertTrue(wrapper.contains("new LinksModule(page, config, opened.landed(), execution)"),
            "the same recorder instance is passed on, so per-test-case sequence numbers continue");

        // And implements none of the Links behaviour itself.
        for (String moved : List.of(
            "NavigationLinkInspector", "DiscoveryDiagnostics", "DiscoveryEvidence",
            "TestedItem.builder(", "getLinks()", "awaitRenderedContent(",
            "execution.discoveredLinks(", "execution.requestedLink(", "execution.excludedLink("
        )) {
            assertFalse(wrapper.contains(moved), moved + " belongs to LinksModule now");
        }
    }

    private static void assertOrdered(String source, String earlier, String later) {
        int first = source.indexOf(earlier);
        int second = source.indexOf(later);
        assertTrue(first >= 0, "missing: " + earlier);
        assertTrue(second >= 0, "missing: " + later);
        assertTrue(first < second, earlier + " must appear before " + later);
    }
}
