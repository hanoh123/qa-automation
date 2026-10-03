package com.qanize.automation.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the classification contract that replaced fully-qualified-name substring matching.
 *
 * <p>The defect this guards against is silent: the old classifier answered SMOKE for anything it
 * did not recognise, so a misclassification looked exactly like a real smoke result. Every case
 * below therefore asserts the absence of a Smoke answer as explicitly as the presence of the
 * right one.
 */
@Tag("unit")
class CheckIdTest {

    /** Simple class name to expected identity — the registry restated independently of production. */
    private static final Map<String, CheckId> EXPECTED = expected();

    private static Map<String, CheckId> expected() {
        Map<String, CheckId> expected = new LinkedHashMap<>();
        expected.put("GenericSmokeTest", CheckId.SMOKE);
        expected.put("GenericNavigationTest", CheckId.NAVIGATION);
        expected.put("GenericLinkValidationTest", CheckId.LINKS);
        expected.put("GenericFormDetectionTest", CheckId.FORMS);
        expected.put("GenericSearchTest", CheckId.SEARCH);
        expected.put("GenericLoginTest", CheckId.LOGIN);
        expected.put("GenericGameLaunchTest", CheckId.GAME_LAUNCH);
        return expected;
    }

    /** The current package, as produced by the reorganized checks. */
    @Test
    void currentCheckClassesClassifyExactly() {
        for (Map.Entry<String, CheckId> entry : EXPECTED.entrySet()) {
            String className = "com.qanize.automation.checks." + entry.getKey();
            assertEquals(Optional.of(entry.getValue()), CheckId.fromClassName(className), className);
        }
    }

    /**
     * Runs recorded before the package reorganization stored the old fully-qualified names in their
     * Surefire reports, and those artifacts are preserved. They must keep resolving.
     */
    @Test
    void historicalCheckClassesClassifyIdentically() {
        for (Map.Entry<String, CheckId> entry : EXPECTED.entrySet()) {
            String className = "com.hanoh.tests.generic." + entry.getKey();
            assertEquals(Optional.of(entry.getValue()), CheckId.fromClassName(className), className);
        }
    }

    @Test
    void oldAndNewAndBareNamesResolveToTheSameIdentity() {
        for (String simpleName : EXPECTED.keySet()) {
            CheckId historical = CheckId.fromClassName("com.hanoh.tests.generic." + simpleName).orElseThrow();
            CheckId current = CheckId.fromClassName("com.qanize.automation.checks." + simpleName).orElseThrow();
            CheckId bare = CheckId.fromClassName(simpleName).orElseThrow();
            assertSame(historical, current, simpleName);
            assertSame(historical, bare, simpleName);
        }
    }

    /**
     * The whole point of the change: a package that happens to contain a check keyword must not
     * influence the answer. Under the old classifier every one of these returned that keyword's
     * category regardless of which class it actually was.
     */
    @Test
    void misleadingPackageNamesDoNotClassify() {
        for (String className : List.of(
            "com.example.forms.SomeUnrelatedTest",
            "com.example.links.SomeUnrelatedTest",
            "com.example.search.SomeUnrelatedTest",
            "com.example.login.SomeUnrelatedTest",
            "com.example.navigation.SomeUnrelatedTest",
            "com.qanize.automation.checks.forms.NotARegisteredCheck"
        )) {
            assertEquals(Optional.empty(), CheckId.fromClassName(className), className);
        }
    }

    /**
     * A registered check inside packages named after three different checks still classifies by its
     * class, proving the package is never consulted rather than merely ignored when convenient.
     */
    @Test
    void thePackageNeverOverridesTheClass() {
        assertEquals(
            Optional.of(CheckId.SEARCH),
            CheckId.fromClassName("com.example.login.forms.links.GenericSearchTest")
        );
    }

