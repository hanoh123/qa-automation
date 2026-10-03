package com.qanize.automation.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import com.qanize.automation.runtime.AutomationRunRequest;
import com.qanize.automation.runtime.RuntimeConfigGenerator;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.checks.forms.WebsiteFormInteractionConfig;


/**
 * The one configured form field the Forms check may fill in: its configuration, its transport, and
 * the boundary that stops it from touching anything else.
 *
 * <p>The absent state carries most of the weight. A form interaction that is not configured has to
 * be indistinguishable from a feature that was never built — the Forms check must behave exactly as
 * it did when it only discovered forms.
 */
@Tag("unit")
class FormInteractionTest {

    private static final Path CHECK =
        Path.of("src/test/java/com/qanize/automation/checks/forms/GenericFormDetectionTest.java");
    private static final Path EVIDENCE =
        Path.of("src/main/java/com/qanize/automation/checks/forms/FormExecutionEvidence.java");
    /** Where the interaction now lives; CHECK keeps only the JUnit face. */
    private static final Path MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/forms/FormsModule.java");

    // ---------------------------------------------------------------- configuration

    @Test
    void allThreeValuesBlankDisablesTheInteractionWithoutAnError() {
        WebsiteFormInteractionConfig form = new WebsiteFormInteractionConfig("  ", "", null);
        assertFalse(form.enabled(), "a blank configuration is off, not broken");
        assertFalse(WebsiteFormInteractionConfig.disabled().enabled());
    }

    @Test
    void aFullyConfiguredInteractionIsEnabledAndTrimmed() {
        WebsiteFormInteractionConfig form =
            new WebsiteFormInteractionConfig("  #contact ", " #email ", "  qa@example.com ");
        assertTrue(form.enabled());
        assertEquals("#contact", form.formSelector());
        assertEquals("#email", form.fieldSelector());
        assertEquals("qa@example.com", form.testValue());
    }

    @Test
    void theDisplayNameIsNeverASelectorOrTheTestValue() {
        WebsiteFormInteractionConfig form =
            new WebsiteFormInteractionConfig("#contact", "#email", "qa@example.com");
        assertEquals("Configured form field", form.displayName());
        assertFalse(form.displayName().contains("#"), "a selector is a locator, never a name");
        assertFalse(form.displayName().contains("qa@example.com"), "the value is not a name");
    }

    @Test
    void anyPartialConfigurationIsRejectedWithOneStableCode() {
        // Each of the six partial shapes is a configuration mistake, not a degraded mode.
        String[][] partials = {
            {"#contact", "", ""}, {"", "#email", ""}, {"", "", "value"},
            {"#contact", "#email", ""}, {"#contact", "", "value"}, {"", "#email", "value"},
        };
        for (String[] partial : partials) {
            RunConfigurationException failure = assertThrows(RunConfigurationException.class,
                () -> WebsiteConfigLoader.load(configWith(partial[0], partial[1], partial[2]), "test"),
                "expected rejection for " + List.of(partial));
            assertEquals("FORM_INTERACTION_INCOMPLETE", failure.code());
        }
    }

