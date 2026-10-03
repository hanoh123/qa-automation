package com.qanize.automation.checks.login;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.TestedItem;
import com.qanize.automation.runtime.AutomationRunRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Pins the separation between the raw address the Login check reasons about and the sanitized copy
 * it publishes.
 *
 * <p>The Login check reads {@code driver.getCurrentUrl()} once, after submitting the sign-in form.
 * That single value has two very different jobs: it decides whether the configured success
 * condition was met, and it is written into the run artifact as {@code metadata.finalUrl}. The
 * first job needs the address exactly as the browser reported it; the second must never carry a
 * token or session identifier. This test proves both, without a browser and without a credential.
 *
 * <p>All values here are synthetic.
 */
@Tag("unit")
class LoginUrlSanitizationTest {

    private static final Path LOGIN_CHECK =
        Path.of("src/test/java/com/qanize/automation/checks/login/GenericLoginTest.java");
    /** Where the address handling now lives; LOGIN_CHECK keeps only the JUnit face. */
    private static final Path LOGIN_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/login/LoginModule.java");

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    /** Writes a login item exactly as the check does, and returns the persisted line. */
    private String persistLoginItem(String rawFinalUrl) throws IOException {
        Path evidence = runDirectory.resolve("evidence.jsonl");
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY, evidence.toString());

        TestedItem.Builder item = TestedItem.builder("LOGIN", TestedItem.ItemType.LOGIN)
            .id("login-1")
            .name("Configured sign-in")
            .status(TestedItem.Status.PASSED)
            .completedAt(EvidenceRecorder.nowIso());
        String safeFinalUrl = SensitiveDataFilter.sanitizeUrl(rawFinalUrl);
        if (safeFinalUrl != null && !safeFinalUrl.isBlank()) item.meta("finalUrl", safeFinalUrl);
        EvidenceRecorder.record(item.build());

