package com.qanize.automation.engine;

import java.util.List;
import java.util.Objects;

import com.qanize.automation.browser.SeleniumDriverFactory;
import com.qanize.automation.checks.links.LinkSession;
import com.qanize.automation.checks.gamelaunch.GameLaunchExecutionEvidence;
import com.qanize.automation.checks.gamelaunch.GameLaunchModule;
import com.qanize.automation.checks.login.LoginExecutionEvidence;
import com.qanize.automation.checks.login.LoginModule;
import com.qanize.automation.checks.login.WebsiteLoginConfig;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.evidence.SensitiveDataFilter;

import org.openqa.selenium.WebDriver;

/**
 * One browser, one optional sign-in, one crawl.
 *
 * <p>This is the piece the six single-page checks never needed: something that owns a browser for
 * longer than one test method. Each legacy wrapper starts and quits its own Chrome, which is why a
 * full run opens eight of them and why a session signed in by the Login check cannot be reused by
 * anything else. A crawl has the opposite requirement — sign in once, then visit many pages with
 * that same session — so the lifecycle has to live somewhere that outlives a single check.
 *
 * <h2>The lifecycle</h2>
 *
 * <pre>{@code
 *   start one WebDriver
 *     -> wrap it in one CommonPage
 *     -> sign in at most once, only if the configuration truthfully asks for it
 *     -> hand that same session to Crawler + CrawlerPageProcessor
 *     -> always quit that same driver, exactly once
 * }</pre>
 *
 * <p>The driver is started through {@link SeleniumDriverFactory}, so there is still exactly one
 * authoritative Chrome configuration and this class builds no options of its own.
 *
 * <p><strong>Quitting is this class's job and nothing else's.</strong> It happens in a
 * {@code finally}, so it happens after success, after a failed sign-in, and after any crawl
 * failure. It does <em>not</em> happen when starting the browser is what failed — there is nothing
 * to quit, and calling {@code quit()} on a driver that was never returned would replace a clear
 * infrastructure error with a confusing one.
 *
 * <p><strong>A failure is never rewritten by cleanup.</strong> If the work threw and the quit also
 * throws, the original travels on with the cleanup failure attached via
 * {@link Throwable#addSuppressed}. If only the quit threw, that surfaces on its own rather than
 * being swallowed. {@link Error} is rethrown untouched: it is caught only to be able to re-throw
 * it after cleanup, never to downgrade it.
 *
 * <h2>What it does not own</h2>
 *
 * <p>It owns no traversal — {@link Crawler} does. It owns no per-page decisions —
 * {@link CrawlerPageProcessor} does. It adds no evidence system, no second crawl policy and no
 * reporting model. Login stays outside the per-page bridge entirely, because a session is
 * established once per run and not once per page.
 *
 * <p><strong>The signed-in session travels as a local too.</strong> A sign-in used to establish a
 * session that only the browser could use: the link check requests destinations over HTTP from an
 * object that had never heard of it, so a page served only to signed-in visitors came back as a
 * 404 and was reported as a defect in the customer's website. After a sign-in that actually
 * succeeded — and only then — the session is read once, here, and handed to the per-page bridge as
 * an argument. It is never a field, so the next run cannot inherit it. A session that cannot be
 * carried says so in its own type and the link check then requests nothing, because requesting
 * anonymously is what produced the false failure in the first place.
 *
 * <p><strong>Credentials are locals, never fields.</strong> They are read through an injected
 * {@link CredentialProvider} only when a sign-in is actually going to happen, are passed straight
 * to {@link LoginModule} as arguments, and appear in no result, message, exception or
 * {@code toString()}.
 *
 * <p><strong>Wired.</strong> {@code AutomationRunOrchestratorCli} hands one request to
 * {@code CrawlerRunCoordinator}, which constructs this class and calls {@link #run}. It is the
 * one in-process engine, and the CLI's fork guarantees a request never reaches both it and the
 * forked Maven path, so no check runs twice.
 *
 * <p><strong>Screenshots are supporting evidence, never a verdict.</strong> A sign-in the website
 * refused, and anything that escapes the run before the browser is quit, are photographed here
 * through the run's {@link CrawlScreenshotRecorder}; the per-page failures are photographed by
 * {@link CrawlerPageProcessor} through the same collaborator. Credentials are handed to it once,
 * only when a sign-in is actually going to happen, so both that picture and every later page
 * picture mask them, and they are dropped again during cleanup. A capture never alters what the
 * run reports: an escaping failure leaves this class as the same object it arrived as, and a
 * capture that fails is recorded by the collaborator rather than raised, wrapped or logged.
 *
 * <p>Credentials that were never configured are deliberately <em>not</em> photographed. Nothing
 * was asked of the website, so a picture of an untouched browser captioned as a sign-in failure
 * would be a false claim.
 */
