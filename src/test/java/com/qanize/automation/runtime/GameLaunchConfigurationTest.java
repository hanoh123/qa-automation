package com.qanize.automation.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.config.RunConfigurationException;
import com.qanize.automation.config.RunConfigurationValidator;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.engine.CrawlRunner;
import com.qanize.automation.engine.CrawlScope;
import com.qanize.automation.engine.Crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Game Launch configuration pathway, end to end, for a check that does not exist yet.
 *
 * <p>Two claims, and they have to hold together. The values a caller supplies must survive the
 * whole journey — command line, run request, generated properties, loader, {@code WebsiteConfig} —
 * because the check that will read them has to find them correct on the day it is written. And
 * asking for the check itself must fail loudly before anything opens a browser, because there is
 * no honest result this build could report for it.
 *
 * <p>Nothing here starts a browser, and nothing here can: the Maven runner and the crawl are both
 * stand-ins that count calls, and the cases that matter assert those counts are zero.
 */
@Tag("unit")
class GameLaunchConfigurationTest {

    // Deliberately not real selectors from any operator's site: they exist to be carried, and a
    // default copied from one site would be a guess applied to every other.
    private static final String CARD = "[data-test='game-card']";
    private static final String NAME = ".card .title";
    private static final String PROVIDER = ".card .vendor";
    private static final String DEMO = "button[data-action='demo']";
    private static final String SURFACE = "#game-frame";
    private static final String ERROR = ".game-error";
    private static final String CLOSE = "button.close-game";
    private static final String INDICATOR = "[data-test='account-menu']";
    private static final String BLOCKER = "[data-test='promo'] button.dismiss";

    // --- the whole pathway ---------------------------------------------------------------------

    @Test
    void everyConfiguredSelectorSurvivesTheCommandLineThePropertiesAndTheLoader() throws IOException {
        String runId = uniqueRunId("game-plumbing");
        CountingMaven maven = new CountingMaven();

        CliResult result = runCli(maven, args(runId,
            "--maxGames=7",
            "--gameCardSelector=" + CARD,
            "--gameNameSelector=" + NAME,
            "--gameProviderSelector=" + PROVIDER,
            "--gameLaunchTriggerSelector=" + DEMO,
            "--gameSurfaceSelector=" + SURFACE,
            "--gameErrorSelector=" + ERROR,
            "--gameCloseSelector=" + CLOSE));

        assertEquals(0, result.exitCode(), result.err());
        WebsiteGameLaunchConfig loaded = loadGeneratedConfig(runId).gameLaunch();

        assertEquals(CARD, loaded.cardSelector());
        assertEquals(NAME, loaded.nameSelector());
        assertEquals(PROVIDER, loaded.providerSelector());
        assertEquals(DEMO, loaded.launchTriggerSelector());
        assertEquals(SURFACE, loaded.gameSurfaceSelector());
        assertEquals(ERROR, loaded.errorSelector());
        assertEquals(CLOSE, loaded.closeSelector());
        assertEquals(7, loaded.maxGames());
        // Configuration travelled; the check did not become runnable by travelling.
        assertFalse(loaded.enabled());
    }

    @Test
    void aSelectorKeepsItsSpacesQuotesAndPunctuationInsideOneArgument() throws IOException {
        String runId = uniqueRunId("game-selector-shape");
        // Everything a real selector can carry, plus the characters a shell would act on.
        String awkward = "div[data-name='Book of $tuff'] > .card:not(.hidden) | rm -rf /";

        CliResult result = runCli(new CountingMaven(),
            args(runId, "--gameCardSelector=" + awkward));

        assertEquals(0, result.exitCode(), result.err());
        assertEquals(awkward, loadGeneratedConfig(runId).gameLaunch().cardSelector());
    }

    @Test
    void surroundingWhitespaceIsTrimmedAndABlankSelectorStaysAbsent() throws IOException {
        String runId = uniqueRunId("game-trim");

        CliResult result = runCli(new CountingMaven(), args(runId,
            "--gameCardSelector=   " + CARD + "   ",
            "--gameNameSelector=   "));

        assertEquals(0, result.exitCode(), result.err());
        WebsiteGameLaunchConfig loaded = loadGeneratedConfig(runId).gameLaunch();
        assertEquals(CARD, loaded.cardSelector());
        assertNull(loaded.nameSelector(), "blank is absent, never an empty selector");
    }

    // --- the launch budget ---------------------------------------------------------------------

