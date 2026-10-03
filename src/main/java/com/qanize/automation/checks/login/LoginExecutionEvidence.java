package com.qanize.automation.checks.login;

import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;


/**
 * Records what the Login check actually did, as it does it.
 *
 * <p>Login is the only check that handles a secret, and the only one whose name invites the reader
 * to assume more than was proven. Two things therefore shape this class.
 *
 * <p><strong>A secret cannot reach it.</strong> It implements
 * {@link GenericLoginHelper.Observer}, whose typing callbacks take a duration and nothing else, so
 * there is no parameter through which a username, a password, a length or any derived form could
 * arrive. Nothing here accepts page source, cookies or storage either.
 *
 * <p><strong>Submitting a form is not signing in.</strong> When no success condition is configured
 * the check still passes — that behaviour is unchanged — but the evidence says plainly that
 * authentication was <em>not verified</em>. It never claims a sign-in succeeded, an account was
 * reached, or permissions were granted, because the check establishes none of those.
 */
public final class LoginExecutionEvidence implements GenericLoginHelper.Observer {

    /** The contract 1.0 id this check already allocates. It is reused, never replaced. */
    public static final String TEST_CASE_ID = "login-1";

    /** The wording required whenever nothing was configured to prove authentication. */
    public static final String NOT_VERIFIED =
        "Login form submission completed, but authentication success was not verified because no"
            + " success condition was configured.";

    static final int MAX_STRUCTURAL_TEXT = 160;

    private static final String CHECK_ID = "LOGIN";

    /**
     * Guarded: reporting must never decide, mask or replace a sign-in result.
     *
     * <p>Login records against one test case only, so the shared per-case counter is keyed on that
     * single id and numbers exactly as before.
     */
    private final CheckStepRecorder steps =
        CheckStepRecorder.guarded(CHECK_ID, "login-step-record-failed");

    private ExecutionStep append(
        Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        return steps.append(
            TEST_CASE_ID, phase, method, action, targetLabel,
            expected, actual, status, durationMs, errorCategory);
    }

    static String structural(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.replaceAll("[\\r\\n]+", " ").trim();
        return trimmed.length() <= MAX_STRUCTURAL_TEXT
            ? trimmed
            : trimmed.substring(0, MAX_STRUCTURAL_TEXT) + "…";
    }

    /** Sanitizes without being able to throw, so recording cannot mask the real failure. */
    private static String describe(Throwable failure) {
        try {
            String sanitized = SensitiveDataFilter.sanitizeMessage(String.valueOf(failure));
            return sanitized == null || sanitized.isBlank() ? "no detail available." : sanitized;
        } catch (RuntimeException unusable) {
            return "no detail available.";
        }
    }

    private static String label(LoginElementDetector.Role role) {
        return role == null ? "control" : role.label();
    }

    // --- observed inside the helper -------------------------------------------------------------

