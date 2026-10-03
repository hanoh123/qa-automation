package com.qanize.automation.checks.login;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Login behaviour, driven without a browser and without real credentials.
 *
 * <p>Every test signs in with obviously fake secrets and then asserts that neither of them, nor any
 * token, reaches a tested item, an execution step or a message. That is the property most worth
 * protecting here: a login check that leaks the password it used is worse than no login check.
 */
@Tag("unit")
class LoginModuleTest {

    /** Fake, and shaped so a leak is unmistakable in any output. */
    private static final String USERNAME = "synthetic-user@example.com";
    private static final String PASSWORD = "synthetic-password-9f2b";

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

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> items() throws IOException {
        Path file = runDirectory.resolve("evidence.jsonl");
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** Read once into a local: an orElseThrow supplier cannot carry a checked exception. */
    private String loginItem() throws IOException {
        List<String> recorded = items();
        for (String line : recorded) {
            if (line.contains("\"id\":\"login-1\"")) return line;
        }
        throw new AssertionError("no login-1 item recorded: " + recorded);
    }

    /** Nothing secret may appear anywhere the run publishes. */
    private void assertNoSecretsAnywhere() throws IOException {
        List<String> everything = new ArrayList<>(items());
        everything.addAll(steps());
        for (String line : everything) {
            assertFalse(line.contains(PASSWORD), "the password leaked: " + line);
            assertFalse(line.contains("synthetic-user"), "the username leaked: " + line);
            assertFalse(line.contains("synthetic-token"), "a token leaked: " + line);
        }
    }

    private static WebsiteLoginConfig login(String successUrlContains, String successText) {
        return new WebsiteLoginConfig(
            true, URI.create("https://example.com/signin"),
            "QANIZE_USER", "QANIZE_PASS", "#user", "#pass", "#submit",
            successUrlContains, successText);
    }

    private LoginModule module(FakeLogin actions, LoginExecutionEvidence execution) {
        return new LoginModule(actions, execution);
    }

    /** The same module with a waiting budget, driven by counted pauses rather than real time. */
    private LoginModule waiting(FakeLogin actions, LoginExecutionEvidence execution, int attempts) {
        return new LoginModule(actions, execution, attempts, actions::tick);
    }

    private static long pauseCount(FakeLogin actions) {
        return actions.performed.stream().filter("pause"::equals).count();
    }

    private static GenericLoginHelper.Result submitted() {
        return new GenericLoginHelper.Result(
            GenericLoginHelper.Outcome.SUBMITTED, null, null,
            Map.of("USERNAME", "configured", "PASSWORD", "configured", "SUBMIT", "configured"));
    }

    private static GenericLoginHelper.Result notResolved(GenericLoginHelper.Outcome outcome, String message) {
        return new GenericLoginHelper.Result(outcome, message, "Check the configured selector.", Map.of());
    }

    // --- the happy path -----------------------------------------------------------------------

    @Test
    void aSubmittedSignInThatMeetsTheSuccessConditionPasses() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard").pageContains("Signed in");

        LoginModule.Result result =
            module(actions, recording()).run(login("/dashboard", "Signed in"), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.PASSED, result.outcome());
        assertNull(result.message(), "a passing result carries no message");
        String item = loginItem();
        assertTrue(item.contains("Signed in and the configured success condition was met."), item);
        assertTrue(item.contains("\"metadata.submitted\":\"true\""), item);
        assertTrue(item.contains("\"metadata.identifierSource\":\"QANIZE_USER\""),
            "the env name is safe to publish; the value is not: " + item);
        assertNoSecretsAnywhere();
    }