    @Test
    void theLaunchBudgetAcceptsItsWholeRangeAndNothingOutsideIt() {
        for (int accepted : List.of(WebsiteGameLaunchConfig.MIN_GAMES, 2,
            WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES, WebsiteGameLaunchConfig.MAX_GAMES)) {
            RunConfigurationValidator.validate(requestWithMaxGames(accepted));
        }
        for (int rejected : List.of(0, -1, WebsiteGameLaunchConfig.MAX_GAMES + 1, 100)) {
            RunConfigurationException failure = assertThrows(RunConfigurationException.class,
                () -> RunConfigurationValidator.validate(requestWithMaxGames(rejected)),
                "maxGames=" + rejected + " must be refused");
            assertEquals("VALUE_OUT_OF_RANGE", failure.code());
            assertTrue(failure.userMessage().contains("limits.maxGames"), failure.userMessage());
        }
    }

    @Test
    void anOmittedBudgetBecomesTheDocumentedDefaultRatherThanZero() throws IOException {
        String runId = uniqueRunId("game-default-budget");

        assertEquals(0, runCli(new CountingMaven(), args(runId)).exitCode());

        assertEquals(WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES,
            loadGeneratedConfig(runId).gameLaunch().maxGames());
        assertEquals(3, WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES, "the documented default");
    }

