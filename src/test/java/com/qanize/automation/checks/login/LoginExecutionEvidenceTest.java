package com.qanize.automation.checks.login;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.checks.search.SearchExecutionEvidence;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.evidence.EvidenceFileReader;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.testsupport.CheckSources;


/**
 * Proves the Login execution evidence without a browser, a website or a credential.
 *
 * <p>Two properties matter more than the rest and most of this file is about them: a secret cannot
 * reach the evidence, and submitting a form is never described as signing in.
 *
 * <p>Every value here is synthetic.
 */
@Tag("unit")
class LoginExecutionEvidenceTest {

    /** Sentinels that must never appear in a persisted record. Not real credentials. */
    private static final String SYNTHETIC_USERNAME = "synthetic-username-sentinel";
    private static final String SYNTHETIC_PASSWORD = "synthetic-password-sentinel";

    /**
     * Stands in for a configured success text. Nothing can pass it to the recorder, so its absence
     * from every record is a real structural proof rather than a word-matching exercise.
     */
    private static final String SYNTHETIC_SUCCESS_TEXT = "synthetic-success-text-sentinel";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private LoginExecutionEvidence recording() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        return new LoginExecutionEvidence();
    }

    private List<String> lines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** Drives the observer exactly as the helper does on a healthy sign-in. */
    private LoginExecutionEvidence submittedRun(LoginExecutionEvidence execution) {
        execution.openedLoginPage(410);
        execution.capturedSuccessTextBaseline(false, 3);
        execution.resolvedControl(LoginElementDetector.Role.USERNAME, "DETECTED", 12);
        execution.resolvedControl(LoginElementDetector.Role.PASSWORD, "DETECTED", 8);
        execution.resolvedControl(LoginElementDetector.Role.SUBMIT, "MANUAL:button[type='submit']", 6);
        execution.typedUsername(31);
        execution.typedPassword(29);
        execution.awaitedSubmitClickable(17);
        execution.clickedSubmit(55);
        execution.readFinalAddress("https://example.com/account", true, 4);
        return execution;
    }

    // --- 1, 2: sequence and timing ------------------------------------------------------------

    @Test
    void theSequenceStartsAtOneAndIsGapless() throws IOException {
        LoginExecutionEvidence execution = submittedRun(recording());
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false);

        List<String> all = lines();
        assertEquals(11, all.size());
        for (int index = 0; index < all.size(); index++) {
            assertTrue(all.get(index).contains("\"sequence\":" + (index + 1)), all.get(index));
        }
        assertEquals(11, execution.recordedSteps());
    }

    @Test
    void eachObservedOperationIsTimedAtItsOwnBoundary() throws IOException {
        String helper = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/login/GenericLoginHelper.java"));

        // Ten observed operations, ten fresh measurements: opening the page, the optional control
        // that reveals the form, the success-text baseline, resolving three controls, two edits,
        // the click preparation and the click. One interval can therefore never be handed to two
        // records, which is the defect that had to be corrected in the Search slice.
        assertEquals(10, helper.split("started = System\\.nanoTime\\(\\);", -1).length - 1,
            "each operation must re-seed the timer immediately before it runs");

        // And each distinct measurement reaches its own record.
        submittedRun(recording());
        List<String> durations = lines().stream()
            .map(line -> line.substring(line.indexOf("\"durationMs\":")))
            .map(part -> part.substring(0, part.indexOf(',') < 0 ? part.length() - 1 : part.indexOf(',')))
            .toList();
        assertEquals(durations.size(), durations.stream().distinct().count(),
            "no interval may be attributed twice: " + durations);
    }

    @Test
    void theVerdictStepUsesZeroDurationRatherThanAnInventedOne() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false);

        assertTrue(lines().getFirst().contains("\"durationMs\":0"), lines().getFirst());
    }

    // --- 3, 4: each method maps to a real operation ---------------------------------------------

    @Test
    void everyEmittedMethodMapsToARealLoginOperation() throws IOException {
        LoginExecutionEvidence execution = submittedRun(recording());
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false);

        List<String> all = lines();
        assertTrue(all.get(0).contains("\"method\":\"NAVIGATE\""), all.get(0));
        assertTrue(all.get(1).contains("\"method\":\"DOM_INSPECTION\""), all.get(1));
        assertTrue(all.get(2).contains("\"method\":\"DOM_INSPECTION\""), all.get(2));
        assertTrue(all.get(3).contains("\"method\":\"DOM_INSPECTION\""), all.get(3));
        assertTrue(all.get(4).contains("\"method\":\"DOM_INSPECTION\""), all.get(4));
        assertTrue(all.get(5).contains("\"method\":\"TYPE\""), all.get(5));
        assertTrue(all.get(6).contains("\"method\":\"TYPE\""), all.get(6));
        assertTrue(all.get(7).contains("\"method\":\"WAIT_FOR_CONDITION\""), all.get(7));
        assertTrue(all.get(8).contains("\"method\":\"CLICK\""), all.get(8));
        assertTrue(all.get(9).contains("\"method\":\"DOM_INSPECTION\""), all.get(9));
        assertTrue(all.get(10).contains("\"method\":\"ASSERT\""), all.get(10));
    }

    @Test
    void usernameAndPasswordTypingAreSeparateTruthfulSteps() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.typedUsername(31);
        execution.typedPassword(29);

        List<String> all = lines();
        assertEquals(2, all.size());
        assertTrue(all.get(0).contains("Cleared the username field"), all.get(0));
        assertTrue(all.get(1).contains("Cleared the password field"), all.get(1));
        assertTrue(all.get(0).contains("\"durationMs\":31"), all.get(0));
        assertTrue(all.get(1).contains("\"durationMs\":29"), all.get(1));
    }

    // --- 5, 6, 7: no credential can reach the evidence --------------------------------------------

    @Test
    void theTypingCallbacksCannotAcceptACredential() throws Exception {
        // Structural, not incidental: a duration is the only thing these can be told.
        for (String name : List.of("typedUsername", "typedPassword")) {
            Method method = GenericLoginHelper.Observer.class.getMethod(name, long.class);
            assertEquals(1, method.getParameterCount(), name);
            assertEquals(long.class, method.getParameterTypes()[0], name);

            Method implementation = LoginExecutionEvidence.class.getMethod(name, long.class);
            assertEquals(1, implementation.getParameterCount(), name);
            assertEquals(long.class, implementation.getParameterTypes()[0], name);
        }
    }

    @Test
    void everyObserverCallbackTakesOnlySafeParameterTypes() {
        // A closed allow-list. `boolean` carries presence state only — it cannot hold the
        // configured success text or any page content — while String is admitted solely for the
        // role resolution and failure explanation the helper builds from configuration and
        // detection, never from a typed value. CharSequence, Object, WebElement and any
        // page-source container stay out, so a future callback cannot quietly widen the seam.
        List<Class<?>> safeTypes = List.of(
            long.class, boolean.class, String.class, Throwable.class,
            LoginElementDetector.Role.class,
            GenericLoginHelper.Outcome.class,
            GenericLoginHelper.Interaction.class
        );
        for (Method method : GenericLoginHelper.Observer.class.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertTrue(safeTypes.contains(parameter),
                    method.getName() + " accepts an unexpected parameter type: " + parameter);
            }
        }
        // The types that would let page content across are explicitly not on the list.
        for (Class<?> unsafe : List.of(
            CharSequence.class, Object.class, StringBuilder.class, char[].class, byte[].class
        )) {
            assertFalse(safeTypes.contains(unsafe), unsafe + " must never be an observer parameter");
        }
    }

    @Test
    void syntheticCredentialSentinelsNeverAppearInThePersistedRecord() throws IOException {
        LoginExecutionEvidence execution = submittedRun(recording());
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false);

        for (String line : lines()) {
            assertFalse(line.contains(SYNTHETIC_USERNAME), line);
            assertFalse(line.contains(SYNTHETIC_PASSWORD), line);
            assertFalse(line.contains("sentinel"), line);
        }
    }

    @Test
    void thePasswordStepStatesThatValueAndLengthWereNotRecorded() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.typedPassword(29);

        String line = lines().getFirst();
        assertTrue(line.contains("Its value and length were not recorded."), line);
        // No length, no mask, no hash, no prefix.
        assertFalse(line.contains("length="), line);
        assertFalse(line.contains("****"), line);
        assertFalse(line.contains("[redacted]"), line);
    }

    @Test
    void neitherTypingStepNamesTheCredentialOrItsSource() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.typedUsername(31);
        execution.typedPassword(29);

        for (String line : lines()) {
            assertTrue(line.contains("the configured"), line);
            assertFalse(line.contains("Env"), line);
            assertFalse(line.contains("getenv"), line);
        }
    }

    // --- 8, 9, 10: URL and page-source safety -------------------------------------------------------

    @Test
    void onlyASanitizedAddressIsPersisted() throws IOException {
        LoginExecutionEvidence execution = recording();
        String raw = "https://example.com/account?session=synthetic-session&tab=profile";
        execution.readFinalAddress(SensitiveDataFilter.sanitizeUrl(raw), true, 4);

        String line = lines().getFirst();
        assertFalse(line.contains("synthetic-session"), line);
        assertTrue(line.contains("session=[redacted]"), line);
        assertTrue(line.contains("tab=profile"), line);
    }

    @Test
    void theCheckHandsTheRecorderOnlyASanitizedAddress() throws IOException {
        // The address handling moved into LoginModule; reading the wrapper would prove nothing.
        // Whitespace removed rather than collapsed: only the call shape matters here, and a line
        // wrap inside the argument list must not decide whether this guard holds.
        String check = Files.readString(LOGIN_MODULE).replaceAll("\\s+", "");

        assertTrue(check.contains("execution.readFinalAddress(SensitiveDataFilter.sanitizeUrl(finalUrl)"),
            "the recorder must never be handed the raw address");
        // And the raw address still drives the comparison, now through the named helper the wait
        // calls on every pass. Comparing the sanitized copy could fail a passing sign-in.
        assertTrue(check.contains("addressContains(finalUrl,expectedUrl)"),
            "the success evaluation must use the raw address");
        assertFalse(check.contains("addressContains(safeFinalUrl"),
            "the sanitized copy must never be what the condition is compared against");
    }

    @Test
    void theSuccessTextCheckRecordsOnlyWhetherItWasFound() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.checkedSuccessText(false, true, 3);
        execution.checkedSuccessText(false, false, 3);

        List<String> all = lines();
        // Only the transition is described; presence alone is never called a success.
        assertTrue(all.get(0).contains("absent before"), all.get(0));
        assertTrue(all.get(0).contains("present afterwards"), all.get(0));
        assertTrue(all.get(0).contains("\"status\":\"PASSED\""), all.get(0));
        assertTrue(all.get(1).contains("still absent"), all.get(1));
        assertTrue(all.get(1).contains("\"status\":\"FAILED\""), all.get(1));
        for (String line : all) {
            assertTrue(line.contains("not recorded"), line);
            assertFalse(line.contains(SYNTHETIC_SUCCESS_TEXT), line);
            assertFalse(line.contains("<html"), line);
            assertFalse(line.contains("<body"), line);
        }
        // A missing success text is a website result, not an analyzer malfunction.
        assertTrue(all.get(1).contains("\"errorCategory\":\"WEBSITE_FINDING\""), all.get(1));
    }

    @Test
    void theRecorderCannotBeHandedPageSourceOrTheConfiguredText() throws Exception {
        // Boolean state only: present-before, present-after, and a duration. There is no parameter
        // through which the configured text or the page source could arrive.
        Method checked = LoginExecutionEvidence.class.getMethod(
            "checkedSuccessText", boolean.class, boolean.class, long.class);
        assertEquals(3, checked.getParameterCount());
        assertEquals(boolean.class, checked.getParameterTypes()[0]);
        assertEquals(boolean.class, checked.getParameterTypes()[1]);
        assertEquals(long.class, checked.getParameterTypes()[2]);

        // No overload takes a String, so the text cannot be handed over by another route either.
        for (Method candidate : LoginExecutionEvidence.class.getMethods()) {
            if (!candidate.getName().equals("checkedSuccessText")) continue;
            for (Class<?> parameter : candidate.getParameterTypes()) {
                assertTrue(parameter == boolean.class || parameter == long.class,
                    "checkedSuccessText must accept state only, not " + parameter);
            }
        }
    }

    // --- 11, 12: success semantics ---------------------------------------------------------------------

    @Test
    void noConfiguredConditionProducesTheExplicitNotVerifiedWording() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false);

        String line = lines().getFirst();
        assertTrue(line.contains(LoginExecutionEvidence.NOT_VERIFIED), line);
        // The existing result is preserved: submission alone still passes.
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
        assertFalse(line.contains("errorCategory"), line);
        // And it never overclaims.
        for (String forbidden : List.of(
            "Login succeeded", "Authentication succeeded", "signed in successfully",
            "reached the account", "permissions"
        )) {
            assertFalse(line.contains(forbidden), forbidden + " must never be claimed: " + line);
        }
    }

    @Test
    void aUrlOnlyConditionIsDescribedAccurately() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, true, true, false, true, false);
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, true, false, false, true, false);

        List<String> all = lines();
        assertTrue(all.get(0).contains("The address matched the configured success condition."), all.get(0));
        assertTrue(all.get(0).contains("\"status\":\"PASSED\""), all.get(0));
        assertTrue(all.get(1).contains("The address did not match"), all.get(1));
        assertTrue(all.get(1).contains("\"errorCategory\":\"WEBSITE_FINDING\""), all.get(1));
        for (String line : all) assertFalse(line.contains(LoginExecutionEvidence.NOT_VERIFIED), line);
    }

    @Test
    void aTextOnlyConditionIsDescribedAccurately() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, true, true, false);
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, true, false, false);

        List<String> all = lines();
        // The verdict reports the transition, not mere presence.
        assertTrue(all.get(0).contains("appeared only after the attempt"), all.get(0));
        assertTrue(all.get(0).contains("\"status\":\"PASSED\""), all.get(0));
        assertTrue(all.get(1).contains("did not appear"), all.get(1));
        assertTrue(all.get(1).contains("\"errorCategory\":\"WEBSITE_FINDING\""), all.get(1));
    }

    @Test
    void bothConditionsAreDescribedTogether() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, true, true, true, true, false);

        String line = lines().getFirst();
        // Both conditions are still required, and both are reported.
        assertTrue(line.contains("The address matched"), line);
        assertTrue(line.contains("appeared only after the attempt"), line);
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
    }

    @Test
    void anUnsubmittedFormNeverClaimsAnythingAboutAuthentication() throws IOException {
        LoginExecutionEvidence execution = recording();
        boolean submitted = false;
        boolean operationFailed = false;
        boolean urlConfigured = false;
        boolean textConfigured = false;
        // No success text is configured, so no baseline was taken and none can be unusable.
        boolean textUnusable = false;
        execution.assertedOutcome(submitted, operationFailed,
            GenericLoginHelper.Outcome.SUBMIT_NOT_FOUND,
            urlConfigured, true, textConfigured, true, textUnusable);

        String line = lines().getFirst();
        assertTrue(line.contains("nothing about authentication was established"), line);
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
    }

    // --- the address step must not claim a submission that did not happen ---------------------------------

    @Test
    void aFailedClickNeverProducesAfterSubmittingOrAfterSigningInWording() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.interactionFailed(GenericLoginHelper.Interaction.CLICK_SUBMIT,
            new IllegalStateException("element click intercepted"), 40);
        execution.readFinalAddress("https://example.com/login", false, 4);

        String address = lines().get(1);
        assertFalse(address.contains("after submitting"), address);
        assertFalse(address.contains("after signing in"), address);
        assertFalse(address.contains("reached after"), address);
    }

    @Test
    void anUnsubmittedAttemptUsesSignInAttemptWordingAndSaysSoExplicitly() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.readFinalAddress("https://example.com/login", false, 4);

        String line = lines().getFirst();
        assertTrue(line.contains("Read the browser address after the sign-in attempt."), line);
        assertTrue(line.contains("The browser reports its current address after the sign-in attempt."), line);
        assertTrue(line.contains("The sign-in form was not submitted, so this address does not"), line);
    }

    @Test
    void aSubmittedAttemptMayUseTheMoreSpecificWording() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.readFinalAddress("https://example.com/account", true, 4);

        String line = lines().getFirst();
        assertTrue(line.contains("after submitting the sign-in form."), line);
        assertTrue(line.contains("The browser reports the address reached after submitting."), line);
        // Even here it never claims the sign-in itself succeeded.
        assertFalse(line.contains("after signing in"), line);
        assertFalse(line.contains("does not reflect a completed sign-in"), line);
    }

    // --- the click-reliability preparation -------------------------------------------------------------

    /** Read once so every structural claim below is made against the real production file. */
    private static String helperSource() throws IOException {
        return Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/login/GenericLoginHelper.java"));
    }

    /**
     * The helper with comments removed, for assertions that count constructs.
     *
     * <p>Counting on the raw file is brittle: the comment explaining why {@code WebElement.click()}
     * is <em>not</em> used contains the very text a click count looks for, so prose could make a
     * guard pass or fail. Counting executable code cannot.
     */
    private static String helperCode() throws IOException {
        return stripComments(helperSource());
    }

    /**
     * The shared click helper the sign-in now delegates to.
     *
     * <p>The centring, ownership hit-test and single pointer click moved here so the configured
     * menu check could reuse them instead of growing a second copy. Every invariant these tests
     * protected still has to hold — it simply holds one file across now, which is why each guard
     * below asserts both that the sign-in delegates and that the implementation is unique.
     */
    private static String sharedClickSource() throws IOException {
        return Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/shared/SafePointerClick.java"));
    }

    private static String sharedClickCode() throws IOException {
        return stripComments(sharedClickSource());
    }

    /**
     * The text between two markers, with both proved present first.
     *
     * <p>A moved constant used to make this fail as StringIndexOutOfBounds, which reports where
     * the test broke rather than which invariant is unmet.
     */
    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "start marker not found: " + startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "end marker not found after the start marker: " + endMarker);
        return source.substring(start, end);
    }

    private static String stripComments(String source) {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)//.*$", " ");
    }

    @Test
    void theAlreadyResolvedControlIsCentredThenWaitedOnThenClickedOnce() throws IOException {
        String helper = helperSource().replaceAll("\\s+", " ");

        // The same resolved element flows through all three operations: nothing is looked up again.
        assertTrue(helper.contains("safeClick.ownsItsClickPoint(submitControl)"), helper);
        assertTrue(helper.contains("safeClick.clickOnce(submitControl);"), helper);
        assertOrdered(helper, "safeClick.ownsItsClickPoint(submitControl)",
            "safeClick.clickOnce(submitControl);");
        // And the thing it delegates to still centres, hit-tests, then clicks that same element.
        String shared = sharedClickSource().replaceAll("\\s+", " ");
        assertOrdered(shared, "centreInViewport(element)", "document.elementFromPoint(x, y)");
        assertTrue(shared.contains("new Actions(driver).moveToElement(element).click().perform();"), shared);
    }

    @Test
    void theControlIsCentredRatherThanLeftAtTheViewportEdge() throws IOException {
        String shared = sharedClickSource();

        assertTrue(shared.contains("block: 'center', inline: 'center'"),
            "the default edge alignment is what produced the interception");
        assertTrue(shared.contains("scrollIntoView"), shared);
        // The sign-in no longer centres anything itself; it delegates, so there is one centring.
        assertFalse(helperCode().contains("scrollIntoView"), "centring must exist in one place only");
    }

    @Test
    void theClickabilityWaitIsBoundedByTheConfiguredTimeout() throws IOException {
        String helper = helperSource().replaceAll("\\s+", " ");

        assertTrue(helper.contains(
            "WaitUtils.wait(driver, timeout).until(ignored -> safeClick.ownsItsClickPoint(submitControl))"),
            "the wait must use the timeout the run already configured");
        // No arbitrary sleep anywhere in the flow, in either file.
        assertFalse(helper.contains("Thread.sleep"), "no arbitrary sleep may be added");
        assertFalse(sharedClickSource().contains("Thread.sleep"), "no arbitrary sleep may be added");
    }

    @Test
    void exactlyOneNativeClickExistsAndNothingClicksAnythingElse() throws IOException {
        String code = helperCode();

        String shared = sharedClickCode();

        // The sign-in itself no longer clicks anything: it asks the shared helper to, once.
        assertEquals(0, code.split("\\.click\\(\\)", -1).length - 1,
            "the sign-in must not click anything itself");
        // Two delegated clicks, and each is a different, named act that a reader can point at:
        // the optional control that reveals the sign-in form, and the submit itself. A third would
        // be something nobody decided to do.
        assertEquals(2, code.split("safeClick\\.clickOnce\\(", -1).length - 1,
            "exactly two delegated clicks, and they are the trigger and the submit");
        assertEquals(1, code.split("safeClick\\.clickOnce\\(trigger\\)", -1).length - 1,
            "one of them reveals the form");
        assertEquals(1, code.split("safeClick\\.clickOnce\\(submitControl\\)", -1).length - 1,
            "and exactly one of them submits it");

        // One real pointer click in the whole project, and it is the Actions chain. The element
        // click was tried instead and reverted: three runs proved the mechanism was never the
        // variable, and it reintroduced the re-scroll and interception risk this chain avoids.
        assertEquals(1, shared.split("\\.click\\(\\)", -1).length - 1,
            "exactly one click call in the shared executable code");
        assertEquals(1, shared.split("new Actions\\(driver\\)", -1).length - 1,
            "exactly one pointer operation");
        assertTrue(shared.contains("moveToElement(element).click().perform();"), shared);

        // WebElement.click() is deliberately gone: the W3C Element Click algorithm re-scrolls the
        // element itself, which undid the prepared centre position.
        assertFalse(code.contains("submitControl.click();"), "WebElement.click() must not be used");
        assertFalse(shared.contains("element.click();"), "no element is clicked directly");

        // Nothing else submits the form.
        assertFalse(code.contains("Keys.ENTER"), "no Enter submission");
        assertFalse(code.contains("Keys.RETURN"), "no Return submission");
        assertFalse(code.contains(".submit()"), "no form submit");
    }

    @Test
    void noJavaScriptClickAndNoOverlayHandlingExists() throws IOException {
        String code = helperCode();

        String shared = sharedClickCode();

        assertFalse(code.contains("arguments[0].click()"), "no JavaScript click");
        assertFalse(shared.contains("arguments[0].click()"), "no JavaScript click");

        // The sign-in runs no script of its own any more.
        assertEquals(0, code.split("executeScript", -1).length - 1,
            "the sign-in must not execute script itself");

        // Exactly two script calls, and both are approved: one centres the control, one hit-tests
        // its click point. Neither clicks, and neither mutates the page.
        assertEquals(2, shared.split("executeScript", -1).length - 1,
            "two script calls: centring and the ownership hit-test");
        assertTrue(shared.contains("scrollIntoView"), "one call centres the control");
        assertTrue(shared.contains("CLICK_POINT_OWNERSHIP"), "one call hit-tests the click point");
        assertTrue(shared.contains("document.elementFromPoint(x, y)"), shared);

        // Nothing hunts for a banner, advert or modal to dismiss.
        for (String forbidden : List.of("cookie", "banner", "consent", "advert", "modal", "overlay", "dismiss")) {
            assertFalse(code.toLowerCase(java.util.Locale.ROOT).contains(forbidden),
                "no " + forbidden + " handling may be added");
        }
    }

    @Test
    void noRetryCanSubmitTheFormTwice() throws IOException {
        String code = helperCode();

        // No loop of any kind can re-enter the click, and there is exactly one executed click,
        // so the form cannot be submitted twice. Asserted on executable code rather than on the
        // word "retry", which legitimately appears in the comment explaining its absence.
        String shared = sharedClickCode();

        for (String source : List.of(code, shared)) {
            assertFalse(source.contains("while ("), "no loop may re-enter the submission");
            assertFalse(source.contains("for ("), "no loop may re-enter the submission");
            assertFalse(source.contains("do {"), "no loop may re-enter the submission");
        }
        assertEquals(1, shared.split("\\.click\\(\\)\\.perform\\(\\);", -1).length - 1,
            "one executed pointer click is the only submission path");
    }

    @Test
    void theWaitAndTheClickAreMeasuredIndependently() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.awaitedSubmitClickable(17);
        execution.clickedSubmit(55);

        List<String> all = lines();
        assertTrue(all.get(0).contains("\"durationMs\":17"), all.get(0));
        assertTrue(all.get(1).contains("\"durationMs\":55"), all.get(1));
        assertTrue(all.get(0).contains("\"method\":\"WAIT_FOR_CONDITION\""), all.get(0));
        assertTrue(all.get(1).contains("\"method\":\"CLICK\""), all.get(1));

        // Two separate boundaries in the sign-in, so one interval cannot serve both. The click
        // itself now lives in SafePointerClick, so this proves the delegation order and the fresh
        // timer rather than naming the Actions chain here.
        String helper = helperSource().replaceAll("\\s+", " ");
        String waitStart =
            "started = System.nanoTime(); try { WaitUtils.wait(driver, timeout).until(ignored -> safeClick.ownsItsClickPoint(submitControl));";
        String waitRecorded = "observer.awaitedSubmitClickable(elapsedMs(started));";
        // A new timer must start between the two records: reusing the wait's `started` would give
        // the click the wait's interval, and this contiguous fragment would then not match.
        String clickStart = "started = System.nanoTime(); try { safeClick.clickOnce(submitControl);";
        String clickRecorded = "observer.clickedSubmit(elapsedMs(started));";
        assertOrdered(helper, waitStart, waitRecorded);
        assertOrdered(helper, waitRecorded, clickStart);
        assertOrdered(helper, clickStart, clickRecorded);
        // And the one pointer click those two intervals bracket belongs to the shared helper.
        assertTrue(sharedClickSource().replaceAll("\\s+", " ")
            .contains("new Actions(driver).moveToElement(element).click().perform();"),
            "the single pointer click lives in SafePointerClick");
    }

    @Test
    void theWaitStepDescribesPreparationAndClaimsNoClick() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.awaitedSubmitClickable(17);

        String line = lines().getFirst();
        assertTrue(line.contains("\"phase\":\"WAIT\""), line);
        assertTrue(line.contains("\"method\":\"WAIT_FOR_CONDITION\""), line);
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
        // It describes preparation: centring and click-point ownership, nothing more.
        assertTrue(line.contains("centre is inside the viewport"), line);
        assertTrue(line.contains("owned its own click point"), line);
        // And it must not imply the click has happened.
        assertTrue(line.contains("has not been clicked"), line);
        assertFalse(line.contains("\"method\":\"CLICK\""), line);
    }

    @Test
    void aWaitTimeoutIsRecordedAndNoSuccessfulClickFollows() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.submitNeverBecameClickable(
            new IllegalStateException("timed out waiting for element to be clickable"), 10_000);
        execution.readFinalAddress("https://example.com/login", false, 4);
        execution.assertedOutcome(false, true, null, false, true, false, true, false);

        List<String> all = lines();
        assertTrue(all.get(0).contains("\"method\":\"WAIT_FOR_CONDITION\""), all.get(0));
        assertTrue(all.get(0).contains("\"status\":\"FAILED\""), all.get(0));
        assertTrue(all.get(0).contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), all.get(0));
        assertTrue(all.get(0).contains("It was not clicked"), all.get(0));
        // No successful click, and the corrected address wording is preserved.
        assertTrue(all.stream().noneMatch(line -> line.contains("\"method\":\"CLICK\"")));
        assertTrue(all.get(1).contains("after the sign-in attempt"), all.get(1));
        // And the corrected category propagation still holds.
        assertTrue(all.get(2).contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), all.get(2));
    }

    @Test
    void aWaitFailureRecordsBeforeTheSameExceptionEscapes() throws IOException {
        LoginExecutionEvidence execution = recording();
        IllegalStateException original = new IllegalStateException("timed out");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> recordThenRethrow(
                () -> { throw original; },
                failure -> execution.submitNeverBecameClickable(failure, 10_000)));

        assertSame(original, thrown, "the original exception instance must escape unchanged");
        assertEquals(1, lines().size(), "the failed wait was recorded before it escaped");

        String helper = helperSource().replace("\r\n", "\n");
        assertTrue(helper.contains(
            "observer.submitNeverBecameClickable(failure, elapsedMs(started));\n            throw failure;"),
            "record-then-rethrow must be adjacent");
    }

    @Test
    void aReportingFailureCannotReplaceAWaitFailure() throws IOException {
        LoginExecutionEvidence execution = recording();
        IllegalStateException original = new IllegalStateException("timed out");
        Throwable hostile = new RuntimeException() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString exploded");
            }
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> recordThenRethrow(
                () -> { throw original; },
                failure -> execution.submitNeverBecameClickable(hostile, 1)));

        assertSame(original, thrown);
    }

    @Test
    void typingAndScreenshotRedactionBehaviourIsUnchanged() throws IOException {
        String helper = helperSource();
        // Screenshot redaction registers the secrets with this test's own browser lifecycle, so it
        // stays in the wrapper and is read there. A module cannot reach protectFromScreenshots.
        String check = Files.readString(CheckSources.entryClass("GenericLoginTest"));

        // Typing is untouched by this fix: still clear-then-sendKeys, nothing centred or waited on.
        assertTrue(helper.contains("usernameField.clear();"));
        assertTrue(helper.contains("usernameField.sendKeys(username);"));
        assertTrue(helper.contains("passwordField.clear();"));
        assertTrue(helper.contains("passwordField.sendKeys(password);"));
        assertFalse(helper.contains("centreInViewport(usernameField)"));
        assertFalse(helper.contains("centreInViewport(passwordField)"));

        // The screenshot redaction registration is unchanged.
        assertTrue(check.contains("protectFromScreenshots(username)"));
        assertTrue(check.contains("protectFromScreenshots(password)"));
    }

    @Test
    void theNewCallbacksStillCannotCarryACredential() throws Exception {
        Method awaited = GenericLoginHelper.Observer.class.getMethod("awaitedSubmitClickable", long.class);
        assertEquals(1, awaited.getParameterCount());
        assertEquals(long.class, awaited.getParameterTypes()[0]);

        Method failed = GenericLoginHelper.Observer.class.getMethod(
            "submitNeverBecameClickable", Throwable.class, long.class);
        assertEquals(2, failed.getParameterCount());
    }

    @Test
    void credentialSentinelsRemainAbsentAfterTheClickFix() throws IOException {
        LoginExecutionEvidence execution = submittedRun(recording());
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false);

        for (String line : lines()) {
            assertFalse(line.contains(SYNTHETIC_USERNAME), line);
            assertFalse(line.contains(SYNTHETIC_PASSWORD), line);
            assertFalse(line.contains("sentinel"), line);
        }
    }

    // --- Fix A: the preparation must prove the control owns its click point --------------------------------

    @Test
    void thePreparationProvesGeometryAndOwnershipNotMerelyVisibility() throws IOException {
        // The preparation moved to SafePointerClick, so the proof follows it. The sign-in's own
        // obligation — that it still asks for this preparation — is asserted at the end.
        String shared = sharedClickSource();

        // Displayed and enabled alone is what let a covered control through.
        assertTrue(shared.contains("element.isDisplayed()"), shared);
        assertTrue(shared.contains("element.isEnabled()"), shared);
        // Centre inside the viewport.
        assertTrue(shared.contains("getBoundingClientRect()"), shared);
        assertTrue(shared.contains("window.innerWidth"), shared);
        assertTrue(shared.contains("window.innerHeight"), shared);
        assertTrue(shared.contains("x < 0 || y < 0 || x >= width || y >= height"), shared);
        // The control, or one of its descendants, owns the point.
        assertTrue(shared.contains("document.elementFromPoint(x, y)"), shared);
        assertTrue(shared.contains("atPoint === element || element.contains(atPoint)"), shared);
        // Zero-area elements cannot own a point.
        assertTrue(shared.contains("box.width === 0 || box.height === 0"), shared);

        // The sign-in still demands that preparation before it clicks anything.
        assertTrue(helperSource().contains("safeClick.ownsItsClickPoint(submitControl)"),
            "the sign-in must still require the ownership proof");
    }

    @Test
    void theHitTestReturnsOnlyABooleanAndNeverIdentifiesTheBlocker() throws IOException {
        String shared = sharedClickSource();

        assertTrue(shared.contains("Boolean.TRUE.equals(owned)"),
            "the script's answer is consumed as a boolean");
        // Nothing about the obstructing element may be read back, in either file.
        for (String forbidden : List.of(
            "atPoint.tagName", "atPoint.id", "atPoint.className", "atPoint.outerHTML",
            "atPoint.textContent", "atPoint.innerText", "atPoint.getAttribute"
        )) {
            assertFalse(shared.contains(forbidden), forbidden + " must never be read");
            assertFalse(helperSource().contains(forbidden), forbidden + " must never be read");
        }
        assertFalse(shared.contains("document.body.innerHTML"), shared);
    }

    @Test
    void theHitTestIsNonMutating() throws IOException {
        // The script moved to SafePointerClick. Both boundaries are asserted before any substring
        // is taken: a missing marker used to surface as StringIndexOutOfBounds, which says nothing
        // about what is actually wrong.
        String script = between(
            sharedClickSource(), "CLICK_POINT_OWNERSHIP = \"\"\"", "\"\"\";");

        for (String mutation : List.of(
            ".value =", ".click()", "arguments[0].click", ".remove()", ".style", "setAttribute",
            "removeAttribute", "appendChild", "innerHTML =", "textContent =", "innerText ="
        )) {
            assertFalse(script.contains(mutation), "the hit-test must not mutate: " + mutation);
        }
        // It reads geometry and answers one question; it never names what it found.
        assertTrue(script.contains("return atPoint === element || element.contains(atPoint);"), script);
    }

    @Test
    void centringIsRequestedAgainOnEveryPollWithinTheBoundedWait() throws IOException {
        // The polled predicate moved; the bound that polls it did not.
        String shared = sharedClickSource().replaceAll("\\s+", " ");
        String helper = helperSource().replaceAll("\\s+", " ");

        // A page still laying out can move the control after the first scroll.
        assertTrue(shared.contains("if (!element.isDisplayed() || !element.isEnabled()) return false; centreInViewport(element);"),
            "each poll re-centres before hit-testing");
        assertTrue(helper.contains("WaitUtils.wait(driver, timeout).until(ignored -> safeClick.ownsItsClickPoint(submitControl))"),
            "still bounded by the configured timeout, and still polling the ownership predicate");
    }

    @Test
    void theFinalClickIsOneActionsPointerClickAndNotWebElementClick() throws IOException {
        String shared = sharedClickCode();
        String helper = helperCode();

        // The single pointer operation in the project lives in the shared helper.
        assertEquals(1, shared.split("new Actions\\(driver\\)", -1).length - 1,
            "exactly one pointer operation");
        assertTrue(shared.contains("moveToElement(element).click().perform();"), shared);
        assertEquals(1, shared.split("\\.click\\(\\)\\.perform\\(\\);", -1).length - 1,
            "one executed pointer click in the shared helper");

        // The sign-in owns no click implementation at all any more; it delegates, once.
        assertEquals(0, helper.split("new Actions\\(", -1).length - 1,
            "the sign-in must not build a pointer operation itself");
        assertEquals(2, helper.split("safeClick\\.clickOnce\\(", -1).length - 1,
            "exactly two delegated clicks: the optional form trigger and the submit");
        assertEquals(1, helper.split("safeClick\\.clickOnce\\(submitControl\\)", -1).length - 1,
            "and only one of them is the submit");

        // WebElement.click() re-scrolls the element itself, which undid the prepared position.
        assertFalse(helper.contains("submitControl.click();"),
            "WebElement.click() must not be used for the submission");
        assertFalse(shared.contains("element.click();"), "no element is clicked directly");
    }

    @Test
    void noClickOccursWhenThePreparationFails() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.submitNeverBecameClickable(
            new IllegalStateException("never owned its click point"), 10_000);
        execution.readFinalAddress("https://example.com/login", false, 4);

        assertTrue(lines().stream().noneMatch(line -> line.contains("\"method\":\"CLICK\"")));
        String wait = lines().getFirst();
        assertTrue(wait.contains("never owned its own click point"), wait);
        assertTrue(wait.contains("was not inspected or recorded"), wait);
    }

    @Test
    void thePreparationStepOnlyClaimsWhatTheHitTestProved() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.awaitedSubmitClickable(29);

        String line = lines().getFirst();
        assertTrue(line.contains("its centre is inside the viewport"), line);
        assertTrue(line.contains("what a pointer would reach there"), line);
        assertTrue(line.contains("It has not been clicked yet."), line);
    }

    // --- Fix B: the success text must prove a transition ----------------------------------------------------

    @Test
    void aSuccessTextAlreadyPresentBeforeLoginCannotProveSuccess() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.capturedSuccessTextBaseline(true, 3);
        execution.checkedSuccessText(true, true, 5);

        List<String> all = lines();
        assertTrue(all.get(0).contains("\"status\":\"FAILED\""), all.get(0));
        assertTrue(all.get(0).contains("\"errorCategory\":\"CONFIGURATION_ERROR\""), all.get(0));
        assertTrue(all.get(0).contains("cannot verify the sign-in"), all.get(0));
        // Present before and after must never pass.
        assertTrue(all.get(1).contains("\"status\":\"FAILED\""), all.get(1));
        assertTrue(all.get(1).contains("\"errorCategory\":\"CONFIGURATION_ERROR\""), all.get(1));
        assertTrue(all.get(1).contains("proves nothing"), all.get(1));
        assertFalse(all.get(1).contains("WEBSITE_FINDING"), all.get(1));
    }

    @Test
    void aSuccessTextAbsentBeforeAndPresentAfterwardsPasses() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.checkedSuccessText(false, true, 5);

        String line = lines().getFirst();
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
        assertTrue(line.contains("absent before the sign-in attempt and present afterwards"), line);
        assertFalse(line.contains("errorCategory"), line);
    }

    @Test
    void aSuccessTextAbsentBothTimesIsAWebsiteFinding() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.checkedSuccessText(false, false, 5);

        String line = lines().getFirst();
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
        assertTrue(line.contains("still absent"), line);
    }

    @Test
    void neitherTheConfiguredTextNorPageContentIsEverPersisted() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.capturedSuccessTextBaseline(true, 3);
        execution.checkedSuccessText(true, true, 5);
        execution.checkedSuccessText(false, true, 5);
        execution.checkedSuccessText(false, false, 5);

        for (String line : lines()) {
            // The sentinel stands in for a configured success text. No method can accept it, so
            // its absence is structural. Generic words such as "text" or "page content" are not
            // forbidden: the evidence deliberately says those values were not recorded.
            assertFalse(line.contains(SYNTHETIC_SUCCESS_TEXT), line);
            assertFalse(line.contains("You logged into"), line);
            assertFalse(line.contains("<html"), line);
            assertFalse(line.contains("<body"), line);
            // Each record still states plainly that nothing was retained.
            assertTrue(line.contains("not recorded"), line);
        }
    }

    @Test
    void theBaselineCallbackCannotCarryTheConfiguredText() throws Exception {
        Method baseline = GenericLoginHelper.Observer.class.getMethod(
            "capturedSuccessTextBaseline", boolean.class, long.class);
        assertEquals(2, baseline.getParameterCount());
        assertEquals(boolean.class, baseline.getParameterTypes()[0]);
        assertEquals(long.class, baseline.getParameterTypes()[1]);

        Method checked = LoginExecutionEvidence.class.getMethod(
            "checkedSuccessText", boolean.class, boolean.class, long.class);
        assertEquals(3, checked.getParameterCount());
        for (Class<?> parameter : checked.getParameterTypes()) {
            assertTrue(parameter == boolean.class || parameter == long.class,
                "no String may reach the success-text step: " + parameter);
        }
    }

    @Test
    void anUnusableTextConditionIsAConfigurationProblemNotAWebsiteFailure() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED,
            false, true, true, false, true);

        String line = lines().getFirst();
        assertTrue(line.contains("\"errorCategory\":\"CONFIGURATION_ERROR\""), line);
        assertFalse(line.contains("WEBSITE_FINDING"), line);
        assertTrue(line.contains("this is a problem with the condition, not"), line);
    }

    @Test
    void aUrlOnlyConditionIsUnaffectedByTheTextChange() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED,
            true, true, false, true, false);
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED,
            true, false, false, true, false);

        assertTrue(lines().get(0).contains("\"status\":\"PASSED\""), lines().get(0));
        assertTrue(lines().get(1).contains("\"errorCategory\":\"WEBSITE_FINDING\""), lines().get(1));
        for (String line : lines()) assertFalse(line.contains("success text"), line);
    }

    @Test
    void bothConditionsStillCombineWithAnd() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED,
            true, true, true, true, false);
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED,
            true, true, true, false, false);

        assertTrue(lines().get(0).contains("\"status\":\"PASSED\""), lines().get(0));
        assertTrue(lines().get(0).contains("appeared only after the attempt"), lines().get(0));
        assertTrue(lines().get(1).contains("\"status\":\"FAILED\""), lines().get(1));
        assertTrue(lines().get(1).contains("did not appear"), lines().get(1));
    }

    @Test
    void aClickFailureOverridesAnyUnrelatedTextMatch() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.interactionFailed(GenericLoginHelper.Interaction.CLICK_SUBMIT,
            new IllegalStateException("element click intercepted"), 40);
        execution.checkedSuccessText(true, true, 5);
        execution.assertedOutcome(false, true, null, false, true, true, false, true);

        String verdict = lines().get(2);
        assertTrue(verdict.contains("\"status\":\"FAILED\""), verdict);
        // The operational failure still outranks the unusable text condition.
        assertTrue(verdict.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), verdict);
        assertTrue(verdict.contains("the browser failed during it"), verdict);
    }

    @Test
    void theCheckAppliesTheTransitionRuleAndKeepsTheAndCombination() throws IOException {
        String check = Files.readString(LOGIN_MODULE).replaceAll("\\s+", " ");

        assertTrue(check.contains("boolean textPresentBefore = Boolean.TRUE.equals(actions.successTextPresentBeforeLogin());"), check);
        assertTrue(check.contains("boolean textOk = expectedText == null || (textFound && !textPresentBefore);"),
            "presence alone must not satisfy the condition");
        assertTrue(check.contains("boolean ok = failure == null && submitted && urlOk && successTextProved && successConditionConfigured;"),
            "each condition keeps its own place in the AND, and a run that configured nothing to"
                + " prove can no longer inherit two vacuous trues as a pass");

        // One decision, taken once and read everywhere. This is what keeps the tested item, the
        // terminal Result and the wrapper's JUnit verdict from ever disagreeing about the same
        // condition in the same run.
        assertEquals(1, check.split("boolean successTextProved = textOk;", -1).length - 1,
            "exactly one transition decision is computed");
        // The page is now read repeatedly on purpose: the check waits for the configured text to
        // appear instead of asking once and calling the silence a rejection. What this guard has
        // always protected is unchanged -- every read happens while the decision is still being
        // made, and no consumer re-asks the page after it has been taken.
        assertTrue(check.lastIndexOf("actions.pageSourceContains(")
                < check.indexOf("boolean successTextProved = textOk;"),
            "the page is only ever read while the wait is still running");
        assertTrue(
            check.contains("login.optionalSuccessText().isPresent(), successTextProved, textUnusable)"),
            "the recorded verdict reads that decision rather than recomputing it");
        assertTrue(check.contains("if (expectedText != null && !successTextProved) {"),
            "the terminal result reads that decision rather than re-asking the page");
        assertFalse(check.contains("!actions.pageSourceContains(expectedText)"),
            "re-evaluating the text in the terminal chain is the defect this guards against");
    }

    // --- the verdict inherits the category of the failure that caused it -----------------------------------

    @Test
    void aClickInterceptionIsInfrastructureInBothTheOperationAndTheVerdict() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.interactionFailed(GenericLoginHelper.Interaction.CLICK_SUBMIT,
            new IllegalStateException("element click intercepted"), 40);
        execution.readFinalAddress("https://example.com/login", false, 4);
        execution.assertedOutcome(false, true, null, false, true, false, true, false);

        String operation = lines().get(0);
        String verdict = lines().get(2);
        assertTrue(operation.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), operation);
        assertTrue(verdict.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), verdict);
        // One event, one category: the verdict must not relabel it as the website's fault.
        assertFalse(verdict.contains("WEBSITE_FINDING"), verdict);
        assertTrue(verdict.contains("the browser failed during it"), verdict);
    }

    @Test
    void aTypingOrNavigationExceptionStaysInfrastructureThroughTheVerdict() throws IOException {
        LoginExecutionEvidence typing = recording();
        typing.interactionFailed(GenericLoginHelper.Interaction.TYPE_PASSWORD,
            new IllegalStateException("not interactable"), 9);
        typing.assertedOutcome(false, true, null, false, true, false, true, false);
        assertTrue(lines().get(1).contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), lines().get(1));

        assertEquals(ErrorCategory.INFRASTRUCTURE_ERROR,
            LoginExecutionEvidence.verdictCategory(false, true, null, false),
            "a navigation failure reaches the verdict the same way");
    }

    @Test
    void aCompletedFlowThatMissesAConfiguredUrlConditionIsAWebsiteFinding() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, true, false, false, true, false);

        String line = lines().getFirst();
        assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
        assertFalse(line.contains("INFRASTRUCTURE_ERROR"), line);
    }

    @Test
    void aCompletedFlowThatMissesAConfiguredTextConditionIsAWebsiteFinding() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, true, false, false);

        assertTrue(lines().getFirst().contains("\"errorCategory\":\"WEBSITE_FINDING\""), lines().getFirst());
    }

    @Test
    void anInvalidManualSelectorKeepsItsConfigurationClassificationInTheVerdict() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.controlNotResolved(LoginElementDetector.Role.USERNAME,
            GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, "The selector is not valid CSS.", 2);
        // The configuration problem here is the supplied selector. The text flag stays false on
        // purpose: if it were true the verdict would be CONFIGURATION_ERROR for the wrong reason
        // and this test would stop proving anything about selectors.
        boolean textUnusable = false;
        execution.assertedOutcome(false, false,
            GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, false, true, false, true, textUnusable);

        for (String line : lines()) {
            assertTrue(line.contains("\"errorCategory\":\"CONFIGURATION_ERROR\""), line);
            assertFalse(line.contains("WEBSITE_FINDING"), line);
        }
    }

    @Test
    void aControlThePageNeverPresentedStaysAWebsiteFindingInBoth() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.controlNotResolved(LoginElementDetector.Role.PASSWORD,
            GenericLoginHelper.Outcome.PASSWORD_NOT_FOUND, "No password field was found.", 9);
        // No success text is configured, so the website finding must come from the missing control
        // alone. A true flag here would flip the category and hide the behaviour under test.
        boolean textUnusable = false;
        execution.assertedOutcome(false, false,
            GenericLoginHelper.Outcome.PASSWORD_NOT_FOUND, false, true, false, true, textUnusable);

        for (String line : lines()) {
            assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
        }
    }

    @Test
    void theVerdictCategoryTableIsExhaustiveAndConsistent() {
        boolean usableTextCondition = false;

        // A pass carries no category at all.
        assertEquals(null, LoginExecutionEvidence.verdictCategory(
            true, false, GenericLoginHelper.Outcome.SUBMITTED, usableTextCondition));

        // An operational failure outranks everything else, whatever the outcome says — and it
        // outranks an unusable success text too.
        for (GenericLoginHelper.Outcome outcome : GenericLoginHelper.Outcome.values()) {
            assertEquals(ErrorCategory.INFRASTRUCTURE_ERROR,
                LoginExecutionEvidence.verdictCategory(false, true, outcome, usableTextCondition),
                outcome.name());
            assertEquals(ErrorCategory.INFRASTRUCTURE_ERROR,
                LoginExecutionEvidence.verdictCategory(false, true, outcome, true),
                outcome.name() + " with an unusable success text");
        }

        // Otherwise the configuration decides: a bad supplied selector, or a success text that was
        // already on the page before the attempt.
        assertEquals(ErrorCategory.CONFIGURATION_ERROR, LoginExecutionEvidence.verdictCategory(
            false, false, GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, usableTextCondition));
        assertEquals(ErrorCategory.CONFIGURATION_ERROR, LoginExecutionEvidence.verdictCategory(
            false, false, GenericLoginHelper.Outcome.SUBMITTED, true),
            "a success text that could not prove anything is the configuration's problem");
        for (GenericLoginHelper.Outcome outcome : List.of(
            GenericLoginHelper.Outcome.SUBMITTED,
            GenericLoginHelper.Outcome.USERNAME_NOT_FOUND,
            GenericLoginHelper.Outcome.PASSWORD_NOT_FOUND,
            GenericLoginHelper.Outcome.SUBMIT_NOT_FOUND,
            GenericLoginHelper.Outcome.AMBIGUOUS_ELEMENTS
        )) {
            assertEquals(ErrorCategory.WEBSITE_FINDING,
                LoginExecutionEvidence.verdictCategory(false, false, outcome, false), outcome.name());
        }
    }

    // --- 13, 14, 15: failure paths ------------------------------------------------------------------------

    @Test
    void anUnresolvedControlProducesNoTypeOrClickRecord() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.openedLoginPage(400);
        execution.controlNotResolved(LoginElementDetector.Role.PASSWORD,
            GenericLoginHelper.Outcome.PASSWORD_NOT_FOUND, "No password field was found.", 9);

        assertTrue(lines().stream().noneMatch(line -> line.contains("\"method\":\"TYPE\"")));
        assertTrue(lines().stream().noneMatch(line -> line.contains("\"method\":\"CLICK\"")));
        String line = lines().get(1);
        assertTrue(line.contains("Nothing was typed and nothing was clicked."), line);
        assertTrue(line.contains("\"errorCategory\":\"WEBSITE_FINDING\""), line);
    }

    @Test
    void anInvalidManualSelectorStaysAConfigurationProblem() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.controlNotResolved(LoginElementDetector.Role.USERNAME,
            GenericLoginHelper.Outcome.INVALID_MANUAL_SELECTOR, "The selector is not valid CSS.", 2);

        String line = lines().getFirst();
        assertTrue(line.contains("\"errorCategory\":\"CONFIGURATION_ERROR\""), line);
        assertFalse(line.contains("WEBSITE_FINDING"), line);
    }

    @Test
    void aTypingFailureIsInfrastructureAndNamesNoCredential() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.interactionFailed(GenericLoginHelper.Interaction.TYPE_PASSWORD,
            new IllegalStateException("element not interactable"), 12);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"TYPE\""), line);
        assertTrue(line.contains("\"status\":\"FAILED\""), line);
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
        assertTrue(line.contains("No credential value was recorded."), line);
    }

    @Test
    void aClickFailureIsRecordedAsAFailedClick() throws IOException {
        LoginExecutionEvidence execution = recording();
        execution.interactionFailed(GenericLoginHelper.Interaction.CLICK_SUBMIT,
            new IllegalStateException("click intercepted"), 20);

        String line = lines().getFirst();
        assertTrue(line.contains("\"method\":\"CLICK\""), line);
        assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
    }

    /** Mirrors the helper's record-then-rethrow so the rethrow is proven behaviourally. */
    private static void recordThenRethrow(Runnable action, java.util.function.Consumer<Throwable> record) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            record.accept(failure);
            throw failure;
        }
    }

    @Test
    void theOriginalExceptionEscapesUnchangedAfterBeingRecorded() throws IOException {
        LoginExecutionEvidence execution = recording();
        IllegalStateException original = new IllegalStateException("stale element");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> recordThenRethrow(
                () -> { throw original; },
                failure -> execution.interactionFailed(
                    GenericLoginHelper.Interaction.TYPE_USERNAME, failure, 5)));

        assertSame(original, thrown, "the original exception instance must escape unchanged");
        assertEquals(1, lines().size(), "the failure was recorded before it escaped");
    }

    @Test
    void aReportingFailureCannotReplaceTheLoginFailure() throws IOException {
        LoginExecutionEvidence execution = recording();
        IllegalStateException original = new IllegalStateException("session not created");
        Throwable hostile = new RuntimeException() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString exploded");
            }
        };

        execution.loginPageOpenFailed(hostile, 1);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> recordThenRethrow(
                () -> { throw original; },
                failure -> execution.loginPageOpenFailed(hostile, 1)));

        assertSame(original, thrown, "reporting must never replace the browser failure");
        assertEquals(2, lines().size());
    }

    @Test
    void recordingIsANoOpWithoutARunDirectory() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        LoginExecutionEvidence execution = new LoginExecutionEvidence();

        execution.typedPassword(5);
        assertNotNull(execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, false, true, false, true, false));
        assertEquals(List.of(), lines());
    }

    // --- 16: nothing is invented ---------------------------------------------------------------------------

    @Test
    void noHttpScreenshotOrBrowserLifecycleStepIsEverEmitted() throws IOException {
        LoginExecutionEvidence execution = submittedRun(recording());
        execution.checkedSuccessText(false, true, 3);
        execution.assertedOutcome(true, false, GenericLoginHelper.Outcome.SUBMITTED, true, true, true, true, false);

        for (String line : lines()) {
            assertFalse(line.contains("HTTP_REQUEST"), line);
            assertFalse(line.contains("SCREENSHOT"), line);
            assertFalse(line.contains("BROWSER_START"), line);
            assertFalse(line.contains("BROWSER_CLOSE"), line);
        }
    }

    @Test
    void neitherTheRecorderNorTheCheckPressesEnterOrSubmitsAForm() throws IOException {
        List<String> methodNames = List.of(LoginExecutionEvidence.class.getDeclaredMethods())
            .stream().map(Method::getName).toList();
        for (String forbidden : List.of("pressedEnter", "submittedForm", "waited", "requested")) {
            assertFalse(methodNames.contains(forbidden), forbidden + " must not exist");
        }

        // Both halves: the flow moved to the module, and neither half may acquire these.
        for (String check : List.of(
            Files.readString(CheckSources.entryClass("GenericLoginTest")),
            Files.readString(LOGIN_MODULE))) {
            assertFalse(check.contains("Keys.ENTER"), "the check does not press Enter");
            assertFalse(check.contains(".submit()"), "the check does not submit a form");
            assertFalse(check.contains("executeScript"), "the check runs no JavaScript");
        }
    }

    // --- 17: the existing helper contract survives ---------------------------------------------------------------

    @Test
    void theThreeArgumentHelperCallStillExistsAndObservesNothing() throws Exception {
        Method original = GenericLoginHelper.class.getMethod(
            "login", WebsiteLoginConfig.class, String.class, String.class);
        assertEquals(3, original.getParameterCount());

        Method observed = GenericLoginHelper.class.getMethod(
            "login", WebsiteLoginConfig.class, String.class, String.class,
            GenericLoginHelper.Observer.class);
        assertEquals(4, observed.getParameterCount());

        // Every callback on the no-op observer is safe to call and records nothing.
        GenericLoginHelper.Observer none = GenericLoginHelper.Observer.NONE;
        none.openedLoginPage(1);
        none.loginPageOpenFailed(new IllegalStateException("x"), 1);
        none.resolvedControl(LoginElementDetector.Role.USERNAME, "DETECTED", 1);
        none.controlNotResolved(LoginElementDetector.Role.USERNAME,
            GenericLoginHelper.Outcome.USERNAME_NOT_FOUND, "none", 1);
        none.typedUsername(1);
        none.typedPassword(1);
        none.clickedSubmit(1);
        none.interactionFailed(GenericLoginHelper.Interaction.CLICK_SUBMIT,
            new IllegalStateException("x"), 1);
    }

    @Test
    void theHelperDoesNotDependOnTheEvidencePackage() throws IOException {
        String helper = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/login/GenericLoginHelper.java"));

        assertFalse(helper.contains("com.qanize.automation.evidence"),
            "the browser layer must not depend on the evidence layer");
    }

    // --- 18, 19, 20, 21, 22: structure and isolation -----------------------------------------------------------------

    @Test
    void thereAreStillExactlySixChecksAndNoNewDirectory() throws IOException {
        {
            List<String> names = CheckSources.entryClassFileNames();
            assertEquals(7, names.size(), "the seven customer-facing checks, and nothing else: " + names);
            assertEquals(1, names.stream().filter(name -> name.contains("Login")).count());
        }
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/login")));
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/auth")));
    }

    /** Where the sign-in flow now lives; the wrapper keeps only the JUnit face. */
    private static final Path LOGIN_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/login/LoginModule.java");

    @Test
    void theWrapperStaysAThinJUnitFaceOverTheModule() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericLoginTest"));

        // Surefire classification and the credential transport depend on these staying here.
        assertTrue(wrapper.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertTrue(wrapper.contains("assumeTrue(config.tests().login()"),
            "the selected-check gate cannot move into a module");
        assertTrue(wrapper.contains("assumeTrue(login.enabled()"), "the enabled gate stays here too");
        assertTrue(wrapper.contains("protectFromScreenshots(username)"), wrapper);
        assertTrue(wrapper.contains("protectFromScreenshots(password)"), wrapper);
        assertTrue(wrapper.contains("new LoginModule(driver, WebsiteConfigLoader.timeout(config), execution)"),
            "the same recorder instance is passed on, so sequence numbers continue");

        // And implements none of the sign-in flow itself.
        for (String moved : List.of(
            "GenericLoginHelper", "TestedItem.builder(", "SensitiveDataFilter",
            "execution.readFinalAddress(", "execution.assertedOutcome(", "maskIdentifier")) {
            assertFalse(wrapper.contains(moved), moved + " belongs to LoginModule now");
        }
    }

    @Test
    void noOtherCheckUsesTheLoginRecorder() throws IOException {
        for (String other : List.of(
            "GenericSmokeTest", "GenericNavigationTest", "GenericLinkValidationTest",
            "GenericFormDetectionTest", "GenericSearchTest"
        )) {
            assertFalse(
                Files.readString(CheckSources.entryClass(other)).contains("LoginExecutionEvidence"),
                other + " must not record through the Login check's recorder"
            );
        }
        assertTrue(Files.readString(CheckSources.entryClass("GenericLoginTest"))
            .contains("LoginExecutionEvidence"));
    }

    @Test
    void loginSharesTheFileWithTheOtherChecksWithoutColliding() throws IOException {
        LoginExecutionEvidence login = recording();
        new SmokeExecutionEvidence().navigated("https://example.com/", 10);
        new SearchExecutionEvidence().typedSearchTerm("input", "shoes", 20);
        login.typedPassword(30);

        List<String> all = lines();
        assertEquals(3, all.size());
        assertTrue(all.get(2).contains("\"checkId\":\"LOGIN\""), all.get(2));
        assertTrue(all.get(2).contains("\"testCaseId\":\"login-1\""), all.get(2));
    }

    @Test
    void contractOneEvidenceStaysReadableAlongsideLoginSteps() throws IOException {
        LoginExecutionEvidence execution = recording();
        Path contractOne = runDirectory.resolve("evidence.jsonl");

        EvidenceRecorder.record(TestedItem.builder("LOGIN", TestedItem.ItemType.LOGIN)
            .id("login-1").name("Configured sign-in")
            .status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso()).build());
        execution.typedUsername(5);

        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
        assertFalse(result.hasParseErrors());
    }

    @Test
    void everyRecordedActionFollowsTheCallThatPerformedIt() throws IOException {
        String helper = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/login/GenericLoginHelper.java"));

        assertOrdered(helper, "page.openUrl(config.loginUrl().toString());", "observer.openedLoginPage(");
        assertOrdered(helper, "usernameField.sendKeys(username);", "observer.typedUsername(");
        assertOrdered(helper, "passwordField.sendKeys(password);", "observer.typedPassword(");
        assertOrdered(helper, "safeClick.clickOnce(submitControl);", "observer.clickedSubmit(");
        // Each observed failure path is named individually, so approving a new one is a visible
        // edit to this list rather than a number that quietly grows. A global count of
        // `throw failure;` used to stand in for this and broke the moment the approved
        // click-preparation wait was added — while still not proving which paths existed.
        String normalized = helper.replace("\r\n", "\n");
        for (String observerCall : OBSERVED_FAILURE_PATHS) {
            String block = "catch (RuntimeException failure) {\n            "
                + observerCall + "\n            throw failure;\n        }";
            assertTrue(normalized.contains(block),
                "this path must catch, record, then rethrow the caught instance: " + observerCall);
        }

        // And nothing rethrows outside that list: an unrelated catch added elsewhere fails here
        // instead of silently satisfying the guard.
        assertEquals(OBSERVED_FAILURE_PATHS.size(),
            normalized.split("throw failure;", -1).length - 1,
            "every rethrow must belong to a named, approved failure path");
        assertEquals(OBSERVED_FAILURE_PATHS.size(),
            normalized.split("catch \\(RuntimeException failure\\)", -1).length - 1,
            "every operational catch must belong to a named, approved failure path");
    }

    /**
     * The Login failure paths that record evidence and then rethrow, in flow order.
     *
     * <p>The helper's other catch clauses are its pre-existing control-flow signals
     * ({@code ManualSelectorException} / {@code UnresolvedRoleException}), which return a Result
     * rather than rethrowing and never escape the class, so they are deliberately not here.
     */
    private static final List<String> OBSERVED_FAILURE_PATHS = List.of(
        "observer.loginPageOpenFailed(failure, elapsedMs(started));",
        "observer.interactionFailed(Interaction.TYPE_USERNAME, failure, elapsedMs(started));",
        "observer.interactionFailed(Interaction.TYPE_PASSWORD, failure, elapsedMs(started));",
        "observer.submitNeverBecameClickable(failure, elapsedMs(started));",
        "observer.interactionFailed(Interaction.CLICK_SUBMIT, failure, elapsedMs(started));"
    );

    @Test
    void noObservedFailurePathReplacesOrSwallowsItsException() throws IOException {
        String helper = helperSource().replace("\r\n", "\n");

        // Every operational catch block ends in a rethrow of the caught variable: none constructs
        // a replacement, and none falls through silently.
        java.util.regex.Matcher blocks = java.util.regex.Pattern
            .compile("catch \\(RuntimeException failure\\) \\{(.*?)\\n        \\}", java.util.regex.Pattern.DOTALL)
            .matcher(helper);
        int seen = 0;
        while (blocks.find()) {
            String body = blocks.group(1);
            seen++;
            assertTrue(body.contains("throw failure;"), "must rethrow the caught instance: " + body.trim());
            assertFalse(body.contains("throw new "), "must not replace the exception: " + body.trim());
            assertFalse(body.contains("return "), "must not swallow the failure: " + body.trim());
        }
        assertEquals(OBSERVED_FAILURE_PATHS.size(), seen, "all observed catches were inspected");
    }

    @Test
    void noSuccessIsRecordedBeforeItsOperationCompletes() throws IOException {
        String helper = helperSource().replace("\r\n", "\n");

        // Each success callback sits after the closing brace of its try/catch, so a throw can
        // never reach it.
        for (String pair : List.of(
            "throw failure;\n        }\n        observer.openedLoginPage(elapsedMs(started));",
            "throw failure;\n        }\n        observer.typedUsername(elapsedMs(started));",
            "throw failure;\n        }\n        observer.typedPassword(elapsedMs(started));",
            "throw failure;\n        }\n        observer.awaitedSubmitClickable(elapsedMs(started));",
            "throw failure;\n        }\n        observer.clickedSubmit(elapsedMs(started));"
        )) {
            assertTrue(helper.contains(pair),
                "the success record must follow the try/catch: " + pair.split("\n")[2].trim());
        }
    }

    private static void assertOrdered(String source, String earlier, String later) {
        int first = source.indexOf(earlier);
        int second = source.indexOf(later, first < 0 ? 0 : first);
        assertTrue(first >= 0, "missing: " + earlier);
        assertTrue(second > first, earlier + " must appear before " + later);
    }
}
