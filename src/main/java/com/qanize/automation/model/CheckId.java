package com.qanize.automation.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The identity of a customer-facing website check.
 *
 * <p>This exists because the reporting layer used to derive the check type by uppercasing the
 * Surefire class name and searching it for words such as {@code FORM} or {@code LINK}. That made
 * the package name part of the classification: moving the checks into a package containing one of
 * those words would silently have relabelled every check, and any class the registry did not
 * recognise became Smoke without anyone noticing.
 *
 * <p>Classification here is an exact lookup on the <em>simple</em> class name, so the package is
 * structurally incapable of influencing the result. That is also what makes historical runs safe:
 * {@code com.hanoh.tests.generic.GenericSearchTest} and
 * {@code com.qanize.automation.checks.search.GenericSearchTest} share a simple name and therefore
 * classify identically, without any substring matching.
 *
 * <p>The enum constant names are the wire values written to {@code summary.json}, and they are
 * declared in the order the product presents the checks, with any identity registered ahead of its
 * behaviour last. Renaming a constant is a contract change.
 */
public enum CheckId {

    SMOKE,
    NAVIGATION,
    LINKS,
    FORMS,
    SEARCH,
    LOGIN,

    /**
     * Opening a game through its launch control and confirming it became usable.
     *
     * <p>Declared last and reported last. It is the newest check and the only one that is run once
     * for the whole run rather than once per crawled page, because a game is opened from the list
     * the run signed in to rather than from each page the crawler visits.
     */
    GAME_LAUNCH;

    /**
     * The single source of truth mapping a check class to its identity.
     *
     * <p>Keyed by simple class name so that both historical and current fully-qualified names
     * resolve through one entry. A new check must be registered here; it will otherwise be
     * unclassified rather than silently reported as Smoke.
     */
    private static final Map<String, CheckId> BY_SIMPLE_CLASS_NAME = Map.of(
        "GenericSmokeTest", SMOKE,
        "GenericNavigationTest", NAVIGATION,
        "GenericLinkValidationTest", LINKS,
        "GenericFormDetectionTest", FORMS,
        "GenericSearchTest", SEARCH,
        "GenericLoginTest", LOGIN,
        "GenericGameLaunchTest", GAME_LAUNCH
    );

    /**
     * The checks the product selects, runs and reports today, in presentation order.
     *
     * <p>Distinct from {@code values()} on purpose. {@code values()} is every identity the
     * contract knows about, which is what a parser and a classifier need. This is the list the
     * report is built from, so an identity registered ahead of its behaviour cannot add a row to
     * {@code summary.json}, a card to the Overview, or a tally to a crawl fold until the change
     * that gives it a check class moves it in here. Every declared identity is now a reported
     * check; the distinction remains because it is what makes the next one safe to register.
     */
    private static final List<CheckId> PRODUCT_CHECKS =
        List.of(SMOKE, NAVIGATION, LINKS, FORMS, SEARCH, LOGIN, GAME_LAUNCH);

    /** @see #PRODUCT_CHECKS */
    public static List<CheckId> productChecks() {
        return PRODUCT_CHECKS;
    }

    /**
     * Resolves the check that produced a Surefire class name.
     *
     * <p>Returns empty for null, blank, malformed and unregistered names. An empty result is a
     * real answer — "this class is not one of the product's checks" — and callers must not
     * substitute a default check for it.
     *
     * @param className either a fully-qualified or a simple class name, from any contract version
     */
    public static Optional<CheckId> fromClassName(String className) {
        String simple = simpleClassName(className);
        return simple.isEmpty() ? Optional.empty() : Optional.ofNullable(BY_SIMPLE_CLASS_NAME.get(simple));
    }

    /**
     * Extracts the simple class name from any class name form.
     *
     * <p>Handles the fully-qualified form Surefire writes, the bare simple name older fixtures
     * use, and the {@code Outer$Nested} form a nested test class would produce.
     */
    static String simpleClassName(String className) {
        if (className == null) return "";
        String value = className.trim();
        if (value.isEmpty()) return "";
        String afterPackage = value.substring(value.lastIndexOf('.') + 1);
        return afterPackage.substring(afterPackage.lastIndexOf('$') + 1);
    }

    /** The lower-case label used in user-facing sentences, for example "search check". */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
