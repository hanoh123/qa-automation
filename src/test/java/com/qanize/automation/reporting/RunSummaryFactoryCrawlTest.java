package com.qanize.automation.reporting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.qanize.automation.engine.CrawlRunSummary;
import com.qanize.automation.engine.CrawlRunner;
import com.qanize.automation.engine.Crawler;
import com.qanize.automation.engine.CrawlerPageProcessor;
import com.qanize.automation.engine.CrawlScreenshotRecorder;
import com.qanize.automation.engine.PageAuditContext;
import com.qanize.automation.browser.ScreenshotCaptureService;
import com.qanize.automation.model.ArtifactMetadata;
import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.Finding;
import com.qanize.automation.model.RunError;
import com.qanize.automation.model.RunLifecycle;
import com.qanize.automation.model.RunOutcome;
import com.qanize.automation.model.TestedItem;
import com.qanize.automation.runtime.AutomationRunPaths;
import com.qanize.automation.runtime.AutomationRunRequest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the crawler path actually reports, asserted on the real {@link RunSummary} it returns.
 *
 * <p>{@code CrawlRunSummaryTest} proves the fold; this proves the classification that follows it.
 * The distinction matters because the fold and the outcome live in different classes: an
 * aggregate can be perfectly correct while the branch that reads it puts the run in the wrong
 * category. The single most important assertion here is that an isolated module crash reports
 * {@code ANALYZER_INTERNAL_ERROR} and not {@code WEBSITE_FINDINGS} — handing a customer a defect
 * report about their website for a defect in this analyzer is the failure mode this whole path
 * was corrected to prevent.
 *
 * <p>Every case calls the real factory with real artifact readers over a real run directory.
 */
@Tag("unit")
class RunSummaryFactoryCrawlTest {

    private static final String PAGE = "https://example.com/";

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * A request selecting Links only, mirroring the shape the legacy contract test already uses.
     *
     * <p>Written out rather than shortened: the production record is deliberately explicit, and
     * changing it to make a test terser would be the tail wagging the dog.
     */
    private static AutomationRunRequest linksOnlyRequest() {
        return new AutomationRunRequest(
            AutomationRunRequest.CONTRACT_VERSION, PAGE,
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

    /** The same request with Login selected, for the two sign-in scenarios. */
    private static AutomationRunRequest linksAndLoginRequest() {
        return new AutomationRunRequest(
            AutomationRunRequest.CONTRACT_VERSION, PAGE,
            AutomationRunRequest.DEFAULT_VIEWPORT_PRESET,
            AutomationRunRequest.DEFAULT_VIEWPORT_WIDTH, AutomationRunRequest.DEFAULT_VIEWPORT_HEIGHT,
            1, AutomationRunRequest.DEFAULT_MAX_LINKS,
            true, PAGE + "signin", "QANIZE_USER", "QANIZE_PASSWORD", "", "", "", "", "",
            false, false, true, false, false, true,
            false, "", "", "", "",
            null, null, null,
            null,
            null,
            null
        );
    }

    /**
     * A run directory containing a valid, empty evidence artifact.
     *
     * <p>Empty rather than absent on purpose: these cases are about crawler classification, and a
     * missing artifact would risk proving something about evidence reading instead.
     */
    private static AutomationRunPaths runDirectoryWithEmptyEvidence(String runId) throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        Files.createDirectories(paths.runDirectory());
        Files.writeString(paths.runDirectory().resolve(AutomationRunPaths.EVIDENCE_FILE_NAME), "");
        return paths;
    }

    private static AutomationRunPaths runDirectoryWithEvidence(String runId, String jsonl)
        throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        Files.createDirectories(paths.runDirectory());
        Files.writeString(paths.runDirectory().resolve(AutomationRunPaths.EVIDENCE_FILE_NAME), jsonl);
        return paths;
    }

    private static CrawlerPageProcessor.ModuleOutcome outcome(CrawlerPageProcessor.Status status) {
        return new CrawlerPageProcessor.ModuleOutcome(status, null);
    }

    private static CrawlerPageProcessor.ModuleOutcome crashed(String message) {
        return new CrawlerPageProcessor.ModuleOutcome(
            CrawlerPageProcessor.Status.FAILED, message,
            CrawlerPageProcessor.FailureOrigin.ANALYZER_CRASH);
    }

    private static CrawlerPageProcessor.ModuleOutcome websiteFailure(String message) {
        return new CrawlerPageProcessor.ModuleOutcome(
            CrawlerPageProcessor.Status.FAILED, message,
            CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT);
    }

    /** One crawled page whose Links outcome is supplied and whose other checks are unselected. */
    private static CrawlerPageProcessor.PageAuditResult pageWithLinks(
        CrawlerPageProcessor.ModuleOutcome links
    ) {
        return new CrawlerPageProcessor.PageAuditResult(
            new PageAuditContext(1, PAGE, PAGE, 0, null),
            outcome(CrawlerPageProcessor.Status.PASSED),
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            links,
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            List.of());
    }

    private static Crawler.CrawlResult processedCrawl(int pages) {
        List<Crawler.PageResult> attempted = new java.util.ArrayList<>();
        for (int index = 1; index <= pages; index++) {
            attempted.add(new Crawler.PageResult(PAGE + index, 0, null,
                Crawler.PageStatus.PROCESSED, null, null));
        }
        return new Crawler.CrawlResult(attempted,
            new Crawler.LimitReport(10, 3, false, false, 0));
    }

