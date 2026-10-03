package com.qanize.automation.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;
import com.qanize.automation.checks.gamelaunch.GameCardInspector;
import com.qanize.automation.checks.gamelaunch.GameLaunchModule;
import com.qanize.automation.checks.gamelaunch.LaunchNetworkProbe;
import com.qanize.automation.checks.gamelaunch.LaunchSurfaceDetector;
import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.model.CheckExecution;
import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.TestedItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Game Launch inside the run that owns it.
 *
 * <p>Three properties decide whether this check belongs in the pipeline at all, and each is a
 * claim about the whole run rather than about one game. It runs <em>once</em> — a check that opened
 * the same games again on every crawled page would hammer a third party's platform. It uses the
 * run's <em>own</em> driver, so the session the sign-in established is the session the game opens
 * under. And when it is not selected it does <em>nothing</em>: no discovery, no observation, no
 * evidence, so a run without it is the run that existed before it did.
 *
 * <p>No browser and no UAT. The crawl, the sign-in and the games are all seams; the fake driver
 * fails the test if anything creates a second one.
 */
@Tag("unit")
class GameLaunchRunIntegrationTest {

    private static final String BASE = "https://example.test/";
    /** The address the run was configured with — the one cleanup must return to. */
    private static final String CONFIGURED_START = BASE;

    @TempDir
    Path runDirectory;

