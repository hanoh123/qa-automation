package com.qanize.automation.checks.forms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * The Forms behaviour, driven without a browser.
 *
 * <p>Forms reads structure and, when configured, types into one field. Faking those two kinds of
 * page work is enough to exercise discovery, every control state, the interaction and its refusals
 * — with no WebDriver, no Chrome, no external site and no mocking dependency.
 */
@Tag("unit")
class FormsModuleTest {

    private static final String PAGE = "https://example.com/";

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private FormExecutionEvidence recording() {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString()
        );
        return new FormExecutionEvidence();
    }

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> stepsFor(String testCaseId) throws IOException {
        return steps().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    private List<String> items() throws IOException {
        Path file = runDirectory.resolve("evidence.jsonl");
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    /** Read once into a local: an orElseThrow supplier cannot carry a checked exception. */
    private String itemWithId(String id) throws IOException {
        List<String> recorded = items();
        for (String line : recorded) {
            if (line.contains("\"id\":\"" + id + "\"")) return line;
        }
        throw new AssertionError("no item recorded with id " + id + ": " + recorded);
    }

    private FormsModule module(FakeForms actions, FormExecutionEvidence execution) {
        return new FormsModule(actions, PAGE, execution);
    }

    private static FormsModule.DiscoveredControl control(
        String tag, String type, String name, boolean required, boolean displayed, boolean enabled
    ) {
        return new FormsModule.DiscoveredControl(
            tag, type, name, name, name, required, displayed, enabled, false, null, 3);
    }

    // --- discovery ----------------------------------------------------------------------------

    @Test
    void aPageWithNoFormIsReportedAsSkippedRatherThanAPass() throws IOException {
        FormsModule.Result result = module(new FakeForms(), recording()).inspectDiscoveredForms();

        assertEquals(FormsModule.Outcome.SKIPPED, result.outcome());
        assertFalse(result.passed(), "an uninspected page must never read as a pass");
        assertTrue(result.skipped());
        assertTrue(result.message().contains("No forms were discovered"), result.message());
        assertTrue(itemWithId("forms-discovery").contains("\"testType\":\"FORMS\""),
            itemWithId("forms-discovery"));
    }

    @Test
    void everySupportedControlIsDiscoveredAndDescribedByItsStructure() throws IOException {
        FakeForms actions = new FakeForms().form("contact", "contact-form", "/send", "post", true,
            control("input", "text", "fullName", true, true, true),
            control("textarea", null, "message", false, true, true),
            control("select", null, "country", false, true, true),
            control("button", "submit", "send", false, true, true));

        FormsModule.Result result = module(actions, recording()).inspectDiscoveredForms();

        assertEquals(FormsModule.Outcome.PASSED, result.outcome());
        assertNull(result.message(), "a passing result carries no message");
        String form = itemWithId("form-1");
        assertTrue(form.contains("\"metadata.controlCount\":\"4\""), form);
        assertTrue(form.contains("The form was not submitted."), form);
        assertTrue(form.contains("\"metadata.method\":\"post\""), form);
        assertTrue(form.contains("\"target\":\"/send\""), form);
        for (int index = 1; index <= 4; index++) {
            String field = itemWithId("form-1-field-" + index);
            assertTrue(field.contains("No value was entered or read."), field);
            assertTrue(field.contains("\"status\":\"PASSED\""), field);
        }
        assertTrue(itemWithId("form-1-field-1").contains("\"metadata.required\":\"true\""),
            itemWithId("form-1-field-1"));
    }

    @Test
    void aControlThatCannotBeInteractedWithIsSkippedAndSaysWhy() throws IOException {
        FakeForms actions = new FakeForms().form("contact", null, null, null, true,
            control("input", "text", "hidden", false, false, true),
            control("input", "text", "locked", false, true, false));

        FormsModule.Result result = module(actions, recording()).inspectDiscoveredForms();

        assertEquals(FormsModule.Outcome.PASSED, result.outcome(),
            "a non-interactable control is a fact about the page, not a failure");
        String invisible = itemWithId("form-1-field-1");
        assertTrue(invisible.contains("\"status\":\"SKIPPED\""), invisible);
        assertTrue(invisible.contains("The control is not visible."), invisible);
        String disabled = itemWithId("form-1-field-2");
        assertTrue(disabled.contains("\"status\":\"SKIPPED\""), disabled);
        assertTrue(disabled.contains("The control is disabled."), disabled);
    }

    @Test
    void aFormWhoseStructureCouldNotBeReadFailsTheCheck() throws IOException {
        FakeForms actions = new FakeForms()
            .form("good", null, null, null, true, control("input", "text", "a", false, true, true))
            .brokenForm("java.lang.IllegalStateException: stale element reference");

        FormsModule.Result result = module(actions, recording()).inspectDiscoveredForms();

        assertEquals(FormsModule.Outcome.FAILED, result.outcome());
        assertEquals("Some discovered forms could not be inspected", result.message());
        assertTrue(itemWithId("form-1").contains("\"status\":\"PASSED\""), itemWithId("form-1"));
        String broken = itemWithId("form-2");
        assertTrue(broken.contains("\"status\":\"FAILED\""), broken);
        assertTrue(broken.contains("The form structure could not be inspected."), broken);
        // One form's failure must not contaminate the other's evidence.
        assertFalse(itemWithId("form-1").contains("stale element reference"), itemWithId("form-1"));
    }

    @Test
    void aCredentialShapedControlIsNamedSafelyAndItsValueIsNeverRead() throws IOException {
        FakeForms actions = new FakeForms().form("signin", null, null, null, true,
            control("input", "password", "password", true, true, true));

        module(actions, recording()).inspectDiscoveredForms();

        String field = itemWithId("form-1-field-1");
        assertTrue(field.contains("\"metadata.sensitive\":\"true\""), field);
        assertTrue(field.contains("deliberately not read"), field);
        assertFalse(field.contains("\"metadata.label\":"),
            "a sensitive control's label must not be recorded: " + field);
        for (String line : items()) {
            assertFalse(line.contains("hunter2"), line);
        }
    }

    // --- the configured interaction -----------------------------------------------------------

    private static WebsiteFormInteractionConfig interaction() {
        return new WebsiteFormInteractionConfig("#contact", "#email", "qa@example.com");
    }

    @Test
    void theConfiguredFieldIsClearedTypedAndVerified() throws IOException {
        FakeForms actions = new FakeForms().field(true, true, true, true, "input", "email", "email");

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.PASSED, result.outcome());
        assertEquals(
            List.of("readBack", "clear", "type:qa@example.com", "readBack", "clear", "type:", "readBack"),
            actions.performed,
            "read what was there, cleared, typed once, read back, then put the original back");
        String item = itemWithId("forms-interaction-1");
        assertTrue(item.contains("\"metadata.submitted\":\"false\""), item);
        assertTrue(item.contains("The form was not submitted."), item);
        assertTrue(item.contains("\"status\":\"PASSED\""), item);
    }

    // --- cleanup: the field goes back the way it was found -------------------------------------

    @Test
    void theFieldsOriginalValueIsPutBackAfterTyping() throws IOException {
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "text", "comment")
            .originallyHolding("what the customer had typed");

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.PASSED, result.outcome());
        assertEquals("what the customer had typed", actions.value,
            "the field must be left holding what it held before the check ran");
        String item = itemWithId("forms-interaction-1");
        assertTrue(item.contains("\"metadata.originalValueRestored\":\"true\""), item);
        assertTrue(item.contains("original value was put back"), item);
    }

    @Test
    void theOriginalValueIsNeverRecordedAnywhere() throws IOException {
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "text", "comment")
            .originallyHolding("SYNTHETIC-PRIOR-CONTENTS");

        module(actions, recording()).fillConfiguredField(interaction());

        for (String line : items()) {
            assertFalse(line.contains("SYNTHETIC-PRIOR-CONTENTS"), line);
        }
        for (String line : steps()) {
            assertFalse(line.contains("SYNTHETIC-PRIOR-CONTENTS"), line);
        }
        // Its length is what reaches the timeline instead.
        assertTrue(stepsFor(FormExecutionEvidence.INTERACTION_TEST_CASE_ID).stream()
                .anyMatch(step -> step.contains("24 character(s)")),
            "the captured length is recorded in place of the value");
    }

    @Test
    void aCleanupThatThrowsIsReportedAndDoesNotReplaceTheVerdict() throws IOException {
        // Typing works; putting the original back does not.
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "text", "comment")
            .originallyHolding("prior")
            .typeThrowsAfter(1, new IllegalStateException("element not interactable"));

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        // The interaction itself passed, and that stays true: cleanup is a separate fact.
        assertEquals(FormsModule.Outcome.PASSED, result.outcome());
        String item = itemWithId("forms-interaction-1");
        assertTrue(item.contains("\"metadata.originalValueRestored\":\"false\""), item);
        assertTrue(item.contains("still holds the test value"), item);
        assertTrue(item.contains("could not be put back"), item);
        assertTrue(stepsFor(FormExecutionEvidence.INTERACTION_TEST_CASE_ID).stream()
                .anyMatch(step -> step.contains("\"phase\":\"CLEANUP\"")
                    && step.contains("\"status\":\"FAILED\"")),
            "a cleanup that threw is a failed CLEANUP step, not a silence");
    }

    @Test
    void aFieldThatSilentlyRefusesTheRestoredValueIsReportedToo() throws IOException {
        // Takes the test value, then stops accepting writes: cleanup raises no exception and still
        // leaves the test value on the page. A read-back is the only thing that can catch that.
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "text", "comment")
            .originallyHolding("prior")
            .refusesWritesAfter(1);

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.PASSED, result.outcome(), "the typing itself worked");
        String item = itemWithId("forms-interaction-1");
        assertTrue(item.contains("\"metadata.originalValueRestored\":\"false\""), item);
        assertTrue(item.contains("does not hold its original value again"), item);
        assertFalse(item.contains("prior"), "the field's contents must not be recorded: " + item);
    }

    // --- targets that must never be typed into --------------------------------------------------

    @Test
    void aReadOnlyFieldIsRefusedBeforeAnythingIsTyped() throws IOException {
        // A readonly input reports itself as enabled, so "enabled" alone would wave it through.
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "text", "readonly-field").readOnly();

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured field is read-only, so nothing was typed into it.",
            result.message());
        assertEquals(List.of(), actions.performed, "a read-only field is never read or written");
        assertTrue(stepsFor(FormExecutionEvidence.INTERACTION_TEST_CASE_ID).stream()
                .anyMatch(step -> step.contains("Read-only: true")),
            "the refusal names the reason");
    }

    @Test
    void anAmbiguousFieldSelectorIsRefusedRatherThanNarrowedToTheFirstMatch() throws IOException {
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "checkbox", "my-check").matching(1, 2);

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured field selector matched more than one control inside the form.",
            result.message());
        assertEquals(List.of(), actions.performed, "nothing may be typed into an unchosen control");
    }

    @Test
    void anAmbiguousFormSelectorIsRefusedBeforeTheFieldIsEvenSoughtOut() throws IOException {
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "text", "comment").matching(3, 1);

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured form selector matched more than one form, so no field was chosen.",
            result.message());
        assertEquals(List.of(), actions.performed);
    }

    @Test
    void aFieldThatDidNotHoldTheTypedValueFails() throws IOException {
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "email", "email").readsBack("something else");

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured field did not hold the configured test value.", result.message());
        String item = itemWithId("forms-interaction-1");
        assertTrue(item.contains("Neither the expected nor the observed value is recorded."), item);
        assertFalse(item.contains("something else"), "the observed value must not be recorded: " + item);
        assertFalse(item.contains("qa@example.com"), "the typed value must not be recorded: " + item);
    }

    @Test
    void aMissingConfiguredFormOrFieldFailsWithoutTypingAnything() throws IOException {
        FakeForms noForm = new FakeForms().field(false, false, false, false, null, null, null);
        FormsModule.Result formMissing =
            module(noForm, recording()).fillConfiguredField(interaction());
        assertEquals(FormsModule.Outcome.FAILED, formMissing.outcome());
        assertEquals("The configured form selector matched no form on the submitted page.",
            formMissing.message());
        assertEquals(List.of(), noForm.performed, "nothing may be typed");

        FakeForms noField = new FakeForms().field(true, false, false, false, null, null, null);
        FormsModule.Result fieldMissing =
            module(noField, recording()).fillConfiguredField(interaction());
        assertEquals("The configured field selector matched nothing inside the form.",
            fieldMissing.message());
        assertEquals(List.of(), noField.performed);
    }

    @Test
    void aFieldThatIsNotVisibleAndEnabledIsNeverTypedInto() throws IOException {
        FakeForms hidden = new FakeForms().field(true, true, false, true, "input", "email", "email");
        FormsModule.Result result = module(hidden, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.FAILED, result.outcome());
        assertEquals("The configured field is not visible and enabled.", result.message());
        assertEquals(List.of(), hidden.performed, "nothing may be typed into an unusable field");
    }

    @Test
    void aSensitiveConfiguredFieldIsRefusedBeforeAnythingIsTyped() throws IOException {
        FakeForms actions =
            new FakeForms().field(true, true, true, true, "input", "password", "password");

        FormsModule.Result result = module(actions, recording()).fillConfiguredField(interaction());

        assertEquals(FormsModule.Outcome.SKIPPED, result.outcome(),
            "a refusal is a skip, not a failure");
        assertEquals("The configured field is a sensitive control, so nothing was typed into it.",
            result.message());
        assertEquals(List.of(), actions.performed, "the refusal happens before any typing");
        // Steps are numbered under the interaction case id; the contract 1.0 item id differs.
        assertFalse(stepsFor(FormExecutionEvidence.INTERACTION_TEST_CASE_ID).isEmpty(),
            "refusing must be recorded, not silent");
        for (String line : steps()) {
            assertFalse(line.contains("qa@example.com"), line);
        }
    }

    @Test
    void aBrowserFailureWhileTypingIsRecordedThenRethrownUnchanged() throws IOException {
        RuntimeException typingFailure = new IllegalStateException("element not interactable");
        FakeForms actions = new FakeForms()
            .field(true, true, true, true, "input", "email", "email").typeThrows(typingFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> module(actions, recording()).fillConfiguredField(interaction()));

        assertSame(typingFailure, thrown, "the original exception must be rethrown by reference");
        assertFalse(stepsFor(FormExecutionEvidence.INTERACTION_TEST_CASE_ID).isEmpty(),
            "the failure is recorded before it is rethrown");
    }

    @Test
    void theConfiguredValueNeverReachesAnyRecord() throws IOException {
        FakeForms actions = new FakeForms().field(true, true, true, true, "input", "email", "email");

        module(actions, recording()).fillConfiguredField(interaction());

        for (String line : items()) {
            assertFalse(line.contains("qa@example.com"), "the typed value must not be recorded: " + line);
        }
        for (String line : steps()) {
            assertFalse(line.contains("qa@example.com"), "the typed value must not be recorded: " + line);
        }
    }

    // --- evidence continuity and ownership -----------------------------------------------------

    @Test
    void theModuleContinuesTheSequenceTheWrapperAlreadyStarted() throws IOException {
        FormExecutionEvidence execution = recording();
        // Exactly what the wrapper records before handing over.
        execution.openedStartPage(PAGE, 12);

        module(new FakeForms().form("contact", null, null, null, true,
            control("input", "text", "a", false, true, true)), execution).inspectDiscoveredForms();

        List<String> discovery = stepsFor(FormExecutionEvidence.DISCOVERY_TEST_CASE_ID);
        assertTrue(discovery.get(0).contains("\"sequence\":1"), discovery.get(0));
        // The module must not restart at 1: numbering is per test case, not per object.
        assertTrue(discovery.get(1).contains("\"sequence\":2"), discovery.get(1));
        for (String line : steps()) {
            assertTrue(line.contains("\"checkId\":\"FORMS\""), line);
        }
    }

    @Test
    void theModuleNeverSubmitsOwnsNoBrowserAndOwnsNoCrawlQueue() throws IOException {
        String source = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/forms/FormsModule.java")));

        for (String forbidden : List.of(
            "org.junit", "assertTrue(", "assertFalse(", "assumeTrue(",
            ".submit()", "Keys.ENTER", "Keys.RETURN", "executeScript", "Thread.sleep",
            "new ChromeDriver", "driver.quit", "driver.close", "openUrl",
            "visited", "frontier", "queue", "Crawl", "SafePointerClick")) {
            assertFalse(source.contains(forbidden), "FormsModule must not contain " + forbidden);
        }
        assertEquals(0, source.split("\\.click\\(\\)", -1).length - 1,
            "the module must never click a control, including a submit button");
        // Exactly one place types, and it clears first.
        assertEquals(1, source.split("sendKeys\\(", -1).length - 1, "one typing call, and only one");
        assertEquals(1, source.split("\\.clear\\(\\)", -1).length - 1, "one clear, paired with it");
    }

    /** Executable code only: a comment must neither satisfy a guard nor trip one. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /**
     * The page work Forms performs, scripted.
     *
     * <p>Small on purpose: replacing a real {@code WebDriver} would take a large stub of methods
     * Forms never calls, and would prove less than this does.
     */
    private static final class FakeForms implements FormsModule.FormActions {

        private final List<FormsModule.DiscoveredForm> forms = new ArrayList<>();
        private final List<String> performed = new ArrayList<>();

        private FormsModule.FieldLocation location =
            new FormsModule.FieldLocation(false, false, false, false, false, 0, 0, null, null, null);
        /** What the field currently holds. Starts as what it held before the check arrived. */
        private String value = "";
        /** A field that refuses to take what is typed always reads back this instead. */
        private String stuck;
        private RuntimeException typeFailure;
        private int typeCallsBeforeFailure = 0;
        private int writesAccepted = Integer.MAX_VALUE;

        FakeForms form(String name, String domId, String action, String method, boolean displayed,
                       FormsModule.DiscoveredControl... controls) {
            forms.add(new FormsModule.DiscoveredForm(
                name, domId, action, method, displayed, List.of(controls), null, 7));
            return this;
        }

        FakeForms brokenForm(String failure) {
            forms.add(new FormsModule.DiscoveredForm(
                null, null, null, null, false, List.of(), failure, 7));
            return this;
        }

        FakeForms field(boolean formFound, boolean fieldFound, boolean displayed, boolean enabled,
                        String tag, String type, String name) {
            this.location = new FormsModule.FieldLocation(
                formFound, fieldFound, displayed, enabled, false,
                formFound ? 1 : 0, fieldFound ? 1 : 0, type, name, name);
            return this;
        }

        /** The same control, but the page reports it as read-only. */
        FakeForms readOnly() {
            this.location = new FormsModule.FieldLocation(
                location.formFound(), location.fieldFound(), location.displayed(), location.enabled(),
                true, location.formMatches(), location.fieldMatches(),
                location.type(), location.name(), location.domId());
            return this;
        }

        /** The same control, but the selectors matched this many elements. */
        FakeForms matching(int forms, int fields) {
            this.location = new FormsModule.FieldLocation(
                location.formFound(), location.fieldFound(), location.displayed(), location.enabled(),
                location.readOnly(), forms, fields,
                location.type(), location.name(), location.domId());
            return this;
        }

        /** What the field already contained when the check found it. */
        FakeForms originallyHolding(String existing) {
            this.value = existing;
            return this;
        }

        /** A field that never takes what is typed into it. */
        FakeForms readsBack(String value) {
            this.stuck = value;
            this.value = value;
            return this;
        }

        FakeForms typeThrows(RuntimeException failure) {
            this.typeFailure = failure;
            return this;
        }

        /** Lets the first {@code n} typing calls succeed, then throws. Used for cleanup failures. */
        FakeForms typeThrowsAfter(int n, RuntimeException failure) {
            this.typeCallsBeforeFailure = n;
            this.typeFailure = failure;
            return this;
        }

        /** Takes the first {@code n} writes, then accepts keystrokes without changing anything. */
        FakeForms refusesWritesAfter(int n) {
            this.writesAccepted = n;
            return this;
        }

        @Override
        public boolean awaitRenderedContentTimedOut() {
            return false;
        }

        @Override
        public int count(String cssSelector) {
            return 0;
        }

        @Override
        public List<FormsModule.DiscoveredForm> discoverForms() {
            return List.copyOf(forms);
        }

        @Override
        public FormsModule.FieldLocation locateConfiguredField(String formSelector, String fieldSelector) {
            return location;
        }

        @Override
        public void clearAndType(String typed) {
            if (typeFailure != null && typeCallsBeforeFailure <= 0) throw typeFailure;
            typeCallsBeforeFailure--;
            performed.add("clear");
            performed.add("type:" + typed);
            // A stuck field takes the clear and the keystrokes without error and keeps its own
            // contents, which is how a real field that refuses input behaves.
            if (stuck == null && writesAccepted > 0) this.value = typed;
            writesAccepted--;
        }

        @Override
        public String readBackFieldValue() {
            performed.add("readBack");
            return value;
        }
    }
}
