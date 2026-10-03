package com.qanize.automation.checks.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.DiscoveryDiagnostics;
import com.qanize.automation.evidence.DiscoveryEvidence;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.TestedItem;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * The Forms check, as production code.
 *
 * <p>Two paths that must not be confused. Discovery reads the structure of every form on the page
 * and never touches it: no typing, no clicking, no submitting, and field values are never read, so
 * a password already on the page cannot leak into a report. The configured interaction is the one
 * exception, and it does exactly one thing — clears one named field and types one configured value
 * into it — then reads that field back to prove it took.
 *
 * <p><strong>Nothing is ever submitted.</strong> There is no {@code submit} call, no click on any
 * control and no Enter key anywhere in this class. That is a property of the check, not of the
 * configuration: no configuration can ask for it.
 *
 * <p><strong>It receives an already-open page.</strong> Nothing here creates or closes a browser,
 * and nothing decides what to audit next.
 *
 * <p><strong>It never asserts.</strong> Both entry points return a {@link Result} the caller
 * converts. "Nothing discovered" and "sensitive field refused" stay distinct from "failed", because
 * both have always been reported as skips and that difference reaches the customer's summary.
 */
public final class FormsModule {

    private final FormActions actions;
    private final String pageUrl;
    private final FormExecutionEvidence execution;
    private final EvidenceScope scope;

    /**
     * The production entry point.
     *
     * @param page      a page whose browser is already on {@code pageUrl}
     * @param execution the recorder the caller already used, so per-test-case sequence numbers
     *                  continue rather than restarting
     */
    public FormsModule(
        CommonPage page, WebDriver driver, String pageUrl, FormExecutionEvidence execution
    ) {
        this(page, driver, pageUrl, execution, EvidenceScope.legacy());
    }

    /**
     * The entry point for one crawled page.
     *
     * @param scope which page these forms belong to, so page 2's {@code forms-1} cannot be mistaken
     *              for page 1's
     * @throws NullPointerException when the scope is null; ask for the legacy scope by name
     */
    public FormsModule(
        CommonPage page, WebDriver driver, String pageUrl, FormExecutionEvidence execution,
        EvidenceScope scope
    ) {
        this(new CommonPageForms(page, driver), pageUrl, execution, scope);
    }

    /** The seam a test uses to supply its own page. Not part of the public API. */
    FormsModule(FormActions actions, String pageUrl, FormExecutionEvidence execution) {
        this(actions, pageUrl, execution, EvidenceScope.legacy());
    }

    /** The seam a test uses to supply its own page and scope. Not part of the public API. */
    FormsModule(
        FormActions actions, String pageUrl, FormExecutionEvidence execution, EvidenceScope scope
    ) {
        this.actions = actions;
        this.pageUrl = pageUrl;
        this.execution = execution;
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use the constructor without a scope for the legacy scope");
    }

    // --- discovery: observe, never act --------------------------------------------------------

