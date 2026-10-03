package com.qanize.automation.checks.forms;

import java.util.Locale;
import java.util.Objects;

import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;


/**
 * Records what the Forms check actually did, as it does it.
 *
 * <p>The Forms check reads structure and nothing else: it never types, never clicks and never
 * submits. That is the single most important thing this evidence has to convey, because a report
 * saying "Forms: passed" invites the reader to believe the forms were exercised. Every step here
 * is therefore a {@code DOM_INSPECTION}, and there is deliberately no entry point capable of
 * describing a keystroke, a click, a submission or a validation message.
 *
 * <p><strong>No field values, ever.</strong> No method on this class accepts a control's value,
 * so a value cannot reach the evidence even by mistake. Names and labels arrive already resolved
 * through {@link SensitiveDataFilter#safeFieldName}, which reports a credential-shaped control as
 * {@code [redacted]}, and structural text is bounded so a pathological attribute cannot dominate
 * a record.
 */
public final class FormExecutionEvidence {

    /** Matches the contract 1.0 id used when nothing is discovered. */
    public static final String DISCOVERY_TEST_CASE_ID = "forms-discovery";

    /** Structural attributes are labels, not prose; anything longer is a red flag, not detail. */
    static final int MAX_STRUCTURAL_TEXT = 120;

    private static final String CHECK_ID = "FORMS";

    /** Guarded: a reporting problem must never become a form finding. */
    private final CheckStepRecorder steps =
        CheckStepRecorder.guarded(CHECK_ID, "form-step-record-failed");

    /** Which page these steps belong to. Legacy by default, so existing callers are untouched. */
    private final EvidenceScope scope;

    /** The single-page recorder: identities exactly as this check has always written them. */
    public FormExecutionEvidence() {
        this(EvidenceScope.legacy());
    }

    /**
     * The recorder for one crawled page.
     *
     * @throws NullPointerException when the scope is null; ask for the legacy scope by name
     */
    public FormExecutionEvidence(EvidenceScope scope) {
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use FormExecutionEvidence() for the legacy scope");
    }

    /** One of this check's case ids, qualified by page. */
    public String testCaseId(String moduleTestCaseId) {
        return scope.testCaseId(moduleTestCaseId);
    }

    private ExecutionStep append(
        String testCaseId, Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        // Scoped here, once, so no caller has to remember which page it is recording for.
        return steps.append(
            testCaseId(testCaseId), phase, method, action, targetLabel,
            expected, actual, status, durationMs, errorCategory);
    }

    /** Keeps a structural attribute short and single-line. Never applied to a value. */
    static String structural(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.replaceAll("[\\r\\n]+", " ").trim();
        return trimmed.length() <= MAX_STRUCTURAL_TEXT
            ? trimmed
            : trimmed.substring(0, MAX_STRUCTURAL_TEXT) + "…";
    }

    // --- discovery -----------------------------------------------------------------------------

