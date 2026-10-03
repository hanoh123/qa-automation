package com.qanize.automation.checks.login;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.qanize.automation.checks.shared.BasePage;
import com.qanize.automation.checks.shared.SafePointerClick;
import com.qanize.automation.checks.shared.SelectorUtils;
import com.qanize.automation.checks.shared.WaitUtils;

import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;


/**
 * Performs the configured sign-in.
 *
 * <p>Selectors are optional. Any selector the user supplied is used exactly as given; every other
 * role is resolved by {@link LoginElementDetector}. Nothing is typed and nothing is clicked until
 * all three roles have been resolved, so a page where only some controls can be found is left
 * untouched rather than half filled in.
 *
 * <p><strong>Each control is waited for, not merely looked for.</strong> A sign-in form usually
 * arrives after the document does, so every role — supplied or detected — is polled until its
 * control is present, visible and enabled, bounded by the configured timeout. See
 * {@link #resolve(String, LoginElementDetector.Role, Map)} for why, and for the two cases that are
 * still answered immediately.
 */
public final class GenericLoginHelper {

    /** How a control was obtained, for the user-facing result. */
    public enum Source { MANUAL, DETECTED }

    /** The outcome of an attempt, distinguishing website results from automation problems. */
    public enum Outcome {
        SUBMITTED,
        USERNAME_NOT_FOUND,
        PASSWORD_NOT_FOUND,
        SUBMIT_NOT_FOUND,
        /**
         * The sign-in control was found and is visible, but never became enabled after the
         * credentials were typed. Distinct from {@link #SUBMIT_NOT_FOUND}: the selector was right
         * and the control is on screen, so telling the operator to fix the selector would be wrong.
         */
        SUBMIT_NEVER_ENABLED,
        AMBIGUOUS_ELEMENTS,
        INVALID_MANUAL_SELECTOR,
        /**
         * The page never presented a usable sign-in trigger inside the readiness budget.
         *
         * <p>Deliberately not {@link #INVALID_MANUAL_SELECTOR}. A single-page application answers
         * {@code driver.get} with an empty shell and renders its header some seconds later; a run
         * that gave up while the spinner was still turning learnt nothing about the selector it
         * was given. Saying otherwise sends the operator to change a setting that was correct, so
         * this outcome says what was actually observed and nothing more.
         */
        TRIGGER_NOT_READY
    }

    public record Result(
        Outcome outcome,
        String message,
        String suggestedAction,
        Map<String, String> resolvedRoles
    ) {
        public Result {
            resolvedRoles = Map.copyOf(resolvedRoles);
        }

        public boolean submitted() { return outcome == Outcome.SUBMITTED; }
    }

    /** Which edit or click was being attempted when the browser threw. */
    public enum Interaction { TYPE_USERNAME, TYPE_PASSWORD, CLICK_SUBMIT }

    /**
     * Told what happened, never what was typed.
     *
     * <p>The sign-in flow is the one place the engine handles a secret, so the reporting seam is
     * deliberately shaped so a secret cannot cross it: <strong>no method here accepts a username, a
     * password, a length or any derived form of either.</strong> {@link #typedUsername(long)} and
     * {@link #typedPassword(long)} take a duration and nothing else. What an implementation can
     * learn is which control role was involved, how it was resolved, how long an operation took,
     * and why one failed.
     *
     * <p>This interface lives in the browser package on purpose: the helper must not depend on the
     * evidence layer. Evidence depends on browser, never the reverse.
     */
    public interface Observer {

        /** A no-op, so the existing three-argument call behaves exactly as it always has. */
        Observer NONE = new Observer() { };

        /** {@code driver.get(loginUrl)} returned. */
        default void openedLoginPage(long durationMs) { }

        /**
         * The optional control that reveals the sign-in form was clicked, once.
         *
         * @param selector the configured selector, which is configuration and never a value
         */
        default void clickedLoginTrigger(String selector, long durationMs) { }

