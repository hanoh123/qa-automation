package com.qanize.automation.reporting;

import com.qanize.automation.model.Finding;
import com.qanize.automation.model.RunOutcome;
import com.qanize.automation.model.TestedItem;

import java.time.Instant;
import java.util.List;

import com.qanize.automation.config.RunConfigurationException;
import com.qanize.automation.runtime.AutomationRunPaths;
import com.qanize.automation.runtime.AutomationRunRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RunSummaryContractTest {

    @Test
    void junitFailureBecomesWebsiteFindingWithExactVersionOneFields() {
        AutomationRunPaths paths = AutomationRunPaths.forRunId("summary-contract");
        TestRunSummary results = new TestRunSummary(
            1,
            0,
            1,
            0,
            0,
            TestRunSummary.OverallStatus.FAIL,
            List.of(new TestRunSummary.FailedTestDetail(
                "GenericSearchTest",
                "searches",
                TestRunSummary.ProblemType.FAILURE,
                "Expected search results"
            ))
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            paths,
            AutomationRunRequest.simple("https://example.com/"),
            Instant.now(),
            results,
            1
        );
        String json = new RunSummaryJsonWriter().toJson(summary);

        assertTrue(json.contains("\"outcome\": \"WEBSITE_FINDINGS\""));
        assertTrue(json.contains("\"completedAt\":"));
        assertTrue(json.contains("\"whyItFailed\": \"Expected search results\""));
        assertTrue(json.contains("\"evidence\": []"));
        assertTrue(json.contains("\"type\": \"CONFIG\""));
        assertTrue(json.contains("\"type\": \"PROGRESS\""));
        assertTrue(json.contains("\"type\": \"SUMMARY\""));
        assertFalse(json.contains("\"finishedAt\":"));
        // The v1.0 summary must not carry a top-level "status"; it uses lifecycle + outcome.
        // Top-level fields are indented by two spaces, so this checks the nesting level rather
        // than the bare substring: testExecution[] legitimately contains a nested "status".
        assertFalse(json.contains("\n  \"status\":"));
        assertFalse(json.contains("RUN_CONFIGURATION\""));
        // Per-test execution evidence must be present for every check type.
        assertTrue(json.contains("\"testExecution\": ["));
        assertTrue(json.contains("\"testType\": \"SEARCH\""));
    }

    @Test
    void findingTitlesNeverExposeInternalCheckMethodNames() {
        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("finding-title-contract"),
            searchOnlyRequest(),
            Instant.now(),
            searchFailureResults(),
            1
        );

        Finding finding = summary.findings().getFirst();
        assertEquals("SEARCH", finding.category());
        assertEquals("Search did not behave as configured", finding.title());
        assertFalse(finding.title().contains("configuredSearchCanAcceptQueryWhenEnabled"));
        assertFalse(finding.title().contains("Test"));
    }

    @Test
    void totalsDescribeTheSelectedChecksRatherThanEveryStartedTestClass() {
        // The generic profile always starts six classes; five stop on a JUnit assumption when
        // only Search is selected. The user selected one check, so the totals must say one.
        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("totals-contract"),
            searchOnlyRequest(),
            Instant.now(),
            new TestRunSummary(
                6,
                0,
                1,
                5,
                0,
                TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    "com.hanoh.tests.generic.GenericSearchTest",
                    "configuredSearchCanAcceptQueryWhenEnabled",
                    TestRunSummary.ProblemType.FAILURE,
                    "Configured search input was not visible or editable"
                ))
            ),
            1
        );

        assertEquals(1, summary.totals().total());
        assertEquals(1, summary.totals().failed());
        assertEquals(0, summary.totals().skipped());
        assertEquals(0, summary.totals().passed());
    }

    @Test
    void configurationFailureNeverBecomesAWebsiteFinding() {
        RunSummary summary = new RunSummaryFactory().configurationError(
            AutomationRunPaths.forRunId("configuration-error-contract"),
            AutomationRunRequest.simple("https://example.com/"),
            Instant.now(),
            new RunConfigurationException(
                "SEARCH_INPUT_SELECTOR_REQUIRED",
                "Search testing was enabled, but no search input selector was configured.",
                "Configure the search-field selector or disable Search testing."
            )
        );
        String json = new RunSummaryJsonWriter().toJson(summary);

        assertTrue(json.contains("\"outcome\": \"RUN_CONFIGURATION_ERROR\""));
        assertTrue(json.contains("\"category\": \"RUN_CONFIGURATION\""));
        assertTrue(json.contains("\"findings\": []"));
    }

    @Test
    void selectedCheckWithoutExecutionEvidenceIsNeverReportedAsPassed() {
        // Search selected, but Surefire shows the class ran 1 test and skipped it.
        TestRunSummary raw = new TestRunSummary(
            1, 0, 0, 1, 0,
            TestRunSummary.OverallStatus.PASS,
            List.of(),
            List.of(new TestRunSummary.SuiteResult(
                "com.hanoh.tests.generic.GenericSearchTest", 1, 0, 0, 1,
                "Test is disabled by tests.search=false"
            ))
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("skipped-not-passed"),
            searchOnlyRequest(),
            Instant.now(),
            raw,
            0
        );

        TestExecution search = summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("SEARCH"))
            .findFirst()
            .orElseThrow();
        assertTrue(search.selected());
        assertFalse(search.executed());
        assertEquals(TestExecution.Status.SKIPPED, search.status());
        assertEquals(0, search.passedCount());
        assertEquals("Test is disabled by tests.search=false", search.skippedReason());
    }

    @Test
    void unselectedCheckIsReportedAsNotTestedRatherThanPassed() {
        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("not-selected"),
            searchOnlyRequest(),
            Instant.now(),
            searchFailureResults(),
            1
        );

        TestExecution links = summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("LINKS"))
            .findFirst()
            .orElseThrow();
        assertFalse(links.selected());
        assertFalse(links.executed());
        assertEquals(TestExecution.Status.NOT_TESTED, links.status());
        assertEquals("Not selected for this run.", links.skippedReason());
        assertEquals(7, summary.testExecution().size());
    }

    @Test
    void executedCheckThatFailedIsReportedAsFailedWithRealCounts() {
        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("executed-failed"),
            searchOnlyRequest(),
            Instant.now(),
            new TestRunSummary(
                1, 0, 1, 0, 0,
                TestRunSummary.OverallStatus.FAIL,
                List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericSearchTest", 1, 1, 0, 0, ""
                ))
            ),
            1
        );

        TestExecution search = summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("SEARCH"))
            .findFirst()
            .orElseThrow();
        assertTrue(search.executed());
        assertEquals(TestExecution.Status.FAILED, search.status());
        assertEquals(1, search.failedCount());
        assertEquals(0, search.passedCount());
    }

    @Test
    void itemCountsReconcileWithTheRecordedTestedItemsAndNeverPassWithoutExecution() throws java.io.IOException {
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("reconcile-items").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            String.join("\n",
                "{\"id\":\"link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"PASSED\",\"httpStatus\":200}",
                "{\"id\":\"link-2\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"FAILED\",\"httpStatus\":404}",
                "{\"id\":\"link-3\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"SKIPPED\"}")
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("reconcile-items"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 1, 0, 0, ""))),
            1
        );

        TestExecution links = summary.testExecution().stream()
            .filter(e -> e.testType().equals("LINKS")).findFirst().orElseThrow();

        assertEquals(3, summary.testedItems().size());
        assertEquals(3, links.itemsDiscovered());
        assertEquals(2, links.itemsTested(), "skipped items were never actually validated");
        assertEquals(1, links.itemsPassed());
        assertEquals(1, links.itemsFailed());
        assertEquals(1, links.itemsSkipped());
        assertEquals(links.itemsDiscovered(), links.itemsTested() + links.itemsSkipped());

        // No item may claim PASSED unless a real action produced it.
        assertTrue(summary.testedItems().stream()
            .filter(i -> i.status() == TestedItem.Status.PASSED)
            .allMatch(i -> i.httpStatus() != null || i.actualResult() != null || i.action() != null));

        // Unselected categories must carry no fabricated items.
        assertTrue(summary.testedItems().stream().allMatch(i -> "LINKS".equals(i.testType())));
        assertEquals(TestExecution.Status.NOT_TESTED, summary.testExecution().stream()
            .filter(e -> e.testType().equals("FORMS")).findFirst().orElseThrow().status());
    }

    @Test
    void unreadableEvidenceBecomesAnExecutionErrorNotAWebsiteFinding() throws java.io.IOException {
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("corrupt-evidence").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME), "{not valid json"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("corrupt-evidence"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 1, 0, 0, 0, TestRunSummary.OverallStatus.PASS, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 0, 0, 0, ""))),
            0
        );

        assertTrue(summary.testedItems().isEmpty());
        assertTrue(summary.findings().isEmpty(), "a reporting problem is not a website defect");
        assertTrue(summary.errors().stream().anyMatch(e -> e.code().equals("EVIDENCE_RECORD_UNREADABLE")));
        assertEquals("ANALYZER_INTERNAL", summary.errors().getFirst().category());
    }

    @Test
    void aSyntheticDiscoveryRecordIsCountedAsASkipAndNeverAsADiscoveredTarget()
        throws java.io.IOException {
        // The Whop run's Forms result: no form existed, one record explained why, and the per-check
        // table read "Discovered: 1". Three numbers that used to collide now say three things.
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("synthetic-discovery").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            "{\"id\":\"links-discovery\",\"testType\":\"LINKS\",\"itemType\":\"PAGE\","
                + "\"status\":\"SKIPPED\",\"metadata.discoveryRecord\":\"true\"}"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("synthetic-discovery"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 1, 0, 0, 0, TestRunSummary.OverallStatus.PASS, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 0, 0, 0, ""))),
            1
        );

        TestExecution links = summary.testExecution().stream()
            .filter(e -> e.testType().equals("LINKS")).findFirst().orElseThrow();

        assertEquals(0, links.itemsDiscovered(),
            "an explanation of an absence is not a discovered target");
        assertEquals(0, links.itemsTested());
        assertEquals(1, links.itemsSkipped(), "it is still a real, safe skip");
        assertEquals(1, summary.testedItems().size(), "and still a real evidence record");
    }

    @Test
    void anOrdinarySkippedTargetIsStillCountedAsDiscovered() throws java.io.IOException {
        // The exclusion is keyed on the marker, not on being skipped: a link the safety rules
        // declined to follow was genuinely discovered and must stay in the denominator.
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("real-skip").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            "{\"id\":\"link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"SKIPPED\"}"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("real-skip"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 1, 0, 0, 0, TestRunSummary.OverallStatus.PASS, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 0, 0, 0, ""))),
            1
        );

        TestExecution links = summary.testExecution().stream()
            .filter(e -> e.testType().equals("LINKS")).findFirst().orElseThrow();
        assertEquals(1, links.itemsDiscovered());
        assertEquals(1, links.itemsSkipped());
    }

    @Test
    void aFailedLinkRecommendsVerifyingInABrowserRatherThanClaimingABrokenPage()
        throws java.io.IOException {
        // The analyzer requests a destination itself, with no session and no interaction. A 500
        // answered that way can be authentication, a redirect chain or bot handling. Telling
        // somebody to repair a page on that evidence is a conclusion, not a finding.
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("cautious-links").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            "{\"id\":\"link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
                + "\"status\":\"FAILED\",\"httpStatus\":500}"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("cautious-links"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    "com.hanoh.tests.generic.GenericLinkValidationTest",
                    "everyLinkResolves",
                    "A link returned HTTP 500")),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 1, 0, 0, ""))),
            1
        );

        assertFalse(summary.findings().isEmpty(), "a failed link is still reported");
        for (Finding finding : summary.findings()) {
            assertFalse(finding.recommendation().contains("Repair or remove"),
                finding.recommendation());
            assertTrue(finding.recommendation().contains("Open the destination in a browser"),
                finding.recommendation());
            // The factual part is untouched: the status and the destination still appear.
            assertTrue(finding.actual() != null, "the observed result is still reported");
        }
    }

    @Test
    void aCheckLevelFindingNamesThePageItsOwnFailedItemWasObservedOn() throws java.io.IOException {
        // The real run attributed a finding about links read from a public home page to a private
        // dashboard, because the finding always printed the requested address. It now reads the
        // page its own failed item recorded -- and nothing from any other check.
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("finding-page").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            "{\"id\":\"link-17\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
                + "\"pageUrl\":\"https://site.test/\",\"status\":\"FAILED\",\"httpStatus\":500}"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("finding-page"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    "com.hanoh.tests.generic.GenericLinkValidationTest",
                    "internalLinksOnBasePageHaveValidHttpResponsesWithinConfiguredLimit",
                    "A link returned HTTP 500")),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 1, 0, 0, ""))),
            1
        );

        Finding finding = summary.findings().getFirst();
        assertEquals("https://site.test/", finding.pageUrl(),
            "the page the failed link was read from");
        // The reproduction still starts from what a person would type.
        assertTrue(finding.reproductionSteps().getFirst().contains("https://example.com/"),
            finding.reproductionSteps().toString());
    }

    @Test
    void aFindingWithNoFailedItemToReadKeepsTheRequestedAddressRatherThanGuessing()
        throws java.io.IOException {
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("finding-no-item").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME), "");

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("finding-no-item"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", "x", "failed")),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 1, 0, 0, ""))),
            1
        );

        assertEquals("https://example.com/", summary.findings().getFirst().pageUrl(),
            "no evidence means no invented page");
    }

    @Test
    void aScreenshotIsAttributedToThePageTheCheckThatCapturedItWasWorkingOn()
        throws java.io.IOException {
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("shot-page").runDirectory();
        java.nio.file.Path shots = AutomationRunPaths.forRunId("shot-page").screenshotsDirectory();
        java.nio.file.Files.createDirectories(shots);
        java.nio.file.Files.writeString(
            shots.resolve("GenericLinkValidationTest-internalLinks-20260926-150343-514.png"), "x");
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            "{\"id\":\"link-17\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
                + "\"pageUrl\":\"https://site.test/\",\"status\":\"FAILED\",\"httpStatus\":500}"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("shot-page"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", "x", "failed")),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 1, 0, 0, ""))),
            1
        );

        assertFalse(summary.evidence().isEmpty(), "the screenshot is still reported");
        assertEquals("https://site.test/", summary.evidence().getFirst().pageUrl(),
            "the page the Links check was on, not the address that was submitted");
    }

    @Test
    void aScreenshotWhoseCheckCannotBeIdentifiedKeepsTheRequestedAddress()
        throws java.io.IOException {
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("shot-unknown").runDirectory();
        java.nio.file.Path shots = AutomationRunPaths.forRunId("shot-unknown").screenshotsDirectory();
        java.nio.file.Files.createDirectories(shots);
        java.nio.file.Files.writeString(shots.resolve("something-else-20260926.png"), "x");
        java.nio.file.Files.writeString(
            runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME),
            "{\"id\":\"link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
                + "\"pageUrl\":\"https://site.test/\",\"status\":\"PASSED\"}"
        );

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("shot-unknown"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 1, 0, 0, 0, TestRunSummary.OverallStatus.PASS, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 0, 0, 0, ""))),
            1
        );

        assertEquals("https://example.com/", summary.evidence().getFirst().pageUrl(),
            "an unattributable screenshot must not borrow another check's page");
    }

    private static AutomationRunRequest linksOnlyRequest() {
        return new AutomationRunRequest(
            AutomationRunRequest.CONTRACT_VERSION, "https://example.com/",
            AutomationRunRequest.DEFAULT_VIEWPORT_PRESET,
            AutomationRunRequest.DEFAULT_VIEWPORT_WIDTH, AutomationRunRequest.DEFAULT_VIEWPORT_HEIGHT,
            1, AutomationRunRequest.DEFAULT_MAX_LINKS,
            false, "", "", "", "", "", "", "", "",
            false, false, true, false, false, false,
            false, "", "", "", "",
            null, null, null,
            null,
            null,
            null
        );
    }

    @Test
    void detailCapKeepsRealTotalsAndPrioritisesFailedItems() throws java.io.IOException {
        java.nio.file.Path runDir = AutomationRunPaths.forRunId("detail-cap").runDirectory();
        java.nio.file.Files.createDirectories(runDir);
        StringBuilder jsonl = new StringBuilder();
        // 2 failed, 5 passed, 40 skipped = 47 discovered, well over the per-type cap.
        for (int i = 1; i <= 2; i++) {
            jsonl.append("{\"id\":\"f").append(i)
                 .append("\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"FAILED\",\"httpStatus\":404}\n");
        }
        for (int i = 1; i <= 5; i++) {
            jsonl.append("{\"id\":\"p").append(i)
                 .append("\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"PASSED\",\"httpStatus\":200}\n");
        }
        for (int i = 1; i <= 40; i++) {
            jsonl.append("{\"id\":\"s").append(i)
                 .append("\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",\"status\":\"SKIPPED\"}\n");
        }
        java.nio.file.Files.writeString(runDir.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME), jsonl.toString());

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId("detail-cap"),
            linksOnlyRequest(),
            Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.hanoh.tests.generic.GenericLinkValidationTest", 1, 1, 0, 0, ""))),
            1
        );

        TestExecution links = summary.testExecution().stream()
            .filter(e -> e.testType().equals("LINKS")).findFirst().orElseThrow();

        // Real totals survive the cap untouched.
        assertEquals(47, links.itemsDiscovered());
        assertEquals(7, links.itemsTested());
        assertEquals(5, links.itemsPassed());
        assertEquals(2, links.itemsFailed());
        assertEquals(40, links.itemsSkipped());

        // Only the detail list is trimmed, and it is accounted for.
        assertEquals(RunSummaryFactory.MAX_DETAILED_ITEMS_PER_TYPE, summary.testedItems().size());
        assertEquals(RunSummaryFactory.MAX_DETAILED_ITEMS_PER_TYPE, links.itemsDetailed());
        assertEquals(47 - RunSummaryFactory.MAX_DETAILED_ITEMS_PER_TYPE, links.itemsOmitted());
        assertEquals(links.itemsDiscovered(), links.itemsDetailed() + links.itemsOmitted());

        // Every failure is retained; skipped detail is what gets dropped.
        assertEquals(2, summary.testedItems().stream()
            .filter(i -> i.status() == TestedItem.Status.FAILED).count());
        assertEquals(5, summary.testedItems().stream()
            .filter(i -> i.status() == TestedItem.Status.PASSED).count());

        // Capping must not change the outcome.
        assertEquals(RunOutcome.WEBSITE_FINDINGS, summary.outcome());
    }

    /** A run configuration where Search is the only selected check. */
    private static AutomationRunRequest searchOnlyRequest() {
        return new AutomationRunRequest(
            AutomationRunRequest.CONTRACT_VERSION,
            "https://example.com/",
            AutomationRunRequest.DEFAULT_VIEWPORT_PRESET,
            AutomationRunRequest.DEFAULT_VIEWPORT_WIDTH,
            AutomationRunRequest.DEFAULT_VIEWPORT_HEIGHT,
            1,
            AutomationRunRequest.DEFAULT_MAX_LINKS,
            false,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            false,
            false,
            false,
            true,
            false,
            false,
            true,
            "input[type='search']",
            "",
            "",
            "qanize",
            null, null, null,
            null,
            null,
            null
        );
    }

    private static TestRunSummary searchFailureResults() {
        return new TestRunSummary(
            1,
            0,
            1,
            0,
            0,
            TestRunSummary.OverallStatus.FAIL,
            List.of(new TestRunSummary.FailedTestDetail(
                "com.hanoh.tests.generic.GenericSearchTest",
                "configuredSearchCanAcceptQueryWhenEnabled",
                TestRunSummary.ProblemType.FAILURE,
                "Configured search input was not visible or editable"
            ))
        );
    }

    // ---------------------------------------------------------------------------------------
    // Stable check identity.
    //
    // The fixtures above deliberately keep using the pre-reorganization class names, because
    // preserved runs contain exactly those names. The tests below add the current reality
    // alongside them and pin the behaviour that replaced substring classification.
    // ---------------------------------------------------------------------------------------

    private static final String OLD_PACKAGE = "com.hanoh.tests.generic.";
    private static final String NEW_PACKAGE = "com.qanize.automation.checks.";

    /** Every check the default request selects, so one run exercises the whole registry path. */
    private static TestRunSummary suitesFor(String packagePrefix) {
        return new TestRunSummary(
            4, 4, 0, 0, 0,
            TestRunSummary.OverallStatus.PASS,
            List.of(),
            List.of(
                new TestRunSummary.SuiteResult(packagePrefix + "GenericSmokeTest", 1, 0, 0, 0, ""),
                new TestRunSummary.SuiteResult(packagePrefix + "GenericNavigationTest", 1, 0, 0, 0, ""),
                new TestRunSummary.SuiteResult(packagePrefix + "GenericLinkValidationTest", 1, 0, 0, 0, ""),
                new TestRunSummary.SuiteResult(packagePrefix + "GenericFormDetectionTest", 1, 0, 0, 0, "")
            )
        );
    }

    private static RunSummary summaryFor(String runId, TestRunSummary raw) {
        return new RunSummaryFactory().fromTestResults(
            AutomationRunPaths.forRunId(runId),
            AutomationRunRequest.simple("https://example.com/"),
            Instant.now(),
            raw,
            0
        );
    }

    @Test
    void historicalAndCurrentCheckClassesProduceIdenticalExecutionRecords() {
        // A preserved run and a run recorded today must be indistinguishable to the product.
        RunSummary historical = summaryFor("identity-old", suitesFor(OLD_PACKAGE));
        RunSummary current = summaryFor("identity-new", suitesFor(NEW_PACKAGE));

        assertEquals(historical.testExecution(), current.testExecution());
        assertEquals(historical.totals(), current.totals());
        assertEquals(RunOutcome.NO_FINDINGS, current.outcome());
    }

    @Test
    void everyCurrentCheckClassIsAttributedToItsOwnCheck() {
        RunSummary summary = summaryFor("identity-attribution", suitesFor(NEW_PACKAGE));

        for (String testType : List.of("SMOKE", "NAVIGATION", "LINKS", "FORMS")) {
            TestExecution execution = summary.testExecution().stream()
                .filter(candidate -> candidate.testType().equals(testType))
                .findFirst()
                .orElseThrow();
            assertTrue(execution.executed(), testType + " should be attributed to its own suite");
            assertEquals(TestExecution.Status.PASSED, execution.status(), testType);
        }
    }

    @Test
    void aSuiteFromAnUnregisteredClassIsNeverAttributedToASelectedCheck() {
        // The old classifier answered SMOKE here, so this foreign suite would have been published
        // as the smoke check's result, complete with its pass count.
        RunSummary summary = summaryFor("identity-unknown-suite", new TestRunSummary(
            1, 1, 0, 0, 0,
            TestRunSummary.OverallStatus.PASS,
            List.of(),
            List.of(new TestRunSummary.SuiteResult(
                "com.example.other.SomeUnrelatedTest", 1, 0, 0, 0, ""))
        ));

        TestExecution smoke = summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("SMOKE"))
            .findFirst()
            .orElseThrow();
        assertTrue(smoke.selected());
        assertFalse(smoke.executed(), "an unregistered suite must not stand in for the smoke check");
        assertEquals(TestExecution.Status.NOT_TESTED, smoke.status());
        assertEquals(0, smoke.passedCount());
    }

    @Test
    void aPackageNamedAfterAnotherCheckDoesNotChangeClassification() {
        // Under substring matching this suite classified as LINKS purely because of its package.
        RunSummary summary = summaryFor("identity-misleading-package", new TestRunSummary(
            1, 1, 0, 0, 0,
            TestRunSummary.OverallStatus.PASS,
            List.of(),
            List.of(new TestRunSummary.SuiteResult(
                "com.example.links.forms.GenericSmokeTest", 1, 0, 0, 0, ""))
        ));

        TestExecution smoke = summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("SMOKE")).findFirst().orElseThrow();
        TestExecution links = summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("LINKS")).findFirst().orElseThrow();
        assertTrue(smoke.executed(), "the class decides, not the package");
        assertFalse(links.executed(), "the package must not claim the suite");
    }

    @Test
    void aFailureFromAnUnregisteredClassIsReportedWithoutInventingACheck() {
        RunSummary summary = summaryFor("identity-unknown-finding", new TestRunSummary(
            1, 0, 1, 0, 0,
            TestRunSummary.OverallStatus.FAIL,
            List.of(new TestRunSummary.FailedTestDetail(
                "com.example.other.SomeUnrelatedTest",
                "doesSomething",
                TestRunSummary.ProblemType.FAILURE,
                "Something did not match"
            ))
        ));

        Finding finding = summary.findings().getFirst();
        // The failure is still surfaced — losing it would be worse than mislabelling it.
        assertEquals(RunSummaryFactory.UNCLASSIFIED_CATEGORY, finding.category());
        assertFalse("SMOKE".equals(finding.category()), "an unknown class must never become Smoke");
        assertEquals("Something did not match", finding.whyItFailed());
        // It must not claim a smoke check ran.
        assertFalse(finding.title().contains("target page"));
        assertFalse(finding.expected().contains("configured timeout"));
    }

    @Test
    void blankAndMissingFailingClassNamesDoNotBecomeSmokeFindings() {
        String[] classNames = {null, "", "   "};
        for (int index = 0; index < classNames.length; index++) {
            String className = classNames[index];
            RunSummary summary = summaryFor("identity-blank-" + index, new TestRunSummary(
                1, 0, 1, 0, 0,
                TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    className, "someTest", TestRunSummary.ProblemType.FAILURE, "It failed"
                ))
            ));

            assertEquals(
                RunSummaryFactory.UNCLASSIFIED_CATEGORY,
                summary.findings().getFirst().category(),
                "className=" + className
            );
        }
    }

    @Test
    void knownCheckFindingWordingIsUnchangedForEveryCheck() {
        // Byte-for-byte guard: replacing the classifier must not have altered any user-facing
        // sentence for the six checks the product actually ships.
        assertEquals("Search did not behave as configured", titleFor("GenericSearchTest"));
        assertEquals("The configured login flow did not complete", titleFor("GenericLoginTest"));
        assertEquals("One or more links did not resolve successfully", titleFor("GenericLinkValidationTest"));
        assertEquals("Form detection did not meet expectations", titleFor("GenericFormDetectionTest"));
        assertEquals("A configured page did not load as expected", titleFor("GenericNavigationTest"));
        assertEquals("The target page did not load as expected", titleFor("GenericSmokeTest"));
    }

    private static String titleFor(String simpleClassName) {
        RunSummary summary = summaryFor(
            "identity-wording-" + simpleClassName.toLowerCase(java.util.Locale.ROOT),
            new TestRunSummary(
                1, 0, 1, 0, 0,
                TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    NEW_PACKAGE + simpleClassName, "aTest",
                    TestRunSummary.ProblemType.FAILURE, "It failed"
                ))
            ));
        return summary.findings().getFirst().title();
    }
}