    @Override
    public void openedLoginPage(long durationMs) {
        append(
            Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Opened the configured login page.", null,
            "The login page loads so its sign-in controls can be used.",
            "The login page finished loading.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    @Override
    public void clickedLoginTrigger(String selector, long durationMs) {
        append(
            Phase.ACTION, ExecutionMethod.CLICK,
            "Opened the sign-in form through the configured control.", selector,
            "The sign-in form is on screen so its fields can be used.",
            "The configured control was clicked once and the sign-in form appeared.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    @Override
    public void loginPageOpenFailed(Throwable failure, long durationMs) {
        append(
            Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Tried to open the configured login page.", null,
            "The login page loads so its sign-in controls can be used.",
            "The browser could not open the login page: " + describe(failure)
                + " No credential was entered.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    @Override
    public void resolvedControl(LoginElementDetector.Role role, String resolution, long durationMs) {
        String how = structural(resolution);
        append(
            Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Resolved the " + label(role) + " control.", how,
            "A visible, enabled " + label(role) + " control is present on the login page.",
            "Found the " + label(role) + " control"
                + (how == null ? "." : " (" + how + ")."),
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    @Override
    public void controlNotResolved(
        LoginElementDetector.Role role, GenericLoginHelper.Outcome outcome,
        String explanation, long durationMs
    ) {
        // A selector the user supplied that is wrong is a configuration problem; a page that does
        // not present a usable control is a website result. Neither is an analyzer malfunction.
        boolean configuration = outcome == GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR;
        String detail = structural(explanation);
        append(
            Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for the " + label(role) + " control.", null,
            "A visible, enabled " + label(role) + " control is present on the login page.",
            (detail == null ? "The " + label(role) + " control could not be resolved." : detail)
                + " Nothing was typed and nothing was clicked.",
            ExecutionStep.Status.FAILED, durationMs,
            configuration ? ErrorCategory.CONFIGURATION_ERROR : ErrorCategory.WEBSITE_FINDING
        );
    }

    @Override
    public void typedUsername(long durationMs) {
        append(
            Phase.ACTION, ExecutionMethod.TYPE,
            "Cleared the username field and sent the configured username to it.", null,
            "The username field accepts the configured username without error.",
            "Sent the configured username to the username field. Its value and length were not"
                + " recorded.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    @Override
    public void typedPassword(long durationMs) {
        append(
            Phase.ACTION, ExecutionMethod.TYPE,
            "Cleared the password field and sent the configured password to it.", null,
            "The password field accepts the configured password without error.",
            "Sent the configured password to the password field. Its value and length were not"
                + " recorded.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The configured success text was checked before signing in, to see whether it can prove
     * anything at all.
     *
     * <p>A text already on the login page cannot distinguish a signed-in page from the one in
     * front of it. That is a problem with the configured condition, not with the website, so it is
     * a {@link ErrorCategory#CONFIGURATION_ERROR}. Only the answer is recorded — never the text.
     */
    @Override
    public void capturedSuccessTextBaseline(boolean alreadyPresent, long durationMs) {
        append(
            Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Checked whether the configured success text was already present before signing in.",
            null,
            "The configured success text is absent before signing in, so its later presence can"
                + " prove the sign-in.",
            alreadyPresent
                ? "The configured success text was already present before the sign-in attempt, so"
                  + " it cannot verify the sign-in. The text itself was not recorded."
                : "The configured success text was not present before the sign-in attempt, so its"
                  + " later presence would be meaningful. The text itself was not recorded.",
            alreadyPresent ? ExecutionStep.Status.FAILED : ExecutionStep.Status.PASSED,
            durationMs, alreadyPresent ? ErrorCategory.CONFIGURATION_ERROR : null
        );
    }

    /**
     * A real wait, added because two public login pages left the submit control on the bottom edge
     * of the viewport where another element owned the click point.
     *
     * <p>Centring and waiting are one operation with one measured interval; the native click that
     * follows has its own. Nothing here says the sign-in worked — only that the control was ready
     * to be clicked.
     */
    @Override
    public void awaitedSubmitClickable(long durationMs) {
        append(
            Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Centred the resolved sign-in control and waited until it owned its own click point.",
            null,
            "The resolved sign-in control is displayed, enabled, centred inside the viewport, and"
                + " is the element a pointer would reach at its centre.",
            "The control is displayed, enabled, its centre is inside the viewport, and the browser"
                + " reports that the control itself is what a pointer would reach there. It has not"
                + " been clicked yet.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    @Override
    public void submitNeverBecameClickable(Throwable failure, long durationMs) {
        append(
            Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Centred the resolved sign-in control and waited until it owned its own click point.",
            null,
            "The resolved sign-in control is displayed, enabled, centred inside the viewport, and"
                + " is the element a pointer would reach at its centre.",
            "The control never owned its own click point within the configured timeout: "
                + describe(failure)
                + " It was not clicked, and nothing about authentication was established."
                + " Whatever occupied the point was not inspected or recorded.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    @Override
    public void clickedSubmit(long durationMs) {
        append(
            Phase.ACTION, ExecutionMethod.CLICK,
            "Clicked the resolved sign-in control.", null,
            "The sign-in control is clickable.",
            "The control was clicked. Whether the credentials were accepted was not established by"
                + " this step.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    @Override
    public void interactionFailed(
        GenericLoginHelper.Interaction interaction, Throwable failure, long durationMs
    ) {
        boolean clicking = interaction == GenericLoginHelper.Interaction.CLICK_SUBMIT;
        String what = switch (interaction) {
            case TYPE_USERNAME -> "send the configured username to the username field";
            case TYPE_PASSWORD -> "send the configured password to the password field";
            case CLICK_SUBMIT -> "click the sign-in control";
        };
        append(
            Phase.ACTION, clicking ? ExecutionMethod.CLICK : ExecutionMethod.TYPE,
            "Tried to " + what + ".", null,
            "The sign-in flow completes without a browser error.",
            "The browser could not complete the operation: " + describe(failure)
                + " No credential value was recorded.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    // --- observed in the check, after the click --------------------------------------------------

    /**
     * The address after submitting.
     *
     * @param sanitizedFinalUrl already through {@link SensitiveDataFilter#sanitizeUrl}; the raw
     *                          address stays in the check for the existing comparison
     */
    public ExecutionStep readFinalAddress(
        String sanitizedFinalUrl, boolean submitted, long durationMs
    ) {
        String safe = structural(sanitizedFinalUrl);
        // The address is read after every outcome, including one where the click never completed,
        // so the wording may only claim submission when submission actually happened.
        String action = submitted
            ? "Read the browser address after submitting the sign-in form."
            : "Read the browser address after the sign-in attempt.";
        String expected = submitted
            ? "The browser reports the address reached after submitting."
            : "The browser reports its current address after the sign-in attempt.";
        String actual = safe == null
            ? "The browser reported no address."
            : "The browser is on " + safe + "."
              + (submitted ? "" : " The sign-in form was not submitted, so this address does not"
                  + " reflect a completed sign-in.");
        return append(
            Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            action, safe, expected, actual,
            safe == null ? ExecutionStep.Status.FAILED : ExecutionStep.Status.PASSED,
            durationMs, safe == null ? ErrorCategory.INFRASTRUCTURE_ERROR : null
        );
    }

    /**
     * The configured success text was searched for in the page.
     *
     * <p>Only whether it was found is recorded — never the configured text, never the page source,
     * never the content around a match.
     */
    public ExecutionStep checkedSuccessText(
        boolean presentBefore, boolean presentAfter, long durationMs
    ) {
        // Presence alone proves nothing; only a transition from absent to present does.
        boolean proves = !presentBefore && presentAfter;
        String actual;
        ErrorCategory category;
        if (presentBefore) {
            actual = "The configured success text was already present before the sign-in attempt,"
                + " so finding it afterwards proves nothing. The condition cannot verify this"
                + " sign-in. The text itself and the page content were not recorded.";
            category = ErrorCategory.CONFIGURATION_ERROR;
        } else if (presentAfter) {
            actual = "The configured success text was absent before the sign-in attempt and present"
                + " afterwards. The text itself and the page content were not recorded.";
            category = null;
        } else {
            actual = "The configured success text was absent before the sign-in attempt and is"
                + " still absent. The text itself and the page content were not recorded.";
            category = ErrorCategory.WEBSITE_FINDING;
        }
        return append(
            Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Searched the page for the configured success text.", null,
            // The text is searched for after every outcome, including one where the click never
            // completed, so this expectation must not assume a sign-in happened either.
            "The configured success text is absent before the sign-in attempt and present"
                + " afterwards.",
            actual,
            proves ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, category
        );
    }

    /**
     * The verdict, recorded before the check's assertions can end the test.
     *
     * <p>With no success condition configured the result stays exactly as it is today, and the
     * observation says so in full rather than implying a sign-in was proven.
     */
    public ExecutionStep assertedOutcome(
        boolean submitted, boolean operationFailed, GenericLoginHelper.Outcome outcome,
        boolean urlConditionConfigured, boolean urlConditionMet,
        boolean textConditionConfigured, boolean textConditionMet, boolean textConditionUnusable
    ) {
        boolean anyConditionConfigured = urlConditionConfigured || textConditionConfigured;
        boolean conditionsMet = (!urlConditionConfigured || urlConditionMet)
            && (!textConditionConfigured || textConditionMet);
        boolean ok = submitted && conditionsMet;

        String actual;
        if (operationFailed) {
            actual = "The sign-in flow could not be completed because the browser failed during it,"
                + " so nothing about authentication was established.";
        } else if (!submitted) {
            actual = "The sign-in form was not submitted, so nothing about authentication was"
                + " established.";
        } else if (!anyConditionConfigured) {
            actual = NOT_VERIFIED;
        } else {
            StringBuilder detail = new StringBuilder("The sign-in form was submitted.");
            if (urlConditionConfigured) {
                detail.append(urlConditionMet
                    ? " The address matched the configured success condition."
                    : " The address did not match the configured success condition.");
            }
            if (textConditionConfigured) {
                detail.append(textConditionUnusable
                    ? " The configured success text was already present before the attempt, so it"
                      + " could not verify the sign-in; this is a problem with the condition, not"
                      + " with the website."
                    : textConditionMet
                        ? " The configured success text appeared only after the attempt."
                        : " The configured success text did not appear.");
            }
            actual = detail.toString();
        }

        return append(
            Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked the configured sign-in success condition.", null,
            anyConditionConfigured
                ? "The configured sign-in success condition is met."
                : "The sign-in form is submitted without an error.",
            actual,
            ok ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, verdictCategory(ok, operationFailed, outcome, textConditionUnusable)
        );
    }

    /**
     * The verdict inherits the category of whatever actually caused it.
     *
     * <p>Previously every failed verdict was a {@code WEBSITE_FINDING}, so a browser that could not
     * complete a click was reported as an infrastructure failure at the click and as a website
     * defect one step later — the same event, two contradictory answers, and the more alarming one
     * last. A failure is only the website's when the flow ran to completion and the site then
     * failed a condition the user configured.
     */
    static ErrorCategory verdictCategory(
        boolean ok, boolean operationFailed, GenericLoginHelper.Outcome outcome,
        boolean textConditionUnusable
    ) {
        if (ok) return null;
        // Selenium could not complete navigation, discovery, typing or the click.
        if (operationFailed) return ErrorCategory.INFRASTRUCTURE_ERROR;
        // A selector the user supplied is wrong, or a success text that was already on the page:
        // both are the run configuration's problem, not the site's behaviour.
        if (outcome == GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR || textConditionUnusable) {
            return ErrorCategory.CONFIGURATION_ERROR;
        }
        // A control the page never presented, or a configured success condition the site did not
        // meet. Both are results about the website.
        return ErrorCategory.WEBSITE_FINDING;
    }

    /** How many steps have been recorded for the login test case. */
    public int recordedSteps() {
        return steps.recordedSteps(TEST_CASE_ID);
    }
}