public final class CrawlRunner {

    private final RunOperations operations;
    private final CredentialProvider credentials;
    private final CrawlScreenshotRecorder screenshots;
    private final GameLaunchExecution games;

    /**
     * Opening the run's games, once, after the crawl.
     *
     * <p>A seam rather than a direct call so the whole runner can be proved without a browser, and
     * narrow on purpose: the run's own driver and its own configuration in, a typed result out.
     * Production supplies the real module and nothing else.
     */
    @FunctionalInterface
    public interface GameLaunchExecution {
        GameLaunchModule.Result run(WebDriver driver, WebsiteConfig config);
    }

    /**
     * The production game-launch step: a real {@link GameLaunchModule} on the run's own driver.
     *
     * <p><strong>The address is the configured start URL, not {@code getCurrentUrl()}.</strong>
     * A site may route the list through one address and settle on another, and loading the address
     * it settled on can produce an empty list — so both the address the module opens the list at
     * and the address cleanup returns to are the address the run was configured with, which is the
     * one that is known to work. The crawl has just left this driver on whatever page it visited
     * last, which is exactly why the module loads the list itself rather than assuming one.
     */
    private static GameLaunchModule.Result openGames(
        WebDriver driver, WebsiteConfig config, CrawlScreenshotRecorder screenshots
    ) {
        // Locals rather than fields, static or otherwise: this class deliberately carries no
        // state that could outlive or be shared between runs, and a launch budget is no exception.
        int surfaceAttempts = 20;
        long pollMillis = 500;
        return new GameLaunchModule(
            driver,
            config.gameLaunch(),
            // Whether there is a session to verify at all. The module owns the verification
            // itself, because the answer has to be a positive, configured, polled signal rather
            // than anything this class could infer from the shape of the configuration.
            config.tests().login(),
            new GameLaunchExecutionEvidence(),
            surfaceAttempts,
            () -> pause(pollMillis),
            // Diagnostic only: one picture when no surface appeared, through the same recorder and
            // the same redaction every other capture in this run uses. Swallowed exactly as the
            // page-level captures are, because a failed photograph must never displace the
            // finding it was meant to illustrate.
            () -> captureQuietly(driver, screenshots,
                CrawlScreenshotRecorder.RUN_LEVEL, CrawlScreenshotRecorder.CheckTarget.GAME_LAUNCH))
            .run(driver, config.baseUrl().toString());
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The production runner: a real Chrome, the real sign-in, the real crawl. */
    public CrawlRunner() {
        this(new SeleniumRunOperations(), System::getenv, new CrawlScreenshotRecorder());
    }

    /**
     * The production runner with a caller-supplied credential source.
     *
     * @param credentials consulted only when a sign-in is actually required
     */
    public CrawlRunner(CredentialProvider credentials) {
        this(new SeleniumRunOperations(), credentials, new CrawlScreenshotRecorder());
    }

    /**
     * The production runner whose screenshots the caller can read afterwards.
     *
     * @param screenshots the run's collaborator; read {@link CrawlScreenshotRecorder#captured()}
     *     once {@link #run(WebsiteConfig)} has returned or thrown
     */
    public CrawlRunner(CredentialProvider credentials, CrawlScreenshotRecorder screenshots) {
        this(new SeleniumRunOperations(), credentials, screenshots);
    }

    /** The seam a test uses to avoid a browser, a sign-in and a crawl. Not part of the public API. */
    CrawlRunner(RunOperations operations, CredentialProvider credentials) {
        this(operations, credentials, new CrawlScreenshotRecorder());
    }

    /** The same seam, plus the collaborator that records what was photographed. */
    CrawlRunner(
        RunOperations operations, CredentialProvider credentials, CrawlScreenshotRecorder screenshots
    ) {
        // The recorder is captured from the parameter rather than the field, because a
        // constructor may not read `this` before it has finished delegating. The launch step is
        // otherwise the same static one it has always been.
        this(operations, credentials, screenshots,
            (driver, config) -> openGames(driver, config, screenshots));
    }

    /** The same seam, plus the step that opens the run's games. Not part of the public API. */
    CrawlRunner(
        RunOperations operations,
        CredentialProvider credentials,
        CrawlScreenshotRecorder screenshots,
        GameLaunchExecution games
    ) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.screenshots = Objects.requireNonNull(screenshots, "screenshots must not be null");
        this.games = Objects.requireNonNull(games, "games must not be null");
    }