    @Test
    void unknownBlankAndMalformedNamesAreNeverSmoke() {
        for (String className : List.of(
            "", "   ", ".", "..", "$", "com.example.", "UnknownTest", "Generic", "GenericTest",
            "genericsearchtest", "GenericSearchTest2"
        )) {
            Optional<CheckId> result = CheckId.fromClassName(className);
            assertEquals(Optional.empty(), result, "\"" + className + "\"");
            assertTrue(result.filter(id -> id == CheckId.SMOKE).isEmpty(), className);
        }
    }

    @Test
    void nullIsNeverSmoke() {
        assertEquals(Optional.empty(), CheckId.fromClassName(null));
    }

    @Test
    void nestedClassNamesResolveByTheirInnermostSimpleName() {
        assertEquals(
            Optional.of(CheckId.SMOKE),
            CheckId.fromClassName("com.qanize.automation.checks.Outer$GenericSmokeTest")
        );
    }

    @Test
    void simpleClassNameHandlesEveryClassNameForm() {
        assertEquals("GenericSearchTest", CheckId.simpleClassName("com.a.b.GenericSearchTest"));
        assertEquals("GenericSearchTest", CheckId.simpleClassName("GenericSearchTest"));
        assertEquals("GenericSearchTest", CheckId.simpleClassName("  GenericSearchTest  "));
        assertEquals("Nested", CheckId.simpleClassName("com.a.Outer$Nested"));
        assertEquals("", CheckId.simpleClassName(null));
        assertEquals("", CheckId.simpleClassName("   "));
    }

    /** The wire values written to summary.json. Renaming a constant is a contract change. */
    @Test
    void wireValuesAndPresentationOrderAreFixed() {
        assertEquals(
            List.of("SMOKE", "NAVIGATION", "LINKS", "FORMS", "SEARCH", "LOGIN", "GAME_LAUNCH"),
            Arrays.stream(CheckId.values()).map(Enum::name).toList()
        );
    }

    /**
     * An identity may be registered before the check that fills it exists, but it must not reach
     * the report by doing so.
     *
     * <p>This is the guard that keeps registration inert: everything the product builds a row,
     * a card or a tally from iterates {@code productChecks()}, so an identity outside that list
     * cannot appear in {@code summary.json} no matter how many places know its name.
     */
    @Test
    void productChecksAreTheSevenReportedChecksInPresentationOrder() {
        assertEquals(
            List.of(CheckId.SMOKE, CheckId.NAVIGATION, CheckId.LINKS,
                CheckId.FORMS, CheckId.SEARCH, CheckId.LOGIN, CheckId.GAME_LAUNCH),
            CheckId.productChecks(),
            "the seven reported checks, in presentation order");
        assertTrue(CheckId.productChecks().contains(CheckId.GAME_LAUNCH),
            "GAME_LAUNCH now executes and reports, so it is a product check");
        assertSame(CheckId.GAME_LAUNCH, CheckId.productChecks().getLast(),
            "the newest check stays last, so the stable order never shifts");
    }

    /** The registry is what a classifier reads, and every identity now reports. */
    @Test
    void everyDeclaredIdentityIsAReportedCheck() {
        assertEquals(Optional.of(CheckId.GAME_LAUNCH),
            CheckId.fromClassName("com.qanize.automation.checks.GenericGameLaunchTest"));
        assertEquals(CheckId.values().length, CheckId.productChecks().size(),
            "nothing is registered ahead of its behaviour any more");
    }

    /** Every declared identity must be reachable from a registered class name, and vice versa. */
    @Test
    void theRegistryCoversExactlyTheDeclaredIdentities() {
        List<CheckId> resolved = EXPECTED.keySet().stream()
            .map(name -> CheckId.fromClassName(name).orElseThrow())
            .toList();
        assertEquals(CheckId.values().length, resolved.size());
        assertTrue(resolved.containsAll(Arrays.asList(CheckId.values())));
    }

    @Test
    void labelIsTheLowerCaseSentenceForm() {
        assertEquals("search", CheckId.SEARCH.label());
        assertEquals("navigation", CheckId.NAVIGATION.label());
    }
}
