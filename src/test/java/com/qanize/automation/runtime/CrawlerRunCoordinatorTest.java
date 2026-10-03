package com.qanize.automation.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.qanize.automation.browser.ScreenshotDirectoryResolver;
import com.qanize.automation.config.RunConfigurationException;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.engine.CrawlRunSummary;
import com.qanize.automation.engine.CrawlRunner;
import com.qanize.automation.engine.CrawlScope;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.model.CheckExecution;
import com.qanize.automation.model.CheckId;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things that are only observable here: what the crawl was handed, and what the process
 * looks like afterwards.
 *
 * <p>The CLI tests prove the branch and the reported outcomes. This proves the part of the
 * contract that has no visible effect in the run that breaks it — a system property left
 * installed, or a configuration read from somewhere other than this run's generated file. Both
 * only surface in the <em>next</em> run, which is exactly why they need a test that looks at the
 * process rather than at a summary.
 *
 * <p>Nothing here starts a browser. The coordinator's one seam takes a configuration and returns
 * a typed result, so every case is a lambda.
 */
@Tag("unit")
class CrawlerRunCoordinatorTest {

    private static final CrawlScope FULL = CrawlScope.CONFIGURED_BUDGET;
    private static final CrawlScope START_PAGE = CrawlScope.START_PAGE_ONLY;

    @org.junit.jupiter.api.Test
    void theScopeTheCliChoseReachesTheEngineUnchanged() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-scope", "https://example.com/");
        List<CrawlScope> seen = new ArrayList<>();

        for (CrawlScope asked : List.of(FULL, START_PAGE)) {
            new CrawlerRunCoordinator((config, scope) -> {
                seen.add(scope);
                return cleanResult();
            }).execute(paths, asked);
        }