        /** The browser threw while opening the login page; the same exception is then rethrown. */
        default void loginPageOpenFailed(Throwable failure, long durationMs) { }

        /**
         * A control was resolved.
         *
         * @param resolution the value this helper already publishes for the role, either
         *                   {@code DETECTED} or {@code MANUAL:<selector>} — configuration, never a value
         */
        default void resolvedControl(LoginElementDetector.Role role, String resolution, long durationMs) { }

        /** A control could not be resolved; the helper returns a Result rather than throwing. */
        default void controlNotResolved(
            LoginElementDetector.Role role, Outcome outcome, String explanation, long durationMs) { }

        /** The username field was cleared and the configured username was sent to it. */
        default void typedUsername(long durationMs) { }

        /** The password field was cleared and the configured password was sent to it. */
        default void typedPassword(long durationMs) { }

        /**
         * The configured success text was checked on the login page before signing in.
         *
         * @param alreadyPresent whether it was already there, which would make it unusable as proof
         */
        default void capturedSuccessTextBaseline(boolean alreadyPresent, long durationMs) { }

        /**
         * The resolved submit control was centred in the viewport and became clickable.
         *
         * <p>One interval covers the whole preparation, because centring and waiting happen back
         * to back and cannot be measured apart without changing what the browser does.
         */
        default void awaitedSubmitClickable(long durationMs) { }

        /** The control never became clickable within the configured timeout; the same exception is rethrown. */
        default void submitNeverBecameClickable(Throwable failure, long durationMs) { }

        /** The resolved submit control was clicked. */
        default void clickedSubmit(long durationMs) { }

        /** The browser threw during an edit or the click; the same exception is then rethrown. */
        default void interactionFailed(Interaction interaction, Throwable failure, long durationMs) { }
    }

    /**
     * How long a configured sign-in trigger is given to appear before the run gives up.
     *
     * <p>Longer than the ordinary element timeout, and separate from it on purpose: everything
     * else in this flow is looked for on a page that has already rendered, while this one control
     * is looked for on a page that may still be booting. The wait ends the moment the trigger is
     * usable, so a site that renders in 400ms costs 400ms; only a site that never renders costs
     * the whole budget.
     */
    public static final Duration TRIGGER_READINESS_TIMEOUT = Duration.ofSeconds(30);

    private final WebDriver driver;
    private final BasePage page;
    private final LoginElementDetector detector;
    private final Duration timeout;
    private final Duration triggerReadiness;
    /** The one safe click in the project. Shared with the configured menu check. */
    private final SafePointerClick safeClick;

    /**
     * Whether the configured success text was already on the login page before signing in.
     *
     * <p>{@code null} when no success text is configured, or when the page never opened. A single
     * boolean: the configured text is never retained here, and neither is any page content.
     */
    private Boolean successTextPresentBeforeLogin;

    public GenericLoginHelper(WebDriver driver, Duration timeout) {
        this(driver, timeout, TRIGGER_READINESS_TIMEOUT);
    }

    /**
     * The seam a test uses to shorten the readiness budget.
     *
     * <p>Not public: production always gets {@link #TRIGGER_READINESS_TIMEOUT}. A test that waited
     * the real thirty seconds to prove a timeout would be thirty seconds of nothing happening.
     */
    GenericLoginHelper(WebDriver driver, Duration timeout, Duration triggerReadiness) {
        this.driver = driver;
        this.page = new BasePage(driver, timeout);
        this.detector = new LoginElementDetector(driver);
        this.timeout = timeout;
        this.triggerReadiness = Objects.requireNonNull(triggerReadiness, "triggerReadiness");
        this.safeClick = new SafePointerClick(driver);
    }

    /** Unchanged entry point: behaves exactly as before, observing nothing. */
    public Result login(WebsiteLoginConfig config, String username, String password) {
        return login(config, username, password, Observer.NONE);
    }

