package com.qanize.automation.model;

/**
 * What a check counts when it reports how much it discovered and tested.
 *
 * <p>Contract 1.1, section 4. Without it, {@code discoveredCount: 14} is a number with no noun:
 * fourteen pages, links or fields are very different results.
 *
 * <p>The value set intentionally matches {@link TestedItem.ItemType}, because both describe the
 * same six kinds of thing. They are separate types only because {@code TestedItem} is a contract
 * 1.0 record and 1.1 must not take a dependency on 1.0's shape. Converging them is recorded as a
 * follow-up, not done here, since changing {@code TestedItem} would alter shipping behaviour.
 */
public enum DiscoveryUnit {

    PAGE,
    LINK,
    FORM,
    FIELD,
    SEARCH,
    LOGIN
}
