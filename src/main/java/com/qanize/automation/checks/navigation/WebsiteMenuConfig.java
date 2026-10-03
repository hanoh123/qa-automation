package com.qanize.automation.checks.navigation;

import java.util.Optional;


/**
 * One configured click-to-open menu.
 *
 * <p>Deliberately one menu and no discovery. The analyzer will click this trigger on a real
 * customer website, so the control has to be named by the person who owns the site — guessing a
 * menu from arbitrary page buttons would mean clicking something nobody chose, on a page that may
 * sign out, submit or delete.
 *
 * <p>Both selectors blank means the feature is off, and off must be indistinguishable from never
 * having existed: no test, no evidence, no count. One selector on its own is a configuration
 * mistake rather than a half-feature, because a trigger with no panel cannot prove anything opened.
 */
public record WebsiteMenuConfig(
    String triggerSelector,
    String panelSelector,
    String label
) {

    /** A selector long enough to express any real control, and short enough to bound the artifact. */
    public static final int MAX_SELECTOR_LENGTH = 512;

    /** The label is shown to a person, so it is bounded to a readable length. */
    public static final int MAX_LABEL_LENGTH = 120;

    public static WebsiteMenuConfig disabled() {
        return new WebsiteMenuConfig(null, null, null);
    }

    public WebsiteMenuConfig {
        triggerSelector = trimToNull(triggerSelector);
        panelSelector = trimToNull(panelSelector);
        label = trimToNull(label);
    }

    /** True only when both selectors are present; a half-configured menu is rejected earlier. */
    public boolean enabled() {
        return triggerSelector != null && panelSelector != null;
    }

    public Optional<String> optionalTriggerSelector() {
        return Optional.ofNullable(triggerSelector);
    }

    public Optional<String> optionalPanelSelector() {
        return Optional.ofNullable(panelSelector);
    }

    /** What the report calls this menu. Never a selector: a selector is not a name. */
    public String displayName() {
        return label == null ? "Configured menu" : label;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
