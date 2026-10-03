package com.qanize.automation.checks.forms;

import java.util.Optional;



/**
 * One configured form field that the Forms check is allowed to fill in.
 *
 * <p>Deliberately one form, one field and no discovery. The analyzer types into a control on a
 * real customer website, so the control has to be named by the person who owns the site. Choosing
 * a field from arbitrary discovered forms would mean typing into something nobody selected, on a
 * page that may sign out, order, cancel or delete.
 *
 * <p>All three values blank means the feature is off, and off must be indistinguishable from never
 * having existed: no interaction, no evidence, no count. A partial configuration is a mistake
 * rather than a half-feature, because a field selector with no form cannot be scoped and a field
 * with no value cannot be typed into.
 *
 * <p>The test value is configuration, not a credential. It is chosen by the person configuring the
 * run precisely because it is safe to type; it is never read from an environment variable, never
 * treated as a secret, and never used to fill a sensitive control — {@code SensitiveDataFilter}
 * refuses those before any typing happens.
 */
public record WebsiteFormInteractionConfig(
    String formSelector,
    String fieldSelector,
    String testValue
) {

    /** A selector long enough to express any real control, and short enough to bound the artifact. */
    public static final int MAX_SELECTOR_LENGTH = 512;

    /** A safe test value is a short label, not a document. */
    public static final int MAX_TEST_VALUE_LENGTH = 120;

    public static WebsiteFormInteractionConfig disabled() {
        return new WebsiteFormInteractionConfig(null, null, null);
    }

    public WebsiteFormInteractionConfig {
        formSelector = trimToNull(formSelector);
        fieldSelector = trimToNull(fieldSelector);
        testValue = trimToNull(testValue);
    }

    /**
     * True only when all three values are present.
     *
     * <p>A partially configured interaction is rejected earlier, by the validator and the loader,
     * so this method never has to describe a half state.
     */
    public boolean enabled() {
        return formSelector != null && fieldSelector != null && testValue != null;
    }

    public Optional<String> optionalFormSelector() {
        return Optional.ofNullable(formSelector);
    }

    public Optional<String> optionalFieldSelector() {
        return Optional.ofNullable(fieldSelector);
    }

    public Optional<String> optionalTestValue() {
        return Optional.ofNullable(testValue);
    }

    /**
     * What the report calls this interaction.
     *
     * <p>Never a selector and never the test value: a locator is not a name, and the value belongs
     * in the field rather than in a heading.
     */
    public String displayName() {
        return "Configured form field";
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
