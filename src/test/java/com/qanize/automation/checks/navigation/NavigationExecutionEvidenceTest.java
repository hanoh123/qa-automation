package com.qanize.automation.checks.navigation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.evidence.EvidenceFileReader;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;
import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.testsupport.CheckSources;


/**
 * Proves the Navigation execution evidence without a browser or a website.
 *
 * <p>Navigation is the first check with more than one concrete test case, so most of what is
 * pinned here is correlation: that each destination gets its own id and its own sequence, and that
 * nothing the check did not do is described.
 */
@Tag("unit")
class NavigationExecutionEvidenceTest {

    private static final String BASE = "https://example.com/";
    private static final String DEST_1 = "https://example.com/pricing";
    private static final String DEST_2 = "https://example.com/about";

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

    private List<String> lines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> linesFor(String testCaseId) throws IOException {
        return lines().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    /** Drives one destination exactly as the check does. */
    private void followDestination(NavigationExecutionEvidence execution, String id, String destination) {
        execution.navigatedToDestination(id, destination, 640);
        execution.readAddress(id, destination, destination, false, 3);
        execution.readTitle(id, destination, "Pricing", 2);
        execution.readContent(id, destination, true, 4);
        execution.assertedDestinationUsable(id, destination, true, "The destination loaded.");
        execution.returnedToStartPage(id, BASE, 300);
    }

    // --- correlation across multiple destinations -------------------------------------------

    @Test
    void eachDestinationGetsItsOwnExistingTestCaseId() throws IOException {
        NavigationExecutionEvidence execution = recording();
        followDestination(execution, "nav-1", DEST_1);
        followDestination(execution, "nav-2", DEST_2);

        assertEquals(6, linesFor("nav-1").size());
        assertEquals(6, linesFor("nav-2").size());
        // Neither case leaks into the other: a destination is only ever named by its own case.
        assertTrue(linesFor("nav-1").stream().noneMatch(line -> line.contains("/about")));
        assertTrue(linesFor("nav-2").stream().noneMatch(line -> line.contains("/pricing")));
    }

    @Test
    void sequenceRestartsAtOneForEachTestCaseAndIsGapless() throws IOException {
        NavigationExecutionEvidence execution = recording();
        followDestination(execution, "nav-1", DEST_1);
        followDestination(execution, "nav-2", DEST_2);

        for (String id : List.of("nav-1", "nav-2")) {
            List<String> forCase = linesFor(id);
            for (int index = 0; index < forCase.size(); index++) {
                assertTrue(
                    forCase.get(index).contains("\"sequence\":" + (index + 1)),
                    id + " line " + index + ": " + forCase.get(index)
                );
            }
        }
    }

    @Test
    void everyLineIsAttributedToTheNavigationCheck() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.openedStartPage(BASE, 500);
        followDestination(execution, "nav-1", DEST_1);

        assertTrue(lines().stream().allMatch(line -> line.contains("\"checkId\":\"NAVIGATION\"")));
    }

    // --- honest method attribution -----------------------------------------------------------