        List<String> lines = Files.readAllLines(evidence);
        assertEquals(1, lines.size());
        return lines.getFirst();
    }

    // --- what reaches the artifact -----------------------------------------------------------

    @Test
    void anOrdinaryFinalUrlIsPersistedUnchanged() throws IOException {
        String raw = "https://example.com/dashboard?tab=profile";

        assertEquals(raw, SensitiveDataFilter.sanitizeUrl(raw));
        assertTrue(persistLoginItem(raw).contains("\"metadata.finalUrl\":\"" + raw + "\""),
            "a normal address must survive byte-for-byte");
    }

    @Test
    void aCredentialShapedQueryValueIsRedactedBeforePersistence() throws IOException {
        String line = persistLoginItem(
            "https://example.com/dashboard?token=synthetic-secret&tab=profile");

        assertFalse(line.contains("synthetic-secret"), line);
        assertTrue(line.contains("token=[redacted]"), line);
    }

    @Test
    void onlySensitiveParametersAreRedactedAndSafeOnesSurvive() throws IOException {
        String line = persistLoginItem(
            "https://example.com/app?session=synthetic-session&page=3&sort=name&csrf=synthetic-secret");

        assertFalse(line.contains("synthetic-session"), line);
        assertFalse(line.contains("synthetic-secret"), line);
        assertTrue(line.contains("session=[redacted]"), line);
        assertTrue(line.contains("csrf=[redacted]"), line);
        assertTrue(line.contains("page=3"), line);
        assertTrue(line.contains("sort=name"), line);
    }

    @Test
    void embeddedUserInfoNeverReachesTheArtifact() throws IOException {
        String line = persistLoginItem("https://someone:synthetic-secret@example.com/dashboard");

        assertFalse(line.contains("synthetic-secret"), line);
        assertFalse(line.contains("someone"), line);
        assertTrue(line.contains("example.com/dashboard"), line);
    }

    @Test
    void aPostSignInFragmentTokenNeverReachesTheArtifact() throws IOException {
        // The shape an OAuth implicit-flow redirect leaves in the address bar after signing in.
        String line = persistLoginItem(
            "https://example.com/#access_token=synthetic-secret&token_type=bearer");

        assertFalse(line.contains("synthetic-secret"), line);
        assertTrue(line.contains("access_token=[redacted]"), line);
    }

    @Test
    void blankAndMalformedAddressesStayHarmless() throws IOException {
        assertNull(SensitiveDataFilter.sanitizeUrl(null));
        assertNull(SensitiveDataFilter.sanitizeUrl("   "));
        assertEquals("not a url", SensitiveDataFilter.sanitizeUrl("not a url"));
        assertEquals("/relative?token=[redacted]", SensitiveDataFilter.sanitizeUrl("/relative?token=synthetic-secret"));

        // A blank address is simply omitted, exactly as before the fix.
        Path evidence = runDirectory.resolve("evidence.jsonl");
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY, evidence.toString());
        TestedItem.Builder item = TestedItem.builder("LOGIN", TestedItem.ItemType.LOGIN)
            .id("login-1").status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso());
        String safe = SensitiveDataFilter.sanitizeUrl("   ");
        if (safe != null && !safe.isBlank()) item.meta("finalUrl", safe);
        EvidenceRecorder.record(item.build());

        assertFalse(Files.readAllLines(evidence).getFirst().contains("finalUrl"));
    }

    // --- what the check still reasons about ----------------------------------------------------

    @Test
    void theSuccessConditionStillComparesTheRawAddress() throws IOException {
        String source = Files.readString(LOGIN_MODULE);

        // Both the wait and the assertion must read finalUrl, never the sanitized copy: redacting
        // before comparing could turn a passing sign-in into a failure. They now share one named
        // helper, so there is a single place where the raw address meets the configured text.
        assertTrue(source.contains("private static boolean addressContains(String url, String expected) {"),
            "the comparison lives in one named helper");
        assertTrue(source.contains("return url != null && url.contains(expected);"),
            "and that helper compares the address it was handed");
        assertTrue(source.contains("addressContains(finalUrl, expectedUrl)"),
            "the success evaluation must use the raw address");
        assertFalse(source.contains("addressContains(safeFinalUrl"),
            "the sanitized copy must never reach the comparison");
    }

    @Test
    void aRedactedParameterWouldNotHaveMatchedARawSuccessCondition() {
        // Concrete demonstration of why the comparison must stay raw.
        String raw = "https://example.com/home?session=synthetic-session";
        String sanitized = SensitiveDataFilter.sanitizeUrl(raw);

        assertTrue(raw.contains("synthetic-session"));
        assertFalse(sanitized.contains("synthetic-session"));
        // A user configuring successUrlContains against the real address still passes.
        assertTrue(raw.contains("/home"));
        assertTrue(sanitized.contains("/home"), "the recognisable part survives for the reader");
    }

    @Test
    void onlyTheSanitizedCopyIsPersisted() throws IOException {
        String source = Files.readString(LOGIN_MODULE);

        assertTrue(source.contains("String safeFinalUrl = SensitiveDataFilter.sanitizeUrl(finalUrl);"),
            "the sanitized copy is computed separately from the raw value");
        assertTrue(source.contains("item.meta(\"finalUrl\", safeFinalUrl)"),
            "the artifact receives only the sanitized copy");
        assertFalse(source.contains("item.meta(\"finalUrl\", finalUrl)"),
            "the raw address must never be persisted");
    }

    // --- nothing else moved ----------------------------------------------------------------------

    @Test
    void loginBehaviourAndContractKeysAreUnchanged() throws IOException {
        String source = Files.readString(LOGIN_MODULE);

        // Status rules, test-case id and the metadata key are all untouched.
        assertTrue(source.contains(".id(\"login-1\")"));
        assertTrue(source.replaceAll("\\s+", " ").contains(
            "boolean ok = failure == null && submitted && urlOk && successTextProved"
                + " && successConditionConfigured;"));
        // The URL condition keeps its own place in that AND: a failed text transition must never
        // be compensated for by loosening the address check, and vice versa. The fourth term is
        // the one that stops "nothing was configured" from reading as "everything held".
        assertTrue(source.contains("boolean urlOk = expectedUrl == null || addressContains(finalUrl, expectedUrl);"),
            "the URL success rule is still computed independently");
        assertTrue(source.contains("item.meta(\"finalUrl\""), "the contract key is still finalUrl");
        assertEquals("1.0",
            com.qanize.automation.runtime.AutomationRunRequest.CONTRACT_VERSION);
    }

    /**
     * Every path that publishes the address goes through the sanitizer.
     *
     * <p>This replaces a slice-boundary assertion that required Login to stay uninstrumented. That
     * expectation expired the moment Login instrumentation was approved, and it was the wrong
     * thing for this file to guard anyway — which checks are instrumented belongs to
     * {@code NavigationExecutionEvidenceTest#onlyApprovedChecksAreInstrumented}, and restating it
     * here is exactly the duplication that has broken guards twice already.
     *
     * <p>What is permanent, and what this file exists for, is the boundary itself: adding a second
     * consumer of the address must not create a second way to leak it. There are now two sinks —
     * the contract 1.0 metadata and the contract 1.1 execution step — and neither may receive the
     * raw value.
     */
    @Test
    void everyAddressSinkGoesThroughTheSanitizer() throws IOException {
        // Whitespace-normalized so a line wrap in the check cannot make this pass or fail for the
        // wrong reason; only the call shape matters.
        String check = Files.readString(LOGIN_MODULE).replaceAll("\\s+", " ");

        // Sink 1: contract 1.0 metadata.
        assertTrue(check.contains("item.meta(\"finalUrl\", safeFinalUrl)"));
        assertFalse(check.contains("item.meta(\"finalUrl\", finalUrl)"),
            "the 1.0 artifact must never receive the raw address");

        // Sink 2: contract 1.1 execution evidence, added by the Login instrumentation slice.
        assertTrue(check.replace("( ", "(")
            .contains("execution.readFinalAddress(SensitiveDataFilter.sanitizeUrl(finalUrl)"));
        assertFalse(check.contains("execution.readFinalAddress(finalUrl"),
            "the 1.1 step must never receive the raw address");

        // One sanitizer call per sink: a third consumer added without one fails here.
        assertEquals(2, check.split("SensitiveDataFilter\\.sanitizeUrl\\(finalUrl\\)", -1).length - 1,
            "each address consumer must sanitize, and none may share another's call");

        // And the raw value still drives the comparison, through the helper the wait calls on
        // every pass. Sanitizing before comparing could fail a sign-in that actually succeeded.
        assertTrue(check.contains("addressContains(finalUrl, expectedUrl)"));
    }

    /**
     * The contract 1.1 address step cannot leak, independently of how the check calls it.
     *
     * <p>Behavioural rather than structural: even handed a credential-shaped address, the recorder
     * persists nothing sensitive.
     */
    @Test
    void theExecutionEvidenceAddressStepPersistsNothingSensitive() throws IOException {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        String raw = "https://someone:synthetic-secret@example.com/account?session=synthetic-session&tab=profile";
        new LoginExecutionEvidence().readFinalAddress(SensitiveDataFilter.sanitizeUrl(raw), true, 4);

        Path steps = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        String line = Files.readAllLines(steps).getFirst();
        assertFalse(line.contains("synthetic-secret"), line);
        assertFalse(line.contains("synthetic-session"), line);
        assertFalse(line.contains("someone"), line);
        assertTrue(line.contains("session=[redacted]"), line);
        assertTrue(line.contains("tab=profile"), line);
    }

    /**
     * The sanitization boundary is a pure function of the string it is handed.
     *
     * <p>This replaces an assertion that scanned <em>this file</em> for {@code System.getenv} and
     * so matched its own search literal — it could never have passed, and it proved nothing about
     * the code it was meant to protect. The invariant that matters lives in the production
     * boundary: sanitizing a URL must not read the environment, must not read system properties,
     * and must not be able to accept a credential at all.
     */
    @Test
    void theSanitizationBoundaryDependsOnNothingButItsArgument() throws Exception {
        String filter = Files.readString(
            Path.of("src/main/java/com/qanize/automation/evidence/SensitiveDataFilter.java"));

        assertFalse(filter.contains("System.getenv"),
            "URL sanitization must never resolve an environment variable");
        assertFalse(filter.contains("System.getProperty"),
            "URL sanitization must never read a system property");

        // One argument, and it is the URL. There is no parameter a username, a password or a
        // resolved environment value could arrive through.
        Method sanitizeUrl = SensitiveDataFilter.class.getDeclaredMethod("sanitizeUrl", String.class);
        assertEquals(1, sanitizeUrl.getParameterCount());
        assertEquals(String.class, sanitizeUrl.getParameterTypes()[0]);
        assertEquals(String.class, sanitizeUrl.getReturnType());

        // Pure: the same synthetic input always produces the same output, with no other state.
        String raw = "https://example.com/app?token=synthetic-secret&page=2";
        assertEquals(SensitiveDataFilter.sanitizeUrl(raw), SensitiveDataFilter.sanitizeUrl(raw));
        assertFalse(SensitiveDataFilter.sanitizeUrl(raw).contains("synthetic-secret"));
    }

    /**
     * The runtime credential path is deliberately left exactly as it was.
     *
     * <p>Login must still resolve its configured references from the child process environment —
     * that is how a credential reaches Selenium without ever entering a config file, an argument
     * list or an artifact. This slice changed what is <em>persisted</em>, nothing else, so these
     * are positive assertions that the mechanism survives intact.
     */
    @Test
    void theRuntimeCredentialResolutionPathIsUnchanged() throws IOException {
        // Resolution and screenshot redaction stay with the browser lifecycle, in the wrapper.
        String login = Files.readString(LOGIN_CHECK);

        assertTrue(login.contains("requiredSecret(login.usernameEnv(), \"login.usernameEnv\")"),
            "the username reference is still resolved at runtime");
        assertTrue(login.contains("requiredSecret(login.passwordEnv(), \"login.passwordEnv\")"),
            "the password reference is still resolved at runtime");
        assertTrue(login.contains("String value = System.getenv(envName);"),
            "resolution still reads the process environment, which is the supported transport");
        assertTrue(login.contains("protectFromScreenshots(username)"));
        assertTrue(login.contains("protectFromScreenshots(password)"));

        // Publishing is the module's job, and it publishes only the masked identifier.
        String module = Files.readString(LOGIN_MODULE);
        assertTrue(module.contains("maskIdentifier(username)"));
        for (String source : List.of(login, module)) {
            assertFalse(source.contains("meta(\"username\""));
            assertFalse(source.contains("meta(\"password\""));
        }
        // The credentials are parameters, never retained: a field would outlive the call.
        assertFalse(module.contains("private final String username"), module);
        assertFalse(module.contains("private final String password"), module);
    }
}
