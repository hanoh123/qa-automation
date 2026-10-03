package com.qanize.automation.engine;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.qanize.automation.checks.links.LinkSession;
import com.qanize.automation.checks.login.LoginModule;
import com.qanize.automation.checks.login.WebsiteLoginConfig;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;

import com.qanize.automation.browser.ScreenshotCaptureService;
import com.qanize.automation.browser.ScreenshotDirectoryResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One browser, one optional sign-in, one crawl — proved without any of them.
 *
 * <p>No Chrome, no network, no real site: the three operations that would need them are supplied
 * through the runner's own seam. What is left is exactly what the runner decides for itself — when
 * to sign in, when not to ask for a credential at all, when to stop, and when to quit — and that
 * is what these tests are about.
 */
@Tag("unit")
class CrawlRunnerTest {

    private static final String BASE = "https://example.com/";

    /**
     * The page budget these fixtures configure.
     *
     * <p>Deliberately not ten. While the runner ignored the configuration, a fixture that happened
     * to say ten would have agreed with the bug by coincidence and proved nothing either way.
     */
    private static final int CONFIGURED_MAX_PAGES = 5;

    // --- configuration fixtures ------------------------------------------------------------------

    private static WebsiteConfig config(boolean loginSelected, WebsiteLoginConfig login) {
        return config(loginSelected, login, CONFIGURED_MAX_PAGES, 25);
    }

    private static WebsiteConfig config(
        boolean loginSelected, WebsiteLoginConfig login, int maxPages, int maxLinks
    ) {
        return new WebsiteConfig(
            "1.0", "example", URI.create(BASE), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, maxPages, maxLinks, 10, login,
            new WebsiteTestSelectionConfig(true, false, true, false, false, loginSelected));
    }

    private static WebsiteConfig withMaxPages(int maxPages) {
        return withMaxPages(maxPages, 25);
    }

    private static WebsiteConfig withMaxPages(int maxPages, int maxLinks) {
        return config(false, login(true), maxPages, maxLinks);
    }

    private static WebsiteLoginConfig login(boolean enabled) {
        return new WebsiteLoginConfig(
            enabled, URI.create(BASE + "signin"),
            "QANIZE_USER", "QANIZE_PASSWORD",
            null, null, null, null, null);
    }

    private static WebsiteConfig noLoginSelected() {
        return config(false, login(true));
    }

    private static WebsiteConfig loginSelectedButDisabled() {
        return config(true, login(false));
    }

    private static WebsiteConfig loginRequired() {
        return config(true, login(true));
    }

    private static Crawler.CrawlResult emptyCrawl() {
        return new Crawler.CrawlResult(List.of(),
            new Crawler.LimitReport(10, 3, false, false, 0));
    }

    // --- 1, 2, 8. one browser, one session, one quit ---------------------------------------------

    @Test
    void exactlyOneBrowserIsStartedSharedAndQuitOnASuccessfulRun() {
        ScriptedOperations operations = new ScriptedOperations();

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> "value").run(noLoginSelected());