    /** Inspects every form on the open page. Reads structure only; changes nothing. */
    public Result inspectDiscoveredForms() {
        // Forms on a JavaScript-rendered page do not exist at readyState "complete".
        long stepStart = System.nanoTime();
        boolean contentWaitTimedOut = actions.awaitRenderedContentTimedOut();
        execution.waitedForRenderedContent(pageUrl, contentWaitTimedOut, elapsedMs(stepStart));

        stepStart = System.nanoTime();
        List<DiscoveredForm> forms = actions.discoverForms();
        long formDiscoveryMs = elapsedMs(stepStart);

        // An empty list is not evidence that forms work, so it must not be a silent pass.
        DiscoveryDiagnostics.ForForms diagnostics = new DiscoveryDiagnostics.ForForms(
            actions.count("a[href]"),
            actions.count("button, [role='button']"),
            forms.size(),
            actions.count("iframe, frame"),
            contentWaitTimedOut
        );
        execution.discoveredForms(
            pageUrl, forms.size(), diagnostics.buttons(), diagnostics.anchors(),
            diagnostics.iframes(), formDiscoveryMs);
        if (forms.isEmpty()) {
            String reason = diagnostics.explain("No forms were discovered on the submitted page.");
            execution.noFormsDiscovered(pageUrl, reason);
            DiscoveryEvidence.recordNothingDiscovered(
                "FORMS", scope.itemId("forms-discovery"), "Form discovery", pageUrl,
                "Opened the submitted page and looked for forms to inspect.",
                "At least one form is present to inspect.",
                reason, diagnostics);
            return new Result(Outcome.SKIPPED, reason);
        }

        int inspected = 0;
        int fieldFailures = 0;
        for (int formIndex = 0; formIndex < forms.size(); formIndex++) {
            DiscoveredForm form = forms.get(formIndex);
            String formId = "form-" + (formIndex + 1);
            String failure = form.failure() == null
                ? null : SensitiveDataFilter.sanitizeMessage(form.failure());
            String label = formDisplayName(form.name(), form.domId(), formIndex);
            if (failure == null) {
                execution.inspectedFormStructure(
                    formId, label, form.method(), form.action(),
                    form.controls().size(), form.displayed(), form.durationMs());
            } else {
                execution.formInspectionFailed(formId, label, failure, form.durationMs());
            }

            TestedItem.Builder item = TestedItem.builder("FORMS", TestedItem.ItemType.FORM)
                .id(scope.itemId(formId))
                .name(label)
                .pageUrl(pageUrl)
                .action("Inspected the form structure and controls. The form was not submitted.")
                .expected("The form exposes an inspectable structure with readable controls.")
                .status(failure == null ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
                .completedAt(EvidenceRecorder.nowIso())
                .meta("formIndex", String.valueOf(formIndex + 1))
                .meta("controlCount", String.valueOf(form.controls().size()))
                .meta("visible", String.valueOf(form.displayed()));
            if (form.name() != null && !form.name().isBlank()) item.meta("formName", form.name().trim());
            if (form.domId() != null && !form.domId().isBlank()) item.meta("formId", form.domId().trim());
            if (form.method() != null && !form.method().isBlank()) item.meta("method", form.method().trim());
            // The action target is a URL, never a value, so it is safe to report.
            if (form.action() != null && !form.action().isBlank()) item.target(form.action().trim());
            item.actual(failure == null
                ? "Inspected " + form.controls().size() + " control(s). The form was not submitted."
                : "The form structure could not be inspected.");
            if (failure != null) { item.message(failure); fieldFailures++; }
            // Last in the chain: enrichment only fills keys the check left absent.
            EvidenceRecorder.record(scope.enrich(item).build());
            inspected++;

            for (int controlIndex = 0; controlIndex < form.controls().size(); controlIndex++) {
                recordControl(formId, form.controls().get(controlIndex), controlIndex);
            }
        }

        // Recorded before the verdict is returned, so it survives whatever the caller does next.
        execution.assertedFormsWereInspectable(pageUrl, inspected, fieldFailures);

        if (inspected <= 0) {
            return new Result(Outcome.FAILED, "At least one discovered form should have been inspected");
        }
        return fieldFailures == 0
            ? new Result(Outcome.PASSED, null)
            : new Result(Outcome.FAILED, "Some discovered forms could not be inspected");
    }

    /**
     * One control's structure.
     *
     * <p>No value is passed here at all: the recorder has no parameter that could carry one, and a
     * credential-shaped control is reduced to a safe name before anything is written.
     */
    private void recordControl(String formId, DiscoveredControl control, int controlIndex) {
        String failure = control.failure() == null
            ? null : SensitiveDataFilter.sanitizeMessage(control.failure());
        boolean sensitive =
            SensitiveDataFilter.isSensitiveField(control.type(), control.name(), control.domId());
        String safeName =
            SensitiveDataFilter.safeFieldName(control.type(), control.name(), control.domId());
        boolean interactable = control.displayed() && control.enabled();
        String controlItemId = formId + "-field-" + (controlIndex + 1);

        if (failure != null) {
            execution.controlInspectionFailed(formId, controlItemId, failure, control.durationMs());
        } else {
            execution.inspectedControl(
                formId, controlItemId, safeName, control.label(), control.tag(), control.type(),
                control.required(), control.displayed(), control.enabled(), control.readOnly(),
                sensitive, control.durationMs());
        }

        TestedItem.Builder item = TestedItem.builder("FORMS", TestedItem.ItemType.FIELD)
            .id(scope.itemId(controlItemId))
            .name(safeName != null ? safeName
                : (control.label() != null && !control.label().isBlank()
                    ? control.label().trim() : "Control " + (controlIndex + 1)))
            .pageUrl(pageUrl)
            .action("Read the control's structure. No value was entered or read.")
            .expected("The control exposes a readable type and state.")
            .completedAt(EvidenceRecorder.nowIso())
            .meta("formItemId", formId)
            .meta("required", String.valueOf(control.required()))
            .meta("visible", String.valueOf(control.displayed()))
            .meta("enabled", String.valueOf(control.enabled()))
            .meta("sensitive", String.valueOf(sensitive));
        if (control.tag() != null && !control.tag().isBlank()) item.meta("tag", control.tag().trim());
        if (control.type() != null && !control.type().isBlank()) item.meta("fieldType", control.type().trim());
        if (control.readOnly()) item.meta("readOnly", "true");
        if (control.label() != null && !control.label().isBlank() && !sensitive) {
            item.meta("label", control.label().trim());
        }

        if (failure != null) {
            item.status(TestedItem.Status.FAILED).actual("The control could not be inspected.").message(failure);
        } else if (!interactable) {
            item.status(TestedItem.Status.SKIPPED)
                .actual("The control is present but not interactable.")
                .message(control.displayed() ? "The control is disabled." : "The control is not visible.");
        } else {
            item.status(TestedItem.Status.PASSED)
                .actual("Inspected a "
                    + (control.type() != null && !control.type().isBlank()
                        ? control.type().trim() : control.tag())
                    + " control." + (sensitive ? " Its value was deliberately not read." : ""));
        }
        EvidenceRecorder.record(item.build());
    }

    // --- the configured interaction: one field, one value, never a submit ---------------------

    /**
     * Fills in the one explicitly configured field.
     *
     * <p>The form is never submitted: there is no {@code submit} call, no click on any control, and
     * no Enter key anywhere in this path.
     *
     * @throws RuntimeException the original exception, unchanged, when the browser fails while
     *                          typing — recorded first, then rethrown by reference
     */
    public Result fillConfiguredField(WebsiteFormInteractionConfig form) {
        actions.awaitRenderedContentTimedOut();

        long stepStart = System.nanoTime();
        FieldLocation located = actions.locateConfiguredField(form.formSelector(), form.fieldSelector());
        execution.locatedConfiguredForm(
            form.formSelector(), located.formFound(), located.formMatches(), elapsedMs(stepStart));
        if (!located.formFound()) {
            return new Result(Outcome.FAILED,
                "The configured form selector matched no form on the submitted page.");
        }
        // More than one match is not a form this run may choose between. Picking the first would
        // type into a control nobody selected and report it as the configured one.
        if (located.formMatches() > 1) {
            return new Result(Outcome.FAILED,
                "The configured form selector matched more than one form, so no field was chosen.");
        }

        stepStart = System.nanoTime();
        execution.locatedConfiguredField(
            form.fieldSelector(), located.fieldFound(), located.displayed(), located.enabled(),
            located.readOnly(), located.fieldMatches(), elapsedMs(stepStart));
        if (!located.fieldFound()) {
            return new Result(Outcome.FAILED,
                "The configured field selector matched nothing inside the form.");
        }
        if (located.fieldMatches() > 1) {
            return new Result(Outcome.FAILED,
                "The configured field selector matched more than one control inside the form.");
        }
        if (!(located.displayed() && located.enabled())) {
            return new Result(Outcome.FAILED, "The configured field is not visible and enabled.");
        }
        // Read-only is its own refusal rather than a typing failure. Selenium reports a readonly
        // input as enabled, so without this gate the field would be typed into, would keep its
        // original contents, and the read-back mismatch below would blame the website for a
        // configuration mistake.
        if (located.readOnly()) {
            return new Result(Outcome.FAILED, "The configured field is read-only, so nothing was typed into it.");
        }

        // The refusal happens before any typing, and it is the check's own decision rather than a
        // property of the page: a sensitive control is never filled in, whatever was configured.
        if (SensitiveDataFilter.isSensitiveField(located.type(), located.name(), located.domId())) {
            stepStart = System.nanoTime();
            String safeName =
                SensitiveDataFilter.safeFieldName(located.type(), located.name(), located.domId());
            execution.refusedSensitiveField(safeName, elapsedMs(stepStart));
            return new Result(Outcome.SKIPPED,
                "The configured field is a sensitive control, so nothing was typed into it.");
        }

        // Read once, before anything is typed, so cleanup can put back exactly what was there. The
        // field has already been proven non-sensitive, and the value is never recorded: only its
        // length reaches the evidence, exactly as the typed value does.
        stepStart = System.nanoTime();
        String originalValue = actions.readBackFieldValue();
        execution.capturedOriginalValue(
            form.fieldSelector(), lengthOf(originalValue), elapsedMs(stepStart));

        String testValue = form.testValue();
        stepStart = System.nanoTime();
        try {
            actions.clearAndType(testValue);
        } catch (RuntimeException interactionFailure) {
            execution.interactionFailed(
                form.fieldSelector(), interactionFailure.toString(), elapsedMs(stepStart));
            throw interactionFailure;
        }
        execution.clearedAndTypedTestValue(
            form.fieldSelector(), testValue.length(), elapsedMs(stepStart));

        stepStart = System.nanoTime();
        boolean matches = testValue.equals(actions.readBackFieldValue());
        execution.verifiedFieldValue(form.fieldSelector(), matches, elapsedMs(stepStart));

        // Cleanup. The verdict above is already decided: whether the field could be put back is a
        // separate fact about this run, and it is reported as one rather than folded into the
        // website's result.
        Cleanup cleanup = restoreOriginalValue(form, originalValue);

        TestedItem.Builder item = TestedItem.builder("FORMS", TestedItem.ItemType.FIELD)
            .id(scope.itemId("forms-interaction-1"))
            .name(form.displayName())
            .pageUrl(pageUrl)
            .action("Cleared the configured field, typed the configured test value into it, then put"
                + " the field's original value back. The form was not submitted.")
            .expected("The field holds exactly the configured test value after typing.")
            .completedAt(EvidenceRecorder.nowIso())
            .meta("submitted", "false")
            .meta("originalValueRestored", String.valueOf(cleanup.restored()));
        if (matches) {
            item.status(TestedItem.Status.PASSED)
                .actual("The field's value matches the configured test value."
                    + (cleanup.restored()
                        ? " The field's original value was put back afterwards."
                        : " The field's original value could not be put back."));
        } else {
            item.status(TestedItem.Status.FAILED)
                .actual("The field's value does not match the configured test value.")
                .message("Neither the expected nor the observed value is recorded.");
        }
        // Never silent, and never mistaken for the assertion above: cleanup is reported even when
        // the interaction itself passed, because the page was left holding the test value.
        if (!cleanup.restored()) {
            item.message(cleanup.message());
        }
        EvidenceRecorder.record(item.build());

        return matches
            ? new Result(Outcome.PASSED, null)
            : new Result(Outcome.FAILED, "The configured field did not hold the configured test value.");
    }

    /**
     * Puts the field's original value back, through the one typing path this module has.
     *
     * <p>Deliberately the same {@code clearAndType} call the interaction used: there is exactly one
     * way this check writes into a page, and restoring must not become a second one.
     *
     * <p>Never throws. A cleanup that failed is a fact to report, not a reason to replace the
     * interaction's verdict with an unrelated one.
     */
    private Cleanup restoreOriginalValue(WebsiteFormInteractionConfig form, String originalValue) {
        String restoreTo = originalValue == null ? "" : originalValue;
        long stepStart = System.nanoTime();
        try {
            actions.clearAndType(restoreTo);
        } catch (RuntimeException cleanupFailure) {
            execution.restorationFailed(
                form.fieldSelector(), cleanupFailure.toString(), elapsedMs(stepStart));
            return new Cleanup(false,
                "The field still holds the test value: its original value could not be put back.");
        }
        boolean restored = restoreTo.equals(actions.readBackFieldValue());
        execution.restoredOriginalValue(
            form.fieldSelector(), lengthOf(originalValue), restored, elapsedMs(stepStart));
        return restored
            ? new Cleanup(true, null)
            : new Cleanup(false,
                "The field does not hold its original value again after cleanup.");
    }

    /** What cleanup did, and what to say about it. Never carries a field value. */
    private record Cleanup(boolean restored, String message) { }

    /** A value's length, with null read as empty. The value itself never leaves this class. */
    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String formDisplayName(String name, String domId, int index) {
        if (name != null && !name.isBlank()) return name.trim();
        if (domId != null && !domId.isBlank()) return domId.trim();
        return "Form " + (index + 1);
    }

    // --- what the caller gets back ------------------------------------------------------------

    /**
     * How a Forms run ended.
     *
     * <p>{@code SKIPPED} covers both "no form to inspect" and "the configured field is sensitive".
     * Neither is a failure, and neither may read as a pass.
     */
    public enum Outcome { PASSED, SKIPPED, FAILED }

    /** The outcome of one Forms entry point, and the message the caller reports with it. */
    public record Result(Outcome outcome, String message) {

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }

        public boolean skipped() {
            return outcome == Outcome.SKIPPED;
        }
    }