    @BeforeEach
    void pointTheRecorderAtATemporaryFile() {
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("execution-evidence.jsonl").toString());
    }

    @AfterEach
    void clearTheRecorder() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    // --- not selected means not touched ------------------------------------------------------------

    @Test
    void aRunThatDidNotSelectTheCheckNeverOpensAGame() throws IOException {
        RecordingGames games = new RecordingGames(passed("Aviator"));

        CrawlRunSummary summary = fold(run(config(false), games, 2));

        assertEquals(0, games.runs, "nothing discovers a card when the check is not selected");
        CrawlRunSummary.CheckAggregate aggregate = aggregate(summary);
        assertFalse(aggregate.selected());
        assertFalse(aggregate.executed());
        assertEquals(CheckExecution.Status.NOT_TESTED, aggregate.status());
        assertFalse(Files.exists(runDirectory.resolve("execution-evidence.jsonl")),
            "an unselected check writes no evidence at all");
    }

    // --- once per run, on the run's own driver ------------------------------------------------------

    @Test
    void aSelectedCheckRunsExactlyOnceHoweverManyPagesWereCrawled() {
        RecordingGames games = new RecordingGames(passed("Aviator"));

        run(config(true), games, 4);

        assertEquals(1, games.runs, "once for the run, never once per crawled page");
    }

    @Test
    void theCheckUsesTheRunsOwnDriverRatherThanASecondOne() {
        RecordingGames games = new RecordingGames(passed("Aviator"));
        ScriptedRun scripted = new ScriptedRun();

        run(config(true), games, 2, scripted);

        assertEquals(1, scripted.browsersStarted, "exactly one browser for the whole run");
        assertSame(scripted.driver, games.driver,
            "the games open on the driver the sign-in already used");
    }

    @Test
    void theCheckRunsAfterTheSignInHasCompleted() {
        RecordingGames games = new RecordingGames(passed("Aviator"));
        ScriptedRun scripted = new ScriptedRun();

        run(loginRequired(), games, 2, scripted);

        assertEquals(List.of("startBrowser", "signIn", "captureLinkSession", "crawl", "games"),
            scripted.calls, "a game is opened only once the run is signed in");
    }

    @Test
    void cleanupReturnsToTheConfiguredStartAddressNotToWhereTheBrowserEndedUp() {
        // A site may route its list through one address and settle on another; loading the one it
        // settled on can produce an empty list, so the configured address is what cleanup uses.
        RecordingGames games = new RecordingGames(passed("Aviator"));
        ScriptedRun scripted = new ScriptedRun();
        scripted.driver.url = "https://example.test/somewhere-else-after-a-redirect";

        run(config(true), games, 2, scripted);

        assertEquals(CONFIGURED_START, games.returnUrl);
    }

    @Test
    void theConfiguredBudgetIsWhatTheCheckIsGiven() {
        RecordingGames games = new RecordingGames(passed("Aviator"));

        run(config(true, 7), games, 2);

        assertEquals(7, games.maxGames);
    }

    // --- how games reach the fold --------------------------------------------------------------------

    @Test
    void aPassedGameMakesTheCheckPass() {
        CrawlRunSummary summary = fold(run(config(true),
            new RecordingGames(passed("Aviator")), 2));

        CrawlRunSummary.CheckAggregate aggregate = aggregate(summary);
        assertTrue(aggregate.selected());
        assertTrue(aggregate.executed());
        assertEquals(CheckExecution.Status.PASSED, aggregate.status());
        assertEquals(1, aggregate.passed());
    }

    @Test
    void oneFailedGameFailsTheCheckEvenWhenAnotherPassed() {
        CrawlRunSummary summary = fold(run(config(true),
            new RecordingGames(passed("One"), failed("Two")), 2));

        CrawlRunSummary.CheckAggregate aggregate = aggregate(summary);
        assertEquals(CheckExecution.Status.FAILED, aggregate.status());
        assertEquals(1, aggregate.passed());
        assertEquals(1, aggregate.failed());
        assertEquals(2, aggregate.considered());
    }

    @Test
    void aRunWhereEveryGameWasSkippedReportsSkippedWithAReason() {
        CrawlRunSummary summary = fold(run(config(true),
            new RecordingGames(skipped("One")), 2));

        CrawlRunSummary.CheckAggregate aggregate = aggregate(summary);
        assertEquals(CheckExecution.Status.SKIPPED, aggregate.status());
        assertFalse(aggregate.skipReason().isBlank());
        assertFalse(aggregate.executed());
    }

    // --- the setup-problem contract ---------------------------------------------------------------------

    @Test
    void aListThatCannotBeRestoredIsASetupProblemAndNotAWebsiteFinding() {
        GameLaunchModule.Result stopped = new GameLaunchModule.Result(
            GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM,
            "The game list could not be restored after a launch.",
            List.of(cleanupFailed("One")));

        CrawlRunSummary summary = fold(run(config(true), new RecordingGames(stopped), 2));

        assertTrue(summary.hasGameLaunchSetupProblem());
        assertEquals("The game list could not be restored after a launch.",
            summary.gameLaunchSetupProblem());
        // The game itself is SKIPPED, never FAILED: it was not tested, and the website did nothing
        // wrong. There is no fourth status, and none was invented.
        assertEquals(TestedItem.Status.SKIPPED, stopped.games().getFirst().status());
        assertEquals(GameLaunchModule.RUN_SETUP_PROBLEM_OUTCOME,
            stopped.games().getFirst().evidence().get("gameOutcome"));
        assertEquals(CheckExecution.Status.SKIPPED, aggregate(summary).status());
        // And it is not an analyzer crash either.
        assertFalse(summary.hasAnalyzerFailure());
    }

    @Test
    void aRunThatCompletedCarriesNoSetupProblem() {
        CrawlRunSummary summary = fold(run(config(true),
            new RecordingGames(passed("One"), failed("Two")), 2));

        assertFalse(summary.hasGameLaunchSetupProblem(),
            "an ordinary failed game is a finding, not a reason to stop");
    }

    // --- the production wiring ---------------------------------------------------------------------------

    @Test
    void thePublicRunnerConstructorSuppliesARealGameLaunchRatherThanASeam() throws Exception {
        // The production constructor, exactly as the coordinator calls it. Nothing is started
        // here: a browser is only opened inside run(), so this is the real object graph.
        CrawlRunner production = new CrawlRunner();

        Field wired = CrawlRunner.class.getDeclaredField("games");
        wired.setAccessible(true);
        CrawlRunner.GameLaunchExecution games = (CrawlRunner.GameLaunchExecution) wired.get(production);

        // Proved by behaviour, not by identity: a no-op or a stub could never open a game.
        Catalogue browser = new Catalogue(BASE + "wherever-the-crawl-ended");
        browser.onNavigate = () -> { if (CONFIGURED_START.equals(browser.url)) browser.showCard(); };
        browser.onPointerClick = () -> { browser.url = BASE + "play"; browser.cards.clear(); };

        GameLaunchModule.Result result = games.run(browser, config(true, 1));

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(1, result.games().size(), "the real module opened the game");
        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status(),
            () -> String.valueOf(result.games().getFirst().reason()));
        assertEquals(1, browser.controlClicks, "a real demo control was clicked exactly once");
    }

    @Test
    void theProductionSessionCheckAsksTheBrowserInsteadOfAnsweringYes() {
        // One difference between the two runs: after the launch the configured signed-in
        // indicator is gone. A constant, or a check wired to nothing, cannot tell them apart —
        // and neither could the old heuristic, which read the absence of a login form as proof.
        GameLaunchModule.GameResult stillSignedIn = launchUnderProductionWiring(false);
        assertEquals(TestedItem.Status.PASSED, stillSignedIn.status(),
            () -> String.valueOf(stillSignedIn.reason()));
        assertEquals("VERIFIED", stillSignedIn.evidence().get("sessionPreserved"));

        GameLaunchModule.GameResult signedOut = launchUnderProductionWiring(true);
        assertEquals(TestedItem.Status.FAILED, signedOut.status());
        assertEquals("the signed-in session did not survive the game launch", signedOut.reason());
        assertEquals("NOT_VERIFIED", signedOut.evidence().get("sessionPreserved"));
    }

    @Test
    void aRunWithNoSignInAsksTheBrowserNothingAboutASessionItNeverHad() {
        Catalogue browser = catalogue();

        GameLaunchModule.Result result = productionGames().run(browser, config(true, 1));

        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status());
        assertEquals(0, browser.indicatorQueries,
            "no sign-in was configured, so there is no session to interrogate");
        assertEquals("NOT_APPLICABLE",
            result.games().getFirst().evidence().get("sessionPreserved"));
    }

    /** One game opened through the production wiring, with the session kept or lost. */
    private GameLaunchModule.GameResult launchUnderProductionWiring(boolean sessionIsLost) {
        Catalogue browser = catalogue();
        browser.signedInIndicatorVisible = true;
        browser.onPointerClick = () -> {
            browser.url = BASE + "play";
            browser.cards.clear();
            if (sessionIsLost) browser.signedInIndicatorVisible = false;
        };

        GameLaunchModule.Result result = productionGames().run(browser, loginRequired());

        assertTrue(browser.indicatorQueries > 0,
            "a configured sign-in means the browser is actually asked");
        return result.games().getFirst();
    }

    /** A catalogue that exists only at the configured address. */
    private static Catalogue catalogue() {
        Catalogue browser = new Catalogue(BASE + "wherever-the-crawl-ended");
        browser.onNavigate = () -> { if (CONFIGURED_START.equals(browser.url)) browser.showCard(); };
        browser.onPointerClick = () -> { browser.url = BASE + "play"; browser.cards.clear(); };
        return browser;
    }

    /** The game-launch step the production constructor wired, read from the runner itself. */
    private static CrawlRunner.GameLaunchExecution productionGames() {
        try {
            Field wired = CrawlRunner.class.getDeclaredField("games");
            wired.setAccessible(true);
            return (CrawlRunner.GameLaunchExecution) wired.get(new CrawlRunner());
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError(unreachable);
        }
    }

    @Test
    void noPublicRunnerConstructorLetsAnythingReplaceTheGameLaunchStep() {
        for (java.lang.reflect.Constructor<?> visible : CrawlRunner.class.getConstructors()) {
            for (Class<?> parameter : visible.getParameterTypes()) {
                assertFalse(CrawlRunner.GameLaunchExecution.class.equals(parameter),
                    "a public constructor must not accept a game-launch seam: " + visible);
            }
        }
    }

    @Test
    void theListIsOpenedAtTheConfiguredAddressEvenThoughTheCrawlEndedElsewhere() {
        Catalogue browser = new Catalogue(CONFIGURED_START);
        // The catalogue exists only at the configured address. If discovery ran before the list
        // was opened — or against the address the crawl left behind — there would be no games.
        browser.onNavigate = () -> { if (CONFIGURED_START.equals(browser.url)) browser.showCard(); };
        // A launch that leaves the tab somewhere else and empties the list, as a real one does.
        browser.onPointerClick = () -> { browser.url = BASE + "play?token=x"; browser.cards.clear(); };

        ProductionRun scripted = new ProductionRun(browser);
        scripted.pagesCrawled = 3;
        scripted.lastCrawledUrl = BASE + "page3";

        CrawlRunner.CrawlRunResult outcome =
            new CrawlRunner(scripted, name -> "value", new CrawlScreenshotRecorder())
                .run(config(true, 1));

        CrawlRunner.CrawlRunResult.Crawled crawled = (CrawlRunner.CrawlRunResult.Crawled) outcome;
        assertEquals(CONFIGURED_START, scripted.crawlStartUrl, "the crawl began at the start URL");
        assertEquals(3, crawled.crawl().attempted().size(), "several pages were processed");

        // The crawl left the driver on its last page; Game Launch went back to the configured
        // address itself rather than discovering against whatever was on screen.
        assertEquals(List.of(BASE + "page3", CONFIGURED_START, CONFIGURED_START),
            browser.navigations,
            "one crawl address, then the configured address to open the list and again to restore it");
        assertEquals(CONFIGURED_START, browser.url, "the run ends back on the list");
        assertEquals(1, browser.controlClicks, "the games were opened exactly once for the whole run");
        assertEquals(1, browser.hovers, "one card, hovered once");
        assertEquals(1, browser.quits, "the run still owns and closes its one browser");
        assertEquals(1, crawled.gameLaunch().games().size());
        assertEquals(TestedItem.Status.PASSED, crawled.gameLaunch().games().getFirst().status());
    }

    @Test
    void aRunThatDidNotSelectGameLaunchNavigatesNowhereAfterTheCrawl() {
        Catalogue browser = new Catalogue(CONFIGURED_START);
        browser.onNavigate = () -> { if (CONFIGURED_START.equals(browser.url)) browser.showCard(); };

        ProductionRun scripted = new ProductionRun(browser);
        scripted.pagesCrawled = 2;
        scripted.lastCrawledUrl = BASE + "page2";

        CrawlRunner.CrawlRunResult outcome =
            new CrawlRunner(scripted, name -> "value", new CrawlScreenshotRecorder())
                .run(config(false, 1));

        assertEquals(List.of(BASE + "page2"), browser.navigations,
            "an unselected check costs the run not one extra page load");
        assertEquals(0, browser.controlClicks);
        assertEquals(0, browser.cardQueries);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static CrawlRunSummary.CheckAggregate aggregate(CrawlRunSummary summary) {
        return summary.check(CheckId.GAME_LAUNCH);
    }

    private static CrawlRunSummary fold(CrawlRunner.CrawlRunResult result) {
        return foldOfARunThatSelectedEverything(result);
    }

    private CrawlRunner.CrawlRunResult run(
        WebsiteConfig config, RecordingGames games, int pagesCrawled
    ) {
        return run(config, games, pagesCrawled, new ScriptedRun());
    }

    private CrawlRunner.CrawlRunResult run(
        WebsiteConfig config, RecordingGames games, int pagesCrawled, ScriptedRun scripted
    ) {
        scripted.pagesCrawled = pagesCrawled;
        scripted.games = games;
        return new CrawlRunner(scripted, name -> "value", new CrawlScreenshotRecorder(), games).run(config);
    }

    private static WebsiteConfig config(boolean gameLaunchSelected) {
        return config(gameLaunchSelected, 3);
    }

    private static WebsiteConfig config(boolean gameLaunchSelected, int maxGames) {
        return config(gameLaunchSelected, maxGames, false);
    }

    private static WebsiteConfig loginRequired() {
        return config(true, 3, true);
    }

    private static WebsiteConfig config(
        boolean gameLaunchSelected, int maxGames, boolean loginSelected
    ) {
        return new WebsiteConfig(
            "1.0", "example", URI.create(BASE), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, 5, 25, 10,
            loginSelected ? login() : null,
            new WebsiteTestSelectionConfig(true, false, true, false, false, loginSelected),
            new WebsiteGameLaunchConfig(gameLaunchSelected, maxGames,
                "app-game-item", ".name", ".provider", "button.demo", null, null, null,
                // Named only when the run signs in, exactly as the validator requires.
                loginSelected ? "[data-test='account-menu']" : null, null));
    }

    private static com.qanize.automation.checks.login.WebsiteLoginConfig login() {
        return new com.qanize.automation.checks.login.WebsiteLoginConfig(
            true, URI.create(BASE + "signin"), "QANIZE_USER", "QANIZE_PASSWORD",
            "#user", "#pass", "#submit", "/home", "Welcome");
    }

    private static GameLaunchModule.Result passed(String name) {
        return completed(game(name, TestedItem.Status.PASSED, "PASSED", true));
    }

    private static GameLaunchModule.Result failed(String name) {
        return completed(game(name, TestedItem.Status.FAILED, "FAILED", true));
    }

    private static GameLaunchModule.Result skipped(String name) {
        return completed(game(name, TestedItem.Status.SKIPPED, "SKIPPED", false));
    }

    private static GameLaunchModule.GameResult cleanupFailed(String name) {
        return game(name, TestedItem.Status.SKIPPED,
            GameLaunchModule.RUN_SETUP_PROBLEM_OUTCOME, true);
    }

    private static GameLaunchModule.Result completed(GameLaunchModule.GameResult... games) {
        return new GameLaunchModule.Result(
            GameLaunchModule.RunStatus.COMPLETED, null, List.of(games));
    }

    private static GameLaunchModule.GameResult game(
        String name, TestedItem.Status status, String outcome, boolean clicked
    ) {
        GameCardInspector.GameCandidate candidate = new GameCardInspector.GameCandidate(
            name.toLowerCase(java.util.Locale.ROOT) + "|p", name, "P", 0, true, true);
        return new GameLaunchModule.GameResult(
            candidate, status, LaunchSurfaceDetector.SurfaceType.IFRAME, null,
            clicked, GameLaunchModule.SessionState.NOT_APPLICABLE,
            !GameLaunchModule.RUN_SETUP_PROBLEM_OUTCOME.equals(outcome),
            LaunchNetworkProbe.Observation.NOT_REPORTED,
            Map.of("gameOutcome", outcome, "displayName", name));
    }

    /** Records what the run handed the game-launch step, and how often. */
    private static final class RecordingGames implements CrawlRunner.GameLaunchExecution {

        private final List<GameLaunchModule.Result> scripted = new ArrayList<>();
        private int runs;
        private WebDriver driver;
        private String returnUrl;
        private int maxGames;
        private Runnable onRun;

        private RecordingGames(GameLaunchModule.Result... results) {
            this.scripted.addAll(List.of(results));
        }

        @Override
        public GameLaunchModule.Result run(WebDriver driver, WebsiteConfig config) {
            runs++;
            this.driver = driver;
            // Exactly what production reads: the configured start address, never getCurrentUrl().
            this.returnUrl = config.baseUrl().toString();
            this.maxGames = config.gameLaunch().maxGames();
            if (onRun != null) onRun.run();
            if (scripted.size() == 1) return scripted.getFirst();
            List<GameLaunchModule.GameResult> all = new ArrayList<>();
            scripted.forEach(result -> all.addAll(result.games()));
            return new GameLaunchModule.Result(
                GameLaunchModule.RunStatus.COMPLETED, null, all);
        }
    }

    /** The run's browser, sign-in and crawl, all scripted. Creating a second driver fails here. */
    private static final class ScriptedRun implements CrawlRunner.RunOperations {

        private final List<String> calls = new ArrayList<>();
        private final CountingDriver driver = new CountingDriver();
        private int browsersStarted;
        private int pagesCrawled;
        private RecordingGames games;

        @Override
        public WebDriver startBrowser(WebsiteConfig config) {
            browsersStarted++;
            if (browsersStarted > 1) throw new AssertionError("a run has exactly one browser");
            calls.add("startBrowser");
            if (games != null) games.onRun = () -> calls.add("games");
            return driver;
        }

        @Override
        public com.qanize.automation.checks.login.LoginModule.Result signIn(
            WebDriver driver, WebsiteConfig config,
            com.qanize.automation.checks.login.WebsiteLoginConfig login,
            String username, String password
        ) {
            calls.add("signIn");
            return new com.qanize.automation.checks.login.LoginModule.Result(
                com.qanize.automation.checks.login.LoginModule.Outcome.PASSED, null);
        }

        @Override
        public com.qanize.automation.checks.links.LinkSession captureLinkSession(
            WebDriver driver, WebsiteConfig config
        ) {
            calls.add("captureLinkSession");
            return com.qanize.automation.checks.links.LinkSession.anonymous();
        }

        @Override
        public Crawler.CrawlResult crawl(
            com.qanize.automation.engine.CrawlPolicy policy, String startUrl,
            CrawlerPageProcessor processor
        ) {
            calls.add("crawl");
            List<Crawler.PageResult> attempted = new ArrayList<>();
            for (int page = 1; page <= pagesCrawled; page++) {
                attempted.add(new Crawler.PageResult(
                    BASE + "page" + page, 1, BASE, Crawler.PageStatus.PROCESSED, null, null));
            }
            return new Crawler.CrawlResult(attempted,
                new Crawler.LimitReport(10, 3, false, false, 0));
        }
    }

    /** A driver that exists to be counted and handed around, never to be replaced. */
    private static final class CountingDriver implements WebDriver {
        private String url = BASE;
        @Override public void get(String destination) { url = destination; }
        @Override public String getCurrentUrl() { return url; }
        @Override public String getTitle() { return "example"; }
        @Override public List<org.openqa.selenium.WebElement> findElements(
            org.openqa.selenium.By by) { return List.of(); }
        @Override public org.openqa.selenium.WebElement findElement(org.openqa.selenium.By by) {
            throw new org.openqa.selenium.NotFoundException("none");
        }
        @Override public String getPageSource() { return "<html></html>"; }
        @Override public void close() { }
        @Override public void quit() { }
        @Override public java.util.Set<String> getWindowHandles() { return java.util.Set.of("one"); }
        @Override public String getWindowHandle() { return "one"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new UnsupportedOperationException(); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }

    /** The run's browser, sign-in and crawl, scripted around a driver the test supplies. */
    private static final class ProductionRun implements CrawlRunner.RunOperations {

        private final WebDriver browser;
        private int browsersStarted;
        private int pagesCrawled;
        private String crawlStartUrl;
        private String lastCrawledUrl;

        private ProductionRun(WebDriver browser) {
            this.browser = browser;
        }

        @Override
        public WebDriver startBrowser(WebsiteConfig config) {
            browsersStarted++;
            if (browsersStarted > 1) throw new AssertionError("a run has exactly one browser");
            return browser;
        }

        @Override
        public com.qanize.automation.checks.login.LoginModule.Result signIn(
            WebDriver driver, WebsiteConfig config,
            com.qanize.automation.checks.login.WebsiteLoginConfig login,
            String username, String password
        ) {
            return new com.qanize.automation.checks.login.LoginModule.Result(
                com.qanize.automation.checks.login.LoginModule.Outcome.PASSED, null);
        }

        @Override
        public com.qanize.automation.checks.links.LinkSession captureLinkSession(
            WebDriver driver, WebsiteConfig config
        ) {
            return com.qanize.automation.checks.links.LinkSession.anonymous();
        }

        @Override
        public Crawler.CrawlResult crawl(
            com.qanize.automation.engine.CrawlPolicy policy, String startUrl,
            CrawlerPageProcessor processor
        ) {
            crawlStartUrl = startUrl;
            List<Crawler.PageResult> attempted = new ArrayList<>();
            for (int page = 1; page <= pagesCrawled; page++) {
                attempted.add(new Crawler.PageResult(
                    BASE + "page" + page, 1, BASE, Crawler.PageStatus.PROCESSED, null, null));
            }
            // A real crawl leaves the browser on the last page it visited.
            if (lastCrawledUrl != null) browser.get(lastCrawledUrl);
            return new Crawler.CrawlResult(attempted,
                new Crawler.LimitReport(10, 3, false, false, 0));
        }
    }

    /**
     * A catalogue that exists at one address only.
     *
     * <p>Everything the real module needs and nothing it must not have: no DevTools, so the probe
     * degrades; no history navigation; and the card itself refuses to be clicked.
     */
    private static final class Catalogue implements WebDriver, Interactive {

        private static final String CARD = "app-game-item";
        /** The signed-in indicator the configuration names; the session check polls it. */
        private static final String SIGNED_IN = "[data-test='account-menu']";
        private static final String NAME = ".name";
        private static final String PROVIDER = ".provider";
        private static final String DEMO = "button.demo";

        private String url;
        private final List<String> navigations = new ArrayList<>();
        private final List<WebElement> cards = new ArrayList<>();
        private final Set<String> handles = new LinkedHashSet<>(Set.of("one"));
        private int controlClicks;
        private int hovers;
        private int cardQueries;
        private int quits;
        private int indicatorQueries;
        private boolean signedInIndicatorVisible;
        private Runnable onNavigate;
        private Runnable onPointerClick;

        private Catalogue(String url) {
            this.url = url;
        }

        /** Puts the one game back on the list, without duplicating it. */
        private void showCard() {
            if (cards.isEmpty()) cards.add(new Tile(this));
        }

        @Override
        public void get(String destination) {
            navigations.add(destination);
            url = destination;
            if (onNavigate != null) onNavigate.run();
        }

        @Override
        public List<WebElement> findElements(By by) {
            String selector = by.toString().replace("By.cssSelector: ", "");
            if (CARD.equals(selector)) {
                cardQueries++;
                return List.copyOf(cards);
            }
            if (SIGNED_IN.equals(selector)) {
                indicatorQueries++;
                return signedInIndicatorVisible ? List.of(new Label(this, "")) : List.of();
            }
            return List.of();
        }

        @Override public WebElement findElement(By by) {
            List<WebElement> found = findElements(by);
            if (found.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return found.getFirst();
        }
        @Override public String getCurrentUrl() { return url; }
        @Override public String getTitle() { return "catalogue"; }
        @Override public String getPageSource() { return "<html></html>"; }
        @Override public void close() { handles.remove("one"); }
        @Override public void quit() { quits++; }
        @Override public Set<String> getWindowHandles() { return Set.copyOf(handles); }
        @Override public String getWindowHandle() { return "one"; }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() {
            throw new AssertionError("browser history is never used");
        }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }

        /**
         * The one pointer mechanism, hover and click alike.
         *
         * <p>The production module hovers with the same {@code Actions} pipeline it clicks with,
         * so the two arrive here as the same call. A press is what separates them: a hover moves
         * and stops, a click moves and puts the pointer down.
         */
        @Override
        public void perform(java.util.Collection<Sequence> actions) {
            boolean pressed = actions.stream()
                .anyMatch(sequence -> String.valueOf(sequence.encode()).contains("pointerDown"));
            if (!pressed) {
                hovers++;
                return;
            }
            controlClicks++;
            if (onPointerClick != null) onPointerClick.run();
        }

        @Override public void resetInputState() { }

        /** One game card, with the demo control the configuration names. */
        private record Tile(Catalogue page) implements WebElement {

            @Override
            public List<WebElement> findElements(By by) {
                String selector = by.toString().replace("By.cssSelector: ", "");
                if (NAME.equals(selector)) return List.of(new Label(page, "Meteor Rush"));
                if (PROVIDER.equals(selector)) return List.of(new Label(page, "Studio Nine"));
                if (DEMO.equals(selector)) return List.of(new Label(page, "Demo"));
                return List.of();
            }

            @Override public WebElement findElement(By by) {
                List<WebElement> found = findElements(by);
                if (found.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
                return found.getFirst();
            }
            @Override public void click() { throw new AssertionError("the card itself is never clicked"); }
            @Override public String getTagName() { return CARD; }
            @Override public String getAttribute(String name) { return null; }
            @Override public boolean isSelected() { return false; }
            @Override public boolean isEnabled() { return true; }
            @Override public String getText() { return ""; }
            @Override public boolean isDisplayed() { return true; }
            @Override public void clear() { throw new AssertionError("nothing is cleared"); }
            @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("nothing is typed"); }
            @Override public void submit() { throw new AssertionError("nothing is submitted"); }
            @Override public String getCssValue(String property) { return ""; }
            @Override public Point getLocation() { return new Point(0, 0); }
            @Override public Dimension getSize() { return new Dimension(200, 120); }
            @Override public Rectangle getRect() {
                return new Rectangle(new Point(0, 0), new Dimension(200, 120));
            }
            @Override public <X> X getScreenshotAs(OutputType<X> target) {
                throw new AssertionError("this check takes no screenshot of its own");
            }
        }

        /** A name, a provider or the demo control. */
        private record Label(Catalogue page, String text) implements WebElement {
            @Override public List<WebElement> findElements(By by) { return List.of(); }
            @Override public WebElement findElement(By by) {
                throw new org.openqa.selenium.NotFoundException("none");
            }
            /**
             * The launch control's own click, which is how the game launch now opens a game.
             *
             * <p>Everything else in this project is still clicked through the shared pointer.
             */
            @Override public void click() {
                page.controlClicks++;
                if (page.onPointerClick != null) page.onPointerClick.run();
            }
            @Override public String getTagName() { return "span"; }
            @Override public String getAttribute(String name) {
                if ("src".equals(name)) throw new AssertionError("a frame address must never be read");
                return null;
            }
            @Override public boolean isSelected() { return false; }
            @Override public boolean isEnabled() { return true; }
            @Override public String getText() { return text; }
            @Override public boolean isDisplayed() { return true; }
            @Override public void clear() { throw new AssertionError("nothing is cleared"); }
            @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("nothing is typed"); }
            @Override public void submit() { throw new AssertionError("nothing is submitted"); }
            @Override public String getCssValue(String property) { return ""; }
            @Override public Point getLocation() { return new Point(0, 0); }
            @Override public Dimension getSize() { return new Dimension(80, 30); }
            @Override public Rectangle getRect() {
                return new Rectangle(new Point(0, 0), new Dimension(80, 30));
            }
            @Override public <X> X getScreenshotAs(OutputType<X> target) {
                throw new AssertionError("this check takes no screenshot of its own");
            }
        }
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
