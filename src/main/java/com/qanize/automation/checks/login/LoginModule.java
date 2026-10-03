package com.qanize.automation.checks.login;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.TestedItem;

import org.openqa.selenium.WebDriver;

/**
 * The Login check, as production code.
 *
 * <p>Login is a once-per-run operation, not a per-page one: it establishes a session, and a future
 * run engine will call it once before crawling protected pages. Nothing here is written to be
 * invoked per crawled page, and nothing here decides what to visit next.
 *
 * <p><strong>Credentials arrive as arguments and leave as nothing.</strong> They are never fields,
 * never logged, never serialized and never recorded. What reaches an artifact is the masked
 * identifier, the environment-variable <em>name</em> that supplied the value, and a sanitized copy
 * of the final address — never a username, a password or a token. Resolving those values from the
 * process environment, and registering them for screenshot redaction, stays with the caller,
 * because screenshot redaction is a property of the browser session rather than of this flow.
 *
 * <p><strong>One sign-in, one submit, no retry.</strong> The helper submits exactly once; a failed
 * sign-in is a result, never something to attempt again.
 *
 * <p><strong>It receives an already-open browser.</strong> Nothing here creates or closes a driver.
 *
 * <p><strong>It never asserts.</strong> The outcome is returned so the same flow can back a JUnit
 * wrapper and, later, a run engine.
 */
public final class LoginModule {

    /**
     * How often the configured success condition is re-read while the sign-in settles.
     *
     * <p>The same cadence Game Launch polls its own page conditions with. The number of passes
     * comes from the run timeout, so the operator's one timeout setting still governs how long
     * this check is willing to wait.
     */
    private static final long SUCCESS_POLL_MILLIS = 500;

    private final LoginActions actions;
    private final LoginExecutionEvidence execution;
    private final int attempts;
    private final Runnable pause;

    /**
     * The production entry point.
     *
     * @param driver    a browser the caller already started
     * @param execution the recorder the caller already used, so per-test-case sequence numbers
     *                  continue rather than restarting
     */
    public LoginModule(WebDriver driver, Duration timeout, LoginExecutionEvidence execution) {
        this(new SeleniumLoginActions(driver, timeout), execution,
            attemptsWithin(timeout), () -> pause(SUCCESS_POLL_MILLIS));
    }

    /**
     * The seam a test uses to supply its own actions. Not part of the public API.
     *
     * <p>One attempt and no pause: a test that says nothing about waiting gets the single read it
     * has always got, and only a test that asks for passes has to reason about them.
     */
    LoginModule(LoginActions actions, LoginExecutionEvidence execution) {
        this(actions, execution, 1, () -> { });
    }

    /**
     * The seam a test uses to drive the success wait without sleeping.
     *
     * @param attempts how many times the configured condition may be read before giving up
     * @param pause    what happens between two reads; a test counts instead of sleeping
     */
    LoginModule(LoginActions actions, LoginExecutionEvidence execution, int attempts, Runnable pause) {
        this.actions = actions;
        this.execution = execution;
        this.attempts = Math.max(1, attempts);
        this.pause = Objects.requireNonNull(pause);
    }