    @Test
    void aMalformedBudgetIsRefusedByTheSameArgumentHandlingEveryNumberUses() {
        CliResult result = runCli(new CountingMaven(),
            args(uniqueRunId("game-bad-budget"), "--maxGames=three"));

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("maxGames must be a whole number"), result.err());
    }

    @Test
    void aRepeatedBudgetArgumentTakesTheLastValue() throws IOException {
        // Not a new rule: every repeated argument has behaved this way since the parser was
        // written, and Game Launch does not get a private one.
        String runId = uniqueRunId("game-repeat-budget");

        assertEquals(0,
            runCli(new CountingMaven(), args(runId, "--maxGames=2", "--maxGames=9")).exitCode());

        assertEquals(9, loadGeneratedConfig(runId).gameLaunch().maxGames());
    }

    @Test
    void theLaunchBudgetMovesNeitherThePageBudgetNorTheLinkBudget() throws IOException {
        String runId = uniqueRunId("game-budget-independent");

        assertEquals(0, runCli(new CountingMaven(),
            args(runId, "--maxPages=4", "--maxLinks=17", "--maxGames=10")).exitCode());

        WebsiteConfig config = loadGeneratedConfig(runId);
        assertEquals(4, config.maxPages());
        assertEquals(17, config.maxLinks());
        assertEquals(10, config.gameLaunch().maxGames());
    }

    // --- configurations written before Game Launch existed -------------------------------------

    @Test
    void aPropertiesFileWithNoGameKeysLoadsExactlyAsItAlwaysDid() throws IOException {
        Path file = Files.createTempFile("legacy-run", ".properties");
        Files.writeString(file, """
            contract.version=1.0
            site.url=https://example.com/
            tests.smoke=true
            tests.links=true
            maxPages=5
            maxLinks=25
            """);

        WebsiteGameLaunchConfig gameLaunch = WebsiteConfigLoader.load(file).gameLaunch();

        assertFalse(gameLaunch.enabled());
        assertFalse(gameLaunch.anySelectorConfigured());
        assertEquals(WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES, gameLaunch.maxGames());
    }

    @Test
    void aRequestBuiltTheOldWayCarriesTheDisabledBlockAndStillValidates() {
        AutomationRunRequest legacy = AutomationRunRequest.simple("https://example.com/");

        assertFalse(legacy.gameLaunch().enabled());
        assertFalse(legacy.gameLaunch().anySelectorConfigured());
        assertEquals(WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES, legacy.gameLaunch().maxGames());
        RunConfigurationValidator.validate(legacy);
    }

    @Test
    void aLegacyCommandLineProducesTheGameKeysAtTheirAbsentValues() {
        Properties properties = new RuntimeConfigGenerator()
            .toProperties(AutomationRunRequest.simple("https://example.com/"));

        assertEquals("false", properties.getProperty("tests.gameLaunch"));
        assertEquals("3", properties.getProperty("maxGames"));
        for (String key : List.of("cardSelector", "nameSelector", "providerSelector",
            "launchTriggerSelector", "gameSurfaceSelector", "errorSelector", "closeSelector")) {
            assertEquals("", properties.getProperty("gameLaunch." + key),
                "gameLaunch." + key + " must be present and blank, never a guessed default");
        }
    }

    // --- selecting the check --------------------------------------------------------------------

    @Test
    void selectingTheCheckIsAcceptedNowThatItCanActuallyRun() {
        // The temporary rejection that stood here while the runtime was being built is gone. Every
        // other validation is untouched, which the bounds and length cases above still prove.
        RunConfigurationValidator.validate(enabledRequest());

        Properties properties = new RuntimeConfigGenerator().toProperties(enabledRequest());

        assertEquals("true", properties.getProperty("tests.gameLaunch"));
        assertEquals(CARD, properties.getProperty("gameLaunch.cardSelector"));
        assertEquals(DEMO, properties.getProperty("gameLaunch.launchTriggerSelector"));
    }

    @Test
    void aSelectedCheckIsRefusedWithoutTheThreeSelectorsItCannotProceedWithout() {
        // Refused while the person is still looking at the form, in the same three places the
        // form marks required, rather than discovered after a browser has started.
        for (String[] missing : new String[][] {
            {null, DEMO, SURFACE, "GAME_CARD_SELECTOR_REQUIRED"},
            {CARD, null, SURFACE, "GAME_LAUNCH_TRIGGER_SELECTOR_REQUIRED"},
            {CARD, DEMO, null, "GAME_SURFACE_SELECTOR_REQUIRED"},
        }) {
            RunConfigurationException failure = assertThrows(RunConfigurationException.class,
                () -> RunConfigurationValidator.validate(withGameLaunch(
                    new WebsiteGameLaunchConfig(true, 3, missing[0], null, null, missing[1],
                        missing[2], null, null))));

            assertEquals(missing[3], failure.code());
        }
    }

    @Test
    void anUnselectedCheckIsNeverAskedForASelector() {
        // Every request written before Game launch existed carries none of them and is untouched.
        RunConfigurationValidator.validate(withGameLaunch(
            new WebsiteGameLaunchConfig(false, 3, null, null, null, null, null, null, null)));

        Properties properties = new RuntimeConfigGenerator().toProperties(withGameLaunch(
            new WebsiteGameLaunchConfig(false, 3, null, null, null, null, null, null, null)));
        assertEquals("false", properties.getProperty("tests.gameLaunch"));
        assertEquals("", properties.getProperty("gameLaunch.cardSelector"));
        assertEquals("", properties.getProperty("gameLaunch.authenticatedIndicatorSelector"));
        assertEquals("", properties.getProperty("gameLaunch.blockingOverlayCloseSelector"));
    }

    // --- the two safety selectors ------------------------------------------------------------------

    @Test
    void bothSafetySelectorsSurviveTheCommandLineThePropertiesAndTheLoader() throws IOException {
        String runId = uniqueRunId("game-safety");

        assertEquals(0, runCli(new CountingMaven(), new CountingCrawl(), args(runId,
            "--gameLaunch=true",
            "--gameCardSelector=" + CARD,
            "--gameLaunchTriggerSelector=" + DEMO,
            "--gameSurfaceSelector=" + SURFACE,
            "--gameAuthenticatedIndicatorSelector=" + INDICATOR,
            "--gameBlockingOverlayCloseSelector=" + BLOCKER)).exitCode());

        WebsiteGameLaunchConfig loaded = loadGeneratedConfig(runId).gameLaunch();
        assertEquals(INDICATOR, loaded.authenticatedIndicatorSelector());
        assertEquals(BLOCKER, loaded.blockingOverlayCloseSelector());
    }

    @Test
    void aSignedInRunMustNameSomethingOnlyASignedInVisitorSees() {
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(loginAndGameLaunch(null)));

        assertEquals("GAME_AUTHENTICATED_INDICATOR_REQUIRED", failure.code());

        // With the selector supplied this rule no longer refuses the request. Whatever the login
        // credential rules go on to say is a different check and not this one's business.
        assertNotEquals("GAME_AUTHENTICATED_INDICATOR_REQUIRED",
            refusalCodeOf(() -> RunConfigurationValidator.validate(loginAndGameLaunch(INDICATOR))));
    }

    @Test
    void aRunWithoutLoginIsNeverAskedForASignedInIndicator() {
        RunConfigurationValidator.validate(enabledRequest());
    }

    @Test
    void neitherSafetySelectorMayBeLongerThanTheContractAllows() {
        String tooLong = "a".repeat(WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH + 1);

        RunConfigurationException indicator = assertThrows(RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(withGameLaunch(new WebsiteGameLaunchConfig(
                false, 3, null, null, null, null, null, null, null, tooLong, null))));
        assertEquals("GAME_AUTHENTICATED_INDICATOR_SELECTOR_TOO_LONG", indicator.code());
        assertFalse(indicator.userMessage().contains(tooLong));

        RunConfigurationException blocker = assertThrows(RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(withGameLaunch(new WebsiteGameLaunchConfig(
                false, 3, null, null, null, null, null, null, null, null, tooLong))));
        assertEquals("GAME_BLOCKING_OVERLAY_CLOSE_SELECTOR_TOO_LONG", blocker.code());
        assertFalse(blocker.userMessage().contains(tooLong));
    }

    @Test
    void theSelectedCheckReachesTheLoaderAsAnEnabledBlock() throws IOException {
        String runId = uniqueRunId("game-launch-selected");

        assertEquals(0, runCli(new CountingMaven(), new CountingCrawl(), args(runId,
            "--gameLaunch=true",
            "--gameCardSelector=" + CARD,
            "--gameLaunchTriggerSelector=" + DEMO,
            "--gameSurfaceSelector=" + SURFACE)).exitCode());

        WebsiteGameLaunchConfig loaded = loadGeneratedConfig(runId).gameLaunch();
        assertTrue(loaded.enabled());
        assertEquals(CARD, loaded.cardSelector());
        assertEquals(DEMO, loaded.launchTriggerSelector());
    }

    @Test
    void aValueThatIsNeitherTrueNorFalseIsRejectedRatherThanReadAsFalse() {
        CliResult result = runCli(new CountingMaven(),
            args(uniqueRunId("game-launch-yes"), "--gameLaunch=yes"));

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("gameLaunch must be true or false"), result.err());
    }

    // --- which engine a request selects, in all four combinations ------------------------------

    @Test
    void neitherTheCrawlerNorGameLaunchRunsMavenAndNeverTheEngine() {
        CountingMaven maven = new CountingMaven();
        CountingCrawl crawl = new CountingCrawl();

        assertEquals(0, runCli(maven, crawl,
            args(uniqueRunId("combo-neither"))).exitCode());

        assertEquals(1, maven.calls, "the legacy path is untouched by this change");
        assertEquals(0, crawl.calls, "nothing opens a browser in process");
    }

    @Test
    void gameLaunchAloneRunsTheEngineOnceForItsStartPageAndNeverMaven() {
        // The defect this closes: a request that selected Game Launch without selecting the
        // crawler used to fork Maven, where no browser the check could use exists, so the check
        // silently never ran.
        CountingMaven maven = new CountingMaven();
        CountingCrawl crawl = new CountingCrawl();

        assertEquals(0, runCli(maven, crawl, args(uniqueRunId("combo-game-only"),
            "--maxPages=7",
            "--gameLaunch=true",
            "--gameCardSelector=" + CARD,
            "--gameLaunchTriggerSelector=" + DEMO,
            "--gameSurfaceSelector=" + SURFACE)).exitCode());

        assertEquals(0, maven.calls, "one engine per request, and never both");
        assertEquals(1, crawl.calls, "one entry, so one browser");
        assertEquals(List.of(CrawlScope.START_PAGE_ONLY), crawl.scopes,
            "selecting a game check must not turn a run into a seven-page crawl");
    }

    @Test
    void theCrawlerAloneRunsTheEngineUnderTheConfiguredBudget() {
        CountingMaven maven = new CountingMaven();
        CountingCrawl crawl = new CountingCrawl();

        assertEquals(0, runCli(maven, crawl,
            args(uniqueRunId("combo-crawler-only"), "--crawler=true", "--maxPages=7")).exitCode());

        assertEquals(0, maven.calls);
        assertEquals(1, crawl.calls);
        assertEquals(List.of(CrawlScope.CONFIGURED_BUDGET), crawl.scopes);
    }

    @Test
    void bothSelectedStillRunsTheEngineExactlyOnceUnderTheConfiguredBudget() {
        CountingMaven maven = new CountingMaven();
        CountingCrawl crawl = new CountingCrawl();

        assertEquals(0, runCli(maven, crawl, args(uniqueRunId("combo-both"),
            "--crawler=true",
            "--maxPages=7",
            "--gameLaunch=true",
            "--gameCardSelector=" + CARD,
            "--gameLaunchTriggerSelector=" + DEMO,
            "--gameSurfaceSelector=" + SURFACE)).exitCode());

        assertEquals(0, maven.calls);
        assertEquals(1, crawl.calls, "adding a game check adds no second engine and no second driver");
        assertEquals(List.of(CrawlScope.CONFIGURED_BUDGET), crawl.scopes,
            "a run that asked to crawl still crawls its own budget");
    }

    // --- what this configuration refuses to carry ------------------------------------------------

    @Test
    void thereIsOneLaunchControlAndNoWagerSwitchToConfigure() throws IOException {
        String runId = uniqueRunId("game-one-control");

        // Arguments the parser simply does not read. They must reach nothing downstream. The
        // caller names the control that opens a game; this configuration never names a second
        // launch mode, a stake, a bet or anything that could move money.
        assertEquals(0, runCli(new CountingMaven(), new CountingCrawl(), args(runId,
            "--gameAllowRealMoney=true",
            "--gameStakeSelector=.stake",
            "--gameSpinSelector=.spin",
            "--gameDepositSelector=.deposit",
            "--gameDemoTriggerSelector=.btn-demo")).exitCode());

        String properties = Files.readString(
            AutomationRunPaths.forRunId(runId).propertiesConfigFile());
        for (String forbidden : List.of(
            "realMoney", "RealMoney", "stake", "Stake", "spin", "Spin",
            "deposit", "Deposit", "wager", "Wager", "demo", "Demo")) {
            assertFalse(properties.contains(forbidden), forbidden + " in " + properties);
        }
        // Exactly one launch control key, and it is the neutral one.
        assertTrue(properties.contains("gameLaunch.launchTriggerSelector"), properties);
        assertEquals(1, properties.split("launchTriggerSelector", -1).length - 1, properties);
    }

    @Test
    void theRetiredDemoTriggerArgumentIsNotReadAtAll() throws IOException {
        String runId = uniqueRunId("game-no-demo-arg");

        // The old argument name is now just an unknown token: it must not reach the generated
        // configuration under either name, so a caller cannot keep a Demo-only run alive.
        assertEquals(0, runCli(new CountingMaven(), new CountingCrawl(),
            args(runId, "--gameDemoTriggerSelector=" + DEMO)).exitCode());

        WebsiteGameLaunchConfig loaded = loadGeneratedConfig(runId).gameLaunch();
        assertNull(loaded.launchTriggerSelector(),
            "a retired argument must not populate the launch control");
    }

    @Test
    void aSelectorLongerThanTheContractAllowsIsRefusedWithoutEchoingIt() {
        String tooLong = "a".repeat(WebsiteGameLaunchConfig.MAX_SELECTOR_LENGTH + 1);

        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(requestWithCardSelector(tooLong)));

        assertEquals("GAME_CARD_SELECTOR_TOO_LONG", failure.code());
        assertFalse(failure.userMessage().contains(tooLong), "the value is never echoed back");
        assertFalse(failure.suggestedAction().contains(tooLong));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static AutomationRunRequest requestWithMaxGames(int maxGames) {
        return withGameLaunch(new WebsiteGameLaunchConfig(
            false, maxGames, null, null, null, null, null, null, null));
    }

    private static AutomationRunRequest requestWithCardSelector(String cardSelector) {
        return withGameLaunch(new WebsiteGameLaunchConfig(
            false, WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES,
            cardSelector, null, null, null, null, null, null));
    }

    private static AutomationRunRequest enabledRequest() {
        return withGameLaunch(new WebsiteGameLaunchConfig(
            true, WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES,
            CARD, null, null, DEMO, SURFACE, null, null));
    }

    private static AutomationRunRequest withGameLaunch(WebsiteGameLaunchConfig gameLaunch) {
        AutomationRunRequest base = AutomationRunRequest.simple("https://example.com/");
        return new AutomationRunRequest(
            base.schemaVersion(), base.siteUrl(), base.viewportPreset(), base.viewportWidth(),
            base.viewportHeight(), base.maxPages(), base.maxLinks(), base.loginEnabled(),
            base.loginUrl(), base.loginUsernameEnv(), base.loginPasswordEnv(),
            base.loginUsernameSelector(), base.loginPasswordSelector(), base.loginSubmitSelector(),
            base.loginSuccessUrlContains(), base.loginSuccessText(), base.smokeTest(),
            base.navigationTest(), base.linksTest(), base.searchTest(), base.formsTest(),
            base.loginTest(), base.searchEnabled(), base.searchInputSelector(),
            base.searchSubmitSelector(), base.searchResultsSelector(), base.searchQuery(),
            base.menuTriggerSelector(), base.menuPanelSelector(), base.menuLabel(),
            base.formSelector(), base.formFieldSelector(), base.formTestValue(),
            base.crawlerEnabled(), gameLaunch);
    }

    private static WebsiteConfig loadGeneratedConfig(String runId) {
        return WebsiteConfigLoader.load(
            AutomationRunPaths.forRunId(runId).propertiesConfigFile());
    }

    private static String[] args(String runId, String... extra) {
        List<String> args = new ArrayList<>(List.of(
            "--runId=" + runId,
            "--schemaVersion=1.0",
            "--siteUrl=https://example.com/",
            "--viewportPreset=desktop",
            "--smoke=false",
            "--navigation=false",
            "--links=true",
            "--search=false",
            "--forms=false",
            "--login=false",
            "--loginEnabled=false"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    private static CliResult runCli(CountingMaven maven, String... args) {
        return runCli(maven, new CountingCrawl(), args);
    }

    private static CliResult runCli(CountingMaven maven, CountingCrawl crawl, String... args) {
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

    /** A request that selected both Login and Game launch, with or without the indicator. */
    private static AutomationRunRequest loginAndGameLaunch(String indicator) {
        AutomationRunRequest base = AutomationRunRequest.simple("https://example.com/");
        return new AutomationRunRequest(
            base.schemaVersion(), base.siteUrl(), base.viewportPreset(), base.viewportWidth(),
            base.viewportHeight(), base.maxPages(), base.maxLinks(), true,
            "https://example.com/signin", "QANIZE_USER", "QANIZE_PASSWORD",
            "#user", "#pass", "#submit", "/home", "Welcome",
            base.smokeTest(), base.navigationTest(), base.linksTest(), base.searchTest(),
            base.formsTest(), true, base.searchEnabled(), base.searchInputSelector(),
            base.searchSubmitSelector(), base.searchResultsSelector(), base.searchQuery(),
            base.menuTriggerSelector(), base.menuPanelSelector(), base.menuLabel(),
            base.formSelector(), base.formFieldSelector(), base.formTestValue(),
            base.crawlerEnabled(),
            new WebsiteGameLaunchConfig(true, 3, CARD, null, null, DEMO, SURFACE, null, null,
                indicator, null));
    }

    /** The code a validation refused with, or null when it accepted the request. */
    private static String refusalCodeOf(Runnable validation) {
        try {
            validation.run();
            return null;
        } catch (RunConfigurationException refused) {
            return refused.code();
        }
    }

    private static String uniqueRunId(String label) {
        return "test-" + label + "-" + System.nanoTime();
    }

    private record CliResult(int exitCode, String out, String err) {
    }

    /** Counts Maven invocations without ever starting a process or a browser. */
    private static final class CountingMaven implements AutomationRunOrchestratorCli.MavenRunner {
        private int calls;

        @Override
        public int run(List<String> command) throws IOException {
            calls++;
            Path reports = reportsDirectoryFrom(command);
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("TEST-generic.xml"), """
                <testsuite name="generic" tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="GenericLinkValidationTest" name="checks"/>
                </testsuite>
                """);
            return 0;
        }

        private static Path reportsDirectoryFrom(List<String> command) {
            String prefix = "-Dsurefire.reportsDirectory=";
            return command.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> Path.of(value.substring(prefix.length())))
                .findFirst()
                .orElseThrow();
        }
    }

    /**
     * Stands in for the one in-process engine: counts how often it was entered and remembers the
     * scope it was asked for. No browser, so a second driver would be impossible to hide here.
     */
    private static final class CountingCrawl implements CrawlerRunCoordinator.CrawlExecution {
        private int calls;
        private final List<CrawlScope> scopes = new ArrayList<>();

        @Override
        public CrawlRunner.CrawlRunResult run(WebsiteConfig config, CrawlScope scope) {
            calls++;
            scopes.add(scope);
            return new CrawlRunner.CrawlRunResult.Crawled(
                CrawlRunner.LoginOutcome.NOT_SELECTED,
                new Crawler.CrawlResult(
                    List.of(new Crawler.PageResult(config.baseUrl().toString(), 1, null,
                        Crawler.PageStatus.PROCESSED, null, null)),
                    new Crawler.LimitReport(1, 3, false, false, 0)),
                List.of());
        }
    }
}
