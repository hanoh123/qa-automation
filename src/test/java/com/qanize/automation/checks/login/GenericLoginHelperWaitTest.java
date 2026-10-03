package com.qanize.automation.checks.login;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controls that arrive after the document does.
 *
 * <p>{@code driver.get} returns at readyState "complete", which on a client-rendered application
 * is an empty shell. A run against OrangeHRM opened the login page in 2081 ms and then declared a
 * correct {@code input[name='username']} missing in 10 ms; five seconds later the same selector
 * matched the visible field. The selector was never the problem — the single lookup was, because
 * it happened at the one moment the answer was guaranteed to be no.
 *
 * <p>Every test here drives the real {@link GenericLoginHelper} against a page whose controls
 * appear after a stated number of queries. Nothing sleeps on purpose and no delay is configured:
 * appearance is expressed in queries, so "did it look again?" is the thing being measured rather
 * than wall-clock time. The immediacy tests read the same counter from the other direction — a
 * control already on the page must cost one query and no second look.
 */
@Tag("unit")
class GenericLoginHelperWaitTest {

    private static final String LOGIN_URL = "https://example.com/auth/login";
    private static final String USERNAME_SELECTOR = "input[name='username']";
    private static final String PASSWORD_SELECTOR = "input[name='password']";
    private static final String SUBMIT_SELECTOR = "button[type='submit']";
    private static final String TRIGGER_SELECTOR = ".open-sign-in";