    @Test
    void exactlyOneSignInIsAttemptedAndNothingIsRetried() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted()).currentUrl("https://example.com/x");

        module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        assertEquals(1, actions.signInCalls, "a sign-in is attempted exactly once");
    }

    @Test
    void theActionsHappenInTheOrderTheCheckHasAlwaysUsed() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard").pageContains("Welcome");

        module(actions, recording()).run(login("/dashboard", "Welcome"), USERNAME, PASSWORD);

        // Sign in, read the address, then evaluate the configured text condition.
        assertEquals("signIn", actions.performed.get(0));
        assertEquals("currentUrl", actions.performed.get(1));
        assertTrue(actions.performed.contains("baseline"), actions.performed.toString());
        assertTrue(actions.performed.indexOf("currentUrl") < actions.performed.indexOf("pageSource"),
            actions.performed.toString());
    }

    // --- the flow never submitted -------------------------------------------------------------

    @Test
    void aControlThatCouldNotBeResolvedIsAWebsiteResultRatherThanACrash() throws IOException {
        for (GenericLoginHelper.Outcome outcome : List.of(
            GenericLoginHelper.Outcome.USERNAME_NOT_FOUND,
            GenericLoginHelper.Outcome.PASSWORD_NOT_FOUND,
            GenericLoginHelper.Outcome.SUBMIT_NOT_FOUND)) {

            runDirectory.resolve("evidence.jsonl").toFile().delete();
            runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME).toFile().delete();
            String message = "The configured " + outcome.name() + " control was not found.";
            FakeLogin actions = new FakeLogin().attempt(notResolved(outcome, message))
                .currentUrl("https://example.com/signin");

            LoginModule.Result result =
                module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

            assertEquals(LoginModule.Outcome.FAILED, result.outcome());
            assertEquals(message, result.message(),
                "the helper's own explanation is reported, not an invented one");
            String item = loginItem();
            assertTrue(item.contains("\"metadata.submitted\":\"false\""), item);
            assertTrue(item.contains("\"metadata.outcome\":\"" + outcome.name() + "\""), item);
            assertTrue(item.contains("Check the configured selector."), item);
            assertNoSecretsAnywhere();
        }
    }

    @Test
    void anAttemptThatNeverRanIsReportedAsSuch() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(null).currentUrl("https://example.com/signin");

        LoginModule.Result result =
            module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertEquals("The sign-in flow did not run.", result.message());
        assertTrue(loginItem().contains("The sign-in flow did not run."), loginItem());
    }

    // --- browser failures ---------------------------------------------------------------------

    @Test
    void aBrowserFailureDuringSignInIsContainedAndSanitized() throws IOException {
        FakeLogin actions = new FakeLogin()
            .signInThrows(new IllegalStateException(
                "typing failed at https://user:synthetic-token@example.com/signin?token=synthetic-token"))
            .currentUrl("https://example.com/signin");

        LoginModule.Result result =
            module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().startsWith("Login flow failed: "), result.message());
        assertFalse(result.message().contains("synthetic-token"),
            "a credential in an exception message must not reach the verdict: " + result.message());
        String item = loginItem();
        assertTrue(item.contains("The sign-in flow could not be completed."), item);
        assertNoSecretsAnywhere();
    }

    @Test
    void theBrowserFailureOutranksEveryConfiguredCondition() throws IOException {
        FakeLogin actions = new FakeLogin()
            .signInThrows(new IllegalStateException("driver died"))
            .currentUrl("https://example.com/dashboard").pageContains("Signed in");

        LoginModule.Result result =
            module(actions, recording()).run(login("/dashboard", "Signed in"), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().startsWith("Login flow failed: "),
            "the browser failure is reported first: " + result.message());
    }

    // --- the configured success conditions ----------------------------------------------------

    @Test
    void anAddressThatDoesNotContainTheConfiguredTextFails() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/signin?error=1");

        LoginModule.Result result =
            module(actions, recording()).run(login("/dashboard", null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertEquals("Login success URL did not contain configured text: /dashboard",
            result.message());
        assertTrue(loginItem().contains("The website did not accept the credentials:"), loginItem());
    }

    @Test
    void aSubmittedFormWithNoSuccessConditionDoesNotClaimAConditionWasMet() throws IOException {
        // The sentence this pins used to say "the configured success condition was met" for a run
        // that had no condition configured, while the timeline beside it said authentication was
        // never verified. Submitting a form is not signing in, and the item may not say otherwise.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/games");

        LoginModule.Result result = module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        String item = loginItem();
        assertFalse(item.contains("configured success condition was met"),
            "no condition was configured, so none can have been met: " + item);
        assertTrue(item.contains("no success condition was configured"), item);
        assertTrue(item.contains("authentication was never verified"), item);
        // And the verdict follows the sentence. A run that cannot answer its own question does
        // not get to answer it with a pass.
        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertTrue(item.contains("\"status\":\"FAILED\""), item);
    }

    @Test
    void aConfiguredConditionThatHeldStillSaysSo() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard");

        module(actions, recording()).run(login("/dashboard", null), USERNAME, PASSWORD);

        assertTrue(loginItem().contains("Signed in and the configured success condition was met."),
            loginItem());
    }

    @Test
    void successTextAlreadyPresentBeforeSignInFailsTheItemAndTheResultTogether() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard")
            .pageContains("Welcome").textPresentBefore(true);

        LoginModule.Result result =
            module(actions, recording()).run(login(null, "Welcome"), USERNAME, PASSWORD);

        // One decision, three consumers. The recorded item, the module result, and -- through the
        // result the wrapper asserts on -- the JUnit verdict all read the same boolean. A run can
        // no longer contain a FAILED login item while returning a PASSED login result for the
        // same condition, which is what this test used to document as a preserved asymmetry.
        assertTrue(loginItem().contains("\"status\":\"FAILED\""),
            "the recorded verdict applies the transition rule: " + loginItem());
        assertTrue(loginItem().contains("The website did not accept the credentials:"), loginItem());
        assertEquals(LoginModule.Outcome.FAILED, result.outcome(),
            "the terminal result applies the same transition rule as the item");
        assertTrue(result.message().contains("did not prove a post-login transition"),
            "the message says why presence alone proves nothing: " + result.message());
        assertTrue(result.message().contains("already on the page before"), result.message());

        // Asked once, not once per consumer. Two separate reads are how the two verdicts drifted
        // apart, so the count is the guard that keeps them a single source of truth.
        assertEquals(1, actions.performed.stream().filter("pageSource"::equals).count(),
            "the configured success text is evaluated exactly once: " + actions.performed);
        assertEquals(1, actions.signInCalls, "still exactly one submit, and no retry");
        assertNoSecretsAnywhere();
    }

    @Test
    void successTextThatDisappearsAfterSignInAlsoFailsBothWays() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard")
            .pageContains(null).textPresentBefore(true);

        LoginModule.Result result =
            module(actions, recording()).run(login(null, "Welcome"), USERNAME, PASSWORD);

        // Present before, absent after: still no absent-to-present transition, so still a failure,
        // and the item and the result still agree.
        assertTrue(loginItem().contains("\"status\":\"FAILED\""), loginItem());
        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertNoSecretsAnywhere();
    }

    @Test
    void successTextThatNeverAppearsFails() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard").pageContains(null);

        LoginModule.Result result =
            module(actions, recording()).run(login(null, "Welcome"), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertEquals("Login success text was not found: Welcome", result.message());
    }

    @Test
    void withNoConfiguredConditionASubmittedFormIsNotEnough() throws IOException {
        // The overturned premise. Submitting a form used to be the whole verdict, which is how a
        // run that never signed in reported a pass. Nothing here can prove authentication, so
        // nothing here may claim it.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/anywhere");

        LoginModule.Result result =
            module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertTrue(result.message().contains("no success condition was configured"),
            result.message());
        assertFalse(result.authenticationVerified(),
            "nothing was configured, so nothing was verified");
    }

    // --- waiting for the configured condition -------------------------------------------------

    @Test
    void anAddressThatArrivesWhileTheCheckWaitsPasses() throws IOException {
        // The defect this whole section exists for: a website signs in over the network, so the
        // address changes some time after the click. Asking once, immediately, read the page
        // before it had answered and reported the silence as a rejection.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/signin")
            .settlesAfter(3, "https://example.com/dashboard", null);

        LoginModule.Result result =
            waiting(actions, recording(), 10).run(login("/dashboard", null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.PASSED, result.outcome());
        assertTrue(result.authenticationVerified(), "a condition was configured and it held");
        assertEquals(3, pauseCount(actions), "it waited exactly as long as the website took");
        assertTrue(loginItem().contains("\"metadata.finalUrl\":\"https://example.com/dashboard\""),
            "the recorded address is the one the wait settled on: " + loginItem());
        assertNoSecretsAnywhere();
    }

    @Test
    void successTextThatArrivesWhileTheCheckWaitsPasses() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard").pageContains(null)
            .settlesAfter(2, "https://example.com/dashboard", "Welcome back");

        LoginModule.Result result =
            waiting(actions, recording(), 10).run(login(null, "Welcome back"), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.PASSED, result.outcome());
        assertEquals(2, pauseCount(actions));
        assertNoSecretsAnywhere();
    }

    @Test
    void aConditionThatNeverArrivesFailsOnlyAfterTheWholeBudgetIsSpent() throws IOException {
        // A timeout has to be a timeout. Giving up early would turn a slow sign-in into a website
        // finding, which is the failure mode that made the original defect so convincing.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/signin?error=1");

        LoginModule.Result result =
            waiting(actions, recording(), 6).run(login("/dashboard", null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertEquals("Login success URL did not contain configured text: /dashboard",
            result.message());
        assertEquals(5, pauseCount(actions), "six reads, five pauses: the budget was spent");
        assertTrue(loginItem().contains("never appeared before the timeout"), loginItem());
    }

    @Test
    void aConditionThatAlreadyHoldsIsNotWaitedForAtAll() throws IOException {
        // The budget is a ceiling, not a cost. A sign-in that has already landed pays nothing.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard").pageContains("Welcome");

        LoginModule.Result result =
            waiting(actions, recording(), 20).run(login("/dashboard", "Welcome"), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.PASSED, result.outcome());
        assertEquals(0, pauseCount(actions), "nothing to wait for, so nothing was waited for");
    }

    @Test
    void aSuccessTextThatWasAlreadyThereIsNotWaitedFor() throws IOException {
        // Present before the attempt means the condition can never prove a transition, however
        // long it is watched. Waiting on it would spend the whole budget to learn nothing.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard")
            .pageContains("Welcome").textPresentBefore(true);

        LoginModule.Result result =
            waiting(actions, recording(), 20).run(login(null, "Welcome"), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertEquals(0, pauseCount(actions), "an unusable condition is not worth one pause");
        assertTrue(result.message().contains("already on the page before"), result.message());
    }

    @Test
    void aFlowThatNeverSubmittedIsNotWaitedFor() throws IOException {
        // Nothing was submitted, so there is no sign-in in flight to wait for.
        FakeLogin actions = new FakeLogin()
            .attempt(notResolved(GenericLoginHelper.Outcome.PASSWORD_NOT_FOUND, "No password field."))
            .currentUrl("https://example.com/signin");

        LoginModule.Result result =
            waiting(actions, recording(), 20).run(login("/dashboard", null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.FAILED, result.outcome());
        assertEquals("No password field.", result.message());
        assertEquals(0, pauseCount(actions));
    }

    @Test
    void nothingIsWaitedForWhenNothingWasConfiguredToWaitFor() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/anywhere");

        waiting(actions, recording(), 20).run(login(null, null), USERNAME, PASSWORD);

        assertEquals(0, pauseCount(actions), "there is no condition to poll");
    }

    @Test
    void theRecordedAddressStepDescribesTheSettledPageRatherThanTheClick() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/signin")
            .settlesAfter(2, "https://example.com/dashboard", null);

        waiting(actions, recording(), 10).run(login("/dashboard", null), USERNAME, PASSWORD);

        String addressStep = steps().stream()
            .filter(step -> step.contains("Read the browser address"))
            .findFirst().orElseThrow();
        assertTrue(addressStep.contains("https://example.com/dashboard"),
            "the evidence must describe the page the wait settled on: " + addressStep);
        assertFalse(addressStep.contains("/signin"), addressStep);
    }

    // --- what is published --------------------------------------------------------------------

    @Test
    void onlyASanitizedAddressIsEverPersisted() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/home?session=synthetic-token&tab=profile");

        module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        String item = loginItem();
        assertFalse(item.contains("synthetic-token"), "the raw address must never be persisted: " + item);
        assertTrue(item.contains("session=[redacted]"), item);
        assertTrue(item.contains("tab=profile"), "the recognisable part survives for the reader: " + item);
        for (String line : steps()) {
            assertFalse(line.contains("synthetic-token"), line);
        }
    }

    @Test
    void theRawAddressStillDrivesTheSuccessComparison() throws IOException {
        // A redacted address would not have matched, so the comparison must stay raw.
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/home?session=synthetic-token");

        // The condition names a part of the address the sanitizer keeps. Had the comparison run
        // against the sanitized copy, "/home" would still match -- so the sharper proof is the
        // negative one below: the token is gone from the artifact while the match still succeeded.
        LoginModule.Result result =
            module(actions, recording()).run(login("/home", null), USERNAME, PASSWORD);

        assertEquals(LoginModule.Outcome.PASSED, result.outcome(),
            "the comparison reads the raw address, so a configured match still passes");
        assertFalse(loginItem().contains("synthetic-token"),
            "and the artifact still receives only the sanitized copy");
        assertTrue(loginItem().contains("session=[redacted]"), loginItem());
    }

    @Test
    void theIdentifierIsMaskedAndNeitherCredentialIsEverRecorded() throws IOException {
        FakeLogin actions = new FakeLogin().attempt(submitted())
            .currentUrl("https://example.com/dashboard");

        module(actions, recording()).run(login(null, null), USERNAME, PASSWORD);

        String item = loginItem();
        assertTrue(item.contains("\"metadata.identifier\""), "a masked identifier is published: " + item);
        assertFalse(item.contains("\"metadata.username\""), item);
        assertFalse(item.contains("\"metadata.password\""), item);
        assertNoSecretsAnywhere();
    }

    // --- evidence continuity and ownership -----------------------------------------------------

    @Test
    void theModuleContinuesTheSequenceTheWrapperAlreadyStarted() throws IOException {
        LoginExecutionEvidence execution = recording();
        // A step the caller records before handing over.
        execution.loginPageOpenFailed(new IllegalStateException("could not open"), 5);

        module(new FakeLogin().attempt(submitted()).currentUrl("https://example.com/x"), execution)
            .run(login(null, null), USERNAME, PASSWORD);

        List<String> recorded = steps();
        assertTrue(recorded.get(0).contains("\"sequence\":1"), recorded.get(0));
        // The module must not restart at 1: numbering is per test case, not per object.
        assertTrue(recorded.get(1).contains("\"sequence\":2"), recorded.get(1));
        for (String line : recorded) {
            assertTrue(line.contains("\"checkId\":\"LOGIN\""), line);
        }
    }

    @Test
    void theModuleOwnsNoBrowserLifecycleNoCrawlQueueAndMakesNoAssertion() throws IOException {
        String source = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/login/LoginModule.java")));

        for (String forbidden : List.of(
            "org.junit", "assertTrue(", "assertFalse(", "assumeTrue(",
            "new ChromeDriver", "driver.quit", "driver.close",
            "System.getenv", "System.out", "System.err", "printStackTrace",
            "visited", "frontier", "queue", "Crawl",
            "Keys.ENTER", "Keys.RETURN", ".submit()", "executeScript")) {
            assertFalse(source.contains(forbidden), "LoginModule must not contain " + forbidden);
        }
        // Exactly one sign-in call site: a second would be a silent retry.
        assertEquals(1, source.split("actions\\.signIn\\(", -1).length - 1,
            "one sign-in attempt, and only one");
    }

    /** Executable code only: a comment must neither satisfy a guard nor trip one. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * The sign-in work Login performs, scripted.
     *
     * <p>It records what it was asked to do, and deliberately never stores the credentials it is
     * given beyond checking that they arrived.
     */
    private static final class FakeLogin implements LoginModule.LoginActions {

        private final List<String> performed = new ArrayList<>();
        private final Map<String, String> seen = new LinkedHashMap<>();

        private GenericLoginHelper.Result attempt;
        private RuntimeException signInFailure;
        private String currentUrl;
        private String pageContains;
        private boolean textPresentBefore;
        private int signInCalls;
        /** How many pauses the website takes to finish signing in. Zero means it never does. */
        private int settlesAfterPauses;
        private String settledUrl;
        private String settledText;
        private int pauses;

        FakeLogin attempt(GenericLoginHelper.Result result) { this.attempt = result; return this; }
        FakeLogin signInThrows(RuntimeException failure) { this.signInFailure = failure; return this; }
        FakeLogin currentUrl(String url) { this.currentUrl = url; return this; }
        FakeLogin pageContains(String text) { this.pageContains = text; return this; }
        FakeLogin textPresentBefore(boolean present) { this.textPresentBefore = present; return this; }

        /**
         * A website that finishes signing in a moment after the click.
         *
         * <p>The condition is on the page over time, not on how often the check happens to ask.
         * A check that asked once and gave up would see the pre-sign-in answers forever, which is
         * exactly the defect these tests exist to catch.
         */
        FakeLogin settlesAfter(int pauses, String url, String text) {
            this.settlesAfterPauses = pauses;
            this.settledUrl = url;
            this.settledText = text;
            return this;
        }

        /** What the check does between two reads. Counted instead of slept. */
        void tick() {
            pauses++;
            performed.add("pause");
        }

        private boolean settled() {
            return settlesAfterPauses > 0 && pauses >= settlesAfterPauses;
        }

        @Override
        public GenericLoginHelper.Result signIn(
            WebsiteLoginConfig login, String username, String password,
            GenericLoginHelper.Observer observer
        ) {
            signInCalls++;
            performed.add("signIn");
            seen.put("username", username);
            seen.put("password", password);
            if (signInFailure != null) throw signInFailure;
            return attempt;
        }

        @Override
        public Boolean successTextPresentBeforeLogin() {
            performed.add("baseline");
            return textPresentBefore;
        }

        @Override
        public String safeCurrentUrl() {
            performed.add("currentUrl");
            return settled() ? settledUrl : currentUrl;
        }

        @Override
        public boolean pageSourceContains(String expected) {
            performed.add("pageSource");
            String page = settled() ? settledText : pageContains;
            return page != null && page.contains(expected);
        }
    }
}