    /** The number of condition reads that fit in the run timeout. Never fewer than one. */
    private static int attemptsWithin(Duration timeout) {
        long millis = timeout == null ? 0L : timeout.toMillis();
        return (int) Math.max(1L, millis / SUCCESS_POLL_MILLIS);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Signs in once with the supplied credentials and reports what happened.
     *
     * <p>The credentials are parameters, never fields: nothing retains them beyond this call.
     */
    public Result run(WebsiteLoginConfig login, String username, String password) {
        String loginUrl = login.loginUrl() == null ? null : login.loginUrl().toString();
        Instant startedAt = Instant.now();
        String failure = null;
        GenericLoginHelper.Result attempt = null;
        try {
            attempt = actions.signIn(login, username, password, execution);
        } catch (RuntimeException loginFailure) {
            failure = SensitiveDataFilter.sanitizeMessage(loginFailure.toString());
        }
        // A control we could not find is a website result, not an automation crash.
        boolean submitted = attempt != null && attempt.submitted();
        if (attempt != null && !submitted) {
            failure = null;
        }

        // What the operator asked to be proved. Read once, here, and read by everything below:
        // the wait, the tested item, the execution steps and the terminal verdict all key on these
        // same two answers, so the item and the verdict can never describe different runs.
        String expectedUrl = login.optionalSuccessUrlContains().orElse(null);
        String expectedText = login.optionalSuccessText().orElse(null);
        boolean successConditionConfigured = expectedUrl != null || expectedText != null;

        // The wait. A website signs in over the network: the address changes and the page redraws
        // some time after the click, not during it. Reading once, immediately, asked the page a
        // question it had not had time to answer and then reported the silence as the website's
        // verdict. The condition is now polled for the run timeout and settles the moment it holds,
        // so a sign-in that succeeds in 300ms costs 300ms and only a sign-in that never happens
        // costs the whole budget.
        long waitStart = System.nanoTime();
        // The address first, then the baseline, then the page: the order this check has always
        // used, and the order its steps are recorded in.
        String finalUrl = actions.safeCurrentUrl();
        // A success text already on the login page cannot prove a sign-in, so the condition
        // requires a transition from absent to present. Presence alone is an unusable condition,
        // and an unusable condition is never worth waiting for.
        boolean textPresentBefore = Boolean.TRUE.equals(actions.successTextPresentBeforeLogin());
        boolean textUnusable = expectedText != null && textPresentBefore;
        boolean urlOk = expectedUrl == null || addressContains(finalUrl, expectedUrl);
        boolean textFound = expectedText != null && actions.pageSourceContains(expectedText);
        boolean textOk = expectedText == null || (textFound && !textPresentBefore);
        // Nothing to wait for when the form was never submitted, when no condition was configured,
        // or when the configured text could not prove anything however long it were watched.
        boolean worthWaiting = submitted && successConditionConfigured && !textUnusable;
        for (int pass = 1; worthWaiting && !(urlOk && textOk) && pass < attempts; pass++) {
            pause.run();
            finalUrl = actions.safeCurrentUrl();
            urlOk = expectedUrl == null || addressContains(finalUrl, expectedUrl);
            textFound = expectedText != null && actions.pageSourceContains(expectedText);
            textOk = expectedText == null || (textFound && !textPresentBefore);
        }
        long waitMs = elapsedMs(waitStart);
        // The single decision about the configured success text, taken once, after the wait.
        boolean successTextProved = textOk;

        // The address reached once the wait settled, not the address a millisecond after the click.
        // The raw address drives the comparison above; only the sanitized copy is ever persisted.
        // The address is recorded after every outcome, so the step is told whether the form was
        // actually submitted and must not imply a sign-in that did not happen.
        execution.readFinalAddress(SensitiveDataFilter.sanitizeUrl(finalUrl), submitted, waitMs);
        if (expectedText != null) {
            // Only the answers: never the configured text, never the page content.
            execution.checkedSuccessText(textPresentBefore, textFound, waitMs);
        }

        // A submitted form is not a pass on its own. Authentication is either proved by a condition
        // the operator configured or it is not established, and "not established" is not a success.
        // Both condition flags still default to "met" when unconfigured, so the final term is what
        // stops a run that configured nothing from inheriting two vacuous trues as a verdict.
        boolean ok = failure == null && submitted && urlOk && successTextProved
            && successConditionConfigured;

        // Credentials are never recorded: only the masked identifier and the field types.
        TestedItem.Builder item = TestedItem.builder("LOGIN", TestedItem.ItemType.LOGIN)
            .id("login-1")
            .name("Configured sign-in")
            .pageUrl(loginUrl)
            .target(loginUrl)
            .action("Opened the login page, entered the configured credentials and submitted the form.")
            .expected(describeExpectation(login))
            .status(ok ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
            .startedAt(startedAt.toString())
            .completedAt(Instant.now().toString())
            .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
            .meta("identifier", SensitiveDataFilter.maskIdentifier(username))
            .meta("identifierSource", login.usernameEnv())
            .meta("passwordFieldDetected", "true")
            .meta("submitted", submitted ? "true" : "false");
        if (attempt != null) {
            item.meta("outcome", attempt.outcome().name());
            attempt.resolvedRoles().forEach((role, source) -> item.meta("element." + role, source));
        }
        // The raw address stays in memory for the success comparison above and the verdict below;
        // only a sanitized copy is ever persisted. A post-sign-in address is exactly where a token
        // or session identifier turns up, and this metadata reaches the run artifact and the UI.
        String safeFinalUrl = SensitiveDataFilter.sanitizeUrl(finalUrl);
        if (safeFinalUrl != null && !safeFinalUrl.isBlank()) item.meta("finalUrl", safeFinalUrl);
        item.actual(failure != null
            ? "The sign-in flow could not be completed."
            : !submitted
                ? (attempt == null ? "The sign-in flow did not run." : attempt.message())
                : (ok
                    ? "Signed in and the configured success condition was met."
                    : !successConditionConfigured
                        ? "The sign-in form was submitted, but no success condition was "
                            + "configured, so authentication was never verified."
                        : "The website did not accept the credentials: the configured success "
                            + "condition never appeared before the timeout."));
        if (failure != null) {
            item.message(failure);
        } else if (attempt != null && !submitted) {
            item.message(attempt.suggestedAction());
        }
        EvidenceRecorder.record(item.build());

        // Recorded before the verdict is returned, so it survives whatever the caller does next.
        // With no success condition configured this says authentication was not verified; the
        // PASS/FAIL result itself is unchanged. The verdict inherits the category of whatever
        // caused it: a browser failure stays infrastructure, a bad supplied selector stays
        // configuration, and only a completed flow that misses a configured condition is the
        // website's.
        execution.assertedOutcome(
            submitted,
            failure != null,
            attempt == null ? null : attempt.outcome(),
            login.optionalSuccessUrlContains().isPresent(), urlOk,
            login.optionalSuccessText().isPresent(), successTextProved, textUnusable);

        // Fail-fast, in the order the check has always reported: a browser failure first, then a
        // flow that never submitted, then each configured success condition.
        if (failure != null) {
            return new Result(Outcome.FAILED, "Login flow failed: " + failure);
        }
        if (attempt == null || !attempt.submitted()) {
            return new Result(Outcome.FAILED,
                attempt == null ? "The sign-in flow did not run." : attempt.message());
        }
        // Submitted, and nothing was configured that could prove what the submission achieved.
        // Reported before the two condition checks below because neither of them can run: this is
        // a run that cannot answer its own question, and saying so is the only truthful verdict.
        if (!successConditionConfigured) {
            return new Result(Outcome.FAILED,
                "The sign-in form was submitted, but no success condition was configured, so "
                    + "authentication was never verified.");
        }
        // Both decisions are the ones the tested item above carries, read again rather than taken
        // again. The page is not asked a second time, so this verdict and that item can never
        // disagree, and neither of them can disagree with what the wait actually observed.
        if (expectedUrl != null && !urlOk) {
            return new Result(Outcome.FAILED,
                "Login success URL did not contain configured text: " + expectedUrl);
        }
        if (expectedText != null && !successTextProved) {
            return new Result(Outcome.FAILED, textPresentBefore
                ? "Login success text did not prove a post-login transition: \"" + expectedText
                  + "\" was already on the page before the credentials were submitted."
                : "Login success text was not found: " + expectedText);
        }
        return new Result(Outcome.PASSED, null, successConditionConfigured);
    }

    /** Whether the address actually reached contains what the operator configured. */
    private static boolean addressContains(String url, String expected) {
        return url != null && url.contains(expected);
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String describeExpectation(WebsiteLoginConfig login) {
        String url = login.optionalSuccessUrlContains().orElse(null);
        String text = login.optionalSuccessText().orElse(null);
        if (url != null && text != null) {
            return "The address contains \"" + url + "\" and the page shows \"" + text + "\".";
        }
        if (url != null) return "The address contains \"" + url + "\" after signing in.";
        if (text != null) return "The page shows \"" + text + "\" after signing in.";
        return "The sign-in form is submitted without an error.";
    }

    // --- what the caller gets back ------------------------------------------------------------

    /** How a Login run ended. Login has no "nothing to do" state: it is configured or not run. */
    public enum Outcome { PASSED, FAILED }

    /** The outcome of one sign-in, and the message the caller reports with it. */
    public record Result(Outcome outcome, String message, boolean authenticationVerified) {

        /** Kept for callers that only ask the verdict; verification is the separate question. */
        public Result(Outcome outcome, String message) {
            this(outcome, message, false);
        }

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }

        /**
         * Whether a configured success condition actually proved the sign-in.
         *
         * <p>Separate from {@link #passed()} on purpose. A run with no condition configured passes
         * on having submitted the form without an error, which is not the same as having signed in
         * -- and a caller deciding whether the page is worth photographing needs the second answer,
         * not the first.
         */
        public boolean authenticationVerified() {
            return authenticationVerified;
        }
    }

    // --- the browser work Login needs ---------------------------------------------------------

    /**
     * The sign-in work Login performs. Deliberately not a general abstraction: it exists so this
     * one module can be driven without a browser and without real credentials.
     */
    interface LoginActions {

        /**
         * One sign-in attempt, submitting exactly once.
         *
         * @throws RuntimeException when the browser fails; the caller contains it
         */
        GenericLoginHelper.Result signIn(
            WebsiteLoginConfig login, String username, String password,
            GenericLoginHelper.Observer observer);

        /** Whether the configured success text was already present before the attempt. */
        Boolean successTextPresentBeforeLogin();

        /** The current address, or null when it cannot be read. Never throws. */
        String safeCurrentUrl();

        /** Whether the page currently contains the configured text. Answers only yes or no. */
        boolean pageSourceContains(String expected);
    }

    /** The production sign-in, over the browser the caller already started. */
    private static final class SeleniumLoginActions implements LoginActions {

        private final WebDriver driver;
        private final GenericLoginHelper helper;

        SeleniumLoginActions(WebDriver driver, Duration timeout) {
            this.driver = driver;
            // Hoisted so the pre-attempt success-text baseline survives a thrown sign-in.
            this.helper = new GenericLoginHelper(driver, timeout);
        }

        @Override
        public GenericLoginHelper.Result signIn(
            WebsiteLoginConfig login, String username, String password,
            GenericLoginHelper.Observer observer
        ) {
            return helper.login(login, username, password, observer);
        }

        @Override
        public Boolean successTextPresentBeforeLogin() {
            return helper.successTextPresentBeforeLogin();
        }

        @Override
        public String safeCurrentUrl() {
            try { return driver.getCurrentUrl(); } catch (RuntimeException ignored) { return null; }
        }

        @Override
        public boolean pageSourceContains(String expected) {
            try {
                return driver.getPageSource().contains(expected);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