    /** Long enough that a control appearing a few polls in is found; short enough to fail fast. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    // --- a manual selector that appears asynchronously -------------------------------------------

    @Test
    void aManualSelectorWhoseControlAppearsLaterIsFoundRatherThanCalledMissing() {
        // The OrangeHRM shape: the body is blank when the document completes, and the form is
        // rendered afterwards. Before the wait this was INVALID_MANUAL_SELECTOR in ten milliseconds.
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 3)
            .control(PASSWORD_SELECTOR, "input", 3)
            .control(SUBMIT_SELECTOR, "button", 3);

        GenericLoginHelper.Result result = signIn(page, manualSelectors());

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        assertEquals("MANUAL:" + USERNAME_SELECTOR, result.resolvedRoles().get("USERNAME"));
        assertTrue(page.queriesFor(USERNAME_SELECTOR) >= 3,
            "the page was asked again until the field existed: " + page.queriesFor(USERNAME_SELECTOR));
    }

    @Test
    void theSelectorItselfIsNeverWeakenedOrReplacedWhileWaiting() {
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 2)
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0);

        signIn(page, manualSelectors());

        // Exactly the configured selectors, and nothing else, were ever asked for. A wait that
        // broadened the query would find something — and it would be the wrong element.
        assertEquals(Set.of(USERNAME_SELECTOR, PASSWORD_SELECTOR, SUBMIT_SELECTOR),
            page.selectorsQueried(), "the wait re-asks the same question, it does not ask an easier one");
    }

    @Test
    void aDelayedPasswordFieldIsAwaitedToo() {
        // Each role gets its own wait. A username that arrives first must not make the password
        // field's own lateness fatal.
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0)
            .control(PASSWORD_SELECTOR, "input", 2)
            .control(SUBMIT_SELECTOR, "button", 0);

        GenericLoginHelper.Result result = signIn(page, manualSelectors());

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        assertEquals("MANUAL:" + PASSWORD_SELECTOR, result.resolvedRoles().get("PASSWORD"));
        assertTrue(page.queriesFor(PASSWORD_SELECTOR) >= 2, "the password field was awaited");
    }

    @Test
    void aDelayedSignInButtonIsAwaitedToo() {
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0)
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 2);

        GenericLoginHelper.Result result = signIn(page, manualSelectors());

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        assertEquals("MANUAL:" + SUBMIT_SELECTOR, result.resolvedRoles().get("SUBMIT"));
        assertTrue(page.queriesFor(SUBMIT_SELECTOR) >= 2, "the button was awaited");
    }

    @Test
    void aControlThatExistsButIsNotYetEnabledIsAlsoAwaited() {
        // Present is not the same as usable. A disabled or invisible control is exactly what a
        // half-rendered form looks like, and typing into one is worse than waiting for it.
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0).usableAfter(USERNAME_SELECTOR, 2)
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0);

        GenericLoginHelper.Result result = signIn(page, manualSelectors());

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        // The probe plus two looks that found it unusable plus the one that did not: a resolver
        // that stopped at "present" would have handed back a control the browser refuses to type
        // into, which is how a half-rendered form gets filled in.
        assertTrue(page.queriesFor(USERNAME_SELECTOR) >= 3,
            "presence alone ended the wait after " + page.queriesFor(USERNAME_SELECTOR) + " queries");
        assertEquals(2, page.typedInto.size(),
            "both fields were typed into, and only once the browser would accept the keys");
        assertEquals(1, page.clicks, "and the button was clicked exactly once");
    }

    // --- the timeout still produces the existing failure ------------------------------------------

    @Test
    void aControlThatNeverArrivesStillProducesTheExistingSetupFailure() {
        FakePage page = new FakePage()
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0);

        GenericLoginHelper.Result result =
            signIn(page, manualSelectors(), Duration.ofMillis(600));

        // The same typed outcome, the same sentence and the same suggested action as before: what
        // changed is that it now means the control genuinely never arrived.
        assertEquals(GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, result.outcome());
        assertTrue(result.message().contains("did not match a visible, enabled element"),
            result.message());
        assertTrue(result.message().contains(USERNAME_SELECTOR), result.message());
        assertTrue(result.suggestedAction().contains("Advanced settings"), result.suggestedAction());
        assertFalse(result.submitted());
        // Nothing was typed and nothing was clicked on a page whose form never appeared.
        assertEquals(0, page.clicks);
        assertEquals(List.of(), page.typedInto);
    }

    @Test
    void theWaitIsBoundedByTheConfiguredTimeout() {
        FakePage page = new FakePage();
        long startedAt = System.nanoTime();

        signIn(page, manualSelectors(), Duration.ofMillis(600));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        // Bounded, not indefinite. The ceiling is generous because a poll interval straddles the
        // deadline; what matters is that it ends on its own and near the configured bound.
        assertTrue(elapsedMs < 5_000,
            "the wait must end at the configured timeout, but took " + elapsedMs + "ms");
    }

    @Test
    void aMalformedSelectorIsStillRejectedImmediatelyRatherThanWaitedOn() {
        // No amount of rendering makes invalid CSS valid, so this must not cost the timeout.
        FakePage page = new FakePage().rejectingSelector("input[name=");
        WebsiteLoginConfig config = config("input[name=", PASSWORD_SELECTOR, SUBMIT_SELECTOR);
        long startedAt = System.nanoTime();

        GenericLoginHelper.Result result =
            new GenericLoginHelper(page, Duration.ofSeconds(30)).login(config, "u", "p");

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertEquals(GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, result.outcome());
        assertTrue(result.message().contains("not a valid CSS selector"), result.message());
        assertTrue(elapsedMs < 2_000, "a malformed selector waited " + elapsedMs + "ms");
    }

    // --- automatic detection ---------------------------------------------------------------------

    @Test
    void automaticDetectionStillResolvesAPageThatIsAlreadyRendered() {
        FakePage page = detectablePage(0);

        GenericLoginHelper.Result result = signIn(page, config(null, null, null));

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        assertEquals("DETECTED", result.resolvedRoles().get("USERNAME"));
        assertEquals("DETECTED", result.resolvedRoles().get("PASSWORD"));
        assertEquals("DETECTED", result.resolvedRoles().get("SUBMIT"));
    }

    @Test
    void automaticDetectionAlsoWaitsForAFormThatArrivesLate() {
        FakePage page = detectablePage(2);

        GenericLoginHelper.Result result = signIn(page, config(null, null, null));

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        assertEquals("DETECTED", result.resolvedRoles().get("USERNAME"));
    }

    @Test
    void detectionThatNeverFindsAControlStillReportsTheExistingFailure() {
        FakePage page = new FakePage();

        GenericLoginHelper.Result result =
            signIn(page, config(null, null, null), Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.USERNAME_NOT_FOUND, result.outcome());
        assertTrue(result.message().contains("could not find the username or phone field"),
            result.message());
        assertTrue(result.suggestedAction().contains("Advanced settings"), result.suggestedAction());
    }

    @Test
    void anAmbiguousPageIsAnsweredAtOnceRatherThanWaitedOut() {
        // Two equally plausible identifier fields. Elements accumulate as a page renders rather
        // than disappearing, so more time can add candidates but cannot narrow them to one —
        // waiting would spend the whole timeout to print the same sentence.
        FakePage page = new FakePage()
            .control("input[type='text']", "input", 0, "alpha")
            .control("input[type='text']#two", "input", 0, "beta")
            .control("input[type='password']", "input", 0)
            .control("button", "button", 0);
        long startedAt = System.nanoTime();

        GenericLoginHelper.Result result =
            signIn(page, config(null, null, null), Duration.ofSeconds(30));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertEquals(GenericLoginHelper.Outcome.AMBIGUOUS_ELEMENTS, result.outcome());
        assertTrue(elapsedMs < 2_000, "an ambiguous page waited " + elapsedMs + "ms");
    }

    // --- an already-rendered page pays nothing ----------------------------------------------------

    @Test
    void controlsThatAreAlreadyThereCostOneQueryEachAndNoDelay() {
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0)
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0);
        long startedAt = System.nanoTime();

        GenericLoginHelper.Result result = signIn(page, manualSelectors(), Duration.ofSeconds(30));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        // At most a validity probe and one resolving look per role. A wait that slept before
        // believing the page would show up here as a further query, and as most of a second on
        // the clock. The bound is "no poll", not an exact count, so removing the probe later
        // strengthens this rather than breaking it.
        for (String selector : List.of(USERNAME_SELECTOR, PASSWORD_SELECTOR, SUBMIT_SELECTOR)) {
            assertTrue(page.queriesFor(selector) <= 2,
                selector + " was asked " + page.queriesFor(selector)
                    + " times, so the page was polled rather than believed");
        }
        assertTrue(elapsedMs < 1_000, "an already-rendered page cost " + elapsedMs + "ms");
    }

    @Test
    void detectionOnAnAlreadyRenderedPageAlsoCostsNoDelay() {
        FakePage page = detectablePage(0);
        long startedAt = System.nanoTime();

        signIn(page, config(null, null, null), Duration.ofSeconds(30));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertTrue(elapsedMs < 1_000, "detection on a rendered page cost " + elapsedMs + "ms");
    }

    // --- nothing about a credential is involved ---------------------------------------------------

    @Test
    void nothingAboutACredentialReachesAnyResultOfAWaitThatTimedOut() {
        FakePage page = new FakePage();

        GenericLoginHelper.Result result = new GenericLoginHelper(page, Duration.ofMillis(600))
            .login(config(USERNAME_SELECTOR, PASSWORD_SELECTOR, SUBMIT_SELECTOR),
                "some-user@example.com", "s3cr3t-password");

        String rendered = result.outcome() + " " + result.message() + " "
            + result.suggestedAction() + " " + result.resolvedRoles();
        assertFalse(rendered.contains("some-user@example.com"), rendered);
        assertFalse(rendered.contains("s3cr3t-password"), rendered);
        // Not a length either: "15 characters" identifies a password almost as well as the value.
        assertFalse(rendered.contains("15"), rendered);
        assertFalse(rendered.contains("19"), rendered);
    }

    // --- the optional control that reveals the form ----------------------------------------------

    @Test
    void theConfiguredTriggerIsClickedOnceBeforeAnyFieldIsLookedFor() {
        // The sign-in form exists only after the trigger is clicked: every field answers nothing
        // until the page has been queried once more than the trigger needs. A check that looked
        // for the fields first would find none and never get here.
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 0, "Sign in")
            .control(USERNAME_SELECTOR, "input", 1, "username")
            .control(PASSWORD_SELECTOR, "input", 1)
            .control(SUBMIT_SELECTOR, "button", 1, "Sign in");

        GenericLoginHelper.Result result = signIn(page, withTrigger(TRIGGER_SELECTOR, true));

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome());
        // Two clicks in the whole sign-in and no more: the trigger, then the submit.
        assertEquals(2, page.clicks, "the trigger is clicked once, and the submit once");
    }

    @Test
    void aBlankTriggerLeavesTheSignInExactlyAsItWas() {
        FakePage withoutTrigger = detectablePage(0);
        FakePage withBlankTrigger = detectablePage(0);

        GenericLoginHelper.Result legacy = signIn(withoutTrigger, config(null, null, null));
        GenericLoginHelper.Result blank = signIn(withBlankTrigger, withTrigger("   "));

        assertEquals(legacy.outcome(), blank.outcome());
        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, blank.outcome());
        // One click either way: a blank trigger adds no interaction at all.
        assertEquals(withoutTrigger.clicks, withBlankTrigger.clicks);
        assertEquals(1, withBlankTrigger.clicks);
    }

    @Test
    void aTriggerThatMatchesNothingStopsBeforeAnythingIsTyped() {
        FakePage page = detectablePage(0);

        GenericLoginHelper.Result result = signInWithReadiness(
            page, withTrigger(".never-on-this-page"), Duration.ofMillis(600),
            Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.TRIGGER_NOT_READY, result.outcome());
        assertEquals(0, page.clicks, "nothing is clicked when the configured control is not there");
        assertTrue(result.message().contains("did not become ready"), result.message());
    }

    @Test
    void aTriggerThatArrivesAfterTheAppBootsIsWaitedForRatherThanCalledWrong() {
        // The single-page-application shape. driver.get returns on an empty shell, the header renders some
        // seconds later, and the run used to declare a verified-correct selector missing in the
        // gap. The selector was never the problem; the ten-second ceiling was.
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 6, "Sign in")
            .control(USERNAME_SELECTOR, "input", 6, "username")
            .control(PASSWORD_SELECTOR, "input", 6)
            .control(SUBMIT_SELECTOR, "button", 6, "Sign in");

        GenericLoginHelper.Result result = signIn(page, withTrigger(TRIGGER_SELECTOR, true));

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome());
        assertTrue(page.queriesFor(TRIGGER_SELECTOR) >= 6,
            "the page was asked again until the trigger existed: "
                + page.queriesFor(TRIGGER_SELECTOR));
        assertEquals(2, page.clicks, "one click opens the form, one submits it");
    }

    @Test
    void theTriggerIsGivenTheReadinessBudgetRatherThanTheElementTimeout() {
        // The distinction, made where it is measurable. The trigger appears well past the point
        // the ordinary element timeout would have given up, and comfortably inside the readiness
        // budget. Put this control back on the shorter of the two and this run fails.
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 6, "Sign in")
            .control(USERNAME_SELECTOR, "input", 0, "username")
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0, "Sign in");

        GenericLoginHelper.Result result = signInWithReadiness(
            page, withTrigger(TRIGGER_SELECTOR, true),
            Duration.ofMillis(600), Duration.ofSeconds(8));

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(),
            "the trigger arrived inside its own budget, which is the one it must be given");
        assertTrue(page.queriesFor(TRIGGER_SELECTOR) >= 6,
            "it kept looking past the element timeout: " + page.queriesFor(TRIGGER_SELECTOR));
        assertEquals(2, page.clicks);
    }

    @Test
    void aTriggerThatIsAlreadyThereCostsOneLookAndNoWaiting() {
        // The budget is a ceiling, not a price. A site that has already rendered pays nothing for
        // the longer one, which is what makes a thirty-second ceiling safe to have at all.
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 0, "Sign in")
            .control(USERNAME_SELECTOR, "input", 0, "username")
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0, "Sign in");

        long startedAt = System.nanoTime();
        GenericLoginHelper.Result result = signIn(page, withTrigger(TRIGGER_SELECTOR, true));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome());
        assertTrue(page.queriesFor(TRIGGER_SELECTOR) <= 2,
            "a trigger already on the page was asked for "
                + page.queriesFor(TRIGGER_SELECTOR) + " times");
        assertTrue(elapsedMs < 1_000, "a ready page waited " + elapsedMs + "ms");
    }

    @Test
    void theProductionReadinessBudgetIsThirtySecondsAndLongerThanTheElementTimeout() {
        // The number itself, pinned where a reader can find it. The sign-in trigger is the one
        // control looked for on a page that may still be booting, so it gets its own ceiling.
        assertEquals(Duration.ofSeconds(30), GenericLoginHelper.TRIGGER_READINESS_TIMEOUT);
        assertTrue(GenericLoginHelper.TRIGGER_READINESS_TIMEOUT.compareTo(TIMEOUT) > 0,
            "the readiness budget must exceed the ordinary element timeout");
    }

    @Test
    void aTriggerThatNeverArrivesBlamesTheReadinessRatherThanTheSelector() {
        // The wording is the fix. Reporting a correct selector as wrong sent someone to change a
        // setting that was already right, and cost a diagnostic run to discover.
        FakePage page = detectablePage(0);

        GenericLoginHelper.Result result = signInWithReadiness(
            page, withTrigger(TRIGGER_SELECTOR, true), Duration.ofMillis(600),
            Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.TRIGGER_NOT_READY, result.outcome());
        assertNotEquals(GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, result.outcome(),
            "a page that never rendered says nothing about the selector it was given");

        String reported = result.message() + " " + result.suggestedAction();
        assertTrue(result.message().contains("did not become ready"), result.message());
        assertTrue(result.message().contains("nothing was typed"), result.message());
        assertFalse(reported.contains("did not match"), reported);
        assertFalse(reported.contains("not a valid CSS selector"), reported);
        assertFalse(reported.contains("Correct the trigger selector"),
            "the suggested action must not send the operator to change a correct setting: "
                + reported);
        assertEquals(0, page.clicks, "nothing is clicked and nothing is typed");
        assertEquals(List.of(), page.typedInto);
    }

    @Test
    void theStatedBudgetIsTheOneTheMessageReports() {
        FakePage page = detectablePage(0);

        GenericLoginHelper.Result result = signInWithReadiness(
            page, withTrigger(TRIGGER_SELECTOR, true), Duration.ofMillis(600),
            Duration.ofSeconds(2));

        assertTrue(result.message().contains("2 seconds"),
            "the message reports the budget that was actually applied: " + result.message());
    }

    @Test
    void aMalformedTriggerSelectorIsStillTheOperatorsToFix() {
        // The neutral wording is for a page that did not render. A selector the browser rejects
        // outright is a different answer and must keep pointing at the setting.
        FakePage page = detectablePage(0).rejectingSelector("::::not-css");

        GenericLoginHelper.Result result = signInWithReadiness(
            page, withTrigger("::::not-css"), Duration.ofMillis(600), Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, result.outcome());
        assertTrue(result.message().contains("not a valid CSS selector"), result.message());
        assertTrue(result.suggestedAction().contains("Correct the trigger selector"),
            result.suggestedAction());
    }

    @Test
    void aCoveredTriggerIsNeverClickedAndNoCredentialIsTyped() {
        // Displayed and enabled, and still not reachable: a banner owns the point a pointer would
        // land on. The run stops there — nothing is clicked, and the username and password never
        // reach the page.
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 0, "Sign in")
            .control(USERNAME_SELECTOR, "input", 0, "username")
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0, "Sign in")
            .coveredByAnOverlay(TRIGGER_SELECTOR);

        GenericLoginHelper.Result result = signIn(
            page, withTrigger(TRIGGER_SELECTOR, true), Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, result.outcome());
        assertEquals(0, page.clicks, "a covered control is never clicked");
        assertEquals(List.of(), page.typedInto, "no credential is typed once the run has stopped");
        assertTrue(result.message().contains("covered"), result.message());
        // And it does not say what covered it: that would be something to leak.
        assertFalse(result.message().contains("overlay"), result.message());
    }

    @Test
    void aTriggerThatBecomesTheClickPointOwnerIsClickedOnceBeforeFieldResolution() {
        // The control is reachable, so the run proceeds through the unchanged workflow: one click
        // on the trigger, the fields resolved afterwards, one click on the submit.
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 0, "Sign in")
            .control(USERNAME_SELECTOR, "input", 1, "username")
            .control(PASSWORD_SELECTOR, "input", 1)
            .control(SUBMIT_SELECTOR, "button", 1, "Sign in");

        GenericLoginHelper.Result result = signIn(page, withTrigger(TRIGGER_SELECTOR, true));

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome());
        assertEquals(2, page.clicks, "one click opens the form, one submits it");
        assertEquals(2, page.typedInto.size(),
            "the fields were resolved after the trigger and both were typed: " + page.typedInto);
    }

    @Test
    void aSubmitThatIsDisabledUntilTheFieldsAreFilledIsStillUsed() {
        // The live failure: a correct selector matched a button the form keeps disabled until both
        // fields hold a value. Requiring "enabled" before typing made that a deadlock -- the button
        // waiting for the credentials, the resolver waiting for the button -- and the run reported
        // the selector as matching nothing after waiting out the whole timeout.
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0)
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0)
            .enabledOnceBothFieldsAreFilled(SUBMIT_SELECTOR);

        GenericLoginHelper.Result result = signIn(page, manualSelectors());

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome(), result.message());
        assertEquals("MANUAL:" + SUBMIT_SELECTOR, result.resolvedRoles().get("SUBMIT"));
        // Both credentials went in, and exactly one click followed.
        assertEquals(2, page.typedInto.size(), "both fields were filled: " + page.typedInto);
        assertEquals(1, page.clicks, "the button was clicked once, after it enabled");
    }

    @Test
    void aMissingSubmitStillMeansNothingIsTyped() {
        // Relaxing the resolver to "displayed" must not relax it to "absent". A button that is not
        // on the page at all is still a setup failure, and the credentials stay unused.
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0)
            .control(PASSWORD_SELECTOR, "input", 0);

        GenericLoginHelper.Result result =
            signIn(page, manualSelectors(), Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, result.outcome());
        assertTrue(result.message().contains(SUBMIT_SELECTOR), result.message());
        assertFalse(result.submitted());
        assertEquals(List.of(), page.typedInto, "no credential is typed without a sign-in control");
        assertEquals(0, page.clicks);
    }

    @Test
    void aSubmitThatNeverEnablesIsReportedRatherThanClicked() {
        // The other half of the relaxation: the control is on screen, so the selector is right, but
        // it stays greyed out after both fields are filled. That is its own answer -- not a
        // selector problem -- and it is never clicked.
        FakePage page = new FakePage()
            .control(USERNAME_SELECTOR, "input", 0)
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0)
            .permanentlyDisabled(SUBMIT_SELECTOR);

        GenericLoginHelper.Result result =
            signIn(page, manualSelectors(), Duration.ofMillis(600));

        assertEquals(GenericLoginHelper.Outcome.SUBMIT_NEVER_ENABLED, result.outcome());
        assertFalse(result.submitted());
        assertEquals(0, page.clicks, "a disabled button is never clicked");
        // The credentials were typed -- that is what the form was waiting for -- but the message
        // reports the button, and repeats nothing that was typed.
        assertEquals(2, page.typedInto.size());
        for (String text : List.of(result.message(), result.suggestedAction())) {
            assertFalse(text.contains("user"), text);
            assertFalse(text.contains("password"), text);
            assertFalse(text.contains(SUBMIT_SELECTOR), text);
        }
    }

    @Test
    void theSubmitControlKeepsItsOwnOwnershipWaitUnchanged() {
        // The submit has always refused to click a covered control, and still does: the trigger
        // work added a second caller of the shared check, not a different rule for the submit.
        FakePage page = detectablePage(0).coveredByAnOverlay("button");

        assertThrows(RuntimeException.class,
            () -> signIn(page, config(null, null, null), Duration.ofMillis(600)));

        assertEquals(0, page.clicks, "a covered submit is never clicked");
    }

    // --- what a caller records, and what it must not ----------------------------------------------

    @Test
    void aSuccessfulSubmitRecordsExactlyOneClick() {
        FakePage page = manualPage();

        GenericLoginHelper.Result result = signIn(page, manualSelectors());

        assertEquals(GenericLoginHelper.Outcome.SUBMITTED, result.outcome());
        assertEquals(1, page.clicks, "one click command, delivered once");
    }

    @Test
    void aSubmitTheBrowserRefusedIsNeverRecordedAsAClick() {
        // The rule the diagnostic run exists to protect: evidence may not say a control was
        // clicked unless the click command returned normally. A refusal travels out; it does not
        // become a quiet success.
        FakePage page = manualPage();
        page.refuseClickOn(SUBMIT_SELECTOR,
            new org.openqa.selenium.ElementClickInterceptedException("covered"));
        RecordingObserver observer = new RecordingObserver();

        assertThrows(org.openqa.selenium.ElementClickInterceptedException.class,
            () -> new GenericLoginHelper(page, TIMEOUT)
                .login(manualSelectors(), "user", "password", observer));

        assertEquals(1, page.clicks, "attempted once, never retried");
        assertFalse(observer.recorded.contains("clickedSubmit"),
            "no click may be claimed for a command that threw: " + observer.recorded);
        assertTrue(observer.recorded.contains("interactionFailed"), observer.recorded.toString());
    }

    @Test
    void aTriggerTheBrowserRefusedIsNeverRecordedAsAClickEither() {
        FakePage page = new FakePage()
            .control(TRIGGER_SELECTOR, "a", 0, "Sign in")
            .control(USERNAME_SELECTOR, "input", 0, "username")
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0, "Sign in");
        page.refuseClickOn(TRIGGER_SELECTOR,
            new org.openqa.selenium.StaleElementReferenceException("replaced"));
        RecordingObserver observer = new RecordingObserver();

        assertThrows(org.openqa.selenium.StaleElementReferenceException.class,
            () -> new GenericLoginHelper(page, TIMEOUT)
                .login(withTrigger(TRIGGER_SELECTOR, true), "user", "password", observer));

        assertEquals(1, page.clicks, "attempted once, never retried");
        assertFalse(observer.recorded.contains("clickedLoginTrigger"), observer.recorded.toString());
        assertEquals(List.of(), page.typedInto, "and nothing was typed after a refused trigger");
    }

    /** The three manually configured controls, all already on the page. */
    private static FakePage manualPage() {
        return new FakePage()
            .control(USERNAME_SELECTOR, "input", 0, "username")
            .control(PASSWORD_SELECTOR, "input", 0)
            .control(SUBMIT_SELECTOR, "button", 0, "Sign in");
    }