    @Test
    void anOverlongSelectorIsRejectedWithoutEchoingIt() {
        String enormous = "a".repeat(WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH + 1);
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(configWith(enormous, "#email", "value"), "test"));
        assertEquals("FORM_SELECTOR_TOO_LONG", failure.code());
        assertFalse(failure.userMessage().contains(enormous), "the rejected value is not echoed");
        assertFalse(failure.suggestedAction().contains(enormous));
    }

    @Test
    void anOverlongFieldSelectorAndTestValueAreRejectedWithoutEchoingThem() {
        String longField = "b".repeat(WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH + 1);
        RunConfigurationException field = assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(configWith("#contact", longField, "value"), "test"));
        assertEquals("FORM_FIELD_SELECTOR_TOO_LONG", field.code());
        assertFalse(field.userMessage().contains(longField));

        String longValue = "c".repeat(WebsiteFormInteractionConfig.MAX_TEST_VALUE_LENGTH + 1);
        RunConfigurationException value = assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(configWith("#contact", "#email", longValue), "test"));
        assertEquals("FORM_TEST_VALUE_TOO_LONG", value.code());
        assertFalse(value.userMessage().contains(longValue), "the test value is never echoed");
        assertFalse(value.suggestedAction().contains(longValue));
    }

    @Test
    void valuesExactlyAtTheLimitAreAccepted() throws IOException {
        WebsiteConfig config = WebsiteConfigLoader.load(configWith(
            "a".repeat(WebsiteFormInteractionConfig.MAX_SELECTOR_LENGTH),
            "#email",
            "c".repeat(WebsiteFormInteractionConfig.MAX_TEST_VALUE_LENGTH)), "test");
        assertTrue(config.formInteraction().enabled());
    }

    @Test
    void theRequestValidatorRejectsAPartialInteractionBeforeABrowserStarts() {
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(requestWith("#contact", "", "")));
        assertEquals("FORM_INTERACTION_INCOMPLETE", failure.code());
    }

    @Test
    void theRequestValidatorAcceptsAllBlankAndAllPresent() {
        RunConfigurationValidator.validate(requestWith("", "", ""));
        RunConfigurationValidator.validate(requestWith("#contact", "#email", "qa@example.com"));
    }

    // ---------------------------------------------------------------- transport

    @Test
    void allThreeValuesTravelThroughThePropertiesFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("generated-run.properties");
        new RuntimeConfigGenerator()
            .generate(requestWith("#contact", "#email", "qa@example.com"), file);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        assertEquals("#contact", properties.getProperty("form.selector"));
        assertEquals("#email", properties.getProperty("form.fieldSelector"));
        assertEquals("qa@example.com", properties.getProperty("form.testValue"));
    }

    @Test
    void theFormKeysArePresentAndBlankWhenNothingIsConfigured(@TempDir Path directory)
        throws IOException {
        Path file = directory.resolve("generated-run.properties");
        new RuntimeConfigGenerator().generate(requestWith("", "", ""), file);
        String written = Files.readString(file);
        // The file shape must not vary between runs, or a reader has to guess why a key vanished.
        assertTrue(written.contains("form.selector"));
        assertTrue(written.contains("form.fieldSelector"));
        assertTrue(written.contains("form.testValue"));
    }

    @Test
    void theRunConfigSnapshotCarriesTheInteraction(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("run-config.json");
        new RuntimeConfigGenerator().writeRequestSnapshotIfAbsent(
            requestWith("#contact", "#email", "qa@example.com"), file);
        String json = Files.readString(file);
        assertTrue(json.contains("\"formInteraction\""), json);
        assertTrue(json.contains("\"selector\": \"#contact\""), json);
        assertTrue(json.contains("\"fieldSelector\": \"#email\""), json);
        assertTrue(json.contains("\"testValue\": \"qa@example.com\""), json);
    }

    @Test
    void aConfiguredInteractionSurvivesTheWholeLoaderPath() throws IOException {
        WebsiteConfig config =
            WebsiteConfigLoader.load(configWith("#contact", "#email", "qa@example.com"), "test");
        assertTrue(config.formInteraction().enabled());
        assertEquals("#email", config.formInteraction().fieldSelector());
    }

    // ---------------------------------------------------------------- absent means untouched

    @Test
    void anAbsentInteractionYieldsNoDynamicTestAtAll() throws IOException {
        String source = Files.readString(CHECK);
        String factory = methodBody(source, "Stream<DynamicTest> configuredFieldAcceptsTheTestValue");
        assertTrue(factory.contains("return Stream.empty();"),
            "a disabled interaction must yield no dynamic test at all");
        // A skipped test would still appear in the suite's counts; an empty stream does not.
        assertFalse(factory.contains("assumeTrue(false"),
            "the disabled state must not surface as a skip");
    }

    @Test
    void theDiscoveryCheckIsUnchangedByThisFeature() throws IOException {
        // The discovery path moved into the module; reading CHECK would prove nothing about it.
        String discovery = methodBody(
            Files.readString(MODULE), "public Result inspectDiscoveredForms");
        for (String leaked : List.of("formInteraction", "sendKeys", "clear()", "testValue")) {
            assertFalse(discovery.contains(leaked),
                leaked + " must not appear in the discovery-only path");
        }
    }

    // ---------------------------------------------------------------- the interaction boundary

    @Test
    void theFormIsNeverSubmitted() throws IOException {
        // Whole-module rather than one method: no Forms path may submit, click or press a key,
        // and the wrapper must not have acquired one either.
        String module = Files.readString(MODULE);
        String wrapper = Files.readString(CHECK);
        for (String source : List.of(module, wrapper)) {
            for (String forbidden : List.of(".submit()", "Keys.ENTER", "Keys.RETURN", "SafePointerClick")) {
                assertFalse(source.contains(forbidden),
                    forbidden + " must never appear in the form interaction path");
            }
            assertEquals(0, occurrences(source, ".click()"),
                "the interaction must not click anything, including a submit button");
        }
    }

    @Test
    void theFieldIsClearedTypedAndVerified() throws IOException {
        String module = Files.readString(MODULE);
        // The page adapter is where clearing and typing actually happen, exactly once each.
        String adapter = methodBody(module, "public void clearAndType");
        assertEquals(1, occurrences(adapter, ".clear()"), "the field is cleared once");
        assertEquals(1, occurrences(adapter, ".sendKeys(value)"),
            "the configured value is typed exactly once");
        assertEquals(1, occurrences(module, "sendKeys("),
            "there is exactly one typing call in the whole check");
        // And the interaction compares what came back with what it asked to be typed.
        assertTrue(methodBody(module, "public Result fillConfiguredField")
                .contains("testValue.equals(actions.readBackFieldValue())"),
            "the field must be read back and compared with what was typed");
    }

    @Test
    void aSensitiveFieldIsRefusedBeforeAnythingIsTyped() throws IOException {
        String interaction = methodBody(
            Files.readString(MODULE), "public Result fillConfiguredField");
        int guard = interaction.indexOf("SensitiveDataFilter.isSensitiveField(");
        int typing = interaction.indexOf("actions.clearAndType(");
        assertTrue(guard >= 0, "the sensitive-field guard must exist");
        assertTrue(typing >= 0, "the typing call must exist");
        assertTrue(guard < typing, "the guard must run before anything is typed");
        assertTrue(interaction.contains("refusedSensitiveField("),
            "refusing must be recorded, not silent");
    }

    // ---------------------------------------------------------------- contract 1.1 evidence

    @Test
    void theTypingStepUsesTheExistingActionAndTypeContract() throws IOException {
        String body = methodBody(Files.readString(EVIDENCE), "ExecutionStep clearedAndTypedTestValue");
        assertTrue(body.contains("Phase.ACTION"), "typing is an ACTION");
        assertTrue(body.contains("ExecutionMethod.TYPE"), "typing uses the TYPE method");
        assertTrue(body.contains("ExecutionStep.Status.PASSED"));
    }

    @Test
    void theEvidenceNeverRecordsTheConfiguredValue() throws IOException {
        String source = Files.readString(EVIDENCE);
        String typed = methodBody(source, "ExecutionStep clearedAndTypedTestValue");
        String verified = methodBody(source, "ExecutionStep verifiedFieldValue");
        // The value reaches the recorder only as a length; the parameter itself is an int.
        assertTrue(typed.contains("valueLength"), "only the length of the value is reported");
        assertFalse(typed.contains("testValue"), "the value itself must never be recorded");
        assertFalse(verified.contains("expectedValue") || verified.contains("actualValue"),
            "a mismatch must not leak either value");
    }

    // ---------------------------------------------------------------- eligibility and cleanup

    @Test
    void everyIneligibleTargetIsRefusedBeforeAnythingIsTyped() throws IOException {
        String interaction = methodBody(
            Files.readString(MODULE), "public Result fillConfiguredField");
        int typing = interaction.indexOf("actions.clearAndType(");
        assertTrue(typing >= 0, "the typing call must exist");
        // Six reasons a control must never be typed into. Each is decided before the keystrokes,
        // so none of them can reach the page and then be explained away afterwards.
        for (String guard : List.of(
            "!located.formFound()",
            "located.formMatches() > 1",
            "!located.fieldFound()",
            "located.fieldMatches() > 1",
            "!(located.displayed() && located.enabled())",
            "located.readOnly()"
        )) {
            int at = interaction.indexOf(guard);
            assertTrue(at >= 0, "missing eligibility guard: " + guard);
            assertTrue(at < typing, guard + " must be decided before anything is typed");
        }
    }

    @Test
    void cleanupReusesTheOneTypingPathRatherThanAddingASecond() throws IOException {
        String module = Files.readString(MODULE);
        // Restoring writes to the page, so it must go through the same audited call the
        // interaction uses. A second way to type would need its own guards, and would get them
        // wrong sooner or later.
        assertEquals(1, occurrences(module, "sendKeys("),
            "there is still exactly one typing call in the whole check");
        assertTrue(methodBody(module, "private Cleanup restoreOriginalValue")
                .contains("actions.clearAndType("),
            "cleanup must restore through the one typing path");
        // And it cannot take the interaction's verdict with it.
        assertFalse(methodBody(module, "private Cleanup restoreOriginalValue").contains("throw "),
            "a cleanup failure is reported, never rethrown");
    }

    @Test
    void theOriginalValueIsReadOnceAndNeverRecorded() throws IOException {
        String interaction = methodBody(
            Files.readString(MODULE), "public Result fillConfiguredField");
        int captured = interaction.indexOf("String originalValue = actions.readBackFieldValue()");
        int sensitiveGuard = interaction.indexOf("SensitiveDataFilter.isSensitiveField(");
        assertTrue(captured >= 0, "the original value must be captured before typing");
        assertTrue(sensitiveGuard >= 0 && sensitiveGuard < captured,
            "a sensitive field is refused before its value is ever read");
        // It reaches the recorder as a length, exactly like the typed value does.
        assertTrue(interaction.contains("lengthOf(originalValue)"),
            "only the length of the original value is reported");
        String evidence = Files.readString(EVIDENCE);
        for (String method : List.of("ExecutionStep capturedOriginalValue",
                                     "ExecutionStep restoredOriginalValue")) {
            // The recorder is given an int. There is no parameter a value could arrive through.
            assertTrue(signatureOf(evidence, method).contains("int valueLength"),
                method + " must take the length, not the value");
            assertTrue(methodBody(evidence, method).contains("valueLength"),
                method + " reports that length");
        }
    }

    @Test
    void cleanupIsRecordedUnderTheCleanupPhaseAndNeverAsAWebsiteFinding() throws IOException {
        String evidence = Files.readString(EVIDENCE);
        for (String method : List.of("ExecutionStep restoredOriginalValue",
                                     "ExecutionStep restorationFailed")) {
            String body = methodBody(evidence, method);
            assertTrue(body.contains("Phase.CLEANUP"), method + " belongs to the CLEANUP phase");
            assertFalse(body.contains("WEBSITE_FINDING"),
                method + " must not blame the website for a cleanup QANIZE could not finish");
            assertTrue(body.contains("INFRASTRUCTURE_ERROR"),
                method + " reports a cleanup problem as QANIZE's own");
        }
    }

    @Test
    void theInteractionUsesItsOwnTestCaseIdSoDiscoveryCountsCannotMove() throws IOException {
        String source = Files.readString(EVIDENCE);
        assertTrue(source.contains("INTERACTION_TEST_CASE_ID = \"forms-interaction\""));
        assertTrue(source.contains("DISCOVERY_TEST_CASE_ID = \"forms-discovery\""),
            "the discovery id is unchanged");
    }

    @Test
    void noNewContractVocabularyWasIntroduced() throws IOException {
        String source = Files.readString(EVIDENCE);
        // Every enum reference in this file must already exist in the frozen 1.1 model.
        List<String> allowedPhases = List.of(
            "SETUP", "ACTION", "WAIT", "DISCOVERY", "ASSERTION", "EVIDENCE", "CLEANUP");
        for (String phase : phasesUsed(source)) {
            assertTrue(allowedPhases.contains(phase), "unknown Phase value: " + phase);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> phasesUsed(String source) {
        return java.util.regex.Pattern.compile("Phase\\.([A-Z_]+)")
            .matcher(source).results().map(match -> match.group(1)).distinct().toList();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0;
             index = haystack.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** The parameter list as written, so a guard can assert on types rather than on prose. */
    private static String signatureOf(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method not found: " + signature);
        int open = source.indexOf('(', start);
        int close = source.indexOf(')', open);
        return source.substring(open, close + 1);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method not found: " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') depth++;
            else if (character == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new AssertionError("unbalanced method body: " + signature);
    }

    private static InputStream configWith(String selector, String field, String value) {
        String document = String.join("\n",
            "site.url=https://example.com/",
            "form.selector=" + selector,
            "form.fieldSelector=" + field,
            "form.testValue=" + value) + "\n";
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    private static AutomationRunRequest requestWith(String selector, String field, String value) {
        AutomationRunRequest base = AutomationRunRequest.simple("https://example.com/");
        return new AutomationRunRequest(
            base.schemaVersion(), base.siteUrl(), base.viewportPreset(),
            base.viewportWidth(), base.viewportHeight(), base.maxPages(), base.maxLinks(),
            base.loginEnabled(), base.loginUrl(), base.loginUsernameEnv(), base.loginPasswordEnv(),
            base.loginUsernameSelector(), base.loginPasswordSelector(), base.loginSubmitSelector(),
            base.loginSuccessUrlContains(), base.loginSuccessText(),
            base.smokeTest(), base.navigationTest(), base.linksTest(), base.searchTest(),
            base.formsTest(), base.loginTest(),
            base.searchEnabled(), base.searchInputSelector(), base.searchSubmitSelector(),
            base.searchResultsSelector(), base.searchQuery(),
            base.menuTriggerSelector(), base.menuPanelSelector(), base.menuLabel(),
            selector, field, value
        );
    }
}
