package com.qanize.automation.checks.forms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.checks.links.LinkExecutionEvidence;
import com.qanize.automation.checks.navigation.NavigationExecutionEvidence;
import com.qanize.automation.checks.smoke.SmokeExecutionEvidence;
import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceFileReader;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.testsupport.CheckSources;


/**
 * Proves the Forms execution evidence without a live site.
 *
 * <p>Forms is the check most likely to be over-read: "Forms: passed" invites the belief that the
 * forms were exercised. Most of what is pinned here is the opposite — that the evidence describes
 * inspection only, and that a page with no forms can never look like a success.
 */
@Tag("unit")
class FormExecutionEvidenceTest {

    private static final String PAGE = "https://example.com/contact";

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

    private List<String> lines() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> linesFor(String testCaseId) throws IOException {
        return lines().stream()
            .filter(line -> line.contains("\"testCaseId\":\"" + testCaseId + "\""))
            .toList();
    }

    // --- discovery counts ---------------------------------------------------------------------

    @Test
    void theRealDiscoveryCountsArePreserved() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.discoveredForms(PAGE, 2, 7, 31, 1, 12);

        String line = lines().getFirst();
        assertTrue(line.contains("Found 2 form(s), 7 button(s), 31 link(s) and 1 frame(s)."), line);
        assertTrue(line.contains("\"phase\":\"DISCOVERY\""), line);
        assertTrue(line.contains("\"method\":\"DOM_INSPECTION\""), line);
        assertTrue(line.contains("\"durationMs\":12"), line);
    }

    @Test
    void aRealWaitIsRecordedOnlyWhenItHappens() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 400);
        execution.discoveredForms(PAGE, 1, 0, 0, 0, 5);

        // No wait was driven here, so no WAIT step may appear.
        assertTrue(lines().stream().noneMatch(line -> line.contains("\"phase\":\"WAIT\"")));

        execution.waitedForRenderedContent(PAGE, false, 250);
        List<String> waits = lines().stream()
            .filter(line -> line.contains("\"phase\":\"WAIT\"")).toList();
        assertEquals(1, waits.size());
        assertTrue(waits.getFirst().contains("\"method\":\"WAIT_FOR_CONDITION\""), waits.getFirst());
    }

    // --- zero forms ----------------------------------------------------------------------------

    @Test
    void zeroFormsIsSkippedAndNotApplicable() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.discoveredForms(PAGE, 0, 4, 12, 0, 8);
        execution.noFormsDiscovered(PAGE, "No forms were discovered on the submitted page.");

        for (String line : lines()) {
            assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
            assertTrue(line.contains("\"errorCategory\":\"NOT_APPLICABLE\""), line);
        }
    }

    @Test
    void zeroFormsNeverBecomesPassed() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.discoveredForms(PAGE, 0, 0, 0, 0, 8);
        execution.noFormsDiscovered(PAGE, "No forms were discovered on the submitted page.");

        assertTrue(lines().stream().noneMatch(line -> line.contains("\"status\":\"PASSED\"")));
    }

    @Test
    void zeroFormsNeverClaimsSubmissionOrValidationWasTested() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.noFormsDiscovered(PAGE, "No forms were discovered on the submitted page.");

        String line = lines().getFirst();
        assertTrue(line.contains("No form structure, field behaviour, validation or submission was tested."), line);
        // It must never read as though the analyzer exercised anything and it worked.
        assertFalse(line.contains("submitted successfully"), line);
        assertFalse(line.contains("PASSED"), line);
        assertFalse(line.contains("Inspected"), line);
    }

    // --- forms found ----------------------------------------------------------------------------

    @Test
    void aFormUsesItsExistingTestCaseIdAndSaysItWasNotSubmitted() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedFormStructure("form-1", "contact", "post", "/contact", 4, true, 9);

        String line = linesFor("form-1").getFirst();
        assertTrue(line.contains("\"checkId\":\"FORMS\""), line);
        assertTrue(line.contains("Found 4 control(s)"), line);
        assertTrue(line.contains("Method POST."), line);
        assertTrue(line.contains("Submits to /contact."), line);
        assertTrue(line.contains("Nothing was entered and the form was not submitted."), line);
    }

    @Test
    void controlsAreRecordedUnderTheirOwnFormsTestCase() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedFormStructure("form-1", "contact", "post", "/contact", 2, true, 9);
        execution.inspectedControl("form-1", "form-1-field-1", "email", null, "input", "email",
            true, true, true, false, false, 2);
        execution.inspectedFormStructure("form-2", "search", "get", "/s", 1, true, 4);
        execution.inspectedControl("form-2", "form-2-field-1", "q", null, "input", "search",
            false, true, true, false, false, 1);

        assertEquals(2, linesFor("form-1").size());
        assertEquals(2, linesFor("form-2").size());
        assertTrue(linesFor("form-1").stream().noneMatch(line -> line.contains("form-2-field")));
    }

    @Test
    void sequenceIsDeterministicPerFormTestCase() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedFormStructure("form-1", "contact", "post", "/contact", 2, true, 9);
        execution.inspectedControl("form-1", "form-1-field-1", "email", null, "input", "email",
            true, true, true, false, false, 2);
        execution.inspectedControl("form-1", "form-1-field-2", "[redacted]", null, "input", "password",
            true, true, true, false, true, 2);

        List<String> forCase = linesFor("form-1");
        for (int index = 0; index < forCase.size(); index++) {
            assertTrue(forCase.get(index).contains("\"sequence\":" + (index + 1)), forCase.get(index));
        }
        assertEquals(3, execution.recordedSteps("form-1"));
    }

    @Test
    void aHiddenControlIsSkippedRatherThanFailed() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedControl("form-1", "form-1-field-1", "token", null, "input", "hidden",
            false, false, true, false, true, 1);

        String line = lines().getFirst();
        assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
        assertTrue(line.contains("not visible"), line);
        assertTrue(line.contains("\"errorCategory\":\"NOT_APPLICABLE\""), line);
    }

    // --- state wording: only what the check actually proved --------------------------------------

    @Test
    void aReadonlyControlIsReportedAsReadonlyAndNeverAsInteractable() throws IOException {
        FormExecutionEvidence execution = recording();
        // Displayed and enabled, but readonly — the exact shape that used to read "interactable".
        execution.inspectedControl("form-1", "form-1-field-5", "reference", null, "input", "text",
            false, true, true, true, false, 3);

        String line = lines().getFirst();
        assertTrue(line.contains("displayed and enabled, but readonly"), line);
        assertTrue(line.contains("Whether it can be edited was not tested."), line);
        assertFalse(line.contains("interactable"), line);
        assertFalse(line.contains("editable"), line);
        assertFalse(line.contains("fillable"), line);
        // Status semantics are unchanged: displayed and enabled still passes.
        assertTrue(line.contains("\"status\":\"PASSED\""), line);
        assertFalse(line.contains("errorCategory"), line);
    }

    @Test
    void theWordInteractableNeverAppearsInAnyRecordedState() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedControl("form-1", "f1", "a", null, "input", "text", false, true, true, false, false, 1);
        execution.inspectedControl("form-1", "f2", "b", null, "input", "text", false, true, true, true, false, 1);
        execution.inspectedControl("form-1", "f3", "c", null, "input", "text", false, true, false, false, false, 1);
        execution.inspectedControl("form-1", "f4", "d", null, "input", "hidden", false, false, true, false, false, 1);

        for (String line : lines()) {
            assertFalse(line.contains("interactable"), line);
            assertFalse(line.contains("editable"), line);
        }
    }

    @Test
    void disabledAndHiddenWordingStaysAccurate() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedControl("form-1", "f1", "a", null, "input", "text",
            false, true, false, false, false, 1);
        execution.inspectedControl("form-1", "f2", "b", null, "input", "text",
            false, false, true, false, false, 1);

        List<String> forCase = linesFor("form-1");
        assertTrue(forCase.get(0).contains("displayed but disabled"), forCase.get(0));
        assertTrue(forCase.get(1).contains("not visible"), forCase.get(1));
        // Both are skipped, as before.
        assertTrue(forCase.stream().allMatch(line -> line.contains("\"status\":\"SKIPPED\"")));
    }

    @Test
    void aPlainEnabledControlSaysSoWithoutQualification() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedControl("form-1", "f1", "email", null, "input", "email",
            true, true, true, false, false, 1);

        String line = lines().getFirst();
        assertTrue(line.contains("required, displayed and enabled."), line);
        assertFalse(line.contains("readonly"), line);
    }

    // --- identity: reuse the label the check already read ------------------------------------------

    @Test
    void aSubmitButtonWithNoNameOrIdUsesItsAlreadyKnownAccessibleLabel() throws IOException {
        FormExecutionEvidence execution = recording();
        // safeFieldName returns null when a control has neither name nor id.
        execution.inspectedControl("form-1", "form-1-field-13",
            SensitiveDataFilter.safeFieldName("submit", null, null), "Submit",
            "button", "submit", false, true, true, false, false, 2);

        String line = lines().getFirst();
        assertTrue(line.contains("\"targetLabel\":\"Submit\""), line);
        assertFalse(line.contains("no name, id or accessible label"), line);
    }

    @Test
    void aNameAlwaysWinsOverTheAccessibleLabel() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedControl("form-1", "f1", "emailAddress", "Your email",
            "input", "email", false, true, true, false, false, 1);

        assertTrue(lines().getFirst().contains("\"targetLabel\":\"emailAddress\""), lines().getFirst());
    }

    @Test
    void aCredentialShapedControlNeverFallsBackToItsAccessibleLabel() throws IOException {
        FormExecutionEvidence execution = recording();
        // A label can itself leak: "Password for admin@example.com" must never replace [redacted].
        execution.inspectedControl("form-1", "form-1-field-2", "[redacted]",
            "Password for admin@example.com", "input", "password",
            true, true, true, false, true, 2);

        String line = lines().getFirst();
        assertTrue(line.contains("[redacted]"), line);
        assertFalse(line.contains("admin@example.com"), line);
    }

    @Test
    void anUnidentifiableControlLeavesTheFieldAbsentAndSaysWhy() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedControl("form-1", "form-1-field-9", null, null,
            "input", "text", false, true, true, false, false, 1);

        String line = lines().getFirst();
        assertFalse(line.contains("targetLabel"), line);
        assertTrue(line.contains("no name, id or accessible label"), line);
    }

    @Test
    void anIdentityLabelIsBoundedLikeEveryOtherStructuralAttribute() {
        String huge = "L".repeat(FormExecutionEvidence.MAX_STRUCTURAL_TEXT + 50);

        assertEquals(huge.substring(0, FormExecutionEvidence.MAX_STRUCTURAL_TEXT) + "…",
            FormExecutionEvidence.identify(null, huge, false));
        assertEquals("[redacted]", FormExecutionEvidence.identify("[redacted]", "Password", true));
        assertEquals(null, FormExecutionEvidence.identify(null, "  ", false));
    }

    @Test
    void anInspectionFailureIsInfrastructureNotAWebsiteFinding() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.formInspectionFailed("form-1", "contact", "StaleElementReferenceException", 3);
        execution.controlInspectionFailed("form-1", "form-1-field-1", "StaleElementReferenceException", 1);

        for (String line : linesFor("form-1")) {
            assertTrue(line.contains("\"errorCategory\":\"INFRASTRUCTURE_ERROR\""), line);
            assertFalse(line.contains("WEBSITE_FINDING"), line);
        }
    }

    // --- nothing is exercised ---------------------------------------------------------------------

    @Test
    void noTypeClickHttpRequestOrSubmissionStepIsEverRecorded() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.openedStartPage(PAGE, 400);
        execution.waitedForRenderedContent(PAGE, false, 250);
        execution.discoveredForms(PAGE, 1, 2, 3, 0, 5);
        execution.inspectedFormStructure("form-1", "contact", "post", "/contact", 1, true, 9);
        execution.inspectedControl("form-1", "form-1-field-1", "email", null, "input", "email",
            true, true, true, false, false, 2);
        execution.assertedFormsWereInspectable(PAGE, 1, 0);

        for (String line : lines()) {
            assertFalse(line.contains("\"method\":\"TYPE\""), line);
            assertFalse(line.contains("\"method\":\"CLICK\""), line);
            assertFalse(line.contains("\"method\":\"HTTP_REQUEST\""), line);
            assertFalse(line.contains("\"method\":\"SCREENSHOT\""), line);
            assertFalse(line.contains("\"method\":\"BROWSER_START\""), line);
            assertFalse(line.contains("\"method\":\"BROWSER_CLOSE\""), line);
        }
    }

    @Test
    void theRecorderHasNoEntryPointForFillingClickingOrSubmitting() {
        List<String> methodNames = List.of(FormExecutionEvidence.class.getDeclaredMethods())
            .stream().map(java.lang.reflect.Method::getName).toList();

        for (String forbidden : List.of("typed", "filled", "clicked", "submitted", "validated")) {
            assertFalse(methodNames.contains(forbidden), forbidden + " must not exist");
        }
    }

    /**
     * The discovery path observes and never acts.
     *
     * <p>Scoped to the discovery method rather than the whole class. The Forms check now also has
     * an optional configured interaction that deliberately types into one field, so a file-wide
     * scan would turn this into a claim about a method it was never written about. Narrowing the
     * scope keeps the original guarantee exactly as strong for the path it describes.
     */
    /** Where the Forms behaviour now lives; the wrapper keeps only the JUnit face. */
    private static final Path FORMS_MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/forms/FormsModule.java");

    @Test
    void theCheckItselfNeverFillsClicksSubmitsOrReadsAValue() throws IOException {
        // The discovery path moved into FormsModule, so the guarantee is read where the code is.
        // Reading the wrapper would find no discovery body at all and prove nothing.
        String discovery = methodBody(
            Files.readString(FORMS_MODULE), "public Result inspectDiscoveredForms");

        assertFalse(discovery.contains("sendKeys"), "the Forms discovery path must not type");
        assertFalse(discovery.contains(".click()"), "the Forms discovery path must not click");
        assertFalse(discovery.contains(".submit()"), "the Forms discovery path must not submit");
        assertFalse(discovery.contains(".clear()"), "the Forms discovery path must not clear a field");
        assertFalse(discovery.contains("Keys.ENTER"), "the Forms discovery path must not press Enter");
        assertFalse(discovery.contains("getAttribute(\"value\")"),
            "field values must never be read during discovery");
    }

    /**
     * The configured interaction fills one field and stops there.
     *
     * <p>The half of the boundary the discovery test can no longer cover: typing is expected here,
     * submitting is not. Asserted positively as well as negatively so the test fails if the
     * interaction is ever quietly reduced to another discovery pass.
     */
    @Test
    void theConfiguredInteractionTypesButNeverSubmits() throws IOException {
        String module = Files.readString(FORMS_MODULE);
        String interaction = methodBody(module, "public Result fillConfiguredField");

        // The interaction asks for exactly these three things, in this order.
        assertTrue(interaction.contains("actions.clearAndType("), "the configured value is typed");
        assertTrue(interaction.contains("actions.readBackFieldValue()"),
            "the field is read back so the typed value can be verified");
        // ... and the page adapter is where clearing, typing and reading back actually happen.
        String adapter = methodBody(module, "public void clearAndType");
        assertTrue(adapter.contains(".clear()"), "the configured field is cleared before typing");
        assertTrue(adapter.contains("sendKeys"), "the configured test value is typed");
        assertTrue(methodBody(module, "public String readBackFieldValue")
            .contains("getAttribute(\"value\")"), "the value is read back from the field");

        // What it must never do on someone else's website, whatever the configuration asks for.
        // Whole-module, not just the interaction method: no path may submit, click or press a key.
        for (String forbidden : List.of(".submit()", ".click()", "Keys.ENTER", "Keys.RETURN")) {
            assertFalse(module.contains(forbidden),
                "the Forms check must never " + forbidden + " on someone else's website");
        }
    }

    // --- sensitive data ------------------------------------------------------------------------------

    @Test
    void aCredentialShapedControlIsNamedRedactedAndSaysItsValueWasNotRead() throws IOException {
        FormExecutionEvidence execution = recording();
        // safeFieldName has already resolved a password control to "[redacted]".
        execution.inspectedControl("form-1", "form-1-field-2",
            SensitiveDataFilter.safeFieldName("password", "userPassword", null), null,
            "input", "password", true, true, true, false, true, 2);

        String line = lines().getFirst();
        assertTrue(line.contains("[redacted]"), line);
        assertFalse(line.contains("userPassword"), line);
        assertTrue(line.contains("its value was deliberately not read"), line);
    }

    @Test
    void aFormActionCarryingASecretIsSanitized() throws IOException {
        FormExecutionEvidence execution = recording();
        execution.inspectedFormStructure("form-1", "login", "post",
            "https://example.com/login?csrf=abc123secret&next=/home", 2, true, 5);

        String line = lines().getFirst();
        assertFalse(line.contains("abc123secret"), line);
        assertTrue(line.contains("csrf=[redacted]"), line);
        assertTrue(line.contains("next=/home"), line);
    }

    @Test
    void structuralTextIsBoundedSoOneAttributeCannotDominateARecord() throws IOException {
        FormExecutionEvidence execution = recording();
        String huge = "n".repeat(FormExecutionEvidence.MAX_STRUCTURAL_TEXT + 400);
        execution.inspectedFormStructure("form-1", huge, "post", "/x", 1, true, 5);

        assertEquals(huge.substring(0, FormExecutionEvidence.MAX_STRUCTURAL_TEXT) + "…",
            FormExecutionEvidence.structural(huge));
        assertFalse(lines().getFirst().contains(huge), "the untruncated attribute must not appear");
    }

    // --- the result never depends on reporting ---------------------------------------------------------

    @Test
    void aReportingFailureCannotChangeTheFormsResult() throws IOException {
        FormExecutionEvidence execution = recording();

        execution.inspectedFormStructure("form-1", null, null, null, 0, false, -5);
        execution.inspectedControl("form-1", null, null, null, null, null,
            false, false, false, false, false, -1);
        assertNotNull(execution.inspectedFormStructure("form-2", "ok", "get", "/x", 1, true, 1),
            "a healthy step still records afterwards");

        // The containment did not disappear when the recorder was extracted: it moved, once, into
        // the shared CheckStepRecorder. Three facts replace the old "this file contains the text
        // catch (RuntimeException reportingFailure)" assertion, which proved none of them — not the
        // check id, not the diagnostic label, and nothing at all about run-time behaviour.

        // 1. This class records through the guarded factory, with its own identity and its own
        //    label. Switching to the unguarded factory, or changing the label, fails here.
        String source = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/forms/FormExecutionEvidence.java"));
        assertTrue(
            source.contains("CheckStepRecorder.guarded(CHECK_ID, \"form-step-record-failed\")"),
            "Forms must record through the guarded shared recorder under its own failure label");
        assertFalse(source.contains("CheckStepRecorder.unguarded("),
            "Forms must not become an unguarded recorder");
        assertFalse(source.contains("ExecutionEvidenceRecorder.record("),
            "no Forms step may bypass the guarded recorder and write directly");

        // 2. CHECK_ID really is FORMS — read from what was just written, not from the source.
        assertTrue(linesFor("form-2").getFirst().contains("\"checkId\":\"FORMS\""),
            linesFor("form-2").getFirst());

        // 3. That exact recorder configuration contains a reporting failure at run time. A step the
        //    model refuses to build is swallowed, logged under this check's label and reported as
        //    null, so a reporting problem can never become a form finding. Without this the
        //    assertions above would only pin spelling.
        CheckStepRecorder guarded = CheckStepRecorder.guarded("FORMS", "form-step-record-failed");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        ExecutionStep swallowed;
        try {
            swallowed = guarded.append(
                "form-1", null, ExecutionMethod.DOM_INSPECTION, "Inspected the form structure.",
                "form", "expected", "actual", ExecutionStep.Status.PASSED, 5,
                ErrorCategory.INFRASTRUCTURE_ERROR);
        } finally {
            System.setOut(originalOut);
        }
        assertNull(swallowed, "a contained reporting failure yields no step and no exception");
        assertTrue(
            captured.toString(StandardCharsets.UTF_8)
                .contains("QA Evidence | form-step-record-failed | "),
            captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void recordingIsANoOpWithoutARunDirectory() throws IOException {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);

        assertNotNull(new FormExecutionEvidence()
            .discoveredForms(PAGE, 1, 1, 1, 1, 1));
        assertEquals(List.of(), lines());
    }

    // --- isolation ---------------------------------------------------------------------------------------

    @Test
    void formsSharesTheFileWithTheOtherApprovedChecksWithoutColliding() throws IOException {
        FormExecutionEvidence forms = recording();
        new SmokeExecutionEvidence().navigated(PAGE, 10);
        new NavigationExecutionEvidence().navigatedToDestination("nav-1", PAGE, 20);
        new LinkExecutionEvidence().requestedLink("link-1", PAGE, 200, PAGE, 30);
        forms.discoveredForms(PAGE, 1, 0, 0, 0, 40);

        List<String> all = lines();
        assertEquals(4, all.size());
        assertTrue(all.get(0).contains("\"checkId\":\"SMOKE\""), all.get(0));
        assertTrue(all.get(1).contains("\"checkId\":\"NAVIGATION\""), all.get(1));
        assertTrue(all.get(2).contains("\"checkId\":\"LINKS\""), all.get(2));
        assertTrue(all.get(3).contains("\"checkId\":\"FORMS\""), all.get(3));
    }

    @Test
    void contractOneEvidenceAndDiscoveryEvidenceStayReadable() throws IOException {
        FormExecutionEvidence execution = recording();
        Path contractOne = runDirectory.resolve("evidence.jsonl");

        EvidenceRecorder.record(TestedItem.builder("FORMS", TestedItem.ItemType.FORM)
            .id("form-1").name("contact").pageUrl(PAGE)
            .status(TestedItem.Status.PASSED).completedAt(EvidenceRecorder.nowIso()).build());
        execution.inspectedFormStructure("form-1", "contact", "post", "/contact", 1, true, 5);

        EvidenceFileReader.Result result = new EvidenceFileReader().read(contractOne);
        assertEquals(1, result.items().size());
        assertEquals(List.of(), result.parseErrors());
        assertEquals(1, Files.readAllLines(contractOne).size());
    }

    @Test
    void noOtherCheckUsesTheFormsRecorder() throws IOException {
        for (String other : List.of(
            "GenericSmokeTest", "GenericNavigationTest", "GenericLinkValidationTest",
            "GenericSearchTest", "GenericLoginTest"
        )) {
            assertFalse(
                Files.readString(CheckSources.entryClass(other)).contains("FormExecutionEvidence"),
                other + " must not record through the Forms check's recorder"
            );
        }
        assertTrue(Files.readString(CheckSources.entryClass("GenericFormDetectionTest"))
            .contains("FormExecutionEvidence"));
    }

    @Test
    void thereIsExactlyOneFormsCheckAndNoNewDirectory() throws IOException {
        {
            List<String> names = CheckSources.entryClassFileNames();
            assertEquals(7, names.size(), "the seven customer-facing checks, and nothing else: " + names);
            assertEquals(1, names.stream().filter(name -> name.contains("Form")).count());
        }
        assertFalse(Files.exists(Path.of("src/test/java/com/qanize/automation/forms")));
    }

    @Test
    void everyRecordedActionFollowsTheCallThatPerformedIt() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericFormDetectionTest"));
        String module = Files.readString(FORMS_MODULE);

        // The wrapper opens the start page, then records that it did.
        // The open now goes through OpenedPage, which opens the requested address and reads
        // where the browser landed. The ordering rule is unchanged: the record follows the act.
        assertOrdered(wrapper, "OpenedPage.open(page, requested);", "execution.openedStartPage(");

        // Every read is performed first and described afterwards.
        assertOrdered(module, "actions.awaitRenderedContentTimedOut();", "execution.waitedForRenderedContent(");
        assertOrdered(module, "actions.discoverForms();", "execution.discoveredForms(");
        // The verdict is on disk before the result that reports it is returned.
        assertOrdered(module, "execution.assertedFormsWereInspectable(", "if (inspected <= 0)");
        // ... and only then does the wrapper turn that result into JUnit.
        assertOrdered(wrapper, "new FormsModule(", "assertTrue(result.passed(), result.message());");
    }

    @Test
    void theWrapperStaysAThinJUnitFaceOverTheModule() throws IOException {
        String wrapper = Files.readString(CheckSources.entryClass("GenericFormDetectionTest"));

        // Surefire classification depends on all of these staying exactly as they are.
        assertTrue(wrapper.contains("@Tag(\"website-analysis\")"), "the build selects on this tag");
        assertTrue(wrapper.contains("assumeTrue(config.tests().forms()"),
            "the selected-check gate cannot move into a module");
        assertTrue(wrapper.contains("return Stream.empty();"),
            "a disabled interaction must still yield no dynamic test at all");
        assertTrue(wrapper.contains("assumeTrue(!result.skipped(), result.message());"),
            "nothing discovered, and a refused sensitive field, must still be skips");
        // The page handed to the module is the document that came back, not the address that was
            // asked for: "no forms were discovered" is a statement about a specific page.
        assertTrue(wrapper.contains("new FormsModule(page, driver, opened.landed(), execution)"),
            "the module inspects the page the browser landed on, with the same recorder instance");
        assertFalse(wrapper.contains("new FormsModule(page, driver, pageUrl, execution)"),
            "the requested address must never be the source page of a discovery");

        // And implements none of the Forms behaviour itself.
        for (String moved : List.of(
            "DiscoveryDiagnostics", "DiscoveryEvidence", "TestedItem.builder(", "SensitiveDataFilter",
            "By.cssSelector", "sendKeys", "getAttribute", "execution.discoveredForms(",
            "execution.inspectedControl(", "execution.clearedAndTypedTestValue(")) {
            assertFalse(wrapper.contains(moved), moved + " belongs to FormsModule now");
        }
    }

    /** The body of one method, brace-matched so a neighbouring method cannot bleed into it. */
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

    private static void assertOrdered(String source, String earlier, String later) {
        int first = source.indexOf(earlier);
        int second = source.indexOf(later);
        assertTrue(first >= 0, "missing: " + earlier);
        assertTrue(second >= 0, "missing: " + later);
        assertTrue(first < second, earlier + " must appear before " + later);
    }
}