    /**
     * Runs one crawl against the configured site.
     *
     * @throws RuntimeException whatever the browser, sign-in or crawl threw, never replaced
     * @throws Error            propagated untouched
     */
    public CrawlRunResult run(WebsiteConfig config) {
        return run(config, CrawlScope.CONFIGURED_BUDGET);
    }

    /**
     * Runs one visit against the configured site, as wide as the scope allows.
     *
     * @param scope {@link CrawlScope#CONFIGURED_BUDGET} for a run that asked to crawl,
     *     {@link CrawlScope#START_PAGE_ONLY} for one that only needs the start page open
     * @throws RuntimeException whatever the browser, sign-in or crawl threw, never replaced
     * @throws Error            propagated untouched
     */
    public CrawlRunResult run(WebsiteConfig config, CrawlScope scope) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        // Outside the try on purpose: a browser that never started is nothing to quit.
        // Anything a previous run photographed belongs to that run and not to this one.
        screenshots.startRun();
        WebDriver driver = operations.startBrowser(config);

        Throwable primaryFailure = null;
        try {
            return runWithin(driver, config, scope);
        } catch (RuntimeException | Error failure) {
            // Caught only so the quit below can run, then rethrown by identity.
            primaryFailure = failure;
            // The last moment the browser still shows whatever went wrong. Nothing this does can
            // reach the object being rethrown: it is not wrapped, not given a cause and not given
            // a suppressed exception here.
            captureQuietly(driver, CrawlScreenshotRecorder.RUN_LEVEL,
                CrawlScreenshotRecorder.CheckTarget.UNEXPECTED);
            throw failure;
        } finally {
            quit(driver, primaryFailure);
            // The values are needed for as long as a picture might still be taken, and no longer.
            screenshots.clear();
        }
    }

    private CrawlRunResult runWithin(WebDriver driver, WebsiteConfig config, CrawlScope scope) {
        LoginDecision decision = decideLogin(config);

        LoginOutcome loginOutcome = decision.outcome();
        // A local, exactly like the credentials below and for the same reason. It lives for the
        // length of this one crawl, reaches the one collaborator that needs it as an argument, and
        // has nowhere to survive to — which makes "two runs never share a session" a property of
        // the shape of this method rather than a promise about how it is used.
        LinkSession linkSession = LinkSession.anonymous();
        if (decision.required()) {
            CrawlRunResult prevented = signIn(driver, config, decision.login());
            if (prevented != null) {
                // A sign-in that did not succeed stops the run here. Crawling a site as an
                // anonymous visitor when the customer asked for an authenticated crawl would
                // produce results about a different website than the one under test.
                return prevented;
            }
            loginOutcome = LoginOutcome.SUCCEEDED;
            // Here, and nowhere else: after a sign-in that actually ran against the website and
            // succeeded, and before the first page is opened. Being configured for Login, or
            // having selected the check, satisfies neither condition and so cannot reach this
            // line. A capture with nothing to carry says so in its own type — it never quietly
            // becomes the anonymous session above, because an anonymous request to a page that
            // only exists for signed-in visitors is precisely the false failure this prevents.
            linkSession = operations.captureLinkSession(driver, config);
        }

        // The same driver the sign-in just used, so its session travels into every crawled page.
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        CrawlerPageProcessor processor = new CrawlerPageProcessor(
            page, driver, config,
            (pageNumber, target) -> captureQuietly(driver, pageNumber, target),
            linkSession);
        Crawler.CrawlResult crawl = operations.crawl(
            crawlPolicyFor(config, scope), config.baseUrl().toString(), processor);

        // Once for the run, after the crawl, on the driver the sign-in already used — never once
        // per crawled page. A game is opened from the list this session signed in to, and opening
        // one per page would launch the same games repeatedly against someone else's platform.
        // Not selected means not touched: no card is discovered, nothing is observed and no
        // evidence is written, so a run without this check is byte-identical to one from before
        // the check existed.
        GameLaunchModule.Result gameLaunch = config.gameLaunch().enabled()
            ? games.run(driver, config)
            : null;

        return new CrawlRunResult.Crawled(
            loginOutcome, crawl, processor.results(), screenshots.captured(), gameLaunch);
    }

    /**
     * The budget this crawl runs under: the one the run was configured with.
     *
     * <p>This used to be {@link CrawlPolicy#defaults()}, and that was the defect. A run configured
     * for three pages reached the crawler carrying three — {@code run-config.json} said so, and so
     * did {@code generated-run.properties} — and then the number was dropped here, on the way to
     * the only object that could act on it. The crawler stopped exactly where its policy said to;
     * its policy simply never heard about the customer's setting, so every crawl was budgeted at
     * the default of ten and a three-page run opened ten pages of somebody else's website.
     *
     * <p>Only the page budget comes from the configuration. Depth keeps
     * {@link CrawlPolicy#DEFAULT_MAX_DEPTH} because there is no depth control to configure it
     * from, and widening it from a setting nobody can see would crawl deeper than anyone asked.
     * {@code maxLinks} is a different setting entirely — how many destinations the Links check
     * requests on a page — and is deliberately not consulted here.
     *
     * <p>When the run genuinely specifies no page budget, {@code config.maxPages()} already holds
     * the documented default, applied where absence is still visible: the property loader. So
     * there is no second default here, and nothing to keep in step with the first.
     */
    private static CrawlPolicy crawlPolicyFor(WebsiteConfig config, CrawlScope scope) {
        // A run that did not ask to crawl still opens its start page, because the checks and the
        // game list both need a page to be on. It opens that one and stops.
        int pages = scope == CrawlScope.START_PAGE_ONLY ? 1 : config.maxPages();
        return CrawlPolicy.defaults().withMaxPages(pages);
    }

    /** @return the terminal result when the sign-in did not succeed, or null when it did */
    private CrawlRunResult signIn(WebDriver driver, WebsiteConfig config, WebsiteLoginConfig login) {
        // Read here and nowhere else: two locals that never become fields and never leave this
        // method except as arguments to the sign-in itself.
        String username = credentials.valueOf(login.usernameEnv());
        String password = credentials.valueOf(login.passwordEnv());
        if (blank(username) || blank(password)) {
            // A configuration problem, not a website one: nothing was asked of the site, no
            // browser interaction happened, and so there is nothing truthful to photograph. The
            // names are safe to report; the values are the thing that must never appear.
            return CrawlRunResult.credentialsUnavailable(
                "Login is enabled but the configured credential environment variables are not set: "
                    + login.usernameEnv() + ", " + login.passwordEnv());
        }

        // Handed over for this run only, so that this sign-in picture and every later page
        // picture mask them. Still locals here: the collaborator holds them, this class does not.
        screenshots.protect(username);
        screenshots.protect(password);

        LoginModule.Result result =
            operations.signIn(driver, config, login, username, password);
        // Photographed whenever the sign-in is not *proved*, not only when it is declared failed.
        // A run with no success condition configured passes on having submitted the form, so the
        // one page worth looking at -- the page the site showed after the click -- was never
        // captured in exactly the case where nobody could tell what had happened.
        if (!result.passed() || !result.authenticationVerified()) {
            // The browser is still on the sign-in page. A moment later the run ends and it is not.
            captureQuietly(driver, CrawlScreenshotRecorder.RUN_LEVEL,
                CrawlScreenshotRecorder.CheckTarget.LOGIN);
        }
        return result.passed()
            ? null
            // The site was asked and did not let us in. That is a finding about the website.
            : CrawlRunResult.signInFailed(
                "The configured sign-in did not succeed, so no page was crawled: "
                    + SensitiveDataFilter.sanitizeMessage(result.message()),
                screenshots.captured());
    }

    /**
     * Asks for one picture and lets nothing come back out.
     *
     * <p>A capture is supporting evidence. It may not replace a website failure, an analyzer
     * crash or an escaping throwable, so everything it could raise stops here -- including
     * {@link Error}, because the alternative is an out-of-memory condition inside a screenshot
     * displacing the failure the run was already reporting. The collaborator records every
     * attempt, so a swallowed problem is still visible to the caller afterwards.
     */
    private void captureQuietly(
        WebDriver driver, int pageNumber, CrawlScreenshotRecorder.CheckTarget target
    ) {
        captureQuietly(driver, screenshots, pageNumber, target);
    }

    /** The same swallow, for the launch step, which is reached without an instance. */
    private static void captureQuietly(
        WebDriver driver, CrawlScreenshotRecorder screenshots,
        int pageNumber, CrawlScreenshotRecorder.CheckTarget target
    ) {
        try {
            screenshots.capture(driver, pageNumber, target);
        } catch (RuntimeException | Error ignored) {
            // Intentionally empty, and deliberately not logged: a capture failure can carry text
            // the browser supplied.
        }
    }

    /**
     * Quits the one driver this run started.
     *
     * @param primaryFailure the failure already on its way out, or null when the work succeeded
     */
    private static void quit(WebDriver driver, Throwable primaryFailure) {
        try {
            driver.quit();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) {
                // Nothing else is travelling, so a browser that would not close is the news.
                throw cleanupFailure;
            }
            // Something worse is already on its way out. Java's own mechanism for exactly this.
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    // --- whether to sign in at all ----------------------------------------------------------------

    /**
     * The sign-in decision, taken from the same two gates the single-page Login check uses:
     * {@code tests.login} selects the check, and {@code login.enabled} enables the execution.
     * Either one being false means no credential is ever requested.
     */
    private static LoginDecision decideLogin(WebsiteConfig config) {
        if (!config.tests().login()) {
            return new LoginDecision(LoginOutcome.NOT_SELECTED, null);
        }
        WebsiteLoginConfig login = config.login();
        if (login == null || !login.enabled()) {
            return new LoginDecision(LoginOutcome.DISABLED, null);
        }
        return new LoginDecision(LoginOutcome.REQUIRED, login);
    }

    private record LoginDecision(LoginOutcome outcome, WebsiteLoginConfig login) {

        boolean required() {
            return outcome == LoginOutcome.REQUIRED;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // --- what the caller gets back ----------------------------------------------------------------

    /**
     * What happened about signing in.
     *
     * <p>{@code REQUIRED} is an internal decision state and never reaches a result: by the time a
     * result exists the sign-in has either succeeded or prevented the run.
     */
    public enum LoginOutcome {
        /** {@code tests.login} is false. No credential was requested. */
        NOT_SELECTED,
        /** Selected, but {@code login.enabled} is false or no login block exists. */
        DISABLED,
        /** Internal: a sign-in is going to be attempted. */
        REQUIRED,
        /** The sign-in completed and its session was used for the crawl. */
        SUCCEEDED,
        /** The sign-in did not complete, so nothing was crawled. */
        FAILED
    }

    /**
     * Why a sign-in prevented the run.
     *
     * <p>Two reasons that look similar and must be treated oppositely. Keeping them as separate
     * constants is what lets a later reporting layer decide between "fix your configuration" and
     * "your website rejected a valid sign-in" without parsing a sentence.
     */
    public enum LoginPreventedCause {
        /** The configured credential environment variables were not set. Nothing was attempted. */
        CREDENTIALS_UNAVAILABLE,
        /** The sign-in ran against the website and did not succeed. */
        SIGN_IN_FAILED
    }

    /**
     * The outcome of one run.
     *
     * <p>Sealed into exactly two shapes so that a caller cannot ask a question the run cannot
     * answer. When a sign-in prevents the crawl there is no {@code CrawlResult} to hand back, and
     * rather than returning null or an empty crawl — both of which read downstream as "we crawled
     * the site and found nothing" — {@link LoginPrevented} simply has no crawl field to read. The
     * difference between "no findings" and "never looked" is the whole point, and the type system
     * is a better place to enforce it than a comment.
     *
     * <p>Both shapes validate their own honesty: a {@code LoginPrevented} can only carry
     * {@link LoginOutcome#FAILED}, and a {@code Crawled} can never carry it.
     */
    public sealed interface CrawlRunResult {

        /** Always present, always truthful, never {@code REQUIRED}. */
        LoginOutcome login();

        /**
         * What this run photographed, as an immutable snapshot taken when the result was built.
         *
         * <p>Typed ownership, not a file name to take apart: each reference already says which
         * page and which check it belongs to. The recorder itself is never exposed — a caller that
         * could reach it could also empty it, or read another run's pictures out of it.
         */
        List<CrawlScreenshotRecorder.ScreenshotRef> screenshots();

        /**
         * The sign-in stopped the run before any page was opened.
         *
         * <p>{@code cause} is typed rather than inferred from {@code message}, because the two
         * reasons need opposite treatment downstream and reading English to tell them apart is
         * not a classification. A run that never asked the website anything is a configuration
         * problem; a run the website refused is a finding about the website.
         */
        record LoginPrevented(
            LoginOutcome login,
            LoginPreventedCause cause,
            String message,
            List<CrawlScreenshotRecorder.ScreenshotRef> screenshots
        ) implements CrawlRunResult {

            /** The shape every existing caller uses: a prevented run that photographed nothing. */
            public LoginPrevented(LoginOutcome login, LoginPreventedCause cause, String message) {
                this(login, cause, message, List.of());
            }

            public LoginPrevented {
                if (login != LoginOutcome.FAILED) {
                    throw new IllegalArgumentException(
                        "a prevented run must report FAILED, but reported " + login);
                }
                Objects.requireNonNull(cause, "a prevented run must say which kind of failure");
                if (message == null || message.isBlank()) {
                    throw new IllegalArgumentException("a prevented run must say why");
                }
                // Copied, so the recorder that produced them can go on recording without this
                // result changing underneath whoever is reading it.
                screenshots = List.copyOf(screenshots);
            }
        }

        /** The crawl ran. The sign-in either succeeded or was never needed. */
        record Crawled(
            LoginOutcome login,
            Crawler.CrawlResult crawl,
            List<CrawlerPageProcessor.PageAuditResult> pages,
            List<CrawlScreenshotRecorder.ScreenshotRef> screenshots,
            /** What the run's games amounted to, or null when the check was not selected. */
            GameLaunchModule.Result gameLaunch
        ) implements CrawlRunResult {

            /** The shape every existing caller uses: a crawl that photographed nothing. */
            public Crawled(
                LoginOutcome login,
                Crawler.CrawlResult crawl,
                List<CrawlerPageProcessor.PageAuditResult> pages
            ) {
                this(login, crawl, pages, List.of());
            }

            /** The pre-game-launch shape, so every existing caller compiles unchanged. */
            public Crawled(
                LoginOutcome login,
                Crawler.CrawlResult crawl,
                List<CrawlerPageProcessor.PageAuditResult> pages,
                List<CrawlScreenshotRecorder.ScreenshotRef> screenshots
            ) {
                this(login, crawl, pages, screenshots, null);
            }

            public Crawled {
                if (login == LoginOutcome.FAILED || login == LoginOutcome.REQUIRED) {
                    throw new IllegalArgumentException(
                        "a crawl cannot have happened with login " + login);
                }
                Objects.requireNonNull(crawl, "crawl must not be null");
                pages = List.copyOf(pages);
                screenshots = List.copyOf(screenshots);
            }
        }

        /** The run never asked the website anything: its credentials were not configured. */
        static CrawlRunResult credentialsUnavailable(String message) {
            return new LoginPrevented(
                LoginOutcome.FAILED, LoginPreventedCause.CREDENTIALS_UNAVAILABLE, message);
        }

        /** The website was asked to sign us in and did not. */
        static CrawlRunResult signInFailed(String message) {
            return signInFailed(message, List.of());
        }

        /** The website was asked to sign us in, did not, and was photographed refusing. */
        static CrawlRunResult signInFailed(
            String message, List<CrawlScreenshotRecorder.ScreenshotRef> screenshots
        ) {
            return new LoginPrevented(
                LoginOutcome.FAILED, LoginPreventedCause.SIGN_IN_FAILED, message, screenshots);
        }
    }

    // --- the real-world work this runner orchestrates ----------------------------------------------

    /**
     * Starting a browser, signing in and crawling.
     *
     * <p>Deliberately the three operations that need a real browser or a real network, and nothing
     * else. Everything the runner actually decides — whether to sign in, what to do when it fails,
     * when to quit — stays in the runner, where it can be proved without any of them.
     */
    interface RunOperations {

        /** @throws RuntimeException when the browser could not be started */
        WebDriver startBrowser(WebsiteConfig config);

        LoginModule.Result signIn(
            WebDriver driver, WebsiteConfig config, WebsiteLoginConfig login,
            String username, String password);

        /**
         * Reads the session a successful sign-in just established, so the link check can present
         * it.
         *
         * <p>Deliberately an operation rather than something the runner does itself: reading a
         * browser's cookie jar is real-browser work, which is the one thing this interface is for,
         * and keeping it here means the runner can be proved without a Chrome.
         *
         * <p>It has no default implementation on purpose. A default would have to be either
         * anonymous — which would silently strip the session from any implementation that forgot
         * to override it, reintroducing the exact bug — or authenticated, which would be a lie. A
         * missing implementation should be a compile error, and this is how it becomes one.
         *
         * <p>Called only after a sign-in succeeded. Never throws: an unavailable session is a
         * typed answer, not a failure of the run.
         */
        LinkSession captureLinkSession(WebDriver driver, WebsiteConfig config);

        Crawler.CrawlResult crawl(
            CrawlPolicy policy, String startUrl, CrawlerPageProcessor processor);
    }

    /**
     * Where a credential value comes from.
     *
     * <p>Injected rather than read here so the runner never touches the process environment, and
     * so a test can prove it was not consulted at all when no sign-in was required.
     */
    @FunctionalInterface
    public interface CredentialProvider {

        /** @return the value for that environment-variable name, or null when it is not set */
        String valueOf(String environmentVariableName);
    }

    /** The production operations: the shared driver factory, the real Login, the real Crawler. */
    private static final class SeleniumRunOperations implements RunOperations {

        @Override
        public WebDriver startBrowser(WebsiteConfig config) {
            return new SeleniumDriverFactory().create(config);
        }

        @Override
        public LoginModule.Result signIn(
            WebDriver driver, WebsiteConfig config, WebsiteLoginConfig login,
            String username, String password
        ) {
            return new LoginModule(
                driver, WebsiteConfigLoader.timeout(config), new LoginExecutionEvidence())
                .run(login, username, password);
        }

        @Override
        public LinkSession captureLinkSession(WebDriver driver, WebsiteConfig config) {
            // The same driver the sign-in used, read once. Everything about which cookies may
            // travel, and where to, belongs to LinkSession; nothing about a cookie is visible here.
            return LinkSession.capturedFrom(driver, config.baseUrl());
        }

        @Override
        public Crawler.CrawlResult crawl(
            CrawlPolicy policy, String startUrl, CrawlerPageProcessor processor
        ) {
            return new Crawler(policy).crawl(startUrl, processor);
        }
    }
}