    /** Records which observer callbacks fired, and nothing about their arguments. */
    private static final class RecordingObserver implements GenericLoginHelper.Observer {

        private final List<String> recorded = new ArrayList<>();

        @Override public void openedLoginPage(long durationMs) { recorded.add("openedLoginPage"); }
        @Override public void clickedLoginTrigger(String selector, long durationMs) {
            recorded.add("clickedLoginTrigger");
        }
        @Override public void clickedSubmit(long durationMs) { recorded.add("clickedSubmit"); }
        @Override public void interactionFailed(
            GenericLoginHelper.Interaction interaction, Throwable failure, long durationMs
        ) {
            recorded.add("interactionFailed");
        }
    }

    // --- driving the helper -----------------------------------------------------------------------

    private static GenericLoginHelper.Result signIn(FakePage page, WebsiteLoginConfig config) {
        return signIn(page, config, TIMEOUT);
    }

    private static GenericLoginHelper.Result signIn(
        FakePage page, WebsiteLoginConfig config, Duration timeout
    ) {
        return new GenericLoginHelper(page, timeout).login(config, "user", "password");
    }

    /**
     * The helper with a shortened trigger-readiness budget.
     *
     * <p>Production waits thirty seconds for a sign-in trigger. A test that proved the timeout by
     * actually waiting thirty seconds would be thirty seconds of a fake page doing nothing, so the
     * budget is injected. What is being measured is still the behaviour, not the clock.
     */
    private static GenericLoginHelper.Result signInWithReadiness(
        FakePage page, WebsiteLoginConfig config, Duration timeout, Duration readiness
    ) {
        return new GenericLoginHelper(page, timeout, readiness).login(config, "user", "password");
    }

