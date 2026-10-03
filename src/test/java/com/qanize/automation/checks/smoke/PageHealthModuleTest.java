package com.qanize.automation.checks.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import com.qanize.automation.checks.shared.PageStatusProbe;

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
 * The page-health inspection, driven without a browser.
 *
 * <p>Four reads is the whole surface, so a tiny fake of those four exercises every state the check
 * can report — with no WebDriver, no Chrome, no external site and no mocking dependency.
 */
@Tag("unit")
class PageHealthModuleTest {

    private static final String PAGE = "https://example.com/";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private SmokeExecutionEvidence recording() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        return new SmokeExecutionEvidence();
    }

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
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

    private PageHealthModule module(FakePage page, SmokeExecutionEvidence execution) {
        return new PageHealthModule(page, execution);
    }

    /** The same module, told what the address answers when the browser renders no title. */
    private PageHealthModule module(
        FakePage page, SmokeExecutionEvidence execution, PageStatusProbe probe
    ) {
        return new PageHealthModule(
            page, execution, com.qanize.automation.evidence.EvidenceScope.legacy(), probe);
    }

    /** A probe that answers one status for any address, counting how often it was asked. */
    private static final class FakeStatus implements PageStatusProbe {

        private final int status;
        private int asked;

        FakeStatus(int status) { this.status = status; }

        @Override
        public OptionalInt statusOf(String url) {
            asked++;
            return OptionalInt.of(status);
        }
    }

    // --- a healthy page -----------------------------------------------------------------------

    @Test
    void aPageThatReturnsAnAddressATitleAndADocumentPasses() throws IOException {
        FakePage page = new FakePage(PAGE, "Example", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.PASSED, result.outcome());
        assertNull(result.message(), "a passing result carries no message");
        String item = itemWithId("smoke-page-1");
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
        // The recorder writes JSON, so the wording's own quotes arrive escaped.
        assertTrue(item.contains("Loaded with title \\\"Example\\\"."), item);
        assertTrue(item.contains("\"metadata.redirected\":\"false\""), item);
        assertTrue(item.contains("\"metadata.pageTitle\":\"Example\""), item);
        assertEquals(1, items().size(), "only the page item, with no expectations configured");
    }

    @Test
    void aFinalAddressThatDiffersFromTheRequestedOneIsRecordedAsARedirect() throws IOException {
        FakePage page = new FakePage("https://example.com/home", "Home", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.PASSED, result.outcome(),
            "a redirect is a fact about the page, not a failure");
        String item = itemWithId("smoke-page-1");
        assertTrue(item.contains("\"metadata.redirected\":\"true\""), item);
        assertTrue(item.contains("after redirecting to https://example.com/home"), item);
    }

    // --- the three ways a page is unhealthy ---------------------------------------------------

    @Test
    void aBlankFinalAddressFailsFirstAndStopsTheRest() throws IOException {
        FakePage page = new FakePage("   ", "Example", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, "Example", "hello");

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals("Current URL should not be blank after opening baseUrl", result.message());
        assertTrue(itemWithId("smoke-page-1").contains("\"status\":\"FAILED\""),
            itemWithId("smoke-page-1"));
        // Fail-fast: the configured expectations must not run once the page is already unusable.
        assertEquals(1, items().size(), "no expectation item may be recorded: " + items());
        assertEquals(0, page.titleReads - 1, "the title is read once, during inspection only");
    }

    @Test
    void aBlankTitleFails() throws IOException {
        FakePage page = new FakePage(PAGE, "  ", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals("Page title should not be blank", result.message());
        String item = itemWithId("smoke-page-1");
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
        assertFalse(item.contains("\"metadata.pageTitle\""),
            "a blank title is not published as metadata: " + item);
    }

    // --- a page that is protected rather than broken -------------------------------------------

    @Test
    void anAuthenticationProtectedPageIsSkippedInsteadOfReportedAsNotLoading() throws IOException {
        // The defect, reproduced from a real run: /basic_auth was reachable, returned a document,
        // and had no title because the browser dismissed the credential prompt. It was reported as
        // "the target page did not load as expected" on a page that loaded exactly as designed.
        FakePage page = new FakePage(PAGE, "", true);

        PageHealthModule.Result result =
            module(page, recording(), new FakeStatus(401)).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.SKIPPED, result.outcome());
        assertTrue(result.skipped());
        assertFalse(result.passed(), "nothing about the page was verified");
        assertTrue(result.message().contains("requires authentication"), result.message());
        String item = itemWithId("smoke-page-1");
        assertTrue(item.contains("\"status\":\"SKIPPED\""), item);
        assertTrue(item.contains("\"httpStatus\":401"), item);
        assertTrue(item.contains("AUTH_REQUIRED"), "the classification is published: " + item);
        assertFalse(item.contains("could not load the page"),
            "the page loaded; only its content was withheld: " + item);
    }

    @Test
    void aProtectedPageRecordsNoWebsiteFindingInItsSteps() throws IOException {
        FakePage page = new FakePage(PAGE, "", true);

        module(page, recording(), new FakeStatus(403)).inspect(PAGE, null, null, null);

        List<String> verdicts = steps().stream()
            .filter(line -> line.contains("Checked that the submitted page loaded a usable document."))
            .toList();
        assertEquals(1, verdicts.size(), steps().toString());
        assertFalse(verdicts.get(0).contains("WEBSITE_FINDING"), verdicts.get(0));
        assertTrue(verdicts.get(0).contains("NOT_APPLICABLE"), verdicts.get(0));
        assertTrue(verdicts.get(0).contains("\"status\":\"SKIPPED\""), verdicts.get(0));
    }

    @Test
    void theAddressIsOnlyAskedAboutWhenTheBrowserProducedNoTitle() throws IOException {
        FakeStatus probe = new FakeStatus(200);
        FakePage healthy = new FakePage(PAGE, "Example", true);

        PageHealthModule.Result result =
            module(healthy, recording(), probe).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.PASSED, result.outcome());
        assertEquals(0, probe.asked,
            "an ordinary page must not cost an extra request");
    }

    @Test
    void aTitlelessPageThatIsGenuinelyBrokenStillFailsAndNowNamesItsStatus() throws IOException {
        // The protection must not become an excuse. A 404 with no title is still a failure, and
        // the status it answered with is now part of the report rather than absent from it.
        FakePage page = new FakePage(PAGE, "", true);

        PageHealthModule.Result result =
            module(page, recording(), new FakeStatus(404)).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("404"), result.message());
        assertTrue(itemWithId("smoke-page-1").contains("\"status\":\"FAILED\""));
    }

    @Test
    void withoutAProbeATitlelessPageKeepsExactlyTheVerdictItAlwaysHad() throws IOException {
        // Every caller that supplies no probe — a signed-in run included — behaves as before.
        FakePage page = new FakePage(PAGE, "", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals("Page title should not be blank", result.message());
    }

    @Test
    void aBrowserThatNeverProducedADocumentIsStillAFailureAndIsNeverProbed() throws IOException {
        FakeStatus probe = new FakeStatus(401);
        FakePage page = new FakePage(PAGE, "", true);

        PageHealthModule.Result result = module(page, recording(), probe)
            .inspect(PAGE, "TimeoutException: never loaded", null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals(0, probe.asked, "no document came back, so there is nothing to explain");
        assertTrue(itemWithId("smoke-page-1").contains("could not load the page"));
    }

    @Test
    void anUnreadableDocumentFails() throws IOException {
        FakePage page = new FakePage(PAGE, "Example", false);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals("Page source/body should not be blank", result.message());
        assertTrue(itemWithId("smoke-page-1").contains("\"status\":\"FAILED\""),
            itemWithId("smoke-page-1"));
    }

    // --- browser failures ---------------------------------------------------------------------

    @Test
    void aNavigationFailureTheCallerAlreadyRecordedStillProducesAFailedPageItem() throws IOException {
        FakePage page = new FakePage(PAGE, "Example", true);

        PageHealthModule.Result result = module(page, recording())
            .inspect(PAGE, "TimeoutException: navigation timed out", null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        String item = itemWithId("smoke-page-1");
        assertTrue(item.contains("The browser could not load the page."), item);
        assertTrue(item.contains("navigation timed out"), item);
        assertEquals(0, page.urlReads, "a page that never opened must not be read");
        assertEquals(0, page.titleReads);
    }

    @Test
    void aBrowserFailureWhileReadingIsContainedAndSanitized() throws IOException {
        FakePage page = new FakePage(PAGE, "Example", true)
            .titleThrows(new IllegalStateException(
                "lost session at https://example.com/x?token=synthetic-token"));

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, null);

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome(),
            "a read failure is contained, not propagated");
        String item = itemWithId("smoke-page-1");
        assertTrue(item.contains("The browser could not load the page."), item);
        assertTrue(item.contains("IllegalStateException"), item);
        for (String line : steps()) {
            assertFalse(line.contains("synthetic-token"), "the token must be sanitized: " + line);
        }
    }

    // --- the optional configured expectations -------------------------------------------------

    @Test
    void aTitleThatContainsTheConfiguredTextPasses() throws IOException {
        FakePage page = new FakePage(PAGE, "Example Shop", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, "Example", null);

        assertEquals(PageHealthModule.Outcome.PASSED, result.outcome());
        String item = itemWithId("smoke-expected-title");
        assertTrue(item.contains("Title contains \\\"Example\\\"."), item);
        assertTrue(item.contains("Title is \\\"Example Shop\\\"."), item);
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
    }

    @Test
    void aTitleThatDoesNotContainTheConfiguredTextFailsAndStopsTheTextCheck() throws IOException {
        FakePage page = new FakePage(PAGE, "Something else", true);

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, "Example", "hello");

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals("Page title did not contain expected text: Example", result.message());
        assertTrue(itemWithId("smoke-expected-title").contains("\"status\":\"FAILED\""),
            itemWithId("smoke-expected-title"));
        assertEquals(0, page.textSearches, "the text expectation must not run after a title failure");
        assertEquals(2, items().size(), "page item and title item only: " + items());
    }

    @Test
    void pageTextThatContainsTheConfiguredValuePasses() throws IOException {
        FakePage page = new FakePage(PAGE, "Example", true).pageText("Welcome back, friend");

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, "Welcome back");

        assertEquals(PageHealthModule.Outcome.PASSED, result.outcome());
        String item = itemWithId("smoke-expected-text");
        assertTrue(item.contains("Page contains \\\"Welcome back\\\"."), item);
        assertTrue(item.contains("The text was found."), item);
        assertEquals(1, page.textSearches, "the page is searched exactly once");
    }

    @Test
    void pageTextThatDoesNotContainTheConfiguredValueFails() throws IOException {
        FakePage page = new FakePage(PAGE, "Example", true).pageText("Nothing here");

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, null, "Welcome back");

        assertEquals(PageHealthModule.Outcome.FAILED, result.outcome());
        assertEquals("Page did not contain expected visible/source text: Welcome back",
            result.message());
        String item = itemWithId("smoke-expected-text");
        assertTrue(item.contains("The text was not found."), item);
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
    }

    @Test
    void bothConfiguredExpectationsRunInOrderWhenBothAreSet() throws IOException {
        FakePage page = new FakePage(PAGE, "Example Shop", true).pageText("Welcome back");

        PageHealthModule.Result result =
            module(page, recording()).inspect(PAGE, null, "Example", "Welcome back");

        assertEquals(PageHealthModule.Outcome.PASSED, result.outcome());
        assertEquals(3, items().size(), "page, title and text items");
        assertTrue(items().get(0).contains("\"id\":\"smoke-page-1\""), items().get(0));
        assertTrue(items().get(1).contains("\"id\":\"smoke-expected-title\""), items().get(1));
        assertTrue(items().get(2).contains("\"id\":\"smoke-expected-text\""), items().get(2));
    }

    // --- evidence continuity and ownership -----------------------------------------------------

    @Test
    void theModuleContinuesTheSequenceTheWrapperAlreadyStarted() throws IOException {
        SmokeExecutionEvidence execution = recording();
        // Exactly what the wrapper records before handing over.
        execution.navigated(PAGE, 12);

        module(new FakePage(PAGE, "Example", true), execution).inspect(PAGE, null, null, null);

        List<String> recorded = steps();
        assertTrue(recorded.get(0).contains("\"sequence\":1"), recorded.get(0));
        assertTrue(recorded.get(0).contains("\"method\":\"NAVIGATE\""), recorded.get(0));
        // The module must not restart at 1: numbering is per test case, not per object.
        assertTrue(recorded.get(1).contains("\"sequence\":2"), recorded.get(1));
        for (String line : recorded) {
            assertTrue(line.contains("\"checkId\":\"SMOKE\""), line);
            assertTrue(line.contains("\"testCaseId\":\"smoke-page-1\""), line);
        }
    }

    @Test
    void theModuleNeitherNavigatesNorOwnsABrowserOrACrawlQueue() throws IOException {
        String source = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/smoke/PageHealthModule.java")));

        for (String forbidden : List.of(
            "org.junit", "assertTrue(", "assertFalse(", "assumeTrue(",
            "openUrl", "driver.get", "new ChromeDriver", "driver.quit", "driver.close",
            ".click(", ".submit()", "sendKeys", "executeScript", "Keys.",
            "visited", "frontier", "queue", "Crawl",
            "NavigationLinkInspector", "HttpClient", "GenericLoginHelper")) {
            assertFalse(source.contains(forbidden), "PageHealthModule must not contain " + forbidden);
        }
    }

    /** Executable code only: a comment must neither satisfy a guard nor trip one. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * The four page reads, scripted.
     *
     * <p>It exposes nothing that could navigate, click or submit — which is the point: the module
     * could not perform those actions even if it tried.
     */
    private static final class FakePage implements PageHealthModule.PageState {

        private final String url;
        private final String title;
        private final boolean loaded;
        private String pageText = "";
        private RuntimeException titleFailure;

        private int urlReads;
        private int titleReads;
        private int textSearches;

        FakePage(String url, String title, boolean loaded) {
            this.url = url;
            this.title = title;
            this.loaded = loaded;
        }

        FakePage pageText(String text) { this.pageText = text; return this; }
        FakePage titleThrows(RuntimeException failure) { this.titleFailure = failure; return this; }

        @Override
        public String currentUrl() {
            urlReads++;
            return url;
        }

        @Override
        public String title() {
            titleReads++;
            if (titleFailure != null) throw titleFailure;
            return title;
        }

        @Override
        public boolean hasLoadedDocument() {
            return loaded;
        }

        @Override
        public boolean isTextPresent(String expectedText) {
            textSearches++;
            return pageText != null && pageText.contains(expectedText);
        }
    }
}