    /**
     * The same flow, reporting each real operation to a secret-blind {@link Observer}.
     *
     * <p>Every operation is timed at its own boundary, so no two records can be given the same
     * elapsed interval. Observing adds no lookup, click, keystroke, wait or page read: it only
     * reports what the calls below already do.
     */
    public Result login(
        WebsiteLoginConfig config, String username, String password, Observer observer
    ) {
        config.validateReadyForLogin();

        long started = System.nanoTime();
        try {
            page.openUrl(config.loginUrl().toString());
        } catch (RuntimeException failure) {
            observer.loginPageOpenFailed(failure, elapsedMs(started));
            throw failure;
        }
        observer.openedLoginPage(elapsedMs(started));

        // The optional control that reveals the form, clicked once, before anything is looked
        // for. Blank is the ordinary case and skips this block entirely, which is what keeps a
        // configuration written before this field existed behaving exactly as it always did.
        //
        // It reveals a form; it does not sign anything in. There is one click, it is never
        // retried, and it happens before a single character is typed.
        if (config.optionalTriggerSelector().isPresent()) {
            String triggerSelector = config.optionalTriggerSelector().get();
            started = System.nanoTime();
            By triggerLocator;
            try {
                triggerLocator = SelectorUtils.css(triggerSelector);
                driver.findElements(triggerLocator);
            } catch (RuntimeException invalid) {
                return triggerProblem(triggerSelector, TriggerProblem.MALFORMED);
            }
            // A disabled trigger cannot open anything, so this one must already be enabled.
            //
            // Given the readiness budget rather than the ordinary element timeout: this is the
            // first thing looked for on a page that may still be booting, and the wait returns the
            // instant the control is usable, so a responsive site pays nothing for the longer
            // ceiling.
            WebElement trigger = awaitFirstUsable(triggerLocator, true, triggerReadiness);
            if (trigger == null) {
                return triggerProblem(triggerSelector, TriggerProblem.NOT_READY);
            }
            // The same ownership wait the submit control gets, for the same reason and through
            // the same shared helper: displayed and enabled is not the same as reachable, and a
            // banner sitting over the control would otherwise receive this click instead of it.
            // If it never becomes the owner, nothing is clicked and nothing is typed.
            if (!awaitClickPointOwnership(trigger)) {
                return triggerProblem(triggerSelector, TriggerProblem.COVERED);
            }
            safeClick.clickOnce(trigger);
            observer.clickedLoginTrigger(triggerSelector, elapsedMs(started));
        }

        // A configured success text that is already on the login page cannot prove a sign-in. One
        // public practice page prints "You logged into a secure area!" in its own instructions, so
        // a plain post-attempt search reported success after a click that never landed. The
        // baseline is a boolean: the text itself is never held, compared aloud or published.
        successTextPresentBeforeLogin = null;
        if (config.optionalSuccessText().isPresent()) {
            started = System.nanoTime();
            successTextPresentBeforeLogin = containsSuccessText(config.optionalSuccessText().get());
            observer.capturedSuccessTextBaseline(successTextPresentBeforeLogin, elapsedMs(started));
        }

        Map<String, String> roles = new LinkedHashMap<>();

        WebElement usernameField;
        started = System.nanoTime();
        try {
            usernameField = resolve(config.optionalUsernameSelector().orElse(null),
                LoginElementDetector.Role.USERNAME, roles);
            observer.resolvedControl(LoginElementDetector.Role.USERNAME,
                roles.get(LoginElementDetector.Role.USERNAME.name()), elapsedMs(started));
        } catch (ManualSelectorException failure) {
            return observed(observer, LoginElementDetector.Role.USERNAME,
                failure.toResult(roles), elapsedMs(started));
        } catch (UnresolvedRoleException failure) {
            return observed(observer, LoginElementDetector.Role.USERNAME,
                failure.toResult(roles, Outcome.USERNAME_NOT_FOUND), elapsedMs(started));
        }

        WebElement passwordField;
        started = System.nanoTime();
        try {
            passwordField = resolve(config.optionalPasswordSelector().orElse(null),
                LoginElementDetector.Role.PASSWORD, roles);
            observer.resolvedControl(LoginElementDetector.Role.PASSWORD,
                roles.get(LoginElementDetector.Role.PASSWORD.name()), elapsedMs(started));
        } catch (ManualSelectorException failure) {
            return observed(observer, LoginElementDetector.Role.PASSWORD,
                failure.toResult(roles), elapsedMs(started));
        } catch (UnresolvedRoleException failure) {
            return observed(observer, LoginElementDetector.Role.PASSWORD,
                failure.toResult(roles, Outcome.PASSWORD_NOT_FOUND), elapsedMs(started));
        }

        WebElement submitControl;
        started = System.nanoTime();
        try {
            submitControl = resolve(config.optionalSubmitSelector().orElse(null),
                LoginElementDetector.Role.SUBMIT, roles);
            observer.resolvedControl(LoginElementDetector.Role.SUBMIT,
                roles.get(LoginElementDetector.Role.SUBMIT.name()), elapsedMs(started));
        } catch (ManualSelectorException failure) {
            return observed(observer, LoginElementDetector.Role.SUBMIT,
                failure.toResult(roles), elapsedMs(started));
        } catch (UnresolvedRoleException failure) {
            return observed(observer, LoginElementDetector.Role.SUBMIT,
                failure.toResult(roles, Outcome.SUBMIT_NOT_FOUND), elapsedMs(started));
        }

        // Every role is resolved: only now is anything typed or clicked.
        started = System.nanoTime();
        try {
            usernameField.clear();
            usernameField.sendKeys(username);
        } catch (RuntimeException failure) {
            observer.interactionFailed(Interaction.TYPE_USERNAME, failure, elapsedMs(started));
            throw failure;
        }
        observer.typedUsername(elapsedMs(started));

        started = System.nanoTime();
        try {
            passwordField.clear();
            passwordField.sendKeys(password);
        } catch (RuntimeException failure) {
            observer.interactionFailed(Interaction.TYPE_PASSWORD, failure, elapsedMs(started));
            throw failure;
        }
        observer.typedPassword(elapsedMs(started));

        // Now, and only now, the control has to be enabled. Both fields hold their values, so a
        // form that gates its button on them has had everything it was waiting for. A button that
        // is still disabled after that is not a selector problem and not something to click
        // anyway: it is reported as its own outcome, with the credentials already typed but
        // nothing submitted.
        if (!awaitEnabled(submitControl)) {
            return new Result(
                Outcome.SUBMIT_NEVER_ENABLED,
                "The sign-in button stayed disabled after the credentials were entered, so it was "
                    + "never clicked.",
                "Check whether the sign-in form needs anything else before it will enable its "
                    + "button, such as a second field or a tick box.",
                roles
            );
        }

        // elementToBeClickable proved only "displayed and enabled", which a covered control
        // satisfies: a run centred the control, was told it had become clickable in 29 ms, and
        // was then intercepted at (669, 767). The condition now has to prove the control actually
        // owns the point a pointer would land on.
        started = System.nanoTime();
        try {
            WaitUtils.wait(driver, timeout).until(ignored -> safeClick.ownsItsClickPoint(submitControl));
        } catch (RuntimeException failure) {
            observer.submitNeverBecameClickable(failure, elapsedMs(started));
            throw failure;
        }
        observer.awaitedSubmitClickable(elapsedMs(started));

        // One pointer click, and deliberately not WebElement.click(): the W3C Element Click
        // algorithm scrolls the element into view itself before dispatching, which is what moved
        // the prepared control back down to the viewport edge. Actions moves the pointer to the
        // element's in-view centre and only scrolls when the element is not already visible, so
        // the position just verified is the position clicked.
        started = System.nanoTime();
        try {
            safeClick.clickOnce(submitControl);
        } catch (RuntimeException failure) {
            observer.interactionFailed(Interaction.CLICK_SUBMIT, failure, elapsedMs(started));
            throw failure;
        }
        observer.clickedSubmit(elapsedMs(started));

        return new Result(Outcome.SUBMITTED, null, null, roles);
    }