    // --- the page reads Forms needs -----------------------------------------------------------

    /** One control's structure, already read. No value is ever carried here. */
    record DiscoveredControl(
        String tag, String type, String name, String domId, String label,
        boolean required, boolean displayed, boolean enabled, boolean readOnly,
        String failure, long durationMs
    ) { }

    /** One form's structure, already read, with its controls. */
    record DiscoveredForm(
        String name, String domId, String action, String method, boolean displayed,
        List<DiscoveredControl> controls, String failure, long durationMs
    ) { }

    /**
     * Where the one configured field is, and whether it can be typed into.
     *
     * <p>{@code formMatches} and {@code fieldMatches} are how many elements each selector matched,
     * not how many were used. A selector that matches several is a configuration the caller must
     * fix, and it is reported rather than silently narrowed to the first hit.
     *
     * <p>{@code readOnly} is separate from {@code enabled} because Selenium is: a readonly input is
     * reported as enabled, so a check that only asked about enabled would type into it.
     */
    record FieldLocation(
        boolean formFound, boolean fieldFound, boolean displayed, boolean enabled, boolean readOnly,
        int formMatches, int fieldMatches,
        String type, String name, String domId
    ) { }

    /**
     * The page work Forms performs. Deliberately not a general abstraction: it exists so this one
     * module can be driven without a browser.
     */
    interface FormActions {