    private static WebsiteLoginConfig manualSelectors() {
        return config(USERNAME_SELECTOR, PASSWORD_SELECTOR, SUBMIT_SELECTOR);
    }

    private static WebsiteLoginConfig config(String username, String password, String submit) {
        return new WebsiteLoginConfig(
            true, URI.create(LOGIN_URL), "QANIZE_USER", "QANIZE_PASSWORD",
            username, password, submit, null, null);
    }

    /** The same configuration, plus the optional control that reveals the form. */
    private static WebsiteLoginConfig withTrigger(String triggerSelector) {
        return withTrigger(triggerSelector, false);
    }

    private static WebsiteLoginConfig withTrigger(String triggerSelector, boolean manualSelectors) {
        return new WebsiteLoginConfig(
            true, URI.create(LOGIN_URL), "QANIZE_USER", "QANIZE_PASSWORD",
            manualSelectors ? USERNAME_SELECTOR : null,
            manualSelectors ? PASSWORD_SELECTOR : null,
            manualSelectors ? SUBMIT_SELECTOR : null,
            null, null, triggerSelector);
    }

    /** A page detection can resolve: one hinted text field, one password, one submit button. */
    private static FakePage detectablePage(int appearsAfter) {
        return new FakePage()
            .control("input[type='text']", "input", appearsAfter, "username")
            .control("input[type='password']", "input", appearsAfter)
            .control("button", "button", appearsAfter, "Sign in");
    }