    private static CrawlRunSummary crawledWith(CrawlerPageProcessor.ModuleOutcome links) {
        return foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED, processedCrawl(1),
            List.of(pageWithLinks(links))));
    }

    private static RunSummary summaryFor(AutomationRunPaths paths, CrawlRunSummary crawl) {
        return new RunSummaryFactory()
            .fromCrawlResults(paths, linksOnlyRequest(), Instant.now(), crawl);
    }

    // --- 1..7. the seven outcomes, asserted directly ---------------------------------------------

    @Test
    void aCleanCompletedCrawlReportsNoFindingsRatherThanAnInfrastructureError() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-clean");

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        // The whole reason fromCrawlResults exists: no Surefire XML must not read as a broken run.
        assertEquals(RunOutcome.NO_FINDINGS, summary.outcome());
        assertEquals(RunLifecycle.COMPLETED, summary.lifecycle());
        assertTrue(summary.findings().isEmpty());
        assertTrue(summary.errors().isEmpty());
    }

    @Test
    void aNormalWebsiteFailureReportsWebsiteFindings() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-website-failure");

        RunSummary summary = summaryFor(paths,
            crawledWith(websiteFailure("Internal links failed: https://example.com/x returned 404")));

        assertEquals(RunOutcome.WEBSITE_FINDINGS, summary.outcome());
        assertEquals(RunLifecycle.COMPLETED, summary.lifecycle(),
            "a finding about the site is a completed run, not a broken one");
        assertTrue(summary.errors().isEmpty(), "a website finding is not an analyzer error");
    }

    @Test
    void anIsolatedModuleCrashReportsAnalyzerInternalErrorEvenWithNoFailedTestedItem()
        throws IOException {
        // Deliberately empty evidence: there is no failed TestedItem to fall back on, so the
        // classification can only come from the typed crash on the module outcome.
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-module-crash");

        RunSummary summary = summaryFor(paths,
            crawledWith(crashed("IllegalStateException: stale element reference")));

        assertEquals(RunOutcome.ANALYZER_INTERNAL_ERROR, summary.outcome(),
            "a crash in our own module must never be reported as a defect in the website");
        assertEquals(RunLifecycle.FAILED, summary.lifecycle());
        assertTrue(summary.testedItems().isEmpty(), "no item existed to classify from");
        assertTrue(summary.findings().isEmpty(), "and no website finding was invented");
        assertTrue(summary.errors().stream().anyMatch(e -> e.code().equals("ANALYZER_CHECK_ERROR")),
            summary.errors().toString());
    }

    @Test
    void aProcessorLevelPageFailureWithNoPageResultReportsAnalyzerInternalError()
        throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-unhandled-page");

        // process() threw, so the Crawler recorded a failed page and the processor produced no
        // page result at all. This page exists only in the crawl result.
        CrawlRunSummary crawl = foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED,
            new Crawler.CrawlResult(
                List.of(new Crawler.PageResult(PAGE, 0, null,
                    Crawler.PageStatus.FAILED, "IllegalStateException: boom", null)),
                new Crawler.LimitReport(10, 3, false, false, 0)),
            List.of()));

        RunSummary summary = summaryFor(paths, crawl);

        assertEquals(RunOutcome.ANALYZER_INTERNAL_ERROR, summary.outcome(),
            "a page the processor could not handle must not vanish into a clean run");
        assertEquals(RunLifecycle.FAILED, summary.lifecycle());
    }

    @Test
    void loginPreventedByMissingCredentialsReportsARunConfigurationError() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-no-credentials");

        RunSummary summary = new RunSummaryFactory().fromCrawlResults(
            paths, linksAndLoginRequest(), Instant.now(),
            foldOfARunThatSelectedEverything(CrawlRunner.CrawlRunResult.credentialsUnavailable(
                "Login is enabled but the configured credential environment variables are not set: "
                    + "QANIZE_USER, QANIZE_PASSWORD")));

        // Nothing was ever asked of the website, so this is the operator's configuration to fix.
        assertEquals(RunOutcome.RUN_CONFIGURATION_ERROR, summary.outcome());
        assertEquals(RunLifecycle.FAILED, summary.lifecycle());
        assertTrue(summary.errors().stream()
            .anyMatch(e -> e.code().equals("LOGIN_CREDENTIALS_UNAVAILABLE")),
            summary.errors().toString());
    }

    @Test
    void aGameListThatCouldNotBeRestoredSaysOnlyThatAndClaimsNothingAboutRemainingGames()
        throws IOException {
        // The sentence "The remaining games were not attempted." used to be appended here to every
        // setup problem. With a budget of one game, one game attempted and none remaining, it was
        // simply false -- and nothing at this point knows how many were left.
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-list-not-restored");

        RunSummary summary = new RunSummaryFactory().fromCrawlResults(
            paths, linksOnlyRequest(), Instant.now(),
            foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
                CrawlRunner.LoginOutcome.NOT_SELECTED, processedCrawl(1),
                List.of(pageWithLinks(outcome(CrawlerPageProcessor.Status.PASSED))),
                List.of(),
                new com.qanize.automation.checks.gamelaunch.GameLaunchModule.Result(
                    com.qanize.automation.checks.gamelaunch.GameLaunchModule.RunStatus
                        .RUN_SETUP_PROBLEM,
                    "The game list could not be restored after a launch.", List.of()))));

        assertEquals(RunOutcome.RUN_CONFIGURATION_ERROR, summary.outcome());
        var problem = summary.errors().stream()
            .filter(error -> error.code().equals("GAME_LAUNCH_SETUP_PROBLEM"))
            .findFirst().orElseThrow(() -> new AssertionError(summary.errors().toString()));
        assertEquals("The game list could not be restored after a launch.", problem.userMessage(),
            "the reason, and nothing this run cannot prove");
        assertFalse(problem.userMessage().contains("remaining games"),
            "no claim about games that were never counted: " + problem.userMessage());
    }

    // --- an internal error must not erase what the run already proved ---------------------------

    /** One evidence line in the shape the project's own recorder writes and its reader parses. */
    private static String evidenceLine(String id, String testType, String itemType, String status) {
        return "{\"id\":\"" + id + "\",\"testType\":\"" + testType + "\",\"itemType\":\""
            + itemType + "\",\"name\":\"" + id + "\",\"status\":\"" + status + "\"}";
    }

    @Test
    void anInternalErrorKeepsTheEvidenceTheRunAlreadyWrote() throws IOException {
        // The run this pins: Login passed, three games opened, and the summary then said
        // both checks had produced no execution record -- contradicted by the file beside it.
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-internal-error-evidence");
        Files.write(paths.evidenceFile(), List.of(
            evidenceLine("login-1", "LOGIN", "LOGIN", "PASSED"),
            evidenceLine("game-1", "GAME_LAUNCH", "GAME", "PASSED"),
            evidenceLine("game-2", "GAME_LAUNCH", "GAME", "PASSED")));

        RunSummary summary = new RunSummaryFactory().internalError(
            paths, linksAndLoginRequest(), Instant.now(), "StaleElementReferenceException");

        assertEquals(RunOutcome.ANALYZER_INTERNAL_ERROR, summary.outcome(),
            "the run still reports the failure it had");
        assertEquals(RunLifecycle.FAILED, summary.lifecycle());
        TestExecution login = executionOf(summary, "LOGIN");
        assertTrue(login.executed(), "Login had a durable record and must keep it");
        assertEquals(TestExecution.Status.PASSED, login.status());
        assertNotEquals("The analyzer produced no execution record for this check.",
            login.skippedReason());
    }

    @Test
    void anInternalErrorBeforeAnyEvidenceStillReportsNotRun() throws IOException {
        // The other half: nothing was written, so nothing may be claimed.
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-internal-error-empty");

        RunSummary summary = new RunSummaryFactory().internalError(
            paths, linksAndLoginRequest(), Instant.now(), "IllegalStateException");

        TestExecution login = executionOf(summary, "LOGIN");
        assertFalse(login.executed());
        assertEquals(TestExecution.Status.NOT_TESTED, login.status());
        assertEquals("The analyzer produced no execution record for this check.",
            login.skippedReason());
    }

    @Test
    void unreadableEvidenceFallsBackInsteadOfFailingAgain() throws IOException {
        // A reporting path that throws while reporting a failure replaces one problem with two.
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-internal-error-broken");
        Files.write(paths.evidenceFile(), List.of("{not json at all", "  "));

        RunSummary summary = new RunSummaryFactory().internalError(
            paths, linksAndLoginRequest(), Instant.now(), "IllegalStateException");

        assertEquals(RunOutcome.ANALYZER_INTERNAL_ERROR, summary.outcome());
        assertEquals(TestExecution.Status.NOT_TESTED, executionOf(summary, "LOGIN").status());
    }

    @Test
    void theInternalErrorCarriesTheSafeDiagnosticIntoTheJson() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-internal-detail");
        String fingerprint = "StaleElementReferenceException at GameCardInspector.resolve(163)";

        RunSummary summary = new RunSummaryFactory().internalError(
            paths, linksAndLoginRequest(), Instant.now(), fingerprint);
        new RunSummaryJsonWriter().write(paths, summary);
        String json = Files.readString(paths.summaryFile());

        RunError error = summary.errors().getFirst();
        assertEquals(fingerprint, error.diagnostic());
        assertTrue(error.hasDiagnostic());
        assertTrue(json.contains("\"diagnostic\": \"" + fingerprint + "\""), json);
        // Beside the user-facing wording, never instead of it.
        assertEquals("The analyzer encountered an internal error.", error.userMessage());
        assertEquals("Review analyzer logs and retry the run.", error.suggestedAction());
        assertTrue(json.contains("The analyzer encountered an internal error."));
    }

    @Test
    void aBlankDiagnosticIsOmittedAndOrdinaryRunErrorsNeverCarryOne() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-blank-detail");

        RunSummary blank = new RunSummaryFactory().internalError(
            paths, linksAndLoginRequest(), Instant.now(), "   ");
        new RunSummaryJsonWriter().write(paths, blank);
        assertFalse(Files.readString(paths.summaryFile()).contains("\"diagnostic\""),
            "a blank diagnostic is absent from the artifact, not an empty line");
        assertFalse(blank.errors().getFirst().hasDiagnostic());

        AutomationRunPaths other = runDirectoryWithEmptyEvidence("crawl-config-detail");
        RunSummary configuration = new RunSummaryFactory().fromCrawlResults(
            other, linksOnlyRequest(), Instant.now(),
            foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
                CrawlRunner.LoginOutcome.NOT_SELECTED, processedCrawl(1),
                List.of(pageWithLinks(outcome(CrawlerPageProcessor.Status.PASSED))),
                List.of(),
                new com.qanize.automation.checks.gamelaunch.GameLaunchModule.Result(
                    com.qanize.automation.checks.gamelaunch.GameLaunchModule.RunStatus
                        .RUN_SETUP_PROBLEM,
                    "The game list could not be restored after a launch.", List.of()))));
        new RunSummaryJsonWriter().write(other, configuration);
        assertFalse(Files.readString(other.summaryFile()).contains("\"diagnostic\""),
            "only an internal error populates it in this slice");
        for (RunError error : configuration.errors()) {
            assertFalse(error.hasDiagnostic(), error.code());
        }
    }

    @Test
    void noPlantedSecretFromAThrowableCanReachTheDiagnostic() throws IOException {
        // The fingerprint is produced by the CLI, which never reads a message. This is the other
        // end of that promise: the secrets are planted in a real Throwable and the whole pipeline
        // is checked, artifact included.
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-detail-secrets");
        RuntimeException failure = new IllegalStateException(
            "user=8123456789 token=abc123 cookie=SESSION at https://host.example/play?sig=deadbeef");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.qanize.automation.engine.CrawlRunner",
                "runWithin", "CrawlRunner.java", 298)
        });

        // The same reduction the CLI performs, reproduced here because that helper is
        // package-private: a class name and the one frame this project wrote.
        String fingerprint = failure.getClass().getSimpleName()
            + " at CrawlRunner.runWithin(" + failure.getStackTrace()[0].getLineNumber() + ")";
        RunSummary summary = new RunSummaryFactory().internalError(
            paths, linksAndLoginRequest(), Instant.now(), fingerprint);
        new RunSummaryJsonWriter().write(paths, summary);
        String json = Files.readString(paths.summaryFile());

        for (String planted : List.of(
            "8123456789", "abc123", "SESSION", "deadbeef", "host.example", "token=", "cookie=")) {
            assertFalse(json.contains(planted), "found " + planted + " in the artifact");
        }
        assertEquals("IllegalStateException at CrawlRunner.runWithin(298)",
            summary.errors().getFirst().diagnostic());
    }

    @Test
    void anErrorBuiltTheOldWayStillCompilesAndCarriesNoDiagnostic() {
        // The compatibility constructor: every unrelated call site keeps its five arguments.
        RunError legacy = new RunError(
            "RUN_CONFIGURATION", "SOME_CODE", "message", "action", Instant.now());

        assertEquals("", legacy.diagnostic());
        assertFalse(legacy.hasDiagnostic());
    }

    private static TestExecution executionOf(RunSummary summary, String testType) {
        return summary.testExecution().stream()
            .filter(execution -> execution.testType().equals(testType))
            .findFirst().orElseThrow(() -> new AssertionError(summary.testExecution().toString()));
    }

    @Test
    void aSignInTheWebsiteRejectedReportsWebsiteFindings() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-login-rejected");

        RunSummary summary = new RunSummaryFactory().fromCrawlResults(
            paths, linksAndLoginRequest(), Instant.now(),
            foldOfARunThatSelectedEverything(CrawlRunner.CrawlRunResult.signInFailed(
                "The configured sign-in did not succeed, so no page was crawled: rejected")));

        // The site was asked and refused. That is a fact about the site.
        assertEquals(RunOutcome.WEBSITE_FINDINGS, summary.outcome());
        assertEquals(RunLifecycle.COMPLETED, summary.lifecycle());
    }

    @Test
    void corruptEvidenceReportsAnalyzerInternalErrorAndSaysThatIsWhy() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-corrupt-evidence", "{not valid json");

        // The crawl itself was clean, so the only possible cause is the unreadable artifact.
        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        assertEquals(RunOutcome.ANALYZER_INTERNAL_ERROR, summary.outcome());
        assertTrue(summary.errors().stream()
            .anyMatch(e -> e.code().equals("EVIDENCE_RECORD_UNREADABLE")),
            "the reason must be the corruption, not a crawler failure: " + summary.errors());
        assertFalse(summary.errors().stream().anyMatch(e -> e.code().equals("ANALYZER_CHECK_ERROR")),
            "the crawl was clean; nothing crashed");
        assertTrue(summary.findings().isEmpty(), "a reporting problem is not a website defect");
    }

    // --- 8. wording never decides a classification -----------------------------------------------

    @Test
    void changingTheFailureWordingChangesNoClassification() throws IOException {
        List<String> wordings = List.of(
            "IllegalStateException: stale element reference",
            "Internal links failed: returned HTTP 404",
            "completely unrelated prose",
            "");

        int index = 0;
        for (String wording : wordings) {
            index++;
            assertEquals(RunOutcome.ANALYZER_INTERNAL_ERROR,
                summaryFor(runDirectoryWithEmptyEvidence("crawl-wording-crash-" + index),
                    crawledWith(crashed(wording))).outcome(),
                "a crash was reclassified by its wording: " + wording);
            assertEquals(RunOutcome.WEBSITE_FINDINGS,
                summaryFor(runDirectoryWithEmptyEvidence("crawl-wording-site-" + index),
                    crawledWith(websiteFailure(wording))).outcome(),
                "a website finding was reclassified by its wording: " + wording);
        }
    }

    // --- additional guards -------------------------------------------------------------------------

    @Test
    void theCrawlerPathNeitherAcceptsNorConsultsASurefireResult() throws NoSuchMethodException {
        // Asserted on the real signature rather than on source text: there is no parameter through
        // which a Surefire result could arrive, so zero tests cannot be misread as a broken run.
        java.lang.reflect.Method method = RunSummaryFactory.class.getMethod(
            "fromCrawlResults", AutomationRunPaths.class, AutomationRunRequest.class,
            Instant.class, CrawlRunSummary.class);

        assertEquals(4, method.getParameterCount());
        for (Class<?> parameter : method.getParameterTypes()) {
            assertFalse(TestRunSummary.class.isAssignableFrom(parameter),
                "crawler mode must not take a Surefire result: " + parameter);
        }
    }

    @Test
    void allSevenCheckExecutionsSurviveTheCrawlerPath() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-six-checks");

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        assertEquals(
            CheckId.productChecks().stream().map(CheckId::name).toList(),
            summary.testExecution().stream().map(TestExecution::testType).toList(),
            "the seven contract check types, in order, exactly once each");
        assertTrue(
            summary.testExecution().stream()
                .map(TestExecution::testType).toList().contains(CheckId.GAME_LAUNCH.name()),
            "Game Launch now executes and reports, so the summary names it like any other check");
        assertEquals(CheckId.GAME_LAUNCH.name(),
            summary.testExecution().getLast().testType(),
            "and it stays last, so the stable order never shifts");
        assertEquals(TestExecution.Status.PASSED, summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("LINKS"))
            .findFirst().orElseThrow().status());
        assertEquals(TestExecution.Status.NOT_TESTED, summary.testExecution().stream()
            .filter(execution -> execution.testType().equals("SEARCH"))
            .findFirst().orElseThrow().status());
    }

    @Test
    void pageScopedTestedItemIdsSurviveIntoTheSummary() throws IOException {
        // Two pages' worth of Links items, with the page-scoped ids the crawler writes.
        String jsonl =
            "{\"id\":\"page-001/link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
                + "\"name\":\"Pricing\",\"status\":\"PASSED\"}\n"
            + "{\"id\":\"page-002/link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
                + "\"name\":\"Pricing\",\"status\":\"PASSED\"}\n";
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-page-scoped-ids", jsonl);

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        assertEquals(List.of("page-001/link-1", "page-002/link-1"),
            summary.testedItems().stream().map(item -> item.id()).toList(),
            "the page scope must reach the summary intact, not be flattened back to link-1");
        assertEquals(RunOutcome.NO_FINDINGS, summary.outcome());
    }

    @Test
    void aFailedTestedItemBecomesAWebsiteFindingOnTheRightPage() throws IOException {
        String jsonl = "{\"id\":\"page-002/link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
            + "\"name\":\"Gone\",\"pageUrl\":\"https://example.com/about\","
            + "\"status\":\"FAILED\",\"message\":\"returned HTTP 404\"}\n";
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-failed-item", jsonl);

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        assertEquals(RunOutcome.WEBSITE_FINDINGS, summary.outcome());
        assertEquals(1, summary.findings().size());
        assertEquals("LINKS", summary.findings().getFirst().category());
        assertEquals("https://example.com/about", summary.findings().getFirst().pageUrl(),
            "a multi-page crawl points at the page that failed, not the submitted address");
    }

    // --- a link finding names the link ----------------------------------------------------------

    @Test
    void twoBrokenDestinationsProduceTwoFindingsThatCanBeToldApart() throws IOException {
        // The defect, from a real run: two links on the same page failed, and both findings read
        // "One or more links did not resolve successfully" with an actual of "Responded with
        // HTTP 401." — identical rows naming neither destination.
        String jsonl = "{\"id\":\"page-001/link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
            + "\"name\":\"Gone\",\"pageUrl\":\"https://example.com/\","
            + "\"target\":\"https://example.com/gone\",\"httpStatus\":404,"
            + "\"status\":\"FAILED\",\"message\":\"Responded with HTTP 404.\"}\n"
            + "{\"id\":\"page-001/link-2\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
            + "\"name\":\"Broken\",\"pageUrl\":\"https://example.com/\","
            + "\"target\":\"https://example.com/server-error\",\"httpStatus\":500,"
            + "\"status\":\"FAILED\",\"message\":\"Responded with HTTP 500.\"}\n";
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-two-link-findings", jsonl);

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        assertEquals(2, summary.findings().size());
        List<String> titles = summary.findings().stream().map(Finding::title).toList();
        assertEquals(2, Set.copyOf(titles).size(), "two destinations, two distinguishable titles: " + titles);
        assertTrue(titles.get(0).contains("https://example.com/gone"), titles.toString());
        assertTrue(titles.get(0).contains("404"), titles.toString());
        assertTrue(titles.get(1).contains("https://example.com/server-error"), titles.toString());
        assertTrue(titles.get(1).contains("500"), titles.toString());
    }

    @Test
    void aLinkFindingNamesTheSourceTheDestinationAndTheStatusSeparately() throws IOException {
        String jsonl = "{\"id\":\"page-002/link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
            + "\"name\":\"Gone\",\"pageUrl\":\"https://example.com/about\","
            + "\"target\":\"https://example.com/gone\",\"httpStatus\":404,"
            + "\"status\":\"FAILED\",\"message\":\"Responded with HTTP 404.\"}\n";
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-link-finding-detail", jsonl);

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        Finding finding = summary.findings().getFirst();
        // The page the link was found on is still the finding's page.
        assertEquals("https://example.com/about", finding.pageUrl());
        // And the status is attributed to the destination rather than to that page.
        assertTrue(finding.actual().contains("The link on https://example.com/about"), finding.actual());
        assertTrue(finding.actual().contains("to https://example.com/gone"), finding.actual());
        assertTrue(finding.actual().contains("HTTP 404"), finding.actual());
        assertEquals(finding.actual(), finding.whyItFailed());
        // The reproduction walks source, then destination, then the answer.
        assertEquals(3, finding.reproductionSteps().size());
        assertTrue(finding.reproductionSteps().get(0).contains("https://example.com/about"));
        assertTrue(finding.reproductionSteps().get(1).contains("https://example.com/gone"));
        assertTrue(finding.reproductionSteps().get(2).contains("404"));
    }

    @Test
    void aLinkThatCouldNotBeRequestedAtAllSaysSoRatherThanQuotingAStatus() throws IOException {
        String jsonl = "{\"id\":\"page-001/link-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\","
            + "\"name\":\"Slow\",\"pageUrl\":\"https://example.com/\","
            + "\"target\":\"https://example.com/slow\","
            + "\"status\":\"FAILED\",\"message\":\"The request could not be completed.\"}\n";
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-link-no-status", jsonl);

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        Finding finding = summary.findings().getFirst();
        assertTrue(finding.title().contains("could not be requested"), finding.title());
        assertTrue(finding.title().contains("https://example.com/slow"), finding.title());
        assertFalse(finding.actual().contains("HTTP null"), finding.actual());
    }

    @Test
    void aFindingForAnyOtherCheckKeepsTheWordingItAlwaysHad() throws IOException {
        // The change is scoped to one link at a time. A page-level failure is unchanged.
        String jsonl = "{\"id\":\"page-001/smoke-page-1\",\"testType\":\"SMOKE\",\"itemType\":\"PAGE\","
            + "\"name\":\"Submitted page\",\"pageUrl\":\"https://example.com/\","
            + "\"status\":\"FAILED\",\"message\":\"Page title should not be blank\"}\n";
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-smoke-finding", jsonl);

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        Finding finding = summary.findings().getFirst();
        assertEquals("The target page did not load as expected", finding.title());
        assertEquals("Page title should not be blank", finding.actual());
    }

    // --- the cap publishes evidence, and the counts describe the run ------------------------------

    @Test
    void aRunOfTwentyThreePassesAndFourSkipsPublishesTheSkipsAndCountsAllOfThem()
        throws IOException {
        // Run qanize-1790766054652-8e65e2e3's Links shape, as evidence records: 27 discovered, 23
        // requested and passing, 4 skipped -- two external, two requiring authentication. The
        // report published twenty passes and none of the four.
        StringBuilder jsonl = new StringBuilder();
        for (int index = 1; index <= 23; index++) {
            jsonl.append("{\"id\":\"link-").append(index)
                .append("\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",")
                .append("\"pageUrl\":\"https://site.test/\",\"status\":\"PASSED\",\"httpStatus\":200}\n");
        }
        jsonl.append("{\"id\":\"link-skipped-1\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",")
            .append("\"pageUrl\":\"https://site.test/\",\"status\":\"SKIPPED\",")
            .append("\"metadata.exclusion\":\"EXTERNAL\"}\n");
        jsonl.append("{\"id\":\"link-skipped-2\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",")
            .append("\"pageUrl\":\"https://site.test/\",\"status\":\"SKIPPED\",")
            .append("\"metadata.exclusion\":\"EXTERNAL\"}\n");
        jsonl.append("{\"id\":\"link-24\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",")
            .append("\"pageUrl\":\"https://site.test/\",\"status\":\"SKIPPED\",\"httpStatus\":401,")
            .append("\"message\":\"The destination requires authentication and answered HTTP 401.\",")
            .append("\"metadata.linkOutcome\":\"AUTH_REQUIRED\"}\n");
        jsonl.append("{\"id\":\"link-25\",\"testType\":\"LINKS\",\"itemType\":\"LINK\",")
            .append("\"pageUrl\":\"https://site.test/\",\"status\":\"SKIPPED\",\"httpStatus\":401,")
            .append("\"message\":\"The destination requires authentication and answered HTTP 401.\",")
            .append("\"metadata.linkOutcome\":\"AUTH_REQUIRED\"}\n");
        AutomationRunPaths paths = runDirectoryWithEvidence("crawl-cap-keeps-skips", jsonl.toString());

        RunSummary summary = summaryFor(paths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));

        TestExecution links = summary.testExecution().stream()
            .filter(execution -> "LINKS".equals(execution.testType()))
            .findFirst()
            .orElseThrow();
        // The counts describe the run and are taken from the complete list, not the capped one.
        assertEquals(27, links.itemsDiscovered());
        assertEquals(23, links.itemsTested());
        assertEquals(23, links.itemsPassed());
        assertEquals(0, links.itemsFailed());
        assertEquals(4, links.itemsSkipped());
        // And the published detail is still exactly the allowance, with the omission stated.
        assertEquals(20, links.itemsDetailed());
        assertEquals(7, links.itemsOmitted());
        assertEquals(20, summary.testedItems().size());

        // The evidence that had something to say survived.
        List<String> published = summary.testedItems().stream().map(TestedItem::id).toList();
        for (String id : List.of("link-skipped-1", "link-skipped-2", "link-24", "link-25")) {
            assertTrue(published.contains(id), id + " was dropped from the report: " + published);
        }
        assertEquals(4, summary.testedItems().stream()
            .filter(item -> item.status() == TestedItem.Status.SKIPPED).count());
        assertEquals(RunOutcome.NO_FINDINGS, summary.outcome(), "nothing here is a website finding");
    }

    @Test
    void theSummaryCarriesTheSameFieldSetAndTypesAsTheLegacyPath() throws IOException {
        AutomationRunPaths crawlPaths = runDirectoryWithEmptyEvidence("crawl-shape");
        AutomationRunPaths legacyPaths = runDirectoryWithEmptyEvidence("legacy-shape");

        RunSummary fromCrawl = summaryFor(crawlPaths,
            crawledWith(outcome(CrawlerPageProcessor.Status.PASSED)));
        RunSummary fromLegacy = new RunSummaryFactory().fromTestResults(
            legacyPaths, linksOnlyRequest(), Instant.now(),
            new TestRunSummary(1, 1, 0, 0, 0, TestRunSummary.OverallStatus.PASS, List.of(),
                List.of(new TestRunSummary.SuiteResult(
                    "com.qanize.automation.checks.links.GenericLinkValidationTest", 1, 0, 0, 0, ""))),
            0);

        // Both are assembled by the same private method, so the field set is identical by
        // construction. What is asserted here is that neither leaves a contract field unusable.
        assertEquals(fromLegacy.schemaVersion(), fromCrawl.schemaVersion());
        assertNotNull(fromCrawl.totals());
        assertNotNull(fromCrawl.startedAt());
        assertNotNull(fromCrawl.completedAt());
        assertEquals(fromLegacy.testExecution().size(), fromCrawl.testExecution().size());
        assertEquals(fromLegacy.artifacts().size(), fromCrawl.artifacts().size());
        for (RunSummary summary : List.of(fromCrawl, fromLegacy)) {
            assertNotNull(summary.findings());
            assertNotNull(summary.errors());
            assertNotNull(summary.evidence());
            assertNotNull(summary.testedItems());
            assertTrue(summary.durationMs() >= 0);
        }
    }

    @Test
    void noCredentialValueReachesTheSummary() throws IOException {
        String password = "synthetic-password-9f2b";
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("crawl-no-credential-leak");

        RunSummary summary = new RunSummaryFactory().fromCrawlResults(
            paths, linksAndLoginRequest(), Instant.now(),
            foldOfARunThatSelectedEverything(CrawlRunner.CrawlRunResult.credentialsUnavailable(
                "variables not set: QANIZE_USER, QANIZE_PASSWORD")));

        String rendered = summary.toString();
        assertFalse(rendered.contains(password), rendered);
        assertFalse(rendered.contains("synthetic-user"), rendered);
    }

    @Test
    void theLegacyPathStillClassifiesAZeroTestRunAsInfrastructure() throws IOException {
        AutomationRunPaths paths = runDirectoryWithEmptyEvidence("legacy-unchanged");

        RunSummary summary = new RunSummaryFactory().fromTestResults(
            paths, linksOnlyRequest(), Instant.now(),
            new TestRunSummary(0, 0, 0, 0, 0, TestRunSummary.OverallStatus.PASS, List.of(), List.of()),
            0);

        // Unchanged: for the Surefire path, no tests really is an infrastructure problem. That is
        // exactly why crawler mode needed its own method rather than a flag on this one.
        assertEquals(RunOutcome.INFRASTRUCTURE_ERROR, summary.outcome());
        assertEquals(RunLifecycle.FAILED, summary.lifecycle());
    }

    // --- Slice 7: typed screenshot ownership, proved through the real factory ------------------

    private static final String P1 = "https://example.com/one";
    private static final String P2 = "https://example.com/two";

    /** One failed tested item, carrying the page metadata the evidence scope publishes. */
    private static String failedItem(String id, CheckId check, int pageNumber, String pageUrl) {
        return "{\"id\":\"" + id + "\",\"testType\":\"" + check.name() + "\","
            + "\"itemType\":\"PAGE\",\"name\":\"item\",\"pageUrl\":\"" + pageUrl + "\","
            + "\"status\":\"FAILED\",\"message\":\"did not meet the expectation\","
            + "\"metadata.page.number\":\"" + pageNumber + "\"}\n";
    }

    /** A failed item with no page metadata at all, as the once-per-run sign-in records. */
    private static String failedRunLevelItem(String id, CheckId check) {
        return "{\"id\":\"" + id + "\",\"testType\":\"" + check.name() + "\","
            + "\"itemType\":\"PAGE\",\"name\":\"item\",\"pageUrl\":\"" + PAGE + "\","
            + "\"status\":\"FAILED\",\"message\":\"did not meet the expectation\"}\n";
    }

    private static AutomationRunPaths runDirectoryWith(
        String runId, String jsonl, String... screenshotFileNames
    ) throws IOException {
        AutomationRunPaths paths = runDirectoryWithEvidence(runId, jsonl);
        Files.createDirectories(paths.screenshotsDirectory());
        for (String fileName : screenshotFileNames) {
            Files.writeString(paths.screenshotsDirectory().resolve(fileName), "not-a-real-png");
        }
        return paths;
    }

    private static CrawlScreenshotRecorder.ScreenshotRef shot(
        int pageNumber, CrawlScreenshotRecorder.CheckTarget target, String fileName
    ) {
        return new CrawlScreenshotRecorder.ScreenshotRef(
            pageNumber, target, fileName, ScreenshotCaptureService.Stage.NONE, "");
    }

    private static CrawlRunSummary crawlWith(CrawlScreenshotRecorder.ScreenshotRef... references) {
        return foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED, processedCrawl(2),
            List.of(pageWithLinks(outcome(CrawlerPageProcessor.Status.PASSED))),
            List.of(references)));
    }

    private static String artifactIdFor(RunSummary summary, String fileName) {
        return summary.artifacts().stream()
            .filter(artifact -> "SCREENSHOT".equals(artifact.type()))
            .filter(artifact -> artifact.relativePath().endsWith(fileName))
            .map(ArtifactMetadata::id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no artifact collected for " + fileName));
    }

    private static Finding findingOn(RunSummary summary, CheckId check, String pageUrl) {
        return summary.findings().stream()
            .filter(finding -> finding.category().equals(check.name()))
            .filter(finding -> finding.pageUrl().equals(pageUrl))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no " + check + " finding on " + pageUrl + ": " + summary.findings()));
    }

    @Test
    void aScreenshotAttachesOnlyToItsOwnPage() throws IOException {
        String jsonl = failedItem("page-001/link-1", CheckId.LINKS, 1, P1)
            + failedItem("page-002/link-1", CheckId.LINKS, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-shot-per-page", jsonl,
            "page-001-links-failed.png", "page-002-links-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(1, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-001-links-failed.png"),
            shot(2, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-links-failed.png")));

        assertEquals(List.of(artifactIdFor(summary, "page-001-links-failed.png")),
            findingOn(summary, CheckId.LINKS, P1).evidence());
        assertEquals(List.of(artifactIdFor(summary, "page-002-links-failed.png")),
            findingOn(summary, CheckId.LINKS, P2).evidence(),
            "page 2's finding must not carry page 1's picture");
    }

    @Test
    void aScreenshotAttachesOnlyToItsOwnCheckOnThatPage() throws IOException {
        String jsonl = failedItem("page-002/link-1", CheckId.LINKS, 2, P2)
            + failedItem("page-002/search-1", CheckId.SEARCH, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-shot-per-check", jsonl,
            "page-002-search-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(2, CrawlScreenshotRecorder.CheckTarget.SEARCH, "page-002-search-failed.png")));

        assertEquals(List.of(), findingOn(summary, CheckId.LINKS, P2).evidence(),
            "a Search picture is not evidence about a link");
        assertEquals(List.of(artifactIdFor(summary, "page-002-search-failed.png")),
            findingOn(summary, CheckId.SEARCH, P2).evidence());
    }

    @Test
    void twoFailedChecksOnOnePageEachReceiveOnlyTheirOwnScreenshot() throws IOException {
        String jsonl = failedItem("page-002/link-1", CheckId.LINKS, 2, P2)
            + failedItem("page-002/search-1", CheckId.SEARCH, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-two-checks", jsonl,
            "page-002-links-failed.png", "page-002-search-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(2, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-links-failed.png"),
            shot(2, CrawlScreenshotRecorder.CheckTarget.SEARCH, "page-002-search-failed.png")));

        assertEquals(List.of(artifactIdFor(summary, "page-002-links-failed.png")),
            findingOn(summary, CheckId.LINKS, P2).evidence());
        assertEquals(List.of(artifactIdFor(summary, "page-002-search-failed.png")),
            findingOn(summary, CheckId.SEARCH, P2).evidence());
    }

    @Test
    void severalFindingsFromOnePageAndCheckShareThatOneScreenshot() throws IOException {
        String jsonl = failedItem("page-002/link-1", CheckId.LINKS, 2, P2)
            + failedItem("page-002/link-2", CheckId.LINKS, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-shared-shot", jsonl,
            "page-002-links-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(2, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-links-failed.png")));

        String artifactId = artifactIdFor(summary, "page-002-links-failed.png");
        assertEquals(2, summary.findings().size());
        for (Finding finding : summary.findings()) {
            assertEquals(List.of(artifactId), finding.evidence(),
                "one picture of one page's links is truthful evidence for every link finding on it");
        }
    }

    @Test
    void aCapturedScreenshotBecomesOneArtifactAndOneEvidenceRecord() throws IOException {
        String jsonl = failedItem("page-001/link-1", CheckId.LINKS, 1, P1);
        AutomationRunPaths paths = runDirectoryWith("crawl-one-artifact", jsonl,
            "page-001-links-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(1, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-001-links-failed.png")));

        assertEquals(1, summary.artifacts().stream()
            .filter(artifact -> "SCREENSHOT".equals(artifact.type())).count());
        assertEquals(1, summary.evidence().stream()
            .filter(evidence -> "SCREENSHOT".equals(evidence.type())).count());
        assertEquals(artifactIdFor(summary, "page-001-links-failed.png"),
            summary.evidence().get(0).artifactId());
    }

    @Test
    void aFailedCaptureWithNoFileAssociatesNothing() throws IOException {
        String jsonl = failedItem("page-001/link-1", CheckId.LINKS, 1, P1);
        // The capture failed, so no file exists and the reference names none.
        AutomationRunPaths paths = runDirectoryWith("crawl-failed-capture", jsonl);

        RunSummary summary = summaryFor(paths, crawlWith(new CrawlScreenshotRecorder.ScreenshotRef(
            1, CrawlScreenshotRecorder.CheckTarget.LINKS, null,
            ScreenshotCaptureService.Stage.CAPTURE_FAILED, "WebDriverException")));

        assertEquals(List.of(), findingOn(summary, CheckId.LINKS, P1).evidence());
        assertTrue(summary.artifacts().stream()
            .noneMatch(artifact -> "SCREENSHOT".equals(artifact.type())));
    }

    @Test
    void anUnrelatedScreenshotStaysAGeneralArtifactAndAttachesToNothing() throws IOException {
        String jsonl = failedItem("page-001/link-1", CheckId.LINKS, 1, P1);
        AutomationRunPaths paths = runDirectoryWith("crawl-unrelated-shot", jsonl,
            "something-else-entirely.png");

        RunSummary summary = summaryFor(paths, crawlWith());

        assertEquals(1, summary.artifacts().stream()
            .filter(artifact -> "SCREENSHOT".equals(artifact.type())).count(),
            "it is still collected: an artifact nobody owns is still an artifact");
        assertEquals(List.of(), findingOn(summary, CheckId.LINKS, P1).evidence());
    }

    @Test
    void aSingleFindingDoesNotInheritAnUnrelatedCrawlerScreenshot() throws IOException {
        String jsonl = failedItem("page-001/link-1", CheckId.LINKS, 1, P1);
        AutomationRunPaths paths = runDirectoryWith("crawl-no-fallback", jsonl,
            "page-002-search-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(2, CrawlScreenshotRecorder.CheckTarget.SEARCH, "page-002-search-failed.png")));

        // The legacy rule would attach every screenshot here, because there is exactly one
        // finding. For a crawl that is a false statement about a different page and check.
        assertEquals(1, summary.findings().size());
        assertEquals(List.of(), summary.findings().get(0).evidence(),
            "the single-finding fallback must not exist in crawler mode");
    }

    @Test
    void aLoginFailureScreenshotAttachesOnlyToTheLoginFinding() throws IOException {
        String jsonl = failedRunLevelItem("login-1", CheckId.LOGIN)
            + failedItem("page-001/link-1", CheckId.LINKS, 1, P1);
        AutomationRunPaths paths = runDirectoryWith("crawl-login-shot", jsonl, "login-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(shot(
            CrawlScreenshotRecorder.RUN_LEVEL,
            CrawlScreenshotRecorder.CheckTarget.LOGIN,
            "login-failed.png")));

        assertEquals(List.of(artifactIdFor(summary, "login-failed.png")),
            findingOn(summary, CheckId.LOGIN, PAGE).evidence());
        assertEquals(List.of(), findingOn(summary, CheckId.LINKS, P1).evidence(),
            "a sign-in picture says nothing about a link");
    }

    @Test
    void pageOpenAndUnexpectedScreenshotsRemainGeneralArtifacts() throws IOException {
        String jsonl = failedItem("page-002/navigation-1", CheckId.NAVIGATION, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-general-shots", jsonl,
            "page-002-open-failed.png", "crawler-unexpected-failure.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(2, CrawlScreenshotRecorder.CheckTarget.PAGE_OPEN, "page-002-open-failed.png"),
            shot(CrawlScreenshotRecorder.RUN_LEVEL,
                CrawlScreenshotRecorder.CheckTarget.UNEXPECTED,
                "crawler-unexpected-failure.png")));

        // Both are collected and both are reachable; neither is attached to a finding, because
        // neither has a check that truthfully owns it.
        assertEquals(2, summary.artifacts().stream()
            .filter(artifact -> "SCREENSHOT".equals(artifact.type())).count());
        assertEquals(List.of(), findingOn(summary, CheckId.NAVIGATION, P2).evidence(),
            "a page that would not open is not a Navigation finding's evidence by assumption");
    }

    @Test
    void aWindowsStyleSeparatorDoesNotDefeatTheExactFilenameLookup() throws IOException {
        String jsonl = failedItem("page-002/link-1", CheckId.LINKS, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-separators", jsonl,
            "page-002-links-failed.png");

        // A reference whose file name arrived with a Windows separator still resolves.
        RunSummary summary = summaryFor(paths, crawlWith(shot(
            2, CrawlScreenshotRecorder.CheckTarget.LINKS,
            "screenshots\\page-002-links-failed.png")));

        assertEquals(List.of(artifactIdFor(summary, "page-002-links-failed.png")),
            findingOn(summary, CheckId.LINKS, P2).evidence());
    }

    @Test
    void associationIsTypedOwnershipAndNotTheFileName() throws IOException {
        String jsonl = failedItem("page-001/link-1", CheckId.LINKS, 1, P1)
            + failedItem("page-002/search-1", CheckId.SEARCH, 2, P2);
        // The file is named as though it belonged to page 2's Search. The typed reference says
        // page 1's Links, and the typed reference is what decides.
        AutomationRunPaths paths = runDirectoryWith("crawl-typed-not-named", jsonl,
            "page-002-search-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(shot(
            1, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-search-failed.png")));

        assertEquals(List.of(artifactIdFor(summary, "page-002-search-failed.png")),
            findingOn(summary, CheckId.LINKS, P1).evidence(),
            "ownership came from the typed reference, not from the misleading name");
        assertEquals(List.of(), findingOn(summary, CheckId.SEARCH, P2).evidence(),
            "and the name alone attached it to nothing");
    }

    @Test
    void screenshotReferencesOnAResultAreImmutable() {
        List<CrawlScreenshotRecorder.ScreenshotRef> mutable = new java.util.ArrayList<>();
        mutable.add(shot(1, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-001-links-failed.png"));

        CrawlRunner.CrawlRunResult.Crawled crawled = new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED, processedCrawl(1),
            List.of(pageWithLinks(outcome(CrawlerPageProcessor.Status.PASSED))), mutable);
        CrawlRunner.CrawlRunResult.LoginPrevented prevented =
            new CrawlRunner.CrawlRunResult.LoginPrevented(
                CrawlRunner.LoginOutcome.FAILED,
                CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED, "refused", mutable);

        mutable.clear();
        assertEquals(1, crawled.screenshots().size(), "the result copied, it did not alias");
        assertEquals(1, prevented.screenshots().size());
        assertThrows(UnsupportedOperationException.class,
            () -> crawled.screenshots().add(crawled.screenshots().get(0)));
        assertThrows(UnsupportedOperationException.class,
            () -> prevented.screenshots().add(prevented.screenshots().get(0)));
        assertEquals(1, foldOfARunThatSelectedEverything(crawled).screenshots().size());
    }

    @Test
    void noRawAddressCredentialSelectorOrBrowserMessageReachesTheSummary() throws IOException {
        String jsonl = failedItem("page-002/link-1", CheckId.LINKS, 2, P2);
        AutomationRunPaths paths = runDirectoryWith("crawl-no-leak", jsonl,
            "page-002-links-failed.png");

        RunSummary summary = summaryFor(paths, crawlWith(
            shot(2, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-links-failed.png")));

        String artifactsAndEvidence = summary.artifacts() + "|" + summary.evidence() + "|"
            + summary.findings().stream().map(Finding::evidence).toList();
        for (String secret : List.of(
            "hunter2", "tok_live_", "alice.tester@example.test", "session=",
            "input[name=", "not interactable")) {
            assertFalse(artifactsAndEvidence.contains(secret),
                "a screenshot association leaked " + secret);
        }
    }

    @Test
    void theSerializedRunSummaryFieldSetIsUnchanged() {
        assertEquals(
            List.of("schemaVersion", "runId", "lifecycle", "outcome", "targetUrl", "startedAt",
                "completedAt", "durationMs", "totals", "findings", "errors", "evidence",
                "artifacts", "testExecution", "testedItems"),
            java.util.Arrays.stream(RunSummary.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList(),
            "this slice adds no field to the frontend contract");
        assertEquals(
            List.of("id", "type", "label", "mediaType", "relativePath"),
            java.util.Arrays.stream(ArtifactMetadata.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertTrue(java.util.Arrays.stream(Finding.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).toList().contains("evidence"));
    }

    @Test
    void theLegacyScreenshotRuleIsUnchangedIncludingItsSingleFindingFallback() throws IOException {
        // Matched by name, exactly as before.
        AutomationRunPaths named = runDirectoryWithEvidence("legacy-named", "");
        Files.createDirectories(named.screenshotsDirectory());
        Files.writeString(
            named.screenshotsDirectory().resolve("GenericLinkValidationTest-aBrokenLink-1.png"), "x");
        Files.writeString(named.screenshotsDirectory().resolve("unrelated-other.png"), "x");

        RunSummary matched = new RunSummaryFactory().fromTestResults(
            named, linksOnlyRequest(), Instant.now(),
            new TestRunSummary(2, 1, 1, 0, 0, TestRunSummary.OverallStatus.FAIL,
                List.of(
                    new TestRunSummary.FailedTestDetail(
                        "com.qanize.automation.checks.links.GenericLinkValidationTest",
                        "aBrokenLink", "404"),
                    new TestRunSummary.FailedTestDetail(
                        "com.qanize.automation.checks.search.GenericSearchTest",
                        "aMissingField", "no field"))),
            0);

        assertEquals(List.of(artifactIdFor(matched, "GenericLinkValidationTest-aBrokenLink-1.png")),
            matched.findings().get(0).evidence(),
            "the legacy substring rule still matches the test name inside the file name");

        // And with exactly one finding, the legacy fallback still attaches everything.
        AutomationRunPaths fallback = runDirectoryWithEvidence("legacy-fallback", "");
        Files.createDirectories(fallback.screenshotsDirectory());
        Files.writeString(fallback.screenshotsDirectory().resolve("nothing-matching.png"), "x");

        RunSummary single = new RunSummaryFactory().fromTestResults(
            fallback, linksOnlyRequest(), Instant.now(),
            new TestRunSummary(1, 0, 1, 0, 0, TestRunSummary.OverallStatus.FAIL,
                List.of(new TestRunSummary.FailedTestDetail(
                    "com.qanize.automation.checks.links.GenericLinkValidationTest",
                    "aBrokenLink", "404"))),
            0);

        assertEquals(List.of(artifactIdFor(single, "nothing-matching.png")),
            single.findings().get(0).evidence(),
            "legacy behavior is preserved exactly, fallback included");
    }

    /**
     * The fold, for a run that asked for every product check.
     *
     * <p>Which checks were selected is now stated rather than inferred, because a run stopped by
     * its sign-in leaves no trace of what it was asked to do. These cases are about the fold's
     * other behaviour, so they say "everything" and let the selection-specific tests carry that
     * question on their own.
     */
    private static CrawlRunSummary foldOfARunThatSelectedEverything(
        CrawlRunner.CrawlRunResult result
    ) {
        return CrawlRunSummary.from(result, Set.copyOf(CheckId.productChecks()));
    }

}