    @Test
    void openingADestinationIsRecordedAsNavigateNotClick() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.navigatedToDestination("nav-1", DEST_1, 640);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"NAVIGATE\""), line);
        assertFalse(line.contains("CLICK"), line);
        assertTrue(line.contains("directly in the browser"), line);
    }

    @Test
    void thePageTheCrawlerOpenedIsRecordedAsDiscoveryRatherThanAsNavigationsOwnAction()
        throws IOException {
        // The crawler opened this address; Navigation only wrote down how it was reached. The
        // sentence said so, but the step was recorded as ACTION/NAVIGATE — which the results view
        // renders as "Action · Open page" beside the Navigation check, crediting it with an open
        // it never performed.
        NavigationExecutionEvidence execution = recording();

        ExecutionStep step = execution.crawlerOpenedPage(DEST_1, BASE, 180);

        assertEquals(Phase.DISCOVERY, step.phase());
        assertEquals(ExecutionMethod.ASSERT, step.method());
        String line = lines().getFirst();
        assertFalse(line.contains("\"method\":\"NAVIGATE\""),
            "Navigation navigated to nothing here: " + line);
        assertFalse(line.contains("CLICK"), line);
        assertTrue(line.contains("Recorded the page the crawler opened."), line);
        assertTrue(line.contains("followed no link"), line);
    }

    @Test
    void aPageTheCrawlerCouldNotOpenIsNotNavigationsActionEither() throws IOException {
        NavigationExecutionEvidence execution = recording();

        ExecutionStep step = execution.crawlerCouldNotOpenPage(DEST_1, BASE, "TimeoutException", 90);

        assertEquals(Phase.DISCOVERY, step.phase());
        assertEquals(ExecutionMethod.ASSERT, step.method());
        assertFalse(lines().getFirst().contains("\"method\":\"NAVIGATE\""), lines().getFirst());
    }

    @Test
    void aRealNavigationIsStillRecordedAsOne() throws IOException {
        // The distinction only means something if the other side of it survives: when Navigation
        // does open a destination itself, that is still an ACTION and still a NAVIGATE.
        NavigationExecutionEvidence execution = recording();

        ExecutionStep step = execution.navigatedToDestination("nav-1", DEST_1, 640);

        assertEquals(Phase.ACTION, step.phase());
        assertEquals(ExecutionMethod.NAVIGATE, step.method());
    }

    @Test
    void theRecorderCannotDescribeAClickBecauseTheCheckNeverClicks() throws IOException {
        // Structural, not incidental: there is no click entry point, and the check has no
        // .click() call, so a CLICK step cannot originate here.
        List<String> methodNames = List.of(NavigationExecutionEvidence.class.getDeclaredMethods())
            .stream().map(java.lang.reflect.Method::getName).toList();
        assertFalse(methodNames.contains("clicked"));
        assertFalse(methodNames.contains("typed"));

        // Read both halves. The destination-following moved into NavigationModule, so checking
        // only the wrapper would pass while proving nothing.
        String wrapper = Files.readString(CheckSources.entryClass("GenericNavigationTest"));
        String module = Files.readString(NAVIGATION_MODULE);
        for (String source : List.of(wrapper, module)) {
            assertFalse(source.contains(".click()"), "the Navigation check must not click");
            assertFalse(source.contains("HTTP_REQUEST"), "Navigation performs no HTTP request of its own");
        }
    }

    @Test
    void noWaitStepIsProducedWhileFollowingADestinationBecauseNoWaitHappensThere() throws IOException {
        NavigationExecutionEvidence execution = recording();
        followDestination(execution, "nav-1", DEST_1);

        assertTrue(linesFor("nav-1").stream().noneMatch(line -> line.contains("\"phase\":\"WAIT\"")));
    }

    @Test
    void theOnlyWaitRecordedIsTheRealPageContentReadinessPoll() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.openedStartPage(BASE, 500);
        execution.waitedForRenderedContent(BASE, false, 250);
        execution.discoveredCandidates(BASE, 9, 9, 2, 12);

        List<String> waits = lines().stream()
            .filter(line -> line.contains("\"phase\":\"WAIT\"")).toList();
        assertEquals(1, waits.size());
        assertTrue(waits.getFirst().contains("\"method\":\"WAIT_FOR_CONDITION\""), waits.getFirst());
    }

    // --- real observations ---------------------------------------------------------------------

    @Test
    void observationsCarryTheValuesTheBrowserActuallyReturned() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.readAddress("nav-1", DEST_1, "https://example.com/pricing?ref=nav", true, 3);
        execution.readTitle("nav-1", DEST_1, "Pricing — Example", 2);
        execution.readContent("nav-1", DEST_1, true, 4);

        List<String> forCase = linesFor("nav-1");
        assertTrue(forCase.get(0).contains("after a redirect"), forCase.get(0));
        assertTrue(forCase.get(0).contains("ref=nav"), forCase.get(0));
        assertTrue(forCase.get(1).contains("Pricing — Example"), forCase.get(1));
        assertTrue(forCase.get(2).contains("readable content"), forCase.get(2));
    }

    @Test
    void discoveryCountsAreTheRealOnesAndAreNotRounded() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.discoveredCandidates(BASE, 17, 17, 5, 30);

        String line = lines().getFirst();
        assertTrue(line.contains("Found 17 link element(s)"), line);
        assertTrue(line.contains("classified 17 candidate(s)"), line);
        assertTrue(line.contains("5 were eligible"), line);
        assertTrue(line.contains("\"phase\":\"DISCOVERY\""), line);
    }

    // --- passed / failed / skipped ---------------------------------------------------------------

    @Test
    void aUsableDestinationIsRecordedAsPassedWithNoErrorCategory() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.assertedDestinationUsable("nav-1", DEST_1, true, "The destination loaded.");

        String line = lines().getFirst();
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
        assertFalse(line.contains("errorCategory"), line);
    }

    @Test
    void anUnusableDestinationIsAWebsiteFinding() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.readContent("nav-1", DEST_1, false, 5);

        String line = lines().getFirst();
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
    }

    @Test
    void aBrowserFailureIsInfrastructureNotAWebsiteFinding() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.destinationFailed("nav-1", DEST_1, "TimeoutException: timed out", 30_000);

        String line = lines().getFirst();
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
        assertFalse(line.contains("WEBSITE_FINDING"), line);
    }

    @Test
    void zeroEligibleTargetsIsSkippedAndNotApplicableRatherThanPassed() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.discoveredCandidates(BASE, 4, 4, 0, 8);
        execution.nothingEligibleToFollow(BASE, "No eligible navigation links were discovered.");

        for (String line : lines()) {
            assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
            assertTrue(line.contains("\"errorCategory\":\"NOT_APPLICABLE\""), line);
            assertFalse(line.contains("PASSED"), line);
        }
    }

    @Test
    void anExcludedCandidateClaimsNoBrowserAction() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.excludedCandidate("nav-skipped-1", "Log out", "https://example.com/logout",
            "The link looks state-changing.");

        String line = lines().getFirst();
        assertTrue(line.contains("\"phase\":\"DISCOVERY\""), line);
        assertTrue(line.contains("\"method\":\"ASSERT\""), line);
        assertTrue(line.contains("did not follow it"), line);
        assertFalse(line.contains("NAVIGATE"), line);
        assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
    }

    // --- sensitive data --------------------------------------------------------------------------

    @Test
    void credentialShapedQueryValuesAreNeverPersisted() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.navigatedToDestination(
            "nav-1", "https://example.com/reset?token=abc123secret&session=zzz&page=2", 100);

        String line = lines().getFirst();
        assertFalse(line.contains("abc123secret"), line);
        assertFalse(line.contains("zzz"), line);
        assertTrue(line.contains("token=[redacted]"), line);
        assertTrue(line.contains("session=[redacted]"), line);
        // A harmless parameter stays readable, so the evidence still identifies the page.
        assertTrue(line.contains("page=2"), line);
    }

    @Test
    void embeddedCredentialsInTheAuthorityAreRemoved() throws IOException {
        NavigationExecutionEvidence execution = recording();
        execution.navigatedToDestination("nav-1", "https://admin:hunter2@example.com/private", 100);

        String line = lines().getFirst();
        assertFalse(line.contains("hunter2"), line);
        assertFalse(line.contains("admin:"), line);
        assertTrue(line.contains("example.com/private"), line);
    }

    // --- isolation ---------------------------------------------------------------------------------

    @Test
    void navigationStepsShareTheFileWithSmokeWithoutColliding() throws IOException {
        NavigationExecutionEvidence navigation = recording();
        new SmokeExecutionEvidence().navigated("https://example.com/", 10);
        navigation.navigatedToDestination("nav-1", DEST_1, 20);

        List<String> all = lines();
        assertEquals(2, all.size());
        assertTrue(all.get(0).contains("\"checkId\":\"SMOKE\""), all.get(0));
        assertTrue(all.get(0).contains("\"testCaseId\":\"smoke-page-1\""), all.get(0));
        assertTrue(all.get(1).contains("\"checkId\":\"NAVIGATION\""), all.get(1));
    }

    @Test
    void contractOneEvidenceStaysReadableAlongsideNavigationSteps() throws IOException {
        NavigationExecutionEvidence execution = recording();
        Path contractOne = runDirectory.resolve("evidence.jsonl");

        EvidenceRecorder.record(TestedItem.builder("NAVIGATION", TestedItem.ItemType.LINK)
            .id("nav-1").name("Pricing").pageUrl(BASE).target(DEST_1)
            .status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso()).build());
        execution.navigatedToDestination("nav-1", DEST_1, 20);

        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
        assertEquals(1, Files.readAllLines(contractOne).size());
        assertEquals(1, lines().size());
    }

    @Test
    void recordingIsANoOpWithoutARunDirectorySoAResultCannotDependOnIt() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        NavigationExecutionEvidence execution = new NavigationExecutionEvidence();

        ExecutionStep step = execution.navigatedToDestination("nav-1", DEST_1, 20);

        // The step is still returned to the caller; only persistence is skipped.
        assertEquals(Phase.ACTION, step.phase());
        assertEquals(ExecutionMethod.NAVIGATE, step.method());
        assertEquals(List.of(), lines());
    }

    // --- structure ------------------------------------------------------------------------------------

    /**
     * The single authoritative statement of which checks carry contract 1.1 evidence.
     *
     * <p>This is the guard that has to move, deliberately and visibly, each time a slice is
     * approved — the previous version named only Smoke and Navigation and failed the moment Links
     * was instrumented, which is exactly what it is for. Each instrumented check is also paired
     * with its own recorder, so an accidental cross-wiring is caught as well as an unapproved one.
     */
    @Test
    void onlyApprovedChecksAreInstrumented() throws IOException {
        Map<String, String> instrumented = new LinkedHashMap<>();
        instrumented.put("GenericSmokeTest", "SmokeExecutionEvidence");
        instrumented.put("GenericNavigationTest", "NavigationExecutionEvidence");
        instrumented.put("GenericLinkValidationTest", "LinkExecutionEvidence");
        instrumented.put("GenericFormDetectionTest", "FormExecutionEvidence");
        instrumented.put("GenericSearchTest", "SearchExecutionEvidence");
        instrumented.put("GenericLoginTest", "LoginExecutionEvidence");
        instrumented.put("GenericGameLaunchTest", "GameLaunchModule");

        for (Map.Entry<String, String> entry : instrumented.entrySet()) {
            String source = Files.readString(CheckSources.entryClass(entry.getKey()));
            assertTrue(
                source.contains(entry.getValue()),
                entry.getKey() + " is an approved slice and must record execution evidence"
            );
        }

        // Every customer-facing check is now instrumented, so the guard's job changes from
        // "these are not approved yet" to "the list must still cover every check exactly once".
        // A seventh check, or a check missing from this map, fails here.
        {
            List<String> names = CheckSources.entryClassNames();
            assertEquals(instrumented.keySet().stream().sorted().toList(), names,
                "the approved map must name exactly the checks that exist");
        }
    }

    @Test
    void thereIsExactlyOneNavigationCheckAndNoNewCheckDirectory() throws IOException {
        {
            List<String> names = CheckSources.entryClassFileNames();
            assertEquals(7, names.size(), "the seven customer-facing checks, and nothing else: " + names);
            assertEquals(1, names.stream().filter(name -> name.contains("Navigation")).count());
            assertTrue(names.contains("GenericNavigationTest.java"));
        }
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/navigation")));
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/features")));
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/scenarios")));
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/navigation-tests")));
    }

    /** Where the destination-following and menu interaction now live. */
    private static final Path NAVIGATION_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationModule.java");

    @Test
    void everyRecordedActionFollowsTheCallThatPerformedIt() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericNavigationTest"));
        String module = Files.readString(NAVIGATION_MODULE);

        // The wrapper opens the start page, then records that it did.
        // The open now goes through OpenedPage. The ordering rule is unchanged.
        assertOrdered(wrapper, "OpenedPage.open(page, basePage);", "execution.openedStartPage(");

        // Every read and every destination is acted on first and described afterwards.
        assertOrdered(module, "navigation.awaitRenderedContentTimedOut();", "execution.waitedForRenderedContent(");
        assertOrdered(module, "execution.waitedForRenderedContent(", "execution.discoveredCandidates(");
        assertOrdered(module, "navigation.openUrl(destination.toString());", "execution.navigatedToDestination(");
        // The verdict is on disk before the result that reports it is returned. The return is now
        // a guard rather than a ternary, because a check whose destinations all turned out to be
        // unverifiable has a third answer; the rule it is asserting has not moved.
        assertOrdered(module, "execution.assertedDestinationUsable(", "if (!failures.isEmpty())");
        // The baseline is restored after the verdict, not instead of it.
        assertOrdered(module, "execution.assertedDestinationUsable(", "execution.returnedToStartPage(");
        // ... and only then does the wrapper turn that result into JUnit.
        assertOrdered(wrapper, "new NavigationModule(", "assertTrue(result.passed(), result.message());");
    }

    @Test
    void theWrapperStaysAThinJUnitFaceOverTheModule() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericNavigationTest"));

        // Surefire classification depends on all of these staying exactly as they are.
        assertTrue(wrapper.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertTrue(wrapper.contains("assumeTrue(config.tests().navigation()"),
            "the selected-check gate cannot move into a module");
        assertTrue(wrapper.contains("return Stream.empty();"),
            "a disabled menu must still yield no dynamic test at all");
        assertTrue(wrapper.contains("assumeTrue(!result.nothingEligible(), result.message());"),
            "nothing-eligible must still abort the link check as a skip");
        assertTrue(wrapper.contains("assumeTrue(!result.skipped(), result.message());"),
            "destinations that could not be checked abort it the same way, never as a pass");
        // The source page is where the browser landed; each destination the module opens still
        // carries its own address and its own final address after its own redirects.
        //
        // The closing bracket is no longer pinned: the module now also takes the probe below, and
        // pinning it would have made "this call gained a collaborator" indistinguishable from
        // "this call lost its recorder". What is asserted is what the rule always meant.
        assertTrue(wrapper.contains(
                "new NavigationModule(\n"
                    + "            page, driver, config, opened.landed(), execution,"),
            "the same recorder instance is passed on, so per-test-case sequence numbers continue");
        // And the collaborator that arrived with it: a probe the session produced, never a session
        // the wrapper reasons about.
        assertTrue(wrapper.contains("LinkSession.anonymous().pageStatusProbe("),
            "the session decides whether a titleless destination may be asked anything");

        // And implements none of the Navigation behaviour itself.
        for (String moved : List.of(
            "NavigationLinkInspector", "DiscoveryDiagnostics", "DiscoveryEvidence",
            "TestedItem.builder(", "SafePointerClick", "WaitUtils", "SelectorUtils",
            "execution.discoveredCandidates(", "execution.navigatedToDestination(",
            "execution.returnedToStartPage(", "execution.assertedMenuOpened(")) {
            assertFalse(wrapper.contains(moved), moved + " belongs to NavigationModule now");
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