    /**
     * Waits for a resolved control to become enabled.
     *
     * <p>Separate from the ownership wait below, and deliberately so: "still disabled" and
     * "covered by something" are different answers for the operator, and only one of them means
     * the page is waiting on input it has already been given. Neither one clicks.
     *
     * @return true when the control was enabled inside the existing timeout
     */
    private boolean awaitEnabled(WebElement element) {
        try {
            WaitUtils.wait(driver, timeout).until(ignored -> {
                try { return element.isEnabled(); }
                catch (RuntimeException stillSettling) { return false; }
            });
            return true;
        } catch (RuntimeException neverEnabled) {
            return false;
        }
    }

    /**
     * Waits for the control to own the point a pointer would land on, exactly as the submit does.
     *
     * <p>Delegates to the one shared {@link SafePointerClick#ownsItsClickPoint} rather than
     * re-implementing the check, so there is a single definition of "safe to click" in the
     * project. This wait never clicks: it only decides whether the caller may.
     *
     * @return true when the control became the owner inside the existing timeout
     */
    private boolean awaitClickPointOwnership(WebElement element) {
        try {
            WaitUtils.wait(driver, timeout).until(ignored -> safeClick.ownsItsClickPoint(element));
            return true;
        } catch (RuntimeException neverBecameClickable) {
            // Not an exception the caller has to handle: a control that stayed covered is a
            // configuration answer, and the point of returning it is that nothing was clicked.
            return false;
        }
    }