        assertEquals(List.of(FULL, START_PAGE), seen,
            "the coordinator decides no page budget of its own");
    }

    @Test
    void theConfigurationComesFromThisRunsGeneratedFileAndNotFromTheGlobalProperty() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-explicit", "https://wanted.example.com/");
        // The property the forked Maven path uses, pointed at something else entirely. If the
        // coordinator ever consulted it, the crawl would be handed the wrong website.
        String previous = System.getProperty(WebsiteConfigLoader.SITE_CONFIG_PROPERTY);
        System.setProperty(WebsiteConfigLoader.SITE_CONFIG_PROPERTY, "sites/example.properties");
        try {
            List<String> seen = new ArrayList<>();
            new CrawlerRunCoordinator((config, scope) -> {
                seen.add(config.baseUrl().toString());
                return cleanResult();
            }).execute(paths, FULL);

            assertEquals(List.of("https://wanted.example.com/"), seen);
        } finally {
            restore(WebsiteConfigLoader.SITE_CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theGlobalConfigurationPropertyIsNeitherReadNorWritten() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-untouched", "https://example.com/");
        String before = System.getProperty(WebsiteConfigLoader.SITE_CONFIG_PROPERTY);

        new CrawlerRunCoordinator((config, scope) -> cleanResult()).execute(paths, FULL);

        assertEquals(before, System.getProperty(WebsiteConfigLoader.SITE_CONFIG_PROPERTY),
            "a run that set siteConfig would leak its site into whatever ran next");
    }

    @Test
    void twoRunsInOneProcessCannotExchangeConfigurations() throws IOException {
        AutomationRunPaths first = runDirectoryFor("coordinator-a", "https://first.example.com/");
        AutomationRunPaths second = runDirectoryFor("coordinator-b", "https://second.example.com/");
        List<String> seen = new ArrayList<>();
        CrawlerRunCoordinator.CrawlExecution record = (config, scope) -> {
            seen.add(config.baseUrl().toString());
            return cleanResult();
        };

        new CrawlerRunCoordinator(record).execute(first, FULL);
        new CrawlerRunCoordinator(record).execute(second, FULL);

        assertEquals(List.of("https://first.example.com/", "https://second.example.com/"), seen);
    }

    @Test
    void theArtifactDestinationsAreInstalledForTheDurationOfTheCrawl() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-installed", "https://example.com/");
        List<String> evidence = new ArrayList<>();
        List<String> screenshots = new ArrayList<>();

        new CrawlerRunCoordinator((config, scope) -> {
            evidence.add(System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY));
            screenshots.add(System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY));
            return cleanResult();
        }).execute(paths, FULL);

        assertEquals(List.of(absolute(paths.evidenceFile())), evidence);
        assertEquals(List.of(absolute(paths.screenshotsDirectory())), screenshots);
    }

    @Test
    void theDestinationsAreRestoredAfterACleanRun() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-clean", "https://example.com/");

        assertRestoredAround(() -> new CrawlerRunCoordinator((config, scope) -> cleanResult()).execute(paths, FULL));
    }

    @Test
    void theDestinationsAreRestoredAfterAPreventedSignIn() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-prevented", "https://example.com/");

        assertRestoredAround(() -> {
            CrawlRunSummary summary = new CrawlerRunCoordinator((config, scope) ->
                new CrawlRunner.CrawlRunResult.LoginPrevented(
                    CrawlRunner.LoginOutcome.FAILED,
                    CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED,
                    "the sign-in was refused")).execute(paths, FULL);
            assertTrue(summary.login().preventedTheCrawl(),
                "a prevented sign-in is a completed typed result, not a failure to report");
        });
    }

    @Test
    void theDestinationsAreRestoredAfterARuntimeException() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-runtime", "https://example.com/");

        assertRestoredAround(() -> {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                new CrawlerRunCoordinator((config, scope) -> {
                    throw new IllegalStateException("the browser went away");
                }).execute(paths, FULL));
            assertEquals("the browser went away", thrown.getMessage(),
                "the failure travels out unchanged, so the CLI reports what actually happened");
        });
    }

    /**
     * The case a {@code finally} is easy to get wrong and a {@code catch (RuntimeException)} misses
     * entirely. An {@link Error} is not caught anywhere on this path, so if the properties were not
     * restored here they would stay installed for the rest of the JVM.
     */
    @Test
    void theDestinationsAreRestoredAfterAnError() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-error", "https://example.com/");

        assertRestoredAround(() -> {
            StackOverflowError thrown = assertThrows(StackOverflowError.class, () ->
                new CrawlerRunCoordinator((config, scope) -> {
                    throw new StackOverflowError();
                }).execute(paths, FULL));
            assertFalse(thrown == null);
        });
    }

    @Test
    void theDestinationsAreRestoredWhenTheConfigurationItselfIsUnusable() throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(uniqueRunId("coordinator-bad-config"));
        Files.createDirectories(paths.runDirectory());
        // Valid enough to open and parse, and rejected by the loader's own rules: Search is on
        // with no input selector. The point is that a rejection still restores the properties.
        Files.writeString(paths.propertiesConfigFile(), """
            contract.version=1.0
            site.url=https://example.com/
            tests.search=true
            search.enabled=true
            """);

        assertRestoredAround(() -> {
            RunConfigurationException rejected = assertThrows(RunConfigurationException.class, () ->
                new CrawlerRunCoordinator((config, scope) -> {
                    throw new AssertionError("the crawl must not start on an unusable configuration");
                }).execute(paths, FULL));
            assertEquals("SEARCH_INPUT_SELECTOR_REQUIRED", rejected.code());
        });
    }

    @Test
    void theCrawlIsAskedForExactlyOnceAndItsResultIsFoldedUnchanged() throws IOException {
        AutomationRunPaths paths = runDirectoryFor("coordinator-once", "https://example.com/");
        int[] calls = {0};

        CrawlRunSummary summary = new CrawlerRunCoordinator((config, scope) -> {
            calls[0]++;
            return cleanResult();
        }).execute(paths, FULL);

        assertEquals(1, calls[0], "one crawl per run: no retry, no second browser");
        assertFalse(summary.check(CheckId.LOGIN).selected(),
            "the fold is the crawl's own, unedited: this run did not select Login");
        assertEquals(CheckExecution.Status.NOT_TESTED, summary.check(CheckId.LOGIN).status());
        assertFalse(summary.hasAnalyzerFailure());
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Runs the body and proves the process is byte-identical afterwards on both keys. */
    private static void assertRestoredAround(Runnable body) {
        String evidenceBefore = System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        String screenshotsBefore =
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);

        body.run();

        assertEquals(evidenceBefore, System.getProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY),
            "the evidence destination must be exactly what it was, including absent");
        assertEquals(screenshotsBefore,
            System.getProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY),
            "and so must the screenshot destination");
    }

    private static AutomationRunPaths runDirectoryFor(String label, String siteUrl)
        throws IOException {
        AutomationRunPaths paths = AutomationRunPaths.forRunId(uniqueRunId(label));
        Files.createDirectories(paths.runDirectory());
        // Written by the same generator the CLI uses, so this is the file a real run would read.
        new RuntimeConfigGenerator().generate(
            requestFor(siteUrl), paths.propertiesConfigFile());
        return paths;
    }

    private static AutomationRunRequest requestFor(String siteUrl) {
        return new AutomationRunRequest(
            AutomationRunRequest.CONTRACT_VERSION, siteUrl,
            AutomationRunRequest.DEFAULT_VIEWPORT_PRESET,
            AutomationRunRequest.DEFAULT_VIEWPORT_WIDTH,
            AutomationRunRequest.DEFAULT_VIEWPORT_HEIGHT,
            AutomationRunRequest.DEFAULT_MAX_PAGES, AutomationRunRequest.DEFAULT_MAX_LINKS,
            false, "", "", "", "", "", "", "", "",
            false, false, true, false, false, false,
            false, "", "", "", "test",
            null, null, null, null, null, null,
            true);
    }

    private static CrawlRunner.CrawlRunResult cleanResult() {
        return new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED,
            new com.qanize.automation.engine.Crawler.CrawlResult(
                List.of(),
                new com.qanize.automation.engine.Crawler.LimitReport(10, 3, false, false, 0)),
            List.of());
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String uniqueRunId(String label) {
        return "test-" + label + "-" + System.nanoTime();
    }
}