        assertEquals(1, operations.browsersStarted, "exactly one driver per run");
        assertEquals(1, operations.driver.quits, "and it is quit exactly once");
        assertEquals(List.of("startBrowser", "crawl"), operations.calls);
        assertInstanceOf(CrawlRunner.CrawlRunResult.Crawled.class, result);
    }

    @Test
    void theSameSessionReachesTheSignInAndTheCrawl() {
        ScriptedOperations operations = new ScriptedOperations();

        new CrawlRunner(operations, name -> "value").run(loginRequired());

        // Only one browser was ever started, and that is the instance the sign-in received.
        assertEquals(1, operations.browsersStarted);
        assertSame(operations.driver, operations.signInDriver,
            "the sign-in used the one driver this run started");

        // The per-page bridge holds its driver privately, so this proves it the only way that is
        // not circular: exactly one driver exists for the whole run, and the bridge was built
        // after the sign-in and handed to the crawl. A second browser would show up as a second
        // startBrowser call, and an anonymous crawl would need one.
        //
        // The session is read between the two, from that same driver — which is the step that
        // carries the sign-in past the browser and into the link check.
        assertEquals(List.of("startBrowser", "signIn", "captureLinkSession", "crawl"),
            operations.calls);
        assertInstanceOf(CrawlerPageProcessor.class, operations.processor,
            "the crawl was given the existing per-page bridge");
        assertEquals(1, operations.driver.quits);
    }

    // --- 3, 4, 5, 6. the sign-in decision --------------------------------------------------------

    @Test
    void aSelectedAndEnabledLoginIsPerformedExactlyOnceBeforeTheCrawl() {
        ScriptedOperations operations = new ScriptedOperations();
        RecordingCredentials credentials = new RecordingCredentials("user", "secret");

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, credentials).run(loginRequired());

        assertEquals(List.of("startBrowser", "signIn", "captureLinkSession", "crawl"),
            operations.calls,
            "signed in once, and before the first page — reading the session it established "
                + "is a separate step and not a second sign-in");
        assertEquals(1, operations.signIns);
        assertEquals(List.of("QANIZE_USER", "QANIZE_PASSWORD"), credentials.requested,
            "the configured environment-variable names, read once each");
        assertEquals(CrawlRunner.LoginOutcome.SUCCEEDED, result.login());
        assertInstanceOf(CrawlRunner.CrawlRunResult.Crawled.class, result);
    }

    @Test
    void anUnselectedLoginNeverRequestsACredentialAndNeverSignsIn() {
        ScriptedOperations operations = new ScriptedOperations();
        RecordingCredentials credentials = new RecordingCredentials("user", "secret");

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, credentials).run(noLoginSelected());

        assertEquals(List.of(), credentials.requested,
            "an unselected check must not touch the credential source at all");
        assertEquals(0, operations.signIns);
        assertEquals(List.of("startBrowser", "crawl"), operations.calls);
        assertEquals(CrawlRunner.LoginOutcome.NOT_SELECTED, result.login());
    }

    @Test
    void aDisabledLoginNeverRequestsACredentialAndNeverSignsIn() {
        ScriptedOperations operations = new ScriptedOperations();
        RecordingCredentials credentials = new RecordingCredentials("user", "secret");

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, credentials).run(loginSelectedButDisabled());

        assertEquals(List.of(), credentials.requested);
        assertEquals(0, operations.signIns);
        assertEquals(CrawlRunner.LoginOutcome.DISABLED, result.login());
        assertInstanceOf(CrawlRunner.CrawlRunResult.Crawled.class, result,
            "a disabled sign-in still crawls; it simply crawls as an anonymous visitor");
    }

    @Test
    void aMissingLoginBlockIsDisabledRatherThanACrash() {
        ScriptedOperations operations = new ScriptedOperations();
        RecordingCredentials credentials = new RecordingCredentials("user", "secret");

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, credentials).run(config(true, null));

        assertEquals(CrawlRunner.LoginOutcome.DISABLED, result.login());
        assertEquals(List.of(), credentials.requested);
    }

    // --- 7, 9. a sign-in that fails stops the run ------------------------------------------------

    @Test
    void aFailedSignInPreventsTheCrawlAndSaysSoWithoutClaimingOne() {
        ScriptedOperations operations = new ScriptedOperations()
            .signInResult(new LoginModule.Result(
                LoginModule.Outcome.FAILED, "Login success text was not found: Welcome"));

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> "value").run(loginRequired());

        assertEquals(List.of("startBrowser", "signIn"), operations.calls, "the crawl never started");
        assertEquals(0, operations.crawls);
        assertEquals(1, operations.driver.quits, "and the browser still closed exactly once");

        // The result has no crawl to read, which is the point: an empty CrawlResult here would
        // read downstream as "we crawled the site and found nothing".
        CrawlRunner.CrawlRunResult.LoginPrevented prevented =
            assertInstanceOf(CrawlRunner.CrawlRunResult.LoginPrevented.class, result);
        assertEquals(CrawlRunner.LoginOutcome.FAILED, prevented.login());
        assertEquals(CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED, prevented.cause(),
            "the website was asked and refused, which is a finding about the website");
        assertTrue(prevented.message().contains("no page was crawled"), prevented.message());
        assertTrue(prevented.message().contains("Login success text was not found"),
            prevented.message());
    }

    @Test
    void anUnsetCredentialPreventsTheCrawlAndNamesTheVariableNotTheValue() {
        ScriptedOperations operations = new ScriptedOperations();

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> null).run(loginRequired());

        assertEquals(0, operations.signIns, "there is nothing to sign in with");
        assertEquals(0, operations.crawls);
        assertEquals(1, operations.driver.quits);

        CrawlRunner.CrawlRunResult.LoginPrevented prevented =
            assertInstanceOf(CrawlRunner.CrawlRunResult.LoginPrevented.class, result);
        assertEquals(CrawlRunner.LoginPreventedCause.CREDENTIALS_UNAVAILABLE, prevented.cause(),
            "nothing was asked of the website, so this is a configuration problem not a finding");
        assertTrue(prevented.message().contains("QANIZE_USER"), prevented.message());
        assertTrue(prevented.message().contains("QANIZE_PASSWORD"), prevented.message());
    }

    @Test
    void theResultTypeItselfRefusesToDescribeACrawlThatNeverHappened() {
        // A prevented run can only report FAILED, and a crawl can never report it. The two
        // shapes cannot be confused for one another, and neither can lie about the other's state.
        assertThrows(IllegalArgumentException.class, () -> new CrawlRunner.CrawlRunResult
            .LoginPrevented(CrawlRunner.LoginOutcome.SUCCEEDED,
                CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED, "pretending"));
        assertThrows(IllegalArgumentException.class, () -> new CrawlRunner.CrawlRunResult
            .LoginPrevented(CrawlRunner.LoginOutcome.FAILED,
                CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED, "  "));
        assertThrows(NullPointerException.class, () -> new CrawlRunner.CrawlRunResult
            .LoginPrevented(CrawlRunner.LoginOutcome.FAILED, null, "no cause given"));
        assertThrows(IllegalArgumentException.class, () -> new CrawlRunner.CrawlRunResult
            .Crawled(CrawlRunner.LoginOutcome.FAILED, emptyCrawl(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CrawlRunner.CrawlRunResult
            .Crawled(CrawlRunner.LoginOutcome.REQUIRED, emptyCrawl(), List.of()));
    }

    // --- 10, 11, 12, 13, 14. failures and cleanup ------------------------------------------------

    @Test
    void aCrawlRuntimeExceptionPropagatesUnchangedAndTheBrowserStillCloses() {
        IllegalStateException crawlFailure = new IllegalStateException("the session died");
        ScriptedOperations operations = new ScriptedOperations().crawlThrows(crawlFailure);

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
            () -> new CrawlRunner(operations, name -> "value").run(noLoginSelected()));

        assertSame(crawlFailure, escaped, "the original exception, not a wrapper");
        assertEquals(1, operations.driver.quits);
    }

    @Test
    void aCrawlErrorPropagatesUnchangedAndTheBrowserStillCloses() {
        StackOverflowError crawlFailure = new StackOverflowError("unrecoverable");
        ScriptedOperations operations = new ScriptedOperations().crawlThrows(crawlFailure);

        StackOverflowError escaped = assertThrows(StackOverflowError.class,
            () -> new CrawlRunner(operations, name -> "value").run(noLoginSelected()));

        assertSame(crawlFailure, escaped, "an Error is never downgraded to a result");
        assertEquals(1, operations.driver.quits, "and cleanup still ran");
    }

    @Test
    void aSignInThatThrowsPropagatesAndTheBrowserStillCloses() {
        IllegalStateException signInFailure = new IllegalStateException("driver died mid sign-in");
        ScriptedOperations operations = new ScriptedOperations().signInThrows(signInFailure);

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
            () -> new CrawlRunner(operations, name -> "value").run(loginRequired()));

        assertSame(signInFailure, escaped);
        assertEquals(0, operations.crawls);
        assertEquals(1, operations.driver.quits);
    }

    @Test
    void aBrowserThatNeverStartedIsNeverQuit() {
        IllegalStateException startFailure = new IllegalStateException("chromedriver is missing");
        ScriptedOperations operations = new ScriptedOperations().startThrows(startFailure);

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
            () -> new CrawlRunner(operations, name -> "value").run(noLoginSelected()));

        assertSame(startFailure, escaped);
        // There is no driver to quit, and pretending otherwise would replace a clear
        // infrastructure error with a NullPointerException from the cleanup path.
        assertEquals(List.of("startBrowser"), operations.calls);
    }

    @Test
    void aQuitThatFailsOnAnOtherwiseGoodRunIsSurfaced() {
        IllegalStateException quitFailure = new IllegalStateException("the browser would not close");
        ScriptedOperations operations = new ScriptedOperations().quitThrows(quitFailure);

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
            () -> new CrawlRunner(operations, name -> "value").run(noLoginSelected()));

        assertSame(quitFailure, escaped, "nothing else was travelling, so this is the news");
        assertEquals(1, operations.crawls, "the crawl itself had already completed");
    }

    @Test
    void aQuitThatFailsDuringAnotherFailureNeverReplacesIt() {
        IllegalStateException crawlFailure = new IllegalStateException("the real problem");
        IllegalStateException quitFailure = new IllegalStateException("and the browser hung too");
        ScriptedOperations operations = new ScriptedOperations()
            .crawlThrows(crawlFailure)
            .quitThrows(quitFailure);

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
            () -> new CrawlRunner(operations, name -> "value").run(noLoginSelected()));

        assertSame(crawlFailure, escaped, "the original failure is what the caller sees");
        assertEquals(1, escaped.getSuppressed().length, "and the cleanup failure is not lost");
        assertSame(quitFailure, escaped.getSuppressed()[0]);
    }

    // --- 15. credentials stay out of everything --------------------------------------------------

    @Test
    void noCredentialValueAppearsInAnyResultMessageOrToString() {
        String password = "synthetic-password-9f2b";
        ScriptedOperations operations = new ScriptedOperations()
            .signInResult(new LoginModule.Result(
                LoginModule.Outcome.FAILED, "rejected password=" + password));

        CrawlRunner.CrawlRunResult result = new CrawlRunner(operations,
            name -> "QANIZE_PASSWORD".equals(name) ? password : "synthetic-user").run(loginRequired());

        String rendered = result + " " + ((CrawlRunner.CrawlRunResult.LoginPrevented) result).message();
        assertFalse(rendered.contains(password), "a credential reached a result: " + rendered);
        assertFalse(rendered.contains("synthetic-user"), rendered);
        // The variable names are safe and useful; the values are what must never travel.
        assertTrue(rendered.contains("password=[redacted]"), rendered);
    }

    @Test
    void theRunnerKeepsNoCredentialFields() {
        Map<String, Class<?>> declared = new LinkedHashMap<>();
        for (Field field : CrawlRunner.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertFalse(Modifier.isStatic(field.getModifiers()),
                "a static field would outlive every run: " + field);
            assertTrue(
                Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
                field + " must be a private final injected collaborator");
            declared.put(field.getName(), field.getType());
        }

        // The exact shape, by name and by type. A count says only how many; this says which, so a
        // new field of any kind fails here instead of being absorbed by adjusting a number.
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("operations", CrawlRunner.RunOperations.class);
        expected.put("credentials", CrawlRunner.CredentialProvider.class);
        expected.put("screenshots", CrawlScreenshotRecorder.class);
        // The run's game-launch step: another injected collaborator, and like the others it holds
        // no credential and nothing that could outlive the run.
        expected.put("games", CrawlRunner.GameLaunchExecution.class);
        assertEquals(expected, declared, "only the injected collaborators may be fields");

        for (Field field : CrawlRunner.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("username", "password", "secret", "token", "apikey")) {
                assertFalse(name.contains(forbidden), "credentials must be locals: " + field);
            }
            // Nothing here can hold a value, a collection of values, a browser, or the answer a
            // previous run produced.
            Class<?> type = field.getType();
            assertFalse(CharSequence.class.isAssignableFrom(type),
                field + " must not hold a value");
            assertFalse(Collection.class.isAssignableFrom(type),
                field + " must not hold a collection of values");
            assertFalse(Map.class.isAssignableFrom(type), field + " must not hold a value store");
            assertFalse(WebDriver.class.isAssignableFrom(type), field + " must not hold a browser");
            assertFalse(CrawlRunner.CrawlRunResult.class.isAssignableFrom(type),
                field + " must not keep a previous run's result");
        }

        // The credential field is a source, not a store: it answers a question by variable name
        // and has nowhere to keep what it answered.
        assertTrue(CrawlRunner.CredentialProvider.class.isInterface());
        assertEquals(0, CrawlRunner.CredentialProvider.class.getDeclaredFields().length);

        // The screenshot collaborator is injected infrastructure. It does hold this run's values
        // while a picture might still be taken -- that is its job -- but nothing about it is
        // static, so no value can survive into another runner instance.
        for (Field field : CrawlScreenshotRecorder.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            if (Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isFinal(field.getModifiers()),
                    "a mutable static would be shared by every run: " + field);
                assertFalse(Collection.class.isAssignableFrom(field.getType()),
                    "no static collection may accumulate across runs: " + field);
                continue;
            }
            assertTrue(
                Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
                field + " must be private and final");
        }
    }

    // --- the signed-in session reaches the link check --------------------------------------------

    @Test
    void aSuccessfulSignInSuppliesTheSessionTheLinkCheckWillPresent() {
        ScriptedOperations operations = new ScriptedOperations();

        new CrawlRunner(operations, name -> "value").run(loginRequired());

        assertEquals(1, operations.sessionCaptures, "read once for the run, not once per page");
        assertSame(operations.driver, operations.sessionDriver,
            "the very browser the sign-in used — a second session would not be signed in");
    }

    @Test
    void theSessionIsReadAfterTheSignInAndBeforeTheFirstPage() {
        // Ordering is the property. Read before the sign-in and there is no session yet; read after
        // the crawl and every page has already been checked without it.
        ScriptedOperations operations = new ScriptedOperations();

        new CrawlRunner(operations, name -> "value").run(loginRequired());

        assertEquals(List.of("startBrowser", "signIn", "captureLinkSession"),
            operations.callsWhenSessionCaptured);
        assertEquals(List.of("startBrowser", "signIn", "captureLinkSession", "crawl"),
            operations.calls);
    }

    @Test
    void aRunThatNeverSignedInAsksForNoSessionAtAll() {
        for (WebsiteConfig config : List.of(noLoginSelected(), loginSelectedButDisabled())) {
            ScriptedOperations operations = new ScriptedOperations();

            new CrawlRunner(operations, name -> "value").run(config);

            assertEquals(0, operations.sessionCaptures,
                "being configured for Login decides nothing; only a sign-in that succeeded does");
            assertEquals(List.of("startBrowser", "crawl"), operations.calls);
        }
    }

    @Test
    void aSignInThatDidNotSucceedNeverReachesTheSessionOrTheCrawl() {
        ScriptedOperations operations = new ScriptedOperations()
            .signInResult(new LoginModule.Result(LoginModule.Outcome.FAILED, "rejected"));

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> "value").run(loginRequired());

        assertInstanceOf(CrawlRunner.CrawlRunResult.LoginPrevented.class, result);
        assertEquals(0, operations.sessionCaptures);
        assertEquals(0, operations.crawls, "nothing was crawled, so nothing needed a session");
    }

    @Test
    void anUnavailableSessionStaysTypedAndNeverBecomesAnonymous() {
        // The one transition that would put the original bug back: a sign-in that succeeded,
        // followed by anonymous HTTP requests to pages only its session can see.
        ScriptedOperations operations = new ScriptedOperations()
            .capturedSession(LinkSession.authenticatedDiscoveryOnly(null));

        new CrawlRunner(operations, name -> "value").run(loginRequired());

        assertEquals(LinkSession.Mode.AUTHENTICATED_DISCOVERY_ONLY,
            operations.capturedSession.mode());
        assertEquals(1, operations.sessionCaptures);
        assertEquals(1, operations.crawls, "the crawl still runs; it simply requests nothing");
    }

    @Test
    void theRunnerKeepsNoSessionBetweenRuns() {
        ScriptedOperations first = new ScriptedOperations().capturedSession(
            LinkSession.authenticated(URI.create(BASE), List.of(new LinkSession.SessionCookie(
                "session", "first-run", "example.com", "/", false, false))));
        ScriptedOperations second = new ScriptedOperations().capturedSession(
            LinkSession.authenticatedDiscoveryOnly(null));

        new CrawlRunner(first, name -> "value").run(loginRequired());
        new CrawlRunner(second, name -> "value").run(loginRequired());

        assertEquals(1, first.sessionCaptures);
        assertEquals(1, second.sessionCaptures);
        // Pinned exactly by theRunnerKeepsNoCredentialFields: the session is a local in runWithin
        // and there is no field of any kind it could have been kept in.
        assertFalse(java.util.Arrays.stream(CrawlRunner.class.getDeclaredFields())
            .anyMatch(field -> LinkSession.class.isAssignableFrom(field.getType())),
            "a session field would outlive the run that established it");
    }

    @Test
    void theSessionOperationHasNoDefaultToSilentlyFallBackTo() throws NoSuchMethodException {
        // A default implementation would have to be either anonymous — which strips the session
        // from any implementation that forgot to override it, reintroducing the exact defect — or
        // authenticated, which would be a lie. A missing implementation must be a compile error.
        assertFalse(
            CrawlRunner.RunOperations.class
                .getMethod("captureLinkSession", WebDriver.class, WebsiteConfig.class)
                .isDefault(),
            "captureLinkSession must be required, not defaulted");
    }

    // --- 16, 17, 18. the crawl is the existing one -----------------------------------------------

    @Test
    void theCrawlUsesTheConfiguredPolicyTheConfiguredBaseUrlAndTheExistingBridge() {
        ScriptedOperations operations = new ScriptedOperations();

        new CrawlRunner(operations, name -> "value").run(noLoginSelected());

        // This assertion used to read CrawlPolicy.defaults(), and passing was the defect: the
        // fixture configures five pages, the runner handed the crawler ten, and the test agreed
        // with the runner instead of with the configuration. The page budget is the run's; the
        // depth budget is still the default, because nothing configures it.
        assertEquals(new CrawlPolicy(CONFIGURED_MAX_PAGES, CrawlPolicy.DEFAULT_MAX_DEPTH),
            operations.policy,
            "the crawl runs under the page budget the run was configured with");
        assertEquals(CONFIGURED_MAX_PAGES, operations.policy.maxPages());
        assertEquals(BASE, operations.startUrl, "the crawl starts at the configured base URL");
        assertInstanceOf(CrawlerPageProcessor.class, operations.processor,
            "the per-page bridge is the existing one");
    }

    @Test
    void thePageBudgetFollowsTheConfigurationRatherThanAFixedDefault() {
        // Two runs, two settings, two budgets. A runner that still reached for a constant would
        // hand both of these the same number.
        for (int configured : List.of(1, 3, 10)) {
            ScriptedOperations operations = new ScriptedOperations();

            new CrawlRunner(operations, name -> "value").run(withMaxPages(configured));

            assertEquals(configured, operations.policy.maxPages(),
                "a run configured for " + configured + " pages");
            assertEquals(CrawlPolicy.DEFAULT_MAX_DEPTH, operations.policy.maxDepth(),
                "depth is not configurable and must not move with the page budget");
        }
    }

    @Test
    void theMaximumLinksSettingIsNotThePageBudget() {
        // Adjacent in the form, adjacent in the properties file, and opposite in meaning: one
        // bounds pages opened, the other destinations requested on a page.
        ScriptedOperations operations = new ScriptedOperations();

        new CrawlRunner(operations, name -> "value").run(withMaxPages(3, 25));

        assertEquals(3, operations.policy.maxPages());
    }

    @Test
    void theCrawledResultCarriesTheCrawlAndItsPerPageResults() {
        ScriptedOperations operations = new ScriptedOperations();

        CrawlRunner.CrawlRunResult.Crawled crawled = assertInstanceOf(
            CrawlRunner.CrawlRunResult.Crawled.class,
            new CrawlRunner(operations, name -> "value").run(noLoginSelected()));

        assertSame(operations.crawlResult, crawled.crawl(), "the existing Crawler.CrawlResult");
        assertEquals(List.of(), crawled.pages(),
            "and the processor's own per-page results, with no third model in between");
    }

    // --- 19, 20. nothing from the JUnit world leaks into main scope -------------------------------

    @Test
    void theRunnerReferencesNoJUnitWrapperOrTestFramework() throws IOException {
        String raw = Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlRunner.java"));
        String code = code(raw);

        for (String forbidden : List.of(
            "org.junit", "Generic", "BaseSeleniumTest", "ScreenshotOnFailure",
            "ThreadLocal", "getInstance", "System.getenv(")) {
            assertFalse(code.contains(forbidden), "CrawlRunner must not contain " + forbidden);
        }
        // The production default still reads the environment, but only as a method reference
        // handed to the injected provider -- never by calling getenv inside the run.
        assertTrue(code.contains("System::getenv"), "the production credential source");

        // Every import is the JDK, Selenium, or this project's own production packages.
        for (String imported : imports(raw)) {
            assertTrue(
                imported.startsWith("java.")
                    || imported.startsWith("org.openqa.selenium.")
                    || imported.startsWith("com.qanize.automation."),
                "unexpected dependency: " + imported);
            assertFalse(imported.contains(".browser.Base"), imported);
        }
    }

    @Test
    void theRunnerHoldsNoStaticOrSharedBrowser() throws IOException {
        String code = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/engine/CrawlRunner.java")));

        assertFalse(code.contains("static WebDriver"), "no globally shared driver");
        assertFalse(code.contains("private WebDriver"), "the runner stores no driver between runs");
        assertEquals(1, code.split("\\.quit\\(\\)", -1).length - 1,
            "exactly one place quits the browser");
    }

    @Test
    void theSixLegacyWrappersAreUnchangedByThisSlice() throws IOException {
        for (String wrapper : List.of(
            "src/test/java/com/qanize/automation/checks/smoke/GenericSmokeTest.java",
            "src/test/java/com/qanize/automation/checks/links/GenericLinkValidationTest.java",
            "src/test/java/com/qanize/automation/checks/navigation/GenericNavigationTest.java",
            "src/test/java/com/qanize/automation/checks/forms/GenericFormDetectionTest.java",
            "src/test/java/com/qanize/automation/checks/search/GenericSearchTest.java",
            "src/test/java/com/qanize/automation/checks/login/GenericLoginTest.java")) {
            String source = Files.readString(Path.of(wrapper));
            assertTrue(source.contains("extends GenericBaseSeleniumTest"), wrapper);
            assertFalse(source.contains("CrawlRunner"), wrapper + " must not learn about the runner");
        }
    }

    @Test
    void twoRunsShareNoState() {
        ScriptedOperations first = new ScriptedOperations();
        ScriptedOperations second = new ScriptedOperations();
        new CrawlRunner(first, name -> "value").run(noLoginSelected());
        new CrawlRunner(second, name -> "value").run(noLoginSelected());

        assertEquals(1, first.browsersStarted);
        assertEquals(1, second.browsersStarted);
        assertEquals(1, first.driver.quits);
        assertEquals(1, second.driver.quits);
        assertFalse(first.driver == second.driver, "each run got its own browser");
        // A static field is the only way two runs could see each other at all; which instance
        // fields exist is pinned exactly by theRunnerKeepsNoCredentialFields.
        for (Field field : CrawlRunner.class.getDeclaredFields()) {
            assertFalse(Modifier.isStatic(field.getModifiers()),
                "a static field would be shared by every run: " + field);
        }
    }

    @Test
    void twoRunsShareNoCredentialsOrScreenshotState() {
        RecordingCapture firstCapture = new RecordingCapture();
        ScriptedOperations firstRun = new ScriptedOperations().signInResult(refused());
        new CrawlRunner(firstRun, new RecordingCredentials(USERNAME, PASSWORD),
            new CrawlScreenshotRecorder(firstCapture)).run(loginRequired());

        RecordingCapture secondCapture = new RecordingCapture();
        ScriptedOperations secondRun = new ScriptedOperations().signInResult(refused());
        new CrawlRunner(secondRun, new RecordingCredentials("bob@example.test", "other-secret"),
            new CrawlScreenshotRecorder(secondCapture)).run(loginRequired());

        assertEquals(List.of(USERNAME, PASSWORD), firstCapture.sensitiveValues.get(0));
        assertEquals(List.of("bob@example.test", "other-secret"),
            secondCapture.sensitiveValues.get(0));
        assertFalse(secondCapture.sensitiveValues.toString().contains(PASSWORD),
            "the first run's password never reached the second run's masking");
        assertFalse(firstCapture.sensitiveValues.toString().contains("other-secret"),
            "and nothing travelled backwards either");
        assertFalse(firstRun.driver == secondRun.driver, "each run got its own browser");
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Source with comments removed, so a guard cannot match documentation. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<String> imports(String source) {
        return source.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("import ") && line.endsWith(";"))
            .map(line -> line.substring("import ".length(), line.length() - 1)
                .replace("static ", "").trim())
            .toList();
    }

    /** Records which environment-variable names were asked for, and answers with fixed values. */
    private static final class RecordingCredentials implements CrawlRunner.CredentialProvider {

        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> requested = new ArrayList<>();

        RecordingCredentials(String username, String password) {
            values.put("QANIZE_USER", username);
            values.put("QANIZE_PASSWORD", password);
        }

        @Override
        public String valueOf(String environmentVariableName) {
            requested.add(environmentVariableName);
            return values.get(environmentVariableName);
        }
    }

    /**
     * The three real-world operations, scripted.
     *
     * <p>Nothing here starts Chrome or touches a network. It records the order it was called in,
     * which driver each call received, and what the crawl was asked to do.
     */
    private static final class ScriptedOperations implements CrawlRunner.RunOperations {

        private final List<String> calls = new ArrayList<>();
        private final CountingDriver driver = new CountingDriver();

        private int browsersStarted;
        private int signIns;
        private int crawls;

        private WebDriver signInDriver;
        private CrawlPolicy policy;
        private String startUrl;
        private CrawlerPageProcessor processor;

        private final Crawler.CrawlResult crawlResult = new Crawler.CrawlResult(List.of(),
            new Crawler.LimitReport(10, 3, false, false, 0));

        private int sessionCaptures;
        private WebDriver sessionDriver;
        private final List<String> callsWhenSessionCaptured = new ArrayList<>();
        private LinkSession capturedSession =
            LinkSession.authenticated(URI.create(BASE), List.of(
                new LinkSession.SessionCookie("session", "abc123", "example.com", "/", false, true)));

        private LoginModule.Result signInResult =
            new LoginModule.Result(LoginModule.Outcome.PASSED, null);
        private Throwable startFailure;
        private Throwable signInFailure;
        private Throwable crawlFailure;

        ScriptedOperations capturedSession(LinkSession session) {
            this.capturedSession = session;
            return this;
        }

        ScriptedOperations signInResult(LoginModule.Result result) {
            this.signInResult = result;
            return this;
        }

        ScriptedOperations startThrows(Throwable failure) {
            this.startFailure = failure;
            return this;
        }

        ScriptedOperations signInThrows(Throwable failure) {
            this.signInFailure = failure;
            return this;
        }

        ScriptedOperations crawlThrows(Throwable failure) {
            this.crawlFailure = failure;
            return this;
        }

        ScriptedOperations quitThrows(Throwable failure) {
            driver.quitFailure = failure;
            return this;
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        @Override
        public WebDriver startBrowser(WebsiteConfig config) {
            calls.add("startBrowser");
            browsersStarted++;
            rethrow(startFailure);
            return driver;
        }

        @Override
        public LoginModule.Result signIn(
            WebDriver driver, WebsiteConfig config, WebsiteLoginConfig login,
            String username, String password
        ) {
            calls.add("signIn");
            signIns++;
            signInDriver = driver;
            rethrow(signInFailure);
            return signInResult;
        }

        @Override
        public LinkSession captureLinkSession(WebDriver driver, WebsiteConfig config) {
            calls.add("captureLinkSession");
            sessionCaptures++;
            sessionDriver = driver;
            // A copy taken at the moment of the call, so a test can ask what had already happened
            // when the session was read rather than only what happened in total.
            callsWhenSessionCaptured.addAll(calls);
            return capturedSession;
        }

        @Override
        public Crawler.CrawlResult crawl(
            CrawlPolicy policy, String startUrl, CrawlerPageProcessor processor
        ) {
            calls.add("crawl");
            crawls++;
            this.policy = policy;
            this.startUrl = startUrl;
            this.processor = processor;
            rethrow(crawlFailure);
            return crawlResult;
        }
    }

    /**
     * A browser that only counts how often it was asked to close.
     *
     * <p>Every other method is unreachable on the paths under test: the runner hands the driver
     * straight to the seam and never drives it. If one of them is ever called, that itself is the
     * finding — the runner would have started touching the browser directly.
     */
    private static final class CountingDriver
        implements WebDriver, TakesScreenshot, JavascriptExecutor {

        private int quits;
        private int screenshotsTaken;
        private Throwable quitFailure;

        /**
         * Enough of a camera for the real capture service to use.
         *
         * <p>Deliberately the real service and a real file: a screenshot that is never written is
         * not evidence that anything was redacted or that anything landed anywhere.
         */
        @Override
        public <X> X getScreenshotAs(OutputType<X> target) {
            screenshotsTaken++;
            return target.convertFromPngBytes("pretend-this-is-a-png".getBytes());
        }

        @Override
        public Object executeScript(String script, Object... args) {
            return null;
        }

        /**
         * Loudly unimplemented, on purpose.
         *
         * <p>The masking the capture service performs is synchronous, so nothing under test has
         * any reason to reach for asynchronous JavaScript. Answering null here would let such a
         * change slip through as a screenshot that silently masked nothing; failing here makes it
         * a compile-and-run failure instead.
         */
        @Override
        public Object executeAsyncScript(String script, Object... args) {
            throw new UnsupportedOperationException(
                "Async JavaScript is not expected in this test");
        }

        @Override
        public void quit() {
            quits++;
            if (quitFailure instanceof RuntimeException runtime) throw runtime;
            if (quitFailure instanceof Error error) throw error;
        }

        private static UnsupportedOperationException unexpected(String method) {
            return new UnsupportedOperationException(
                "CrawlRunner must not call driver." + method + "() itself");
        }

        @Override public void get(String url) { throw unexpected("get"); }
        @Override public String getCurrentUrl() { throw unexpected("getCurrentUrl"); }
        @Override public String getTitle() { throw unexpected("getTitle"); }
        @Override public List<WebElement> findElements(By by) { throw unexpected("findElements"); }
        @Override public WebElement findElement(By by) { throw unexpected("findElement"); }
        @Override public String getPageSource() { throw unexpected("getPageSource"); }
        @Override public void close() { throw unexpected("close"); }
        @Override public Set<String> getWindowHandles() { throw unexpected("getWindowHandles"); }
        @Override public String getWindowHandle() { throw unexpected("getWindowHandle"); }
        @Override public TargetLocator switchTo() { throw unexpected("switchTo"); }
        @Override public Navigation navigate() { throw unexpected("navigate"); }
        @Override public Options manage() { throw unexpected("manage"); }
    }

    // --- Slice 6: crawler-run screenshots -----------------------------------------------------

    private static final String USERNAME = "alice.tester@example.test";
    private static final String PASSWORD = "hunter2-VERY-SECRET";

    @TempDir
    Path screenshotsDirectory;

    @BeforeEach
    void pointScreenshotsAtTheWorkspace() {
        System.setProperty(
            ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY,
            screenshotsDirectory.toString());
        System.clearProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY);
    }

    @AfterEach
    void clearScreenshotProperties() {
        System.clearProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);
        System.clearProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY);
    }

    private static LoginModule.Result refused() {
        return new LoginModule.Result(LoginModule.Outcome.FAILED, "The sign-in was rejected.");
    }

    /** A sign-in whose configured success condition never arrived before the timeout. */
    private static LoginModule.Result timedOut() {
        return new LoginModule.Result(LoginModule.Outcome.FAILED,
            "Login success URL did not contain configured text: /dashboard", false);
    }

    private List<String> writtenScreenshots() throws IOException {
        try (var files = Files.list(screenshotsDirectory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aSignInWhoseConditionTimedOutIsPhotographedAndStopsTheCrawl() throws IOException {
        // The whole point of making the check wait: the picture is taken once the wait has
        // settled, so it shows the page the website actually ended on rather than the spinner a
        // few hundred milliseconds after the click. The runner captures on the way past, which is
        // why it needs no knowledge of the wait itself.
        ScriptedOperations operations = new ScriptedOperations().signInResult(timedOut());
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> "value", recorder).run(loginRequired());

        assertEquals(List.of("login-failed.png"), writtenScreenshots(),
            "exactly one sign-in picture, taken after the wait returned");
        assertEquals(1, recorder.captured().size());
        assertEquals(CrawlScreenshotRecorder.RUN_LEVEL, recorder.captured().get(0).pageNumber());
        assertEquals(CrawlScreenshotRecorder.CheckTarget.LOGIN, recorder.captured().get(0).target());

        // And the run stops there. Crawling pages while unable to prove a session would report
        // signed-out pages as if they were signed-in ones.
        assertInstanceOf(CrawlRunner.CrawlRunResult.LoginPrevented.class, result);
        assertEquals(List.of("startBrowser", "signIn"), operations.calls,
            "no session was captured and no page was opened");
        assertEquals(0, operations.sessionCaptures);
    }

    @Test
    void aCleanRunPhotographsNothing() throws IOException {
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        new CrawlRunner(new ScriptedOperations(), name -> "value", recorder).run(noLoginSelected());

        assertEquals(List.of(), recorder.captured());
        assertEquals(List.of(), writtenScreenshots());
    }

    @Test
    void aSignInNobodyProvedIsPhotographedEvenThoughItPassed() throws IOException {
        // The gap this closes: with no success condition configured, a sign-in passes on having
        // submitted the form without an error. Photographing only declared failures meant the one
        // page worth looking at -- what the site showed after the click -- was never captured in
        // exactly the case where nobody could tell whether a session existed.
        ScriptedOperations operations = new ScriptedOperations()
            .signInResult(new LoginModule.Result(LoginModule.Outcome.PASSED, null, false));
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        new CrawlRunner(operations, new RecordingCredentials(USERNAME, PASSWORD), recorder)
            .run(loginRequired());

        assertEquals(1, recorder.captured().size(), "the unproved sign-in was photographed");
        assertEquals(1, operations.driver.screenshotsTaken, "exactly one shutter release");
    }

    @Test
    void aSignInAConfiguredConditionProvedIsNotPhotographed() throws IOException {
        // The other side of the same rule: a run that actually proved its sign-in has nothing to
        // photograph, and a picture of a healthy page is noise in the evidence directory.
        ScriptedOperations operations = new ScriptedOperations()
            .signInResult(new LoginModule.Result(LoginModule.Outcome.PASSED, null, true));
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        new CrawlRunner(operations, new RecordingCredentials(USERNAME, PASSWORD), recorder)
            .run(loginRequired());

        assertEquals(List.of(), recorder.captured());
        assertEquals(0, operations.driver.screenshotsTaken);
    }

    @Test
    void aRefusedSignInIsPhotographedExactlyOnceBeforeTheBrowserIsQuit() throws IOException {
        ScriptedOperations operations = new ScriptedOperations().signInResult(refused());
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        CrawlRunner.CrawlRunResult result = new CrawlRunner(
            operations, new RecordingCredentials(USERNAME, PASSWORD), recorder).run(loginRequired());

        assertInstanceOf(CrawlRunner.CrawlRunResult.LoginPrevented.class, result);
        assertEquals(1, recorder.captured().size(), "one picture, for the refusal");
        assertEquals(1, operations.driver.screenshotsTaken, "and exactly one shutter release");
        assertEquals(List.of("login-failed.png"), writtenScreenshots());
        assertEquals(1, operations.driver.quits, "the browser is still quit exactly once");

        CrawlScreenshotRecorder.ScreenshotRef reference = recorder.captured().get(0);
        assertTrue(reference.captured());
        assertTrue(reference.runLevel(), "a sign-in belongs to the run, not to a crawled page");
        assertEquals(CrawlScreenshotRecorder.CheckTarget.LOGIN, reference.target());
        assertEquals(ScreenshotCaptureService.Stage.NONE, reference.stage());
    }

    @Test
    void missingCredentialsPhotographNothingAtAll() throws IOException {
        ScriptedOperations operations = new ScriptedOperations();
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        CrawlRunner.CrawlRunResult result =
            new CrawlRunner(operations, name -> null, recorder).run(loginRequired());

        assertInstanceOf(CrawlRunner.CrawlRunResult.LoginPrevented.class, result);
        assertEquals(CrawlRunner.LoginPreventedCause.CREDENTIALS_UNAVAILABLE,
            ((CrawlRunner.CrawlRunResult.LoginPrevented) result).cause());
        // Nothing was asked of the website, so a picture of an untouched browser captioned as a
        // sign-in failure would be a false claim.
        assertEquals(List.of(), recorder.captured());
        assertEquals(0, operations.driver.screenshotsTaken);
        assertEquals(List.of(), writtenScreenshots());
    }

    @Test
    void anEscapingRuntimeExceptionIsStillTheExactSameObjectAfterAPicture() throws IOException {
        RuntimeException escaping = new IllegalStateException("the crawl broke");
        ScriptedOperations operations = new ScriptedOperations().crawlThrows(escaping);
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> new CrawlRunner(operations, name -> "value", recorder).run(noLoginSelected()));

        assertSame(escaping, thrown, "the failure travels on by identity");
        assertEquals("the crawl broke", thrown.getMessage());
        assertEquals(0, thrown.getSuppressed().length, "a picture is not a suppressed exception");
        assertNull(thrown.getCause());
        assertEquals(List.of("crawler-unexpected-failure.png"), writtenScreenshots());
        assertEquals(1, operations.driver.quits);
    }

    @Test
    void anEscapingErrorIsStillTheExactSameObjectAfterAPicture() throws IOException {
        Error escaping = new AssertionError("the JVM is unhappy");
        ScriptedOperations operations = new ScriptedOperations().crawlThrows(escaping);
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        Error thrown = assertThrows(Error.class,
            () -> new CrawlRunner(operations, name -> "value", recorder).run(noLoginSelected()));

        assertSame(escaping, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertNull(thrown.getCause());
        assertEquals(1, recorder.captured().size());
        assertEquals(1, operations.driver.quits, "cleanup still happened exactly once");
    }

    @Test
    void aScreenshotFailureNeverReachesTheEscapingFailuresCauseOrSuppressedTree() {
        RuntimeException escaping = new IllegalStateException("the crawl broke");
        // No screenshots directory can be written, so the capture itself fails.
        System.setProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY,
            screenshotsDirectory.resolve("a-file-not-a-directory").toString());
        ScriptedOperations operations = new ScriptedOperations().crawlThrows(escaping);
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> new CrawlRunner(operations, name -> "value", recorder).run(noLoginSelected()));

        assertSame(escaping, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertNull(thrown.getCause());
    }

    @Test
    void credentialsAreMaskedButNeverAppearInNamesResultsOrDescriptions() throws IOException {
        ScriptedOperations operations = new ScriptedOperations().signInResult(refused());
        RecordingCapture capture = new RecordingCapture();
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder(capture);

        new CrawlRunner(operations, new RecordingCredentials(USERNAME, PASSWORD), recorder)
            .run(loginRequired());

        // They reached the masking, which is the entire reason the runner hands them over.
        assertEquals(List.of(USERNAME, PASSWORD), capture.sensitiveValues.get(0));

        // And nowhere else.
        for (String secret : List.of(USERNAME, PASSWORD)) {
            assertFalse(capture.fileNames.toString().contains(secret), "leaked into a file name");
            assertFalse(recorder.captured().toString().contains(secret), "leaked into a result");
            assertFalse(writtenScreenshots().toString().contains(secret), "leaked onto disk");
            assertFalse(recorder.toString().contains(secret), "leaked into toString");
        }
        assertEquals(List.of("login-failed.png"), capture.fileNames);
    }

    @Test
    void oneRunsCredentialsDoNotSurviveIntoTheNextCapture() {
        RecordingCapture capture = new RecordingCapture();
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder(capture);
        ScriptedOperations operations = new ScriptedOperations().signInResult(refused());

        new CrawlRunner(operations, new RecordingCredentials(USERNAME, PASSWORD), recorder)
            .run(loginRequired());

        assertEquals(List.of(USERNAME, PASSWORD), capture.sensitiveValues.get(0),
            "the sign-in picture masked this run's credentials");

        // The run has ended. Asking the same collaborator for another picture now hands the
        // masking nothing, because cleanup dropped what the finished run was protecting.
        recorder.capture(operations.driver, 1, CrawlScreenshotRecorder.CheckTarget.LINKS);

        assertEquals(List.of(), capture.sensitiveValues.get(1),
            "the previous run's credentials did not survive its cleanup");
        assertNotNull(recorder.captured());
    }

    @Test
    void theRecordersCapturedListIsImmutableAndOwnsItsPageAndCheck() {
        ScriptedOperations operations = new ScriptedOperations().signInResult(refused());
        CrawlScreenshotRecorder recorder = new CrawlScreenshotRecorder();

        new CrawlRunner(operations, new RecordingCredentials(USERNAME, PASSWORD), recorder)
            .run(loginRequired());

        List<CrawlScreenshotRecorder.ScreenshotRef> captured = recorder.captured();
        assertEquals(1, captured.size());
        assertThrows(UnsupportedOperationException.class, () -> captured.add(captured.get(0)));
        assertEquals(CrawlScreenshotRecorder.RUN_LEVEL, captured.get(0).pageNumber());
        assertEquals(CrawlScreenshotRecorder.CheckTarget.LOGIN, captured.get(0).target());
    }

    /**
     * Watches every capture, then performs the real one.
     *
     * <p>Recording and delegating rather than replacing: the picture is still taken by the same
     * production service, so this cannot become a second screenshot mechanism that passes while
     * the real one is broken.
     */
    private static final class RecordingCapture
        implements CrawlScreenshotRecorder.CaptureOperation {

        private final ScreenshotCaptureService real = new ScreenshotCaptureService();
        private final List<List<String>> sensitiveValues = new ArrayList<>();
        private final List<String> fileNames = new ArrayList<>();

        @Override
        public ScreenshotCaptureService.Result capture(
            WebDriver driver, String fileName, List<String> values
        ) {
            fileNames.add(fileName);
            // Snapshotted on arrival: the collaborator clears its own list during cleanup.
            sensitiveValues.add(List.copyOf(values));
            return real.capture(driver, fileName, values);
        }
    }
}
