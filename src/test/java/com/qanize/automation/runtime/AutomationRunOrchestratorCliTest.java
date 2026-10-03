package com.qanize.automation.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.browser.ScreenshotCaptureService;
import com.qanize.automation.browser.ScreenshotDirectoryResolver;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.engine.CrawlPolicy;
import com.qanize.automation.engine.CrawlScope;
import com.qanize.automation.engine.CrawlRunner;
import com.qanize.automation.engine.CrawlScreenshotRecorder;
import com.qanize.automation.engine.Crawler;
import com.qanize.automation.engine.CrawlerPageProcessor;
import com.qanize.automation.engine.PageAuditContext;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;
import com.qanize.automation.reporting.ExecutionTimelineJsonWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class AutomationRunOrchestratorCliTest {

    @Test
    void genericMavenCommandUsesOnlyTheGenericProfileAndRunDirectories() {
        AutomationRunPaths paths = AutomationRunPaths.forRunId("run-command-check");
        List<String> command = AutomationRunMavenCommand.genericTestCommand(paths);

        assertEquals(AutomationRunMavenCommand.mavenExecutable(), command.getFirst());
        assertTrue(command.contains("test"));
        assertTrue(command.contains("-Pgeneric"));
        assertTrue(command.stream().anyMatch(value -> value.contains("generated-run.properties")));
        assertTrue(command.stream().anyMatch(value -> value.contains("surefire-reports")));
        assertTrue(command.stream().anyMatch(value -> value.contains("screenshots")));
        assertEquals(1, command.stream().filter(value -> value.startsWith("-P")).count(),
            "the generic profile is the only profile the orchestrator may select: " + command);
    }

    @Test
    void invalidSearchWritesConfigurationOutcomeWithoutStartingMaven() throws IOException {
        String runId = uniqueRunId("invalid-search");
        RecordingRunner runner = new RecordingRunner(false);

        CliResult result = runCli(
            runner,
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--search=true",
            "--searchEnabled=true",
            "--searchQuery=qanize"
        );

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(2, result.exitCode());
        assertFalse(runner.called);
        assertTrue(Files.isRegularFile(paths.runConfigFile()));
        assertTrue(Files.isRegularFile(paths.progressFile()));
        assertTrue(Files.isRegularFile(paths.summaryFile()));
        String summary = Files.readString(paths.summaryFile());
        assertTrue(summary.contains("\"outcome\": \"RUN_CONFIGURATION_ERROR\""));
        assertTrue(summary.contains("\"code\": \"SEARCH_INPUT_SELECTOR_REQUIRED\""));
        // No top-level "status" in the v1.0 contract. testExecution[] has its own nested one.
        assertFalse(summary.contains("\n  \"status\":"));
        // A configuration error never reaches execution, so Search must not look like it ran.
        assertTrue(summary.contains("\"testType\": \"SEARCH\""));
        assertTrue(summary.contains("\"executed\": false"));
        assertFalse(summary.contains("\"status\": \"PASSED\""));
    }

    @Test
    void validSearchWritesParserCompatibleProgressAndSummary() throws IOException {
        String runId = uniqueRunId("valid-search");
        RecordingRunner runner = new RecordingRunner(true);

        CliResult result = runCli(
            runner,
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=false",
            "--navigation=false",
            "--links=false",
            "--forms=false",
            "--search=true",
            "--searchEnabled=true",
            "--searchInputSelector=input[type='search']",
            "--searchSubmitSelector=button[type='submit']",
            "--searchResultsSelector=#results",
            "--searchQuery=qanize"
        );

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(0, result.exitCode());
        assertTrue(runner.called);
        String config = Files.readString(paths.runConfigFile());
        String progress = Files.readString(paths.progressFile());
        String summary = Files.readString(paths.summaryFile());
        assertTrue(config.contains("\"checks\": {"));
        assertTrue(config.contains("\"viewport\": {\"preset\": \"desktop\""));
        assertTrue(progress.contains("\"state\": \"COMPLETED\""));
        assertTrue(progress.contains("\"completedChecks\": 1"));
        assertTrue(progress.contains("\"totalChecks\": 1"));
        assertTrue(summary.contains("\"outcome\": \"NO_FINDINGS\""));
        assertTrue(summary.contains("\"completedAt\":"));
        assertFalse(summary.contains("\"finishedAt\":"));
    }

    /**
     * QANIZE validates the request and writes run-config.json before starting this process.
     * That copy is authoritative and must survive the run untouched: a second serializer writing
     * the same artifact is how the configuration the user sees drifts from the one that ran.
     */
    @Test
    void authoritativeRunConfigurationFromQanizeIsNotOverwritten() throws IOException {
        String runId = uniqueRunId("preserve-config");
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        Files.createDirectories(paths.runDirectory());
        String authoritativeConfig = """
            {
              "schemaVersion": "1.0",
              "qanizeAuthoritativeMarker": "written-by-qanize-before-java-started"
            }
            """;
        Files.writeString(paths.runConfigFile(), authoritativeConfig);

        CliResult result = runCli(
            new RecordingRunner(true),
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=false",
            "--navigation=false",
            "--links=false",
            "--forms=false",
            "--search=true",
            "--searchEnabled=true",
            "--searchInputSelector=input[type='search']",
            "--searchQuery=qanize"
        );

        assertEquals(0, result.exitCode());
        assertEquals(authoritativeConfig, Files.readString(paths.runConfigFile()));
        // The derived properties adapter is still generated next to it.
        assertTrue(Files.isRegularFile(paths.propertiesConfigFile()));
    }

    /** With no configuration present, a direct command-line run still writes one. */
    @Test
    void directCommandLineRunStillWritesTheRunConfiguration() throws IOException {
        String runId = uniqueRunId("writes-config");

        CliResult result = runCli(
            new RecordingRunner(true),
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=true",
            "--navigation=false",
            "--links=false",
            "--forms=false",
            "--search=false",
            "--searchEnabled=false"
        );

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(paths.runConfigFile()));
        assertTrue(Files.readString(paths.runConfigFile()).contains("\"schemaVersion\": \"1.0\""));
    }

    @Test
    void mavenStartFailureBecomesInfrastructureError() throws IOException {
        String runId = uniqueRunId("infrastructure-failure");

        CliResult result = runCli(
            command -> {
                throw new IOException("Maven executable was unavailable");
            },
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=true",
            "--navigation=false",
            "--links=false",
            "--forms=false",
            "--search=false",
            "--searchEnabled=false",
            "--login=false",
            "--loginEnabled=false"
        );

        String summary = Files.readString(
            AutomationRunPaths.forRunId(runId).summaryFile()
        );
        assertEquals(3, result.exitCode());
        assertTrue(summary.contains("\"outcome\": \"INFRASTRUCTURE_ERROR\""));
        assertTrue(summary.contains("\"category\": \"INFRASTRUCTURE\""));
        assertTrue(summary.contains("\"code\": \"MAVEN_PROCESS_START_FAILED\""));
        assertTrue(summary.contains("\"findings\": []"));
    }

    @Test
    void aCompletedRunAlsoWritesTheAdditiveExecutionTimeline() throws IOException {
        String runId = uniqueRunId("timeline");
        EvidenceProducingRunner runner = new EvidenceProducingRunner();

        CliResult result = runCli(
            runner,
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=true",
            "--navigation=false",
            "--links=false",
            "--forms=false"
        );

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        Path timeline = paths.resolveArtifact(ExecutionTimelineJsonWriter.EXECUTION_TIMELINE_FILE_NAME);
        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(timeline), "A completed run must produce the timeline artifact");

        String json = Files.readString(timeline);
        assertTrue(json.contains("\"schemaVersion\": \"1.1\""));
        assertTrue(json.contains("\"runId\": \"" + runId + "\""));
        assertTrue(json.contains("\"sourcePresent\": true"));
        assertTrue(json.contains("\"checkId\": \"SMOKE\""));
        assertTrue(json.contains("\"testCaseId\": \"smoke-page-1\""));
    }

    @Test
    void theTimelineIsAdditiveAndLeavesTheContractOneZeroSummaryAlone() throws IOException {
        String runId = uniqueRunId("additive");
        CliResult result = runCli(
            new EvidenceProducingRunner(),
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=true",
            "--navigation=false",
            "--links=false",
            "--forms=false"
        );

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        String summary = Files.readString(paths.summaryFile());
        assertEquals(0, result.exitCode());
        // The summary is still contract 1.0 and still describes only what it always described.
        assertTrue(summary.contains("\"schemaVersion\": \"1.0\""));
        assertFalse(summary.contains("execution-timeline"),
            "The timeline must not appear in the 1.0 manifest in this slice");
        assertFalse(summary.contains("executionTimeline"));
        assertFalse(summary.contains("\"steps\""), "Steps must not leak into the 1.0 summary");
        // The raw evidence the summary is built from is untouched by the projection.
        assertTrue(Files.isRegularFile(paths.resolveArtifact("execution-evidence.jsonl")));
    }

    @Test
    void aTimelineFailureCannotChangeTheAnalyzerResult() throws IOException {
        String runId = uniqueRunId("timeline-failure");
        // A directory where the artifact file belongs: the timeline cannot be written, and the
        // run must still report exactly the result it would otherwise have reported.
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        Files.createDirectories(
            paths.resolveArtifact(ExecutionTimelineJsonWriter.EXECUTION_TIMELINE_FILE_NAME));

        CliResult result = runCli(
            new EvidenceProducingRunner(),
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=true",
            "--navigation=false",
            "--links=false",
            "--forms=false"
        );

        String summary = Files.readString(paths.summaryFile());
        assertEquals(0, result.exitCode(), "A reporting problem must not change the exit code");
        assertFalse(summary.contains("ANALYZER_INTERNAL_ERROR"),
            "A timeline failure must never be reported as an analyzer error");
        assertTrue(result.out().contains("Outcome: NO_FINDINGS"));
    }

    private static CliResult runCli(AutomationRunOrchestratorCli.MavenRunner runner, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = AutomationRunOrchestratorCli.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            runner
        );
        return new CliResult(
            exitCode,
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8)
        );
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String uniqueRunId(String label) {
        return "test-" + label + "-" + System.nanoTime();
    }

    private static Path reportsDirectoryFrom(List<String> command) {
        String prefix = "-Dsurefire.reportsDirectory=";
        return command.stream()
            .filter(value -> value.startsWith(prefix))
            .map(value -> Path.of(value.substring(prefix.length())))
            .findFirst()
            .orElseThrow();
    }

    private record CliResult(int exitCode, String out, String err) {
    }

    /**
     * Stands in for the Maven child process: writes the Surefire report the summary is built from
     * and the execution evidence the timeline is projected from, then returns as the real process
     * would after exiting. Separate from {@link RecordingRunner} so the existing tests keep the
     * exact runner they were written against.
     */
    private static final class EvidenceProducingRunner implements AutomationRunOrchestratorCli.MavenRunner {
        @Override
        public int run(List<String> command) throws IOException {
            Path reports = reportsDirectoryFrom(command);
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("TEST-generic.xml"), """
                <testsuite name="generic" tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="GenericSmokeTest" name="loads"/>
                </testsuite>
                """);
            Path evidence = evidenceFileFrom(command);
            Files.createDirectories(evidence.getParent());
            ExecutionStep step = new ExecutionStep(
                1, Phase.ACTION, ExecutionMethod.NAVIGATE, "opened the start page",
                null, null, "the page loads", "the page loaded",
                ExecutionStep.Status.PASSED, 120, null);
            Files.writeString(
                evidence.resolveSibling(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME),
                ExecutionEvidenceRecorder.toJsonLine("SMOKE", "smoke-page-1", step, null)
                    + System.lineSeparator());
            return 0;
        }
    }

    private static Path evidenceFileFrom(List<String> command) {
        String prefix = "-Dqanize.evidenceFile=";
        return command.stream()
            .filter(value -> value.startsWith(prefix))
            .map(value -> Path.of(value.substring(prefix.length())))
            .findFirst()
            .orElseThrow();
    }


    // --- Slice 8: the crawler branch, proved through the real CLI ------------------------------

    private static final String CRAWL_PAGE = "https://example.com/one";

    /**
     * Every argument the crawler cases share, so a case shows only what it is actually about.
     *
     * <p>Login off and one check selected, which keeps the totals small enough to assert exactly.
     */
    private static String[] crawlerArgs(String runId, String siteUrl, String... extra) {
        List<String> args = new java.util.ArrayList<>(List.of(
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=" + siteUrl,
            "--viewportPreset=desktop",
            "--smoke=false",
            "--navigation=false",
            "--links=true",
            "--forms=false",
            "--search=false",
            "--searchEnabled=false",
            "--login=false",
            "--loginEnabled=false"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    private static CliResult runCli(
        AutomationRunOrchestratorCli.MavenRunner maven,
        CrawlerRunCoordinator.CrawlExecution crawl,
        String... args
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = AutomationRunOrchestratorCli.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            maven,
            new CrawlerRunCoordinator(crawl));
        return new CliResult(
            exitCode,
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Stands in for the crawl: records exactly what it was handed and what the process properties
     * were at the moment it ran, writes whatever evidence the case needs, and returns a typed
     * result. No browser, no network, no Maven.
     */
    private static final class RecordingCrawl implements CrawlerRunCoordinator.CrawlExecution {

        private final java.util.function.Function<WebsiteConfig, CrawlRunner.CrawlRunResult> behaviour;
        private final List<String> baseUrls = new java.util.ArrayList<>();
        private final List<String> evidenceProperty = new java.util.ArrayList<>();
        private final List<String> screenshotsProperty = new java.util.ArrayList<>();
        private final List<CrawlScope> scopes = new java.util.ArrayList<>();
        private int calls;

        private RecordingCrawl(
            java.util.function.Function<WebsiteConfig, CrawlRunner.CrawlRunResult> behaviour
        ) {
            this.behaviour = behaviour;
        }

        @Override
        public CrawlRunner.CrawlRunResult run(WebsiteConfig config, CrawlScope scope) {
            calls++;
            scopes.add(scope);
            baseUrls.add(config.baseUrl().toString());
            evidenceProperty.add(System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
            screenshotsProperty.add(
                System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
            return behaviour.apply(config);
        }
    }

    /** Counts Maven invocations without ever starting a process. */
    private static final class CountingMaven implements AutomationRunOrchestratorCli.MavenRunner {
        private int calls;

        @Override
        public int run(List<String> command) throws IOException {
            calls++;
            Path reports = reportsDirectoryFrom(command);
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("TEST-generic.xml"), """
                <testsuite name="generic" tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="GenericLinksTest" name="checks"/>
                </testsuite>
                """);
            return 0;
        }
    }

    /** Writes one evidence line to wherever the installed property currently points. */
    private static void writeEvidenceThroughTheInstalledProperty(String jsonl) {
        try {
            Path evidence = Path.of(System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, jsonl);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** Writes one screenshot file to wherever the installed property currently points. */
    private static void writeScreenshotThroughTheInstalledProperty(String fileName) {
        try {
            Path directory = Path.of(
                System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(fileName), "not-a-real-png");
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static String failedLinksEvidence(int pageNumber, String pageUrl) {
        return "{\"id\":\"page-00" + pageNumber + "/link-1\",\"testType\":\"LINKS\","
            + "\"itemType\":\"PAGE\",\"name\":\"a link\",\"pageUrl\":\"" + pageUrl + "\","
            + "\"status\":\"FAILED\",\"message\":\"the link did not resolve\","
            + "\"metadata.page.number\":\"" + pageNumber + "\"}\n";
    }

    private static CrawlerPageProcessor.ModuleOutcome outcome(CrawlerPageProcessor.Status status) {
        return new CrawlerPageProcessor.ModuleOutcome(status, null);
    }

    private static CrawlerPageProcessor.PageAuditResult pageWithLinks(
        CrawlerPageProcessor.ModuleOutcome links
    ) {
        return new CrawlerPageProcessor.PageAuditResult(
            new PageAuditContext(1, CRAWL_PAGE, CRAWL_PAGE, 0, null),
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
            attempted.add(new Crawler.PageResult(CRAWL_PAGE + index, 0, null,
                Crawler.PageStatus.PROCESSED, null, null));
        }
        return new Crawler.CrawlResult(attempted, new Crawler.LimitReport(10, 3, false, false, 0));
    }

    private static CrawlRunner.CrawlRunResult crawled(
        CrawlerPageProcessor.ModuleOutcome links,
        CrawlScreenshotRecorder.ScreenshotRef... screenshots
    ) {
        return new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED, processedCrawl(1),
            List.of(pageWithLinks(links)), List.of(screenshots));
    }

    // --- 1, 2, 3, 4, 26. exactly one path, and never both -------------------------------------

    @Test
    void aRequestWithoutTheCrawlerArgumentRunsMavenOnceAndTheCrawlerNever() {
        CountingMaven maven = new CountingMaven();
        RecordingCrawl crawl = new RecordingCrawl(config -> {
            throw new AssertionError("the crawler must not run for a legacy request");
        });

        CliResult result = runCli(maven, crawl,
            crawlerArgs(uniqueRunId("legacy-absent"), "https://example.com/"));

        assertEquals(0, result.exitCode());
        assertEquals(1, maven.calls, "the legacy path runs Maven exactly once");
        assertEquals(0, crawl.calls, "and never reaches the crawler");
    }

    @Test
    void anExplicitlyDisabledCrawlerRunsMavenOnceAndTheCrawlerNever() {
        CountingMaven maven = new CountingMaven();
        RecordingCrawl crawl = new RecordingCrawl(config -> {
            throw new AssertionError("the crawler must not run when it is switched off");
        });

        CliResult result = runCli(maven, crawl,
            crawlerArgs(uniqueRunId("legacy-false"), "https://example.com/", "--crawler=false"));

        assertEquals(0, result.exitCode());
        assertEquals(1, maven.calls);
        assertEquals(0, crawl.calls);
    }

    @Test
    void anEnabledCrawlerRunsTheCrawlerOnceAndMavenNever() throws IOException {
        String runId = uniqueRunId("crawler-only");
        CountingMaven maven = new CountingMaven();
        RecordingCrawl crawl = new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        });

        CliResult result = runCli(maven, crawl,
            crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(0, result.exitCode());
        assertEquals(1, crawl.calls, "the crawler runs exactly once");
        assertEquals(0, maven.calls, "and no Maven process is ever started");
        assertTrue(Files.isRegularFile(paths.summaryFile()));
    }

    /**
     * The mutual exclusion itself, stated as one property over every shape of the argument.
     *
     * <p>Written as a loop rather than three separate cases because the claim is about the whole
     * argument space, not about three points in it: whatever the request says, the two counters
     * must add up to one.
     */
    @Test
    void everyRequestRunsExactlyOnePathAndNeverBoth() throws IOException {
        for (String[] extra : List.of(
            new String[] {},
            new String[] {"--crawler=false"},
            new String[] {"--crawler=true"},
            new String[] {"--crawler=TRUE"}
        )) {
            CountingMaven maven = new CountingMaven();
            RecordingCrawl crawl = new RecordingCrawl(config -> {
                writeEvidenceThroughTheInstalledProperty("");
                return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
            });

            runCli(maven, crawl,
                crawlerArgs(uniqueRunId("exclusive"), "https://example.com/", extra));

            assertEquals(1, maven.calls + crawl.calls,
                "exactly one execution path per request, for " + List.of(extra));
            assertTrue(maven.calls == 0 || crawl.calls == 0,
                "the two paths are never both taken, for " + List.of(extra));
        }
    }

    // --- 5. the legacy command is untouched ---------------------------------------------------

    @Test
    void theLegacyMavenCommandIsStillExactlyWhatItWas() {
        AutomationRunPaths paths = AutomationRunPaths.forRunId("run-command-unchanged");

        assertEquals(
            List.of(
                AutomationRunMavenCommand.mavenExecutable(),
                "test",
                "-Pgeneric",
                "-DsiteConfig=" + paths.configFile(),
                "-Dsurefire.reportsDirectory=" + paths.surefireReportsDirectory(),
                "-Dqanize.screenshotsDir=" + paths.screenshotsDirectory(),
                "-Dqanize.evidenceFile=" + paths.evidenceFile()),
            AutomationRunMavenCommand.genericTestCommand(paths),
            "the forked command is a frozen wire format: this slice may not add a token to it");
    }

    // --- 6. the crawl budget is the verified default, and is not configurable from a request ---

    @Test
    void theCrawlerPathUsesTheDefaultCrawlBudgetAndNothingElse() throws IOException {
        assertEquals(new CrawlPolicy(10, 3), CrawlPolicy.defaults(),
            "the verified default budget this slice is allowed to run");

        // Neither the CLI nor the coordinator may name a budget at all: a policy constructed
        // there would be a second source of truth, and a request-supplied one would let a caller
        // ask this analyzer to crawl further than anything that has been verified.
        for (String file : List.of("AutomationRunOrchestratorCli", "CrawlerRunCoordinator")) {
            String code = code(Files.readString(
                Path.of("src/main/java/com/qanize/automation/runtime/" + file + ".java")));
            assertFalse(code.contains("new CrawlPolicy("), file + " must not construct a budget");
            assertFalse(code.contains("CrawlPolicy"), file + " must not mention a budget at all");
        }
        String runner = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlRunner.java")));
        assertEquals(1, count(runner, "CrawlPolicy.defaults()"),
            "the one budget the crawl uses, chosen in the runner and nowhere else");
        assertEquals(0, count(runner, "request."),
            "and never taken from a request");
    }

    // --- 7, 8. the configuration is this run's, and cannot be inherited ------------------------

    @Test
    void theCrawlerReceivesTheConfigurationGeneratedForThisExactRequest() throws IOException {
        RecordingCrawl crawl = new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        });

        runCli(new CountingMaven(), crawl, crawlerArgs(
            uniqueRunId("config-exact"), "https://first.example.com/", "--crawler=true"));

        assertEquals(List.of("https://first.example.com/"), crawl.baseUrls,
            "the crawl is handed the site this request named, read from this run's own file");
    }

    @Test
    void asecondRunCannotInheritTheFirstRunsConfiguration() throws IOException {
        RecordingCrawl first = new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        });
        RecordingCrawl second = new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        });

        runCli(new CountingMaven(), first, crawlerArgs(
            uniqueRunId("config-a"), "https://first.example.com/", "--crawler=true"));
        runCli(new CountingMaven(), second, crawlerArgs(
            uniqueRunId("config-b"), "https://second.example.com/", "--crawler=true"));

        assertEquals(List.of("https://first.example.com/"), first.baseUrls);
        assertEquals(List.of("https://second.example.com/"), second.baseUrls,
            "the second run reads its own generated file, never whatever ran before it");
    }

    // --- 9, 10, 11, 12, 14. the artifact-property lifecycle -----------------------------------

    @Test
    void artifactPropertiesAreInstalledBeforeTheCrawlerWritesAnything() throws IOException {
        String runId = uniqueRunId("properties-installed");
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        RecordingCrawl crawl = new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty(failedLinksEvidence(1, CRAWL_PAGE));
            writeScreenshotThroughTheInstalledProperty("page-001-links-failed.png");
            return crawled(outcome(CrawlerPageProcessor.Status.FAILED));
        });

        runCli(new CountingMaven(), crawl,
            crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        assertEquals(
            List.of(paths.evidenceFile().toAbsolutePath().normalize().toString()),
            crawl.evidenceProperty,
            "the evidence destination was already this run's when the crawl began");
        assertEquals(
            List.of(paths.screenshotsDirectory().toAbsolutePath().normalize().toString()),
            crawl.screenshotsProperty,
            "and so was the screenshot destination");
        assertTrue(Files.isRegularFile(paths.evidenceFile()),
            "so the evidence landed inside this run's directory");
        assertTrue(Files.isRegularFile(
            paths.screenshotsDirectory().resolve("page-001-links-failed.png")));
    }

    @Test
    void artifactPropertiesAreRestoredAfterASuccessfulCrawlerRun() throws IOException {
        String evidenceBefore = System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        String screenshotsBefore =
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        }), crawlerArgs(uniqueRunId("restore-success"), "https://example.com/", "--crawler=true"));

        assertEquals(evidenceBefore, System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
        assertEquals(screenshotsBefore,
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
    }

    @Test
    void artifactPropertiesAreRestoredAfterAPreventedSignIn() throws IOException {
        String evidenceBefore = System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        String screenshotsBefore =
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return new CrawlRunner.CrawlRunResult.LoginPrevented(
                CrawlRunner.LoginOutcome.FAILED,
                CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED,
                "the configured sign-in did not succeed");
        }), crawlerArgs(uniqueRunId("restore-prevented"), "https://example.com/", "--crawler=true"));

        assertEquals(evidenceBefore, System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
        assertEquals(screenshotsBefore,
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
    }

    @Test
    void artifactPropertiesAreRestoredAfterACrawlThatThrew() throws IOException {
        String runId = uniqueRunId("restore-crash");
        String evidenceBefore = System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        String screenshotsBefore =
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);

        CliResult result = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            throw new IllegalStateException("the browser went away");
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(3, result.exitCode());
        assertEquals(evidenceBefore, System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
        assertEquals(screenshotsBefore,
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
        // The existing failure policy, unchanged: a crash is the analyzer's, and it is terminal.
        assertTrue(Files.readString(paths.summaryFile()).contains("\"outcome\": \"ANALYZER_INTERNAL_ERROR\""));
        assertTrue(Files.readString(paths.progressFile()).contains("\"state\": \"FAILED\""));
    }

    @Test
    void twoCrawlerRunsCannotMixEvidenceExecutionStepsOrScreenshots() throws IOException {
        String firstRunId = uniqueRunId("isolation-a");
        String secondRunId = uniqueRunId("isolation-b");

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty(failedLinksEvidence(1, CRAWL_PAGE));
            writeScreenshotThroughTheInstalledProperty("page-001-links-failed.png");
            return crawled(outcome(CrawlerPageProcessor.Status.FAILED));
        }), crawlerArgs(firstRunId, "https://first.example.com/", "--crawler=true"));

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        }), crawlerArgs(secondRunId, "https://second.example.com/", "--crawler=true"));

        AutomationRunPaths first = AutomationRunPaths.forRunId(firstRunId);
        AutomationRunPaths second = AutomationRunPaths.forRunId(secondRunId);
        assertTrue(Files.isRegularFile(
            first.screenshotsDirectory().resolve("page-001-links-failed.png")));
        assertFalse(Files.exists(
            second.screenshotsDirectory().resolve("page-001-links-failed.png")),
            "run B must not contain a single artifact produced by run A");
        assertEquals("", Files.readString(second.evidenceFile()));
        String secondSummary = Files.readString(second.summaryFile());
        assertTrue(secondSummary.contains("\"outcome\": \"NO_FINDINGS\""));
        assertFalse(secondSummary.contains("page-001-links-failed.png"));
        assertFalse(secondSummary.contains("first.example.com"));
    }

    // --- 15, 16, 17, 18. the four typed outcomes, written by the real CLI ----------------------

    @Test
    void aCleanCrawlWritesNoFindings() throws IOException {
        String runId = uniqueRunId("outcome-clean");

        CliResult result = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(0, result.exitCode());
        assertTrue(Files.readString(paths.summaryFile()).contains("\"outcome\": \"NO_FINDINGS\""));
        assertTrue(Files.readString(paths.progressFile()).contains("\"state\": \"COMPLETED\""));
    }

    @Test
    void aWebsiteFailureWritesWebsiteFindings() throws IOException {
        String runId = uniqueRunId("outcome-website");

        CliResult result = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty(failedLinksEvidence(1, CRAWL_PAGE));
            return crawled(new CrawlerPageProcessor.ModuleOutcome(
                CrawlerPageProcessor.Status.FAILED, "a link did not resolve",
                CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT));
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        String summary = Files.readString(AutomationRunPaths.forRunId(runId).summaryFile());
        assertEquals(0, result.exitCode(), "a website finding is a completed analysis");
        assertTrue(summary.contains("\"outcome\": \"WEBSITE_FINDINGS\""));
    }

    @Test
    void anAnalyzerCrashWritesAnalyzerInternalError() throws IOException {
        String runId = uniqueRunId("outcome-analyzer");

        CliResult result = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(new CrawlerPageProcessor.ModuleOutcome(
                CrawlerPageProcessor.Status.FAILED, "the module threw",
                CrawlerPageProcessor.FailureOrigin.ANALYZER_CRASH));
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertEquals(3, result.exitCode());
        assertTrue(Files.readString(paths.summaryFile())
            .contains("\"outcome\": \"ANALYZER_INTERNAL_ERROR\""));
        assertTrue(Files.readString(paths.progressFile()).contains("\"state\": \"FAILED\""));
    }

    @Test
    void unavailableCredentialsWriteARunConfigurationError() throws IOException {
        String runId = uniqueRunId("outcome-credentials");

        CliResult result = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return new CrawlRunner.CrawlRunResult.LoginPrevented(
                CrawlRunner.LoginOutcome.FAILED,
                CrawlRunner.LoginPreventedCause.CREDENTIALS_UNAVAILABLE,
                "Login is enabled but the configured credential environment variables are not set");
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        String summary = Files.readString(paths.summaryFile());
        assertEquals(3, result.exitCode());
        assertTrue(summary.contains("\"outcome\": \"RUN_CONFIGURATION_ERROR\""));
        assertTrue(summary.contains("\"code\": \"LOGIN_CREDENTIALS_UNAVAILABLE\""));
        // A prevented sign-in is a completed typed result, not a crash: it still reports fully.
        assertTrue(Files.readString(paths.progressFile()).contains("\"state\": \"FAILED\""));
        assertTrue(result.out().contains("Outcome: RUN_CONFIGURATION_ERROR"));
    }

    // --- 19, 20, 21. what the crawl recorded survives the CLI ----------------------------------

    @Test
    void pageScopedIdsAndScreenshotArtifactsSurviveTheCli() throws IOException {
        String runId = uniqueRunId("artifacts-survive");

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty(failedLinksEvidence(2, CRAWL_PAGE));
            writeScreenshotThroughTheInstalledProperty("page-002-links-failed.png");
            return crawled(
                new CrawlerPageProcessor.ModuleOutcome(
                    CrawlerPageProcessor.Status.FAILED, "a link did not resolve",
                    CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT),
                new CrawlScreenshotRecorder.ScreenshotRef(
                    2, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-links-failed.png",
                    ScreenshotCaptureService.Stage.NONE, ""));
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        String summary = Files.readString(AutomationRunPaths.forRunId(runId).summaryFile());
        assertTrue(summary.contains("page-002/link-1"),
            "the page-scoped tested-item id reaches the summary unchanged");
        assertTrue(summary.contains("screenshots/page-002-links-failed.png"),
            "and the screenshot is collected as an artifact");
        assertTrue(summary.contains("\"type\": \"SCREENSHOT\""));
    }

    @Test
    void aScreenshotAttachesOnlyToTheFindingItWasTakenFor() throws IOException {
        String runId = uniqueRunId("association");

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty(
                failedLinksEvidence(1, "https://example.com/one")
                    + failedLinksEvidence(2, "https://example.com/two"));
            writeScreenshotThroughTheInstalledProperty("page-002-links-failed.png");
            return crawled(
                new CrawlerPageProcessor.ModuleOutcome(
                    CrawlerPageProcessor.Status.FAILED, "a link did not resolve",
                    CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT),
                new CrawlScreenshotRecorder.ScreenshotRef(
                    2, CrawlScreenshotRecorder.CheckTarget.LINKS, "page-002-links-failed.png",
                    ScreenshotCaptureService.Stage.NONE, ""));
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        String summary = Files.readString(AutomationRunPaths.forRunId(runId).summaryFile());
        // Two findings, one picture. Exactly one finding may carry it, and it must be the one
        // from page 2: the Slice 7 association has to survive the CLI without weakening.
        assertEquals(1, count(summary, "\"evidence\": [\"screenshot-001\"]"),
            "exactly one finding carries the picture: " + summary);
        assertEquals(1, count(summary, "\"evidence\": []"),
            "and the other carries nothing at all: " + summary);

        int pageOne = summary.indexOf("\"pageUrl\": \"https://example.com/one\"");
        int pageTwo = summary.indexOf("\"pageUrl\": \"https://example.com/two\"");
        int carriesNothing = summary.indexOf("\"evidence\": []");
        int carriesThePicture = summary.indexOf("\"evidence\": [\"screenshot-001\"]");
        assertTrue(pageOne >= 0 && pageTwo > pageOne, summary);
        assertTrue(pageOne < carriesNothing && carriesNothing < pageTwo,
            "the page-1 finding is the one with no evidence: " + summary);
        assertTrue(pageTwo < carriesThePicture,
            "and the page-2 finding is the one that carries it: " + summary);
        // The picture is still a general artifact as well, exactly once.
        assertTrue(summary.contains("\"relativePath\": \"screenshots/page-002-links-failed.png\""));
    }

    // --- 22, 23, 24, 30. what each path is, and is not, allowed to touch -----------------------

    @Test
    void crawlerModeNeverReadsSurefireOutputAndLegacyModeStillDoes() throws IOException {
        String runId = uniqueRunId("no-surefire");
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        Files.createDirectories(paths.surefireReportsDirectory());
        // A report that would be read if the crawler path ever looked, and whose numbers could
        // not be mistaken for the crawl's.
        Files.writeString(paths.surefireReportsDirectory().resolve("TEST-stale.xml"), """
            <testsuite name="stale" tests="99" failures="7" errors="3" skipped="11">
              <testcase classname="StaleTest" name="fromSomeOtherRun"/>
            </testsuite>
            """);

        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        String summary = Files.readString(paths.summaryFile());
        assertTrue(summary.contains("\"outcome\": \"NO_FINDINGS\""));
        assertFalse(summary.contains("StaleTest"), "no Surefire report may reach a crawl summary");
        assertFalse(summary.contains("\"failed\": 7"));
        assertFalse(summary.contains("\"errors\": 3"));
    }

    @Test
    void eachPathUsesItsOwnSummaryFactoryEntryPointAndNoOther() throws IOException {
        String cli = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/runtime/AutomationRunOrchestratorCli.java")));

        assertEquals(1, count(cli, "fromTestResults("), "the legacy path, once");
        assertEquals(1, count(cli, "fromCrawlResults("), "the crawler path, once");
        assertEquals(1, count(cli, "new SurefireResultReader()"),
            "Surefire is read in exactly one place");
        String crawlerBranch = methodBodyOf(cli, "private static int runCrawler(");
        assertEquals(0, count(crawlerBranch, "SurefireResultReader"),
            "and that place is not the crawler branch: " + crawlerBranch);
        assertEquals(0, count(crawlerBranch, "fromTestResults"), crawlerBranch);
        assertEquals(0, count(crawlerBranch, "mavenRunner"), crawlerBranch);
        assertEquals(0, count(crawlerBranch, "AutomationRunMavenCommand"), crawlerBranch);
        assertEquals(1, count(crawlerBranch, "fromCrawlResults("), crawlerBranch);

        // And no test seam leaked into the entry point the operating system calls.
        String main = methodBodyOf(cli, "public static void main(String[] args)");
        assertEquals(0, count(main, "Runner"), main);
        assertEquals(0, count(main, "Coordinator"), main);
        String coordinator = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/runtime/CrawlerRunCoordinator.java")));
        assertEquals(1, count(coordinator, "new CrawlRunner()"),
            "production still constructs the real crawler, exactly once");
        assertEquals(1, count(coordinator, "WebsiteConfigLoader.load("),
            "and loads the configuration by explicit path");
        assertEquals(0, count(coordinator, "loadSelected"),
            "never through the global siteConfig property");
        assertEquals(0, count(coordinator, "SITE_CONFIG_PROPERTY"), coordinator);
        assertEquals(1, count(coordinator, "RunArtifactPropertyScope.open("),
            "one artifact-property scope, and no second one");
    }

    // --- 25, 27, 28. terminal progress, a rejected value, and nothing sensitive ----------------

    @Test
    void everyCrawlerOutcomeLeavesProgressInATerminalState() throws IOException {
        List<CrawlerRunCoordinator.CrawlExecution> endings = List.of(
            (config, scope) -> {
                writeEvidenceThroughTheInstalledProperty("");
                return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
            },
            (config, scope) -> {
                writeEvidenceThroughTheInstalledProperty(failedLinksEvidence(1, CRAWL_PAGE));
                return crawled(new CrawlerPageProcessor.ModuleOutcome(
                    CrawlerPageProcessor.Status.FAILED, "a link did not resolve",
                    CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT));
            },
            (config, scope) -> {
                writeEvidenceThroughTheInstalledProperty("");
                return new CrawlRunner.CrawlRunResult.LoginPrevented(
                    CrawlRunner.LoginOutcome.FAILED,
                    CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED, "refused");
            },
            (config, scope) -> {
                throw new IllegalStateException("the browser went away");
            });

        for (CrawlerRunCoordinator.CrawlExecution ending : endings) {
            String runId = uniqueRunId("terminal");
            runCli(new CountingMaven(), ending,
                crawlerArgs(runId, "https://example.com/", "--crawler=true"));

            String progress = Files.readString(AutomationRunPaths.forRunId(runId).progressFile());
            assertTrue(
                progress.contains("\"state\": \"COMPLETED\"")
                    || progress.contains("\"state\": \"FAILED\"")
                    || progress.contains("\"state\": \"CANCELLED\""),
                "no crawler ending may leave the run looking like it is still going: " + progress);
            assertTrue(progress.contains("\"phase\": \"FINISHED\""), progress);
        }
    }

    @Test
    void anInvalidCrawlerValueIsRejectedRatherThanQuietlyChoosingAPath() {
        CountingMaven maven = new CountingMaven();
        RecordingCrawl crawl = new RecordingCrawl(config -> {
            throw new AssertionError("nothing may run for a request that was rejected");
        });

        CliResult result = runCli(maven, crawl,
            crawlerArgs(uniqueRunId("invalid-crawler"), "https://example.com/", "--crawler=yes"));

        assertEquals(2, result.exitCode(), "the existing argument-rejection exit code");
        assertEquals(0, maven.calls, "a rejected request runs neither path");
        assertEquals(0, crawl.calls);
        assertTrue(result.err().contains("crawler must be true or false"), result.err());
    }

    /**
     * A crawl message can quote anything the browser or the page gave it. None of it is reported.
     *
     * <p>The summary's wording for a typed cause is written in the factory and assembled from
     * fixed text, so a message that happens to contain a credential, a token or a session URL has
     * no route into {@code summary.json}, {@code progress.json} or either stream. This is the
     * same class of defect Slice 5 removed from the screenshot path, asserted here at the point
     * where a run is actually reported.
     */
    @Test
    void nothingFromACrawlMessageReachesTheSummaryProgressOrEitherStream() throws IOException {
        String runId = uniqueRunId("no-secrets");
        String secret = "hunter2-should-never-appear";

        CliResult result = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return new CrawlRunner.CrawlRunResult.LoginPrevented(
                CrawlRunner.LoginOutcome.FAILED,
                CrawlRunner.LoginPreventedCause.CREDENTIALS_UNAVAILABLE,
                "the sign-in page said: password " + secret + " was rejected");
        }), crawlerArgs(runId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        for (String text : List.of(
            result.out(), result.err(),
            Files.readString(paths.summaryFile()),
            Files.readString(paths.progressFile()))) {
            assertFalse(text.contains(secret),
                "a crawl message must never be echoed into output or artifacts: " + text);
        }
        // What is reported instead: the typed cause, in this analyzer's own words.
        String summary = Files.readString(paths.summaryFile());
        assertTrue(summary.contains("\"outcome\": \"RUN_CONFIGURATION_ERROR\""));
        assertTrue(summary.contains("\"code\": \"LOGIN_CREDENTIALS_UNAVAILABLE\""));
        assertEquals(3, result.exitCode());
    }


    // --- Slice 8a: an Error must not leave the run looking like it is still going ---------------

    /**
     * A distinctive value that an {@link Error} message can carry and nothing may repeat.
     *
     * <p>Shaped like the things that actually leak: a password, a session token and an internal
     * host, all in one string, so a single assertion covers every category the contract names.
     */
    private static final String FATAL_SECRET =
        "hunter2 https://internal.example/session?token=abcd1234";

    @Test
    void anErrorFromTheCrawlIsRethrownByIdentityAfterTheRunIsClosedOut() throws IOException {
        String runId = uniqueRunId("fatal-identity");
        AssertionError fatal = new AssertionError("the analyzer died holding " + FATAL_SECRET);
        CountingMaven maven = new CountingMaven();

        AssertionError thrown = assertThrows(AssertionError.class, () -> runCli(maven,
            new RecordingCrawl(config -> {
                throw fatal;
            }),
            crawlerArgs(runId, "https://example.com/", "--crawler=true")));

        // 1. The same object, not a copy, not a wrapper, not a downgrade to an exit code.
        assertSame(fatal, thrown, "the Error must reach the caller exactly as it was thrown");
        assertEquals(0, maven.calls, "and no Maven process was started on the way");
        // Nothing was attached to it on the way out, so nothing it was carrying was multiplied.
        assertNull(thrown.getCause(), "the Error must not be given a cause");
        assertEquals(0, thrown.getSuppressed().length,
            "and must not be given a suppressed exception either");
    }

    @Test
    void anErrorLeavesTheArtifactDestinationsExactlyAsItFoundThem() throws IOException {
        String runId = uniqueRunId("fatal-properties");
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        // A sentinel rather than whatever the process happens to hold, so "restored" means the
        // exact value came back rather than merely that something is set.
        String sentinelEvidence = Path.of("target", "sentinel-evidence.jsonl")
            .toAbsolutePath().toString();
        String sentinelScreenshots = Path.of("target", "sentinel-screenshots")
            .toAbsolutePath().toString();
        String evidenceBefore = System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        String screenshotsBefore =
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY, sentinelEvidence);
        System.setProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, sentinelScreenshots);
        try {
            RecordingCrawl crawl = new RecordingCrawl(config -> {
                throw new AssertionError("stopped");
            });

            assertThrows(AssertionError.class, () -> runCli(new CountingMaven(), crawl,
                crawlerArgs(runId, "https://example.com/", "--crawler=true")));

            // Installed while the crawl ran...
            assertEquals(
                List.of(paths.evidenceFile().toAbsolutePath().normalize().toString()),
                crawl.evidenceProperty);
            assertEquals(
                List.of(paths.screenshotsDirectory().toAbsolutePath().normalize().toString()),
                crawl.screenshotsProperty);
            // ...and already restored by the time the Error reached this line, which is what
            // makes the next run in this JVM write to its own directory rather than to this one.
            assertEquals(sentinelEvidence,
                System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
            assertEquals(sentinelScreenshots,
                System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
        } finally {
            restoreProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY, evidenceBefore);
            restoreProperty(
                ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, screenshotsBefore);
        }
    }

    @Test
    void anErrorStillWritesATruthfulAnalyzerFailureSummaryAndTerminalProgress() throws IOException {
        String runId = uniqueRunId("fatal-artifacts");

        assertThrows(AssertionError.class, () -> runCli(new CountingMaven(),
            new RecordingCrawl(config -> {
                writeEvidenceThroughTheInstalledProperty("");
                throw new AssertionError("the analyzer died holding " + FATAL_SECRET);
            }),
            crawlerArgs(runId, "https://example.com/", "--crawler=true")));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        String summary = Files.readString(paths.summaryFile());
        String progress = Files.readString(paths.progressFile());

        assertTrue(summary.contains("\"outcome\": \"ANALYZER_INTERNAL_ERROR\""),
            "an Error is the analyzer's failure, and is reported as one: " + summary);
        assertTrue(summary.contains("\"category\": \"ANALYZER_INTERNAL\""));
        assertTrue(summary.contains("\"findings\": []"),
            "a run that died mid-flight has no findings to report about the website");
        assertTrue(progress.contains("\"state\": \"FAILED\""), progress);
        assertTrue(progress.contains("\"phase\": \"FINISHED\""), progress);
        assertFalse(progress.contains("\"state\": \"RUNNING\""),
            "the run must never be left looking like it is still going: " + progress);
        assertFalse(progress.contains("\"state\": \"FINALIZING\""), progress);
        assertFalse(progress.contains("\"state\": \"CONFIGURING\""), progress);
    }

    @Test
    void nothingTheErrorWasCarryingReachesTheSummaryProgressOrEitherStream() throws IOException {
        String runId = uniqueRunId("fatal-secrets");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        AssertionError thrown = assertThrows(AssertionError.class, () ->
            AutomationRunOrchestratorCli.run(
                crawlerArgs(runId, "https://example.com/", "--crawler=true"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new CountingMaven(),
                new CrawlerRunCoordinator((config, scope) -> {
                    throw new AssertionError("the browser said " + FATAL_SECRET);
                })));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        for (String text : List.of(
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8),
            Files.readString(paths.summaryFile()),
            Files.readString(paths.progressFile()))) {
            assertFalse(text.contains("hunter2"), "a credential must not be echoed: " + text);
            assertFalse(text.contains("token=abcd1234"), "nor a token: " + text);
            assertFalse(text.contains("internal.example"), "nor an internal host: " + text);
        }
        // The message survives on the Error itself, which is the caller's to handle, and nowhere
        // else. Nothing was attached to it that would print the message a second time.
        assertTrue(thrown.getMessage().contains(FATAL_SECRET),
            "the Error itself is untouched");
        assertNull(thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void aFailureWhileWritingTheFailureArtifactsDoesNotReplaceTheError() throws IOException {
        String runId = uniqueRunId("fatal-unwritable");
        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        // A directory where summary.json belongs: the summary cannot be written at all, and the
        // Error must still be the thing that comes out.
        Files.createDirectories(paths.summaryFile());
        AssertionError fatal = new AssertionError("stopped");

        AssertionError thrown = assertThrows(AssertionError.class, () -> runCli(new CountingMaven(),
            new RecordingCrawl(config -> {
                throw fatal;
            }),
            crawlerArgs(runId, "https://example.com/", "--crawler=true")));

        assertSame(fatal, thrown,
            "a reporting problem is not the failure this run had, and must not become it");
        assertNull(thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
        // The summary could not be written, so progress is the only thing left that can tell a
        // watcher the run is over. It still does.
        String progress = Files.readString(paths.progressFile());
        assertTrue(progress.contains("\"state\": \"FAILED\""), progress);
        assertTrue(progress.contains("\"phase\": \"FINISHED\""), progress);
    }

    /**
     * The boundary has to cover the whole lifecycle, not just the crawl.
     *
     * <p>Here the crawl succeeds, the evidence is read, the summary is built and written, the
     * timeline is projected and terminal progress is written -- and only then does something die,
     * on the very last statement. If the boundary stopped at the crawl, this run would end with
     * an Error escaping past a half-finished report. It does not: the run is closed out again,
     * truthfully, because a run that died is a run that died whenever it happened.
     */
    @Test
    void aRunThatDiesAfterItsResultsAreAggregatedAndWrittenIsAlsoClosedOut() throws IOException {
        String runId = uniqueRunId("fatal-late");
        AssertionError fatal = new AssertionError("stopped on the last line");
        // A stream that works until the CLI prints its finished summary, which is the last thing
        // inside the boundary and happens after every artifact has already been written.
        PrintStream dyingOut = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8) {
            @Override
            public void println(String line) {
                throw fatal;
            }
        };

        AssertionError thrown = assertThrows(AssertionError.class, () ->
            AutomationRunOrchestratorCli.run(
                crawlerArgs(runId, "https://example.com/", "--crawler=true"),
                dyingOut,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new CountingMaven(),
                new CrawlerRunCoordinator((config, scope) -> {
                    writeEvidenceThroughTheInstalledProperty("");
                    return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
                })));

        AutomationRunPaths paths = AutomationRunPaths.forRunId(runId);
        assertSame(fatal, thrown, "still the same object, from the far end of the lifecycle");
        assertNull(thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
        String progress = Files.readString(paths.progressFile());
        assertTrue(progress.contains("\"state\": \"FAILED\""), progress);
        assertTrue(progress.contains("\"phase\": \"FINISHED\""), progress);
        assertFalse(progress.contains("\"state\": \"RUNNING\""), progress);
        assertTrue(Files.readString(paths.summaryFile())
            .contains("\"outcome\": \"ANALYZER_INTERNAL_ERROR\""),
            "a run that stopped is reported as stopped, whenever in the lifecycle it stopped");
    }

    @Test
    void neitherTheLegacyPathNorARuntimeExceptionChangedBehaviour() throws IOException {
        // Legacy: still one Maven run, still a completed analysis, still exit 0.
        String legacyRunId = uniqueRunId("fatal-legacy-unchanged");
        CountingMaven maven = new CountingMaven();
        CliResult legacy = runCli(maven, new RecordingCrawl(config -> {
            throw new AssertionError("the crawler must not run for a legacy request");
        }), crawlerArgs(legacyRunId, "https://example.com/"));

        assertEquals(0, legacy.exitCode());
        assertEquals(1, maven.calls);
        assertTrue(Files.readString(AutomationRunPaths.forRunId(legacyRunId).summaryFile())
            .contains("\"outcome\": \"NO_FINDINGS\""));

        // RuntimeException: still caught, still reported, still exit 3 -- never rethrown.
        String crashRunId = uniqueRunId("fatal-runtime-unchanged");
        CliResult crash = runCli(new CountingMaven(), new RecordingCrawl(config -> {
            throw new IllegalStateException("the browser went away");
        }), crawlerArgs(crashRunId, "https://example.com/", "--crawler=true"));

        AutomationRunPaths crashPaths = AutomationRunPaths.forRunId(crashRunId);
        assertEquals(3, crash.exitCode(),
            "a RuntimeException is still reported through an exit code, not rethrown");
        assertTrue(Files.readString(crashPaths.summaryFile())
            .contains("\"outcome\": \"ANALYZER_INTERNAL_ERROR\""));
        assertTrue(Files.readString(crashPaths.progressFile()).contains("\"state\": \"FAILED\""));
    }

    @Test
    void aCrawlThatDidNotDieIsNeverReportedAsAnAnalyzerError() throws IOException {
        // Clean.
        String cleanRunId = uniqueRunId("fatal-not-clean");
        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return crawled(outcome(CrawlerPageProcessor.Status.PASSED));
        }), crawlerArgs(cleanRunId, "https://example.com/", "--crawler=true"));

        // A website finding.
        String websiteRunId = uniqueRunId("fatal-not-website");
        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty(failedLinksEvidence(1, CRAWL_PAGE));
            return crawled(new CrawlerPageProcessor.ModuleOutcome(
                CrawlerPageProcessor.Status.FAILED, "a link did not resolve",
                CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT));
        }), crawlerArgs(websiteRunId, "https://example.com/", "--crawler=true"));

        // A sign-in the site refused.
        String preventedRunId = uniqueRunId("fatal-not-prevented");
        runCli(new CountingMaven(), new RecordingCrawl(config -> {
            writeEvidenceThroughTheInstalledProperty("");
            return new CrawlRunner.CrawlRunResult.LoginPrevented(
                CrawlRunner.LoginOutcome.FAILED,
                CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED, "refused");
        }), crawlerArgs(preventedRunId, "https://example.com/", "--crawler=true"));

        assertTrue(Files.readString(AutomationRunPaths.forRunId(cleanRunId).summaryFile())
            .contains("\"outcome\": \"NO_FINDINGS\""));
        String website = Files.readString(AutomationRunPaths.forRunId(websiteRunId).summaryFile());
        assertTrue(website.contains("\"outcome\": \"WEBSITE_FINDINGS\""));
        assertFalse(website.contains("ANALYZER_INTERNAL_ERROR"),
            "a defect in the customer's website must not become a defect in this analyzer");
        assertTrue(Files.readString(AutomationRunPaths.forRunId(preventedRunId).summaryFile())
            .contains("\"outcome\": \"WEBSITE_FINDINGS\""));
    }

    @Test
    void theErrorBoundaryIsOneNarrowCatchAndNeverCatchesThrowable() throws IOException {
        String cli = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/runtime/AutomationRunOrchestratorCli.java")));

        assertEquals(0, count(cli, "catch (Throwable"),
            "catching Throwable would swallow checked exceptions nothing here declares");
        assertEquals(1, count(cli, "catch (Error "),
            "exactly one Error boundary, and it is the crawler one");
        assertEquals(1, count(cli, "throw fatal;"), "which rethrows by identity, once");
        // The boundary belongs to the crawler branch only.
        String crawlerBranch = methodBodyOf(cli, "private static int runCrawler(");
        assertEquals(1, count(crawlerBranch, "catch (Error fatal)"), crawlerBranch);
        assertEquals(1, count(crawlerBranch, "throw fatal;"), crawlerBranch);
        // Nothing about the Error is read beyond its type, anywhere in this class.
        // And it wraps the whole lifecycle, not just the crawl: everything the run does after
        // the crawl returns is inside the same try, so none of it can escape unreported.
        for (String step : List.of(
            "crawlerCoordinator.execute(paths, crawlScope)",
            "summaryFactory.fromCrawlResults(",
            "summaryWriter.write(paths, summary)",
            "writeIfPossible(paths)",
            "writeFinalProgress(",
            "printSummary(out, summary)")) {
            assertEquals(1, count(crawlerBranch, step),
                step + " must sit inside the crawler branch exactly once");
        }
        assertEquals(0, count(cli, "fatal.getMessage()"), cli);
        assertEquals(0, count(cli, "safeMessage(fatal)"), cli);
        assertEquals(0, count(cli, "addSuppressed"), cli);
        assertEquals(0, count(cli, "initCause"), cli);
    }

    /** The body of one uniquely-declared method, matched brace to brace. */
    private static String methodBodyOf(String code, String declaration) {
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

    /** Source with comments removed, so a guard can never match documentation. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static final class RecordingRunner implements AutomationRunOrchestratorCli.MavenRunner {
        private final boolean produceReport;
        private boolean called;

        private RecordingRunner(boolean produceReport) {
            this.produceReport = produceReport;
        }

        @Override
        public int run(List<String> command) throws IOException {
            called = true;
            if (produceReport) {
                Path reports = reportsDirectoryFrom(command);
                Files.createDirectories(reports);
                Files.writeString(reports.resolve("TEST-generic.xml"), """
                    <testsuite name="generic" tests="1" failures="0" errors="0" skipped="0">
                      <testcase classname="GenericSearchTest" name="searches"/>
                    </testsuite>
                    """);
            }
            return 0;
        }
    }

    // --- the safe internal-error fingerprint ------------------------------------------------------

    @Test
    void theFingerprintNamesTheExceptionAndTheFirstQanizeFrame() {
        // A summary saying only "internal error" is true and useless. The class name and the one
        // frame inside this project say which failure it was, and nothing else is taken.
        RuntimeException failure = new org.openqa.selenium.StaleElementReferenceException(
            "stale element reference: element is not attached to https://secret.example/lobby");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.openqa.selenium.remote.RemoteWebElement", "findElements",
                "RemoteWebElement.java", 99),
            new StackTraceElement("com.qanize.automation.checks.gamelaunch.GameCardInspector",
                "resolve", "GameCardInspector.java", 163),
            new StackTraceElement("com.qanize.automation.checks.gamelaunch.GameLaunchModule",
                "play", "GameLaunchModule.java", 395)
        });

        String fingerprint = AutomationRunOrchestratorCli.safeFingerprint(failure);

        assertTrue(fingerprint.contains("StaleElementReferenceException"), fingerprint);
        assertTrue(fingerprint.contains("GameCardInspector.resolve(163)"),
            "the first frame this project wrote: " + fingerprint);
        assertFalse(fingerprint.contains("RemoteWebElement"),
            "a frame from somebody else's library is not ours to publish: " + fingerprint);
    }

    @Test
    void theFingerprintCarriesNothingFromTheExceptionMessage() {
        // A browser exception quotes the page it was looking at. None of that may be written down.
        RuntimeException failure = new IllegalStateException(
            "user=8123456789 token=abc123 cookie=SESSION at https://host.example/play?sig=deadbeef");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.qanize.automation.engine.CrawlRunner",
                "runWithin", "CrawlRunner.java", 298)
        });

        String fingerprint = AutomationRunOrchestratorCli.safeFingerprint(failure);

        for (String planted : java.util.List.of(
            "8123456789", "abc123", "SESSION", "deadbeef", "host.example", "token", "cookie", "?")) {
            assertFalse(fingerprint.contains(planted),
                "found " + planted + " in " + fingerprint);
        }
        assertEquals("IllegalStateException at CrawlRunner.runWithin(298)", fingerprint);
    }

    @Test
    void anExceptionWithNoFrameOfOursIsStillNamed() {
        RuntimeException failure = new IllegalArgumentException("whatever");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 1)
        });

        assertEquals("IllegalArgumentException",
            AutomationRunOrchestratorCli.safeFingerprint(failure));
        assertEquals("", AutomationRunOrchestratorCli.safeFingerprint(null));
    }
}