    /** Why a configured sign-in trigger could not be used. Never a reason to click anyway. */
    private enum TriggerProblem { MALFORMED, NOT_READY, COVERED }

    /**
     * The configured trigger is unusable, so nothing is typed and nothing is submitted.
     *
     * <p>Reported as a manual-selector problem because that is exactly what it is: a selector the
     * caller supplied that this page cannot act on. Nothing falls back to detection — a guessed
     * control on an unknown page is how an analyzer clicks something nobody asked it to.
     */
    private Result triggerProblem(String selector, TriggerProblem problem) {
        String correctTheSelector =
            "Correct the trigger selector in Advanced settings, or clear it if the sign-in form "
                + "is already on the login page.";
        return switch (problem) {
            case MALFORMED -> new Result(Outcome.INVALID_MANUAL_SELECTOR,
                "The sign-in trigger selector you provided is not a valid CSS selector: " + selector,
                correctTheSelector, Map.of());
            // Says what was observed and stops. The page may still have been rendering, so this
            // must not be reported as a selector the operator got wrong -- a run that blamed a
            // correct selector sent someone to change a setting that was already right.
            case NOT_READY -> new Result(Outcome.TRIGGER_NOT_READY,
                "The page or the configured sign-in trigger did not become ready within "
                    + triggerReadiness.toSeconds() + " seconds, so nothing was typed and nothing "
                    + "was clicked. The control looked for was " + selector,
                "Retry when the site is responding normally. If it keeps timing out, watch whether "
                    + "the login page finishes rendering and whether that control appears on it.",
                Map.of());
            // Deliberately does not name what covers it: knowing something is in the way is
            // enough to stop, and identifying it would only create something to leak.
            case COVERED -> new Result(Outcome.INVALID_MANUAL_SELECTOR,
                "The sign-in trigger you provided was covered by something else, so it was never "
                    + "clicked: " + selector,
                correctTheSelector, Map.of());
        };
    }

    /** Reports an unresolved role and returns the unchanged Result the caller already expects. */
    private static Result observed(
        Observer observer, LoginElementDetector.Role role, Result result, long durationMs
    ) {
        observer.controlNotResolved(role, result.outcome(), result.message(), durationMs);
        return result;
    }

    /**
     * Whether the configured success text was already present before the sign-in attempt.
     *
     * @return {@code null} when no success text is configured or the login page never opened
     */
    public Boolean successTextPresentBeforeLogin() {
        return successTextPresentBeforeLogin;
    }