    // --- a page whose controls arrive on a schedule -------------------------------------------------

    /**
     * A browser that renders over time, counted in queries rather than milliseconds.
     *
     * <p>Every {@code findElements} is recorded, and a control answers only once the page has been
     * asked enough times. That makes "did the code look again?" directly observable, and makes the
     * immediacy tests exact: a control already present must be found on the resolving look, with
     * no second one.
     */
    private static final class FakePage implements WebDriver, Interactive, JavascriptExecutor {

        private final Map<String, FakeElement> controls = new LinkedHashMap<>();
        private final Map<String, Integer> queries = new LinkedHashMap<>();
        private final Set<String> rejected = new java.util.LinkedHashSet<>();
        private final Set<String> covered = new java.util.LinkedHashSet<>();
        private final List<String> typedInto = new ArrayList<>();
        private int clicks;
        /**
         * What the browser does when the pointer sequence reaches a named control.
         *
         * <p>Keyed by the configured selector, because a click arrives here as an encoded action
         * rather than as a call on the element: the project clicks through one {@code Actions}
         * chain, and this is where that chain lands.
         */
        private final Map<String, RuntimeException> refusals = new LinkedHashMap<>();

        /** The browser refuses this control's click, once, exactly as a real one would. */
        FakePage refuseClickOn(String selector, RuntimeException failure) {
            refusals.put(selector, failure);
            return this;
        }
        private String currentUrl = "about:blank";