        boolean awaitRenderedContentTimedOut();

        int count(String cssSelector);

        /** Every form on the page, reduced to structure. Reads no field value. */
        List<DiscoveredForm> discoverForms();

        /** Resolves the one configured field and remembers it for the typing below. */
        FieldLocation locateConfiguredField(String formSelector, String fieldSelector);

        /** @throws RuntimeException when the browser fails; the caller rethrows it unchanged */
        void clearAndType(String value);

        /** The field's current value, read back only to compare with what was just typed. */
        String readBackFieldValue();
    }

    /** The production page work, over the page the caller already opened. */
    private static final class CommonPageForms implements FormActions {

        private final CommonPage page;
        private final WebDriver driver;
        private WebElement configuredField;

        CommonPageForms(CommonPage page, WebDriver driver) {
            this.page = page;
            this.driver = driver;
        }

        @Override
        public boolean awaitRenderedContentTimedOut() {
            return page.awaitRenderedContent().timedOut();
        }

        @Override
        public int count(String cssSelector) {
            return page.count(cssSelector);
        }

        @Override
        public List<DiscoveredForm> discoverForms() {
            List<DiscoveredForm> discovered = new ArrayList<>();
            for (WebElement form : driver.findElements(By.cssSelector("form"))) {
                long formStart = System.nanoTime();
                String name = null;
                String domId = null;
                String action = null;
                String method = null;
                boolean displayed = false;
                List<WebElement> controls = List.of();
                String failure = null;
                try {
                    name = form.getAttribute("name");
                    domId = form.getAttribute("id");
                    action = form.getAttribute("action");
                    method = form.getAttribute("method");
                    displayed = form.isDisplayed();
                    controls = form.findElements(By.cssSelector("input, textarea, select, button"));
                } catch (RuntimeException inspectionFailure) {
                    failure = inspectionFailure.toString();
                }
                List<DiscoveredControl> read = new ArrayList<>();
                for (WebElement control : controls) {
                    read.add(readControl(control));
                }
                discovered.add(new DiscoveredForm(
                    name, domId, action, method, displayed, read, failure, elapsedMs(formStart)));
            }
            return discovered;
        }