    /** Reads the page only to answer yes or no; nothing about the page is retained. */
    private boolean containsSuccessText(String expected) {
        try {
            String source = driver.getPageSource();
            return source != null && source.contains(expected);
        } catch (RuntimeException unreadable) {
            // Unreadable now means "not observed present", which keeps the condition usable.
            return false;
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * Resolves one control, waiting for it to appear rather than asking once.
     *
     * <p><strong>Why the wait exists.</strong> {@code driver.get} returns when the document reaches
     * readyState "complete", which on a client-rendered application is the empty shell — the
     * sign-in form is added afterwards, by the framework. A run against OrangeHRM opened the page
     * in 2081 ms and then declared a perfectly correct {@code input[name='username']} missing in
     * 10 ms, because the lookup was a single {@code findElements} against a body that was still
     * blank. The same selector matched the visible field five seconds later. What was wrong was
     * never the selector: it was asking once, at the one moment the answer was guaranteed to be no.
     *
     * <p>So each role is now polled until its control is present, visible and enabled, bounded by
     * the configured timeout — the same one the submit-clickability wait below already uses. The
     * condition is evaluated immediately and only then slept on, so a control that is already
     * there costs one query and no delay.
     *
     * <p><strong>What still answers at once, and why.</strong> Two cases cannot improve with time
     * and are reported immediately rather than made to wait out the timeout:
     *
     * <ul>
     *   <li>a malformed manual selector — no amount of rendering makes invalid CSS valid;</li>
     *   <li>an ambiguous detection — the page has rendered its controls and there are several.
     *       Elements accumulate as a page renders rather than disappearing, so waiting can add
     *       candidates but cannot narrow them to one.</li>
     * </ul>
     *
     * <p><strong>What does not change.</strong> A supplied selector is still authoritative and
     * still never falls back to detection; the filter is still "displayed and enabled"; and every
     * failure still leaves through the same two exceptions carrying the same typed outcome and the
     * same customer-facing sentence. The report a timed-out control produces is the one it has
     * always produced — the difference is that it now means the control genuinely never arrived.
     */
    private WebElement resolve(
        String manualSelector,
        LoginElementDetector.Role role,
        Map<String, String> roles
    ) {
        if (manualSelector != null) {
            // A supplied selector is authoritative: it must never fall back to detection.
            By locator;
            try {
                locator = SelectorUtils.css(manualSelector);
                // One probe, inside the same guard as before, so a selector the browser rejects is
                // still reported as malformed rather than waited on and then called missing.
                driver.findElements(locator);
            } catch (RuntimeException invalid) {
                throw new ManualSelectorException(role, manualSelector, true);
            }
            // Only the sign-in control may still be disabled here; see firstUsable.
            WebElement usable = awaitFirstUsable(locator, role != LoginElementDetector.Role.SUBMIT);
            if (usable == null) throw new ManualSelectorException(role, manualSelector, false);
            roles.put(role.name(), Source.MANUAL.name() + ":" + manualSelector);
            return usable;
        }

        LoginElementDetector.Resolution resolution = awaitDetected(role);
        if (!resolution.resolved()) throw new UnresolvedRoleException(resolution);
        roles.put(role.name(), Source.DETECTED.name());
        return resolution.element();
    }

    /**
     * The first displayed, enabled match, once one exists.
     *
     * @return the control, or null when none became usable within the configured timeout
     */
    private WebElement awaitFirstUsable(By locator, boolean enabledRequired) {
        return awaitFirstUsable(locator, enabledRequired, timeout);
    }

    /** The same wait, against a stated budget. Returns as soon as a usable match exists. */
    private WebElement awaitFirstUsable(By locator, boolean enabledRequired, Duration budget) {
        try {
            return WaitUtils.wait(driver, budget)
                .until(active -> firstUsable(active.findElements(locator), enabledRequired));
        } catch (TimeoutException neverAppeared) {
            // Not something to report on its own: the caller turns this into the same typed result
            // it has always produced for a selector that matched nothing usable.
            return null;
        }
    }

    /**
     * Detection, repeated until it resolves or gives an answer more time cannot change.
     *
     * @return the last resolution observed, which on timeout is a truthful "not found"
     */
    private LoginElementDetector.Resolution awaitDetected(LoginElementDetector.Role role) {
        LoginElementDetector.Resolution first = detect(role);
        if (settled(first)) {
            return first;
        }
        AtomicReference<LoginElementDetector.Resolution> last = new AtomicReference<>(first);
        try {
            WaitUtils.wait(driver, timeout).until(active -> {
                LoginElementDetector.Resolution current = detect(role);
                last.set(current);
                return settled(current);
            });
        } catch (TimeoutException neverResolved) {
            // The reference holds the final look at the page, and that is what gets reported.
        }
        return last.get();
    }

    /** Whether this answer is final: resolved, or ambiguous, which waiting cannot improve. */
    private static boolean settled(LoginElementDetector.Resolution resolution) {
        return resolution.resolved()
            || resolution.failure() == LoginElementDetector.Failure.AMBIGUOUS;
    }

    private LoginElementDetector.Resolution detect(LoginElementDetector.Role role) {
        return switch (role) {
            case USERNAME -> detector.detectUsername();
            case PASSWORD -> detector.detectPassword();
            case SUBMIT -> detector.detectSubmit();
        };
    }

    /**
     * The first match this role can actually use.
     *
     * <p>A field must be displayed <em>and</em> enabled: typing into a disabled input silently does
     * nothing, so a half-rendered form is worth waiting out.
     *
     * <p>A sign-in control only has to be displayed. Requiring it to be enabled before anything is
     * typed is a contradiction on every form that keeps its button disabled until both fields are
     * filled -- the button is waiting for the credentials and the resolver is waiting for the
     * button. That deadlock is what made a correct selector wait out the timeout and then get
     * reported as matching nothing. Enabledness is still required, only later: {@link #awaitEnabled}
     * proves it after the credentials are typed, and nothing is clicked until it does.
     */
    private static WebElement firstUsable(List<WebElement> matches, boolean enabledRequired) {
        return matches.stream()
            .filter(element -> {
                try { return element.isDisplayed() && (!enabledRequired || element.isEnabled()); }
                catch (RuntimeException detached) { return false; }
            })
            .findFirst()
            .orElse(null);
    }

    /** A selector the user supplied that is invalid or matches nothing usable. */
    private static final class ManualSelectorException extends RuntimeException {
        private final LoginElementDetector.Role role;
        private final String selector;
        private final boolean malformed;

        private ManualSelectorException(LoginElementDetector.Role role, String selector, boolean malformed) {
            super("manual selector problem");
            this.role = role;
            this.selector = selector;
            this.malformed = malformed;
        }

        private Result toResult(Map<String, String> roles) {
            return new Result(
                Outcome.INVALID_MANUAL_SELECTOR,
                malformed
                    ? "The " + role.label() + " selector you provided is not a valid CSS selector: " + selector
                    : "The " + role.label() + " selector you provided did not match a visible, enabled element: " + selector,
                "Correct the selector in Advanced settings, or clear it so QANIZE can detect the field.",
                roles
            );
        }
    }

    /** Detection could not resolve a role safely. */
    private static final class UnresolvedRoleException extends RuntimeException {
        private final LoginElementDetector.Resolution resolution;

        private UnresolvedRoleException(LoginElementDetector.Resolution resolution) {
            super("unresolved login role");
            this.resolution = resolution;
        }

        private Result toResult(Map<String, String> roles, Outcome notFoundOutcome) {
            Outcome outcome = resolution.failure() == LoginElementDetector.Failure.AMBIGUOUS
                ? Outcome.AMBIGUOUS_ELEMENTS
                : notFoundOutcome;
            return new Result(outcome, resolution.explanation(), resolution.suggestedAction(), roles);
        }
    }
}