        FakePage control(String selector, String tag, int appearsAfterQueries) {
            return control(selector, tag, appearsAfterQueries, "");
        }

        FakePage control(String selector, String tag, int appearsAfterQueries, String name) {
            controls.put(selector,
                new FakeElement(this, selector, tag, name, appearsAfterQueries, 0, () -> true));
            return this;
        }

        /**
         * Something else owns this control's click point, as a banner over it would.
         *
         * <p>The control stays displayed and enabled — which is the whole point: "visible" and
         * "reachable" are different questions, and only the hit-test answers the second.
         */
        FakePage coveredByAnOverlay(String selector) {
            covered.add(selector);
            return this;
        }

        /** Present from the start, but not displayed and enabled until this many queries in. */
        FakePage usableAfter(String selector, int queriesBeforeUsable) {
            FakeElement existing = controls.get(selector);
            controls.put(selector, new FakeElement(
                this, selector, existing.tag, existing.name, existing.appearsAfter,
                queriesBeforeUsable, existing.enabledGate));
            return this;
        }

        /**
         * Displayed from the start, and enabled only once both fields hold a value.
         *
         * <p>This is the real sign-in form, and the reason the old resolver deadlocked: the button
         * is displayed and not enabled at the same time, and the only thing that can change that is
         * the typing the resolver was refusing to do until the button was enabled.
         *
         * <p>A condition on the page rather than a countdown, deliberately. An "enabled after N
         * asks" fake is satisfied by the asking, so it would let the deadlock pass.
         */
        FakePage enabledOnceBothFieldsAreFilled(String selector) {
            return gateEnabledness(selector, () -> typedInto.size() >= 2);
        }