        /** Structure only. A field's value is never read here. */
        private static DiscoveredControl readControl(WebElement control) {
            long controlStart = System.nanoTime();
            String tag = null;
            String type = null;
            String name = null;
            String domId = null;
            String label = null;
            boolean required = false;
            boolean displayed = false;
            boolean enabled = false;
            String readOnly = null;
            String failure = null;
            try {
                tag = control.getTagName();
                type = control.getAttribute("type");
                name = control.getAttribute("name");
                domId = control.getAttribute("id");
                label = control.getAccessibleName();
                required = control.getAttribute("required") != null;
                readOnly = control.getAttribute("readonly");
                displayed = control.isDisplayed();
                enabled = control.isEnabled();
            } catch (RuntimeException inspectionFailure) {
                failure = inspectionFailure.toString();
            }
            return new DiscoveredControl(tag, type, name, domId, label, required, displayed,
                enabled, readOnly != null, failure, elapsedMs(controlStart));
        }

        @Override
        public FieldLocation locateConfiguredField(String formSelector, String fieldSelector) {
            configuredField = null;
            List<WebElement> forms = driver.findElements(By.cssSelector(formSelector));
            if (forms.isEmpty()) {
                return new FieldLocation(false, false, false, false, false, 0, 0, null, null, null);
            }
            List<WebElement> fields = forms.get(0).findElements(By.cssSelector(fieldSelector));
            if (fields.isEmpty()) {
                return new FieldLocation(
                    true, false, false, false, false, forms.size(), 0, null, null, null);
            }
            // Remembered even when the counts below make this an ambiguous configuration: the
            // caller refuses on the counts, and nothing reads this element unless it refuses not to.
            configuredField = fields.get(0);
            return new FieldLocation(true, true,
                configuredField.isDisplayed(), configuredField.isEnabled(),
                configuredField.getAttribute("readonly") != null,
                forms.size(), fields.size(),
                configuredField.getAttribute("type"),
                configuredField.getAttribute("name"),
                configuredField.getAttribute("id"));
        }

        @Override
        public void clearAndType(String value) {
            configuredField.clear();
            configuredField.sendKeys(value);
        }

        @Override
        public String readBackFieldValue() {
            return configuredField.getAttribute("value");
        }
    }
}