    /** {@code driver.get(pageUrl)} returned. */
    public ExecutionStep openedStartPage(String pageUrl, long durationMs) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.NAVIGATE,
            "Opened the submitted page to look for forms.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "The submitted page loads so its forms can be inspected.",
            "The submitted page finished loading.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** The real page-content readiness poll. A client-rendered page has no forms before it. */
    public ExecutionStep waitedForRenderedContent(String pageUrl, boolean timedOut, long durationMs) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the page to render its forms.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "The page puts at least one link or form into the document.",
            timedOut
                ? "The page still had no links or forms when the wait expired."
                : "The page rendered content that could be inspected.",
            timedOut ? ExecutionStep.Status.FAILED : ExecutionStep.Status.PASSED,
            durationMs, timedOut ? ErrorCategory.NOT_APPLICABLE : null
        );
    }

    /** The real counts the check measured. Buttons and frames explain a zero-form result. */
    public ExecutionStep discoveredForms(
        String pageUrl, int forms, int buttons, int anchors, int iframes, long durationMs
    ) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for form elements on the page.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "At least one form is present to inspect.",
            "Found " + forms + " form(s), " + buttons + " button(s), " + anchors
                + " link(s) and " + iframes + " frame(s).",
            forms > 0 ? ExecutionStep.Status.PASSED : ExecutionStep.Status.SKIPPED,
            durationMs, forms > 0 ? null : ErrorCategory.NOT_APPLICABLE
        );
    }

    /**
     * No form exists, so nothing about forms has been established.
     *
     * <p>Recorded as skipped and not-applicable. The wording deliberately says only that no form
     * was found: it must never read as though submission, validation or field behaviour was tested
     * and worked.
     */
    public ExecutionStep noFormsDiscovered(String pageUrl, String reason) {
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked whether the page had any form to inspect.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "At least one form is present to inspect.",
            reason + " No form structure, field behaviour, validation or submission was tested.",
            ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE
        );
    }

    // --- one form -------------------------------------------------------------------------------

    /**
     * One form's structure was read. The form was not submitted.
     *
     * @param action the form's action URL, sanitized here; a URL is not a value
     */
    public ExecutionStep inspectedFormStructure(
        String formTestCaseId, String formLabel, String method, String action,
        int controlCount, boolean visible, long durationMs
    ) {
        String safeMethod = structural(method);
        String safeAction = SensitiveDataFilter.sanitizeUrl(action);
        return append(
            formTestCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the form's structure and controls. The form was not submitted.",
            structural(formLabel),
            "The form exposes an inspectable structure with readable controls.",
            "Found " + controlCount + " control(s). The form is "
                + (visible ? "visible" : "not visible") + "."
                + (safeMethod == null ? "" : " Method " + safeMethod.toUpperCase(Locale.ROOT) + ".")
                + (safeAction == null ? "" : " Submits to " + safeAction + ".")
                + " Nothing was entered and the form was not submitted.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /** The browser threw while reading the form's structure. Not a statement about the website. */
    public ExecutionStep formInspectionFailed(
        String formTestCaseId, String formLabel, String sanitizedFailure, long durationMs
    ) {
        return append(
            formTestCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Tried to read the form's structure.",
            structural(formLabel),
            "The form exposes an inspectable structure with readable controls.",
            "The form structure could not be inspected: " + sanitizedFailure,
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /**
     * One control's structure was read.
     *
     * <p>There is no parameter for the control's value, and there never should be. {@code safeName}
     * is expected to have come from {@link SensitiveDataFilter#safeFieldName}, so a credential
     * field is already {@code [redacted]} before it reaches this method.
     *
     * <p>The state is described from the three facts the check actually read — displayed, enabled
     * and readonly — and never summarised as "interactable". Displayed and enabled does not mean
     * editable: a readonly field satisfies both and cannot be typed into. Nothing here has proven
     * a control can be filled, because nothing tried.
     *
     * @param accessibleLabel the label the check already read; used only when the control has no
     *                        safe name, and never for a credential-shaped control
     */
    public ExecutionStep inspectedControl(
        String formTestCaseId, String controlItemId, String safeName, String accessibleLabel,
        String tag, String type, boolean required, boolean visible, boolean enabled,
        boolean readOnly, boolean sensitive, long durationMs
    ) {
        // Unchanged: this is what decides PASSED vs SKIPPED, exactly as the 1.0 check does.
        boolean interactable = visible && enabled;
        String kind = structural(type) != null ? structural(type) : structural(tag);
        String identity = identify(safeName, accessibleLabel, sensitive);

        String state;
        if (!visible) state = "not visible";
        else if (!enabled) state = "displayed but disabled";
        else if (readOnly) state = "displayed and enabled, but readonly";
        else state = "displayed and enabled";

        return append(
            formTestCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Read the control's structure. No value was entered or read.",
            identity,
            "The control exposes a readable type and state.",
            "Control " + controlItemId + " is a " + (kind == null ? "control" : kind)
                + (required ? ", required" : "") + ", " + state + "."
                + (readOnly ? " Whether it can be edited was not tested." : "")
                + (sensitive ? " It is credential-shaped, so its value was deliberately not read." : "")
                + (identity == null
                    ? " It has no name, id or accessible label, so it is identified only by its position."
                    : ""),
            interactable ? ExecutionStep.Status.PASSED : ExecutionStep.Status.SKIPPED,
            durationMs, interactable ? null : ErrorCategory.NOT_APPLICABLE
        );
    }

    /**
     * How a control is named in the evidence.
     *
     * <p>A credential-shaped control keeps its {@code [redacted]} name and never falls back to its
     * accessible label, because a label such as "Password for admin@example.com" would leak the
     * very thing the redaction protects. Otherwise the already-read accessible label is used when
     * the control has no name or id — that is how a submit button with neither is still called
     * "Submit" rather than left anonymous. When nothing safe exists the field stays absent.
     */
    static String identify(String safeName, String accessibleLabel, boolean sensitive) {
        String name = structural(safeName);
        if (sensitive) return name;
        return name != null ? name : structural(accessibleLabel);
    }

    /** The browser threw while reading one control. */
    public ExecutionStep controlInspectionFailed(
        String formTestCaseId, String controlItemId, String sanitizedFailure, long durationMs
    ) {
        return append(
            formTestCaseId, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Tried to read the control's structure.",
            structural(controlItemId),
            "The control exposes a readable type and state.",
            "The control could not be inspected: " + sanitizedFailure,
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** The verdict, recorded before the check's terminal assertions run. */
    public ExecutionStep assertedFormsWereInspectable(
        String pageUrl, int inspected, int inspectionFailures
    ) {
        boolean ok = inspected > 0 && inspectionFailures == 0;
        return append(
            DISCOVERY_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked that every discovered form could be inspected.",
            SensitiveDataFilter.sanitizeUrl(pageUrl),
            "Every discovered form exposes an inspectable structure.",
            "Inspected " + inspected + " form(s) with " + inspectionFailures
                + " inspection failure(s). No form was submitted.",
            ok ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            0, ok ? null : ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    // --- configured safe field interaction -----------------------------------------------------

    /**
     * The test case id for the one configured field the check may fill in.
     *
     * <p>Separate from {@link #DISCOVERY_TEST_CASE_ID} so discovery-only runs are byte-identical to
     * how they looked before this feature existed: when the interaction is not configured, nothing
     * here is ever called and no step under this id appears in the timeline.
     */
    public static final String INTERACTION_TEST_CASE_ID = "forms-interaction";

    /**
     * The configured form was located and scoped.
     *
     * <p>{@code matches} is how many forms the selector matched. Exactly one is the only usable
     * answer, and the step says which number it got rather than reporting "found".
     */
    public ExecutionStep locatedConfiguredForm(
        String formSelector, boolean found, int matches, long durationMs
    ) {
        boolean usable = found && matches == 1;
        return append(
            INTERACTION_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for the configured form on the submitted page.",
            structural(formSelector),
            "The configured form selector matches exactly one form to work inside.",
            !found
                ? "The configured form selector matched nothing on the page."
                : matches == 1
                    ? "Found exactly one form and scoped the field search to it."
                    : "The configured form selector matched " + matches + " forms, so no single form"
                        + " could be chosen and nothing was typed.",
            usable ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, usable ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /**
     * The configured field was located inside the configured form, and its state was read.
     *
     * <p>All four reasons a control cannot be typed into are read in one step and reported in one
     * sentence: it was not found, the selector matched several, it is not visible and enabled, or
     * it is read-only. Read-only is asked about separately because a readonly input is reported as
     * enabled, so "enabled" alone would wave it through.
     */
    public ExecutionStep locatedConfiguredField(
        String fieldSelector, boolean found, boolean displayed, boolean enabled, boolean readOnly,
        int matches, long durationMs
    ) {
        boolean usable = found && matches == 1 && displayed && enabled && !readOnly;
        return append(
            INTERACTION_TEST_CASE_ID, Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Looked for the configured field inside the configured form and read whether it is"
                + " visible, enabled and editable.",
            structural(fieldSelector),
            "The configured field exists exactly once inside that form and is visible, enabled and"
                + " not read-only.",
            !found
                ? "The configured field selector matched nothing inside the form."
                : matches > 1
                    ? "The configured field selector matched " + matches + " controls inside the"
                        + " form, so no single control could be chosen and nothing was typed."
                    : "Found the field. Displayed: " + displayed + ". Enabled: " + enabled
                        + ". Read-only: " + readOnly + ".",
            usable ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, usable ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    // --- cleanup: put back what was there -------------------------------------------------------

    /**
     * The field's existing value was read once, before anything was typed, so it can be put back.
     *
     * <p>The value never appears. Its length is recorded for the same reason the typed value's is:
     * enough to tell an empty field from a filled one, without putting its contents in an artifact.
     * Only a field already proven non-sensitive is ever read here.
     */
    public ExecutionStep capturedOriginalValue(
        String fieldSelector, int valueLength, long durationMs
    ) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.SETUP, ExecutionMethod.DOM_INSPECTION,
            "Read the field's existing value so it can be put back after the test.",
            structural(fieldSelector),
            "The field's existing value can be read before it is replaced.",
            "Read " + Math.max(0, valueLength) + " character(s) from the field. The value itself is"
                + " not recorded.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The field was put back the way it was found.
     *
     * <p>A failed restoration is a QANIZE problem rather than a website finding: the page did
     * nothing wrong, and the run has left its test value in a customer's form.
     */
    public ExecutionStep restoredOriginalValue(
        String fieldSelector, int valueLength, boolean restored, long durationMs
    ) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.CLEANUP, ExecutionMethod.TYPE,
            "Put the field's original value back. The form was not submitted and no submit control"
                + " was clicked.",
            structural(fieldSelector),
            "The field holds its original value again.",
            restored
                ? "Restored " + Math.max(0, valueLength) + " character(s) to the field. The value"
                    + " itself is not recorded."
                : "The field does not hold its original value again. It may still hold the test"
                    + " value. Neither value is recorded here.",
            restored ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, restored ? null : ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** Restoring the original value threw. Reported, never swallowed, and never rethrown. */
    public ExecutionStep restorationFailed(String fieldSelector, String reason, long durationMs) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.CLEANUP, ExecutionMethod.TYPE,
            "Tried to put the field's original value back.",
            structural(fieldSelector),
            "The field holds its original value again.",
            "Cleanup could not be completed: "
                + structural(SensitiveDataFilter.sanitizeMessage(reason))
                + " The field may still hold the test value. Nothing was submitted.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /**
     * The field was recognised as sensitive, so nothing was typed.
     *
     * <p>Refusing is a correct outcome, not a failure of the website, so the step is SKIPPED with
     * {@code NOT_APPLICABLE}. The value that was going to be typed is never mentioned, and the
     * field is named only through {@code SensitiveDataFilter.safeFieldName}.
     */
    public ExecutionStep refusedSensitiveField(String safeName, long durationMs) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.DOM_INSPECTION,
            "Checked whether the configured field is sensitive before typing anything into it.",
            structural(safeName),
            "A sensitive field is never filled in, whatever the configuration asks for.",
            "The field is a password or otherwise sensitive control, so nothing was typed and the"
                + " form was left untouched.",
            ExecutionStep.Status.SKIPPED, durationMs, ErrorCategory.NOT_APPLICABLE
        );
    }

    /**
     * {@code clear()} then {@code sendKeys(testValue)} returned without error.
     *
     * <p>The configured value is never written into the evidence. Its length is reported instead,
     * which is enough to tell an empty type from a real one without putting the value in an
     * artifact. Wording stops where the call stops: this step does not claim the field holds the
     * value — {@link #verifiedFieldValue} is the step that checks that.
     */
    public ExecutionStep clearedAndTypedTestValue(
        String fieldSelector, int valueLength, long durationMs
    ) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.TYPE,
            "Cleared the configured field and typed the configured test value into it. The form was"
                + " not submitted and no submit control was clicked.",
            structural(fieldSelector),
            "The field accepts the configured test value without error.",
            "Cleared the field and sent " + Math.max(0, valueLength) + " character(s) to it. The"
                + " value itself is not recorded. Nothing was submitted by this step.",
            ExecutionStep.Status.PASSED, durationMs, null
        );
    }

    /**
     * The field was read back and compared with what was typed.
     *
     * <p>Only the verdict is recorded. Neither the expected nor the observed value appears, because
     * a mismatch is just as capable of leaking the field's prior contents as the typed value.
     */
    public ExecutionStep verifiedFieldValue(String fieldSelector, boolean matches, long durationMs) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Read the field back and compared it with the configured test value.",
            structural(fieldSelector),
            "The field holds exactly the configured test value after typing.",
            matches
                ? "The field's value matches the configured test value."
                : "The field's value does not match the configured test value. Neither value is"
                    + " recorded here.",
            matches ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs, matches ? null : ErrorCategory.WEBSITE_FINDING
        );
    }

    /** The interaction could not run because the page or the form did not cooperate. */
    public ExecutionStep interactionFailed(String fieldSelector, String reason, long durationMs) {
        return append(
            INTERACTION_TEST_CASE_ID, Phase.ACTION, ExecutionMethod.TYPE,
            "Tried to fill in the configured field.",
            structural(fieldSelector),
            "The configured field can be cleared and typed into.",
            "The interaction could not be completed: "
                + structural(SensitiveDataFilter.sanitizeMessage(reason))
                + " Nothing was submitted.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.INFRASTRUCTURE_ERROR
        );
    }

    /** How many steps have been recorded for one test case. */
    public int recordedSteps(String testCaseId) {
        return steps.recordedSteps(testCaseId);
    }
}