        /** Displayed, and never enabled, however long anything waits. */
        FakePage permanentlyDisabled(String selector) {
            return gateEnabledness(selector, () -> false);
        }

        private FakePage gateEnabledness(String selector, BooleanSupplier gate) {
            FakeElement existing = controls.get(selector);
            controls.put(selector, new FakeElement(
                this, selector, existing.tag, existing.name, existing.appearsAfter,
                existing.usableAfter, gate));
            return this;
        }

        /**
         * The two scripts {@code SafePointerClick} runs, and nothing else.
         *
         * <p>Centring returns nothing. The hit-test returns one boolean: false for a control this
         * page was told is covered, true otherwise — which is what every existing case relies on,
         * so adding the hit-test here changes no test that does not ask to be covered.
         */
        @Override
        public Object executeScript(String script, Object... args) {
            if (script.contains("scrollIntoView")) return null;
            Object target = args.length > 0 ? args[0] : null;
            if (target instanceof FakeElement element) {
                return !covered.contains(element.selector);
            }
            return Boolean.TRUE;
        }

        @Override
        public Object executeAsyncScript(String script, Object... args) {
            throw new UnsupportedOperationException("the sign-in runs no asynchronous script");
        }

        /** A selector the browser itself refuses, as it does for malformed CSS. */
        FakePage rejectingSelector(String selector) {
            rejected.add(selector);
            return this;
        }

        int queriesFor(String selector) {
            return queries.getOrDefault(selector, 0);
        }

        Set<String> selectorsQueried() {
            return Set.copyOf(queries.keySet());
        }

        @Override
        public List<WebElement> findElements(By by) {
            String selector = by.toString().replace("By.cssSelector: ", "");
            if (rejected.contains(selector)) {
                throw new IllegalArgumentException("invalid selector");
            }
            int count = queries.merge(selector, 1, Integer::sum);
            List<WebElement> matches = new ArrayList<>();
            for (Map.Entry<String, FakeElement> entry : controls.entrySet()) {
                FakeElement element = entry.getValue();
                if (matchesQuery(entry.getKey(), selector, element)
                    && count > element.appearsAfter) {
                    // Counted on the element itself, not across the page: a control's readiness
                    // must not be advanced by queries that were looking for something else.
                    element.observed++;
                    matches.add(element);
                }
            }
            return matches;
        }

        /**
         * Selector matching, kept deliberately literal.
         *
         * <p>An exact match is a control asked for by its own selector. Detection asks broader
         * questions ("every text input"), so a control also answers a detection selector that
         * names its tag or its type — enough for these tests and no more, because a fake that
         * tried to be a CSS engine would be the thing under test.
         */
        private boolean matchesQuery(String controlSelector, String query, FakeElement element) {
            if (controlSelector.equals(query)) return true;
            if (query.contains(controlSelector)) return true;
            if ("button".equals(element.tag) && query.contains("button")) return true;
            return "input".equals(element.tag)
                && controlSelector.startsWith("input")
                && query.contains(controlSelector.split("#")[0]);
        }

        @Override public void get(String url) { currentUrl = url; }
        @Override public String getCurrentUrl() { return currentUrl; }
        @Override public String getPageSource() { return "<html></html>"; }
        @Override public String getTitle() { return "Sign in"; }
        @Override public WebElement findElement(By by) {
            List<WebElement> matches = findElements(by);
            if (matches.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return matches.getFirst();
        }
        @Override public void close() { }
        @Override public void quit() { }
        @Override public Set<String> getWindowHandles() { return Set.of("main"); }
        @Override public String getWindowHandle() { return "main"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new UnsupportedOperationException(); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }

        // Interactive, so the one pointer click in the project can complete without a browser.
        /**
         * Where the project's one click lands: an encoded pointer sequence naming its target.
         *
         * <p>A refusal is raised here rather than on the element, because a real browser refuses
         * the action rather than a method call, and because that is where the production click
         * actually goes.
         */
        @Override
        public void perform(Collection<Sequence> actions) {
            clicks++;
            List<WebElement> targets = new ArrayList<>();
            for (Sequence sequence : actions) {
                collectTargets(sequence.encode(), targets);
            }
            for (Map.Entry<String, RuntimeException> refused : refusals.entrySet()) {
                WebElement control = controls.get(refused.getKey());
                if (control != null && targets.contains(control)) {
                    throw refused.getValue();
                }
            }
        }

        /** Pulls the elements a pointer sequence names out of its encoded form, by identity. */
        private static void collectTargets(Object encoded, List<WebElement> into) {
            if (encoded instanceof WebElement element) {
                if (!into.contains(element)) into.add(element);
                return;
            }
            if (encoded instanceof Map<?, ?> map) {
                map.values().forEach(value -> collectTargets(value, into));
                return;
            }
            if (encoded instanceof Iterable<?> many) {
                many.forEach(value -> collectTargets(value, into));
            }
        }
        @Override public void resetInputState() { }
    }

    /** One control, which exists only after enough queries and is usable only after a few more. */
    private static final class FakeElement implements WebElement {

        private final FakePage page;
        private final String selector;
        private final String tag;
        private final String name;
        private final int appearsAfter;
        private final int usableAfter;
        private final BooleanSupplier enabledGate;

        FakeElement(
            FakePage page, String selector, String tag, String name, int appearsAfter,
            int usableAfter, BooleanSupplier enabledGate
        ) {
            this.page = page;
            this.selector = selector;
            this.tag = tag;
            this.name = name;
            this.appearsAfter = appearsAfter;
            this.usableAfter = usableAfter;
            this.enabledGate = enabledGate;
        }

        /** How many times this control has been handed back by a query. */
        private int observed;

        private boolean usable() { return observed > usableAfter; }

        @Override public boolean isDisplayed() { return usable(); }

        /**
         * Displayed is a precondition, and past that the page's own condition decides.
         *
         * <p>The default gate is always true, so nothing about the existing cases changes.
         */
        @Override public boolean isEnabled() { return usable() && enabledGate.getAsBoolean(); }
        @Override public String getText() { return name; }
        @Override public String getAccessibleName() { return name; }

        @Override
        public String getAttribute(String attribute) {
            return switch (attribute) {
                case "name", "id", "aria-label", "placeholder", "data-testid" -> name;
                case "type" -> "button".equals(tag) ? "submit" : "";
                default -> "";
            };
        }

        @Override public void clear() { requireUsable(); }

        @Override public void sendKeys(CharSequence... keys) {
            requireUsable();
            // The label only. A fake that recorded the keys would be a fake that could leak them.
            page.typedInto.add(name);
        }

        @Override public void click() { requireUsable(); page.clicks++; }

        /** What a browser does when something types into a control that is not interactable. */
        private void requireUsable() {
            if (!usable()) {
                throw new IllegalStateException("element not interactable");
            }
        }

        // --- the rest of the WebElement contract --------------------------------------------------
        //
        // Selenium's WebElement has seventeen abstract methods and the sign-in flow uses six of
        // them. The other eleven still have to exist, and what they return matters more than it
        // looks: a fake that quietly answers a question nobody meant to ask is how a test starts
        // passing for the wrong reason. So each one below is either a real answer this fake can
        // give truthfully, or a refusal that would be loud if the flow ever started asking.

        /** The element's own tag, which is a fact this fake actually knows. */
        @Override public String getTagName() { return tag; }

        /** Neither a text input nor a submit button is ever in a selected state. */
        @Override public boolean isSelected() { return false; }

        /**
         * The same answer for every property.
         *
         * <p>Nothing in the sign-in flow reads a computed style, so there is no value this could
         * return that would be more truthful than the empty string a browser gives for a property
         * it has no answer for. Deterministic on purpose: a plausible-looking "block" or "visible"
         * would be a lie that some future condition could come to depend on.
         */
        @Override public String getCssValue(String propertyName) { return ""; }

        /**
         * Fixed geometry, because this page has no layout.
         *
         * <p>The click-point hit-test that would read these only runs against a
         * {@code JavascriptExecutor}, and {@link FakePage} deliberately is not one — so these are
         * never consulted. Constant values keep them from implying a layout that does not exist.
         */
        @Override public Point getLocation() { return ORIGIN; }

        @Override public Dimension getSize() { return SIZE; }

        /** Built from the two above rather than from four loose ints, whose order is easy to invert. */
        @Override public Rectangle getRect() { return new Rectangle(ORIGIN, SIZE); }

        /**
         * Refused rather than answered.
         *
         * <p>The helper clicks the resolved control; it never submits a form, never searches inside
         * an element and never photographs one. Returning something harmless for these would let a
         * future change start using them and still pass here. A refusal fails loudly instead,
         * which is the point of a test double.
         */
        @Override public void submit() {
            throw new UnsupportedOperationException("the sign-in clicks its control, it never submits");
        }

        @Override public List<WebElement> findElements(By by) {
            throw new UnsupportedOperationException("this page is flat: nothing searches inside a control");
        }

        @Override public WebElement findElement(By by) {
            throw new UnsupportedOperationException("this page is flat: nothing searches inside a control");
        }

        @Override public <X> X getScreenshotAs(OutputType<X> target) {
            throw new UnsupportedOperationException("nothing is rendered, so nothing can be captured");
        }

        private static final Point ORIGIN = new Point(0, 0);
        private static final Dimension SIZE = new Dimension(200, 30);
    }
}
