package com.qanize.automation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract 1.1, section 4 and the invariants in section 9.
 *
 * <p>Invariants 1–3 are the machine-checkable form of the rule this project has held by hand since
 * the SPA discovery defect. The tests below are written to prove a fabricated pass cannot be
 * constructed at all, rather than that it is merely not produced today.
 */
@Tag("unit")
class CheckExecutionTest {

    private static CheckExecution execution(
        CheckId checkId, boolean selected, boolean executed, CheckExecution.Status status,
        int discovered, int tested, int passed, int failed, int skipped, String skipReason,
        List<ConcreteTestCase> testCases
    ) {
        return new CheckExecution(
            checkId, selected, executed, status,
            Instant.parse("2026-08-13T09:14:24.002Z"), Instant.parse("2026-08-13T09:14:31.115Z"),
            7113, DiscoveryUnit.LINK, discovered, tested, passed, failed, skipped, skipReason,
            testCases, List.of("screenshot-001")
        );
    }

    private static CheckExecution passingLinks() {
        return execution(CheckId.LINKS, true, true, CheckExecution.Status.PASSED,
            14, 12, 12, 0, 2, "", List.of());
    }

    private static ConcreteTestCase testCase(
        CheckId checkId, ConcreteTestCase.Status status, ErrorCategory errorCategory
    ) {
        return new ConcreteTestCase(
            checkId.name().toLowerCase(java.util.Locale.ROOT) + "-001", checkId, "An item", status,
            null, null, null, null, null, 0, null, null, null, null, errorCategory
        );
    }

    // --- enum value sets -----------------------------------------------------------------

    @Test
    void checkStatusMirrorsTheContractOneStates() {
        assertEquals(
            List.of("PASSED", "FAILED", "WARNING", "SKIPPED", "NOT_TESTED"),
            Arrays.stream(CheckExecution.Status.values()).map(Enum::name).toList()
        );
    }

    @Test
    void discoveryUnitHasExactlyTheApprovedValuesInOrder() {
        assertEquals(
            List.of("PAGE", "LINK", "FORM", "FIELD", "SEARCH", "LOGIN"),
            Arrays.stream(DiscoveryUnit.values()).map(Enum::name).toList()
        );
    }

    // --- required and optional fields ----------------------------------------------------

    @Test
    void requiredFieldsAreNamedWhenMissing() {
        assertTrue(rejection(() -> execution(
            null, true, true, CheckExecution.Status.NOT_TESTED, 0, 0, 0, 0, 0, "", List.of()
        )).contains("CheckExecution.checkId"));

        assertTrue(rejection(() -> execution(
            CheckId.LINKS, true, true, null, 0, 0, 0, 0, 0, "", List.of()
        )).contains("CheckExecution.status"));
    }

    @Test
    void aMinimalNotTestedCheckIsValidWithEveryOptionalFieldAbsent() {
        CheckExecution minimal = new CheckExecution(
            CheckId.FORMS, false, false, CheckExecution.Status.NOT_TESTED,
            null, null, 0, null, 0, 0, 0, 0, 0, null, null, null
        );

        assertNull(minimal.startedAt());
        assertNull(minimal.completedAt());
        assertNull(minimal.discoveryUnit());
        assertEquals(0L, minimal.durationMs());
        assertEquals("", minimal.skipReason());
        assertEquals(List.of(), minimal.testCases());
        assertEquals(List.of(), minimal.evidenceRefs());
    }

    // --- invariant 1: testedCount == passed + failed --------------------------------------

    @Test
    void testedCountMustEqualPassedPlusFailed() {
        String message = rejection(() -> execution(
            CheckId.LINKS, true, true, CheckExecution.Status.FAILED,
            14, 12, 10, 1, 2, "", List.of()));
        assertTrue(message.contains("testedCount must equal passed + failed"), message);
    }

    @Test
    void anExactlyReconcilingCheckIsAccepted() {
        CheckExecution links = passingLinks();
        assertEquals(links.testedCount(), links.passed() + links.failed());
        assertEquals(links.discoveredCount(), links.testedCount() + links.skipped());
    }

    // --- invariant 2: discoveredCount == testedCount + skipped ----------------------------

    @Test
    void discoveredCountMustEqualTestedPlusSkipped() {
        String message = rejection(() -> execution(
            CheckId.LINKS, true, true, CheckExecution.Status.PASSED,
            20, 12, 12, 0, 2, "", List.of()));
        assertTrue(message.contains("discoveredCount must equal testedCount + skipped"), message);
    }

    @Test
    void aCheckThatDiscoveredItemsButTestedNoneIsExpressible() {
        // The SPA discovery defect in reportable form: 9 found, none eligible, nothing tested.
        CheckExecution links = execution(CheckId.LINKS, true, false, CheckExecution.Status.SKIPPED,
            9, 0, 0, 0, 9, "No eligible links were discovered.", List.of());

        assertEquals(9, links.discoveredCount());
        assertEquals(0, links.testedCount());
        assertEquals(CheckExecution.Status.SKIPPED, links.status());
    }

    // --- invariant 3: a pass requires positive execution evidence -------------------------

    @Test
    void aPassedCheckThatDidNotExecuteCannotBeConstructed() {
        String message = rejection(() -> execution(
            CheckId.LINKS, true, false, CheckExecution.Status.PASSED,
            1, 1, 1, 0, 0, "", List.of()));
        assertTrue(message.contains("PASSED requires executed=true"), message);
    }

    @Test
    void aPassedCheckWithFailuresCannotBeConstructed() {
        String message = rejection(() -> execution(
            CheckId.LINKS, true, true, CheckExecution.Status.PASSED,
            2, 2, 1, 1, 0, "", List.of()));
        assertTrue(message.contains("PASSED requires failed=0"), message);
    }

    @Test
    void aPassedCheckThatTestedNothingCannotBeConstructed() {
        // This is the exact shape the product used to publish: selected, nothing discovered,
        // nothing tested, reported green.
        String message = rejection(() -> execution(
            CheckId.FORMS, true, true, CheckExecution.Status.PASSED,
            0, 0, 0, 0, 0, "", List.of()));
        assertTrue(message.contains("PASSED requires testedCount > 0"), message);
    }

    // --- invariant 4: a skipped check must say why ----------------------------------------

    @Test
    void aSkippedCheckMustCarryAReason() {
        for (String blank : new String[] {null, "", "   "}) {
            String message = rejection(() -> execution(
                CheckId.FORMS, true, false, CheckExecution.Status.SKIPPED,
                0, 0, 0, 0, 0, blank, List.of()));
            assertTrue(message.contains("skipReason is required"), message);
        }
    }

    @Test
    void aSkippedCheckWithAReasonIsAccepted() {
        CheckExecution forms = execution(CheckId.FORMS, true, false, CheckExecution.Status.SKIPPED,
            0, 0, 0, 0, 0, "No forms were discovered on the submitted page.", List.of());
        assertEquals("No forms were discovered on the submitted page.", forms.skipReason());
    }

    // --- invariant 6 and nesting ownership ------------------------------------------------

    @Test
    void aWebsiteFindingCannotAppearInsideACheckThatDidNotExecute() {
        String message = rejection(() -> execution(
            CheckId.LINKS, true, false, CheckExecution.Status.NOT_TESTED,
            0, 0, 0, 0, 0, "",
            List.of(testCase(CheckId.LINKS, ConcreteTestCase.Status.FAILED,
                ErrorCategory.WEBSITE_FINDING))));
        assertTrue(message.contains("WEBSITE_FINDING"), message);
        assertTrue(message.contains("did not execute"), message);
    }

    @Test
    void aNonExecutingCheckMayStillCarryNonFindingCases() {
        // NOT_APPLICABLE and NOT_SELECTED describe why nothing ran; they must remain expressible.
        CheckExecution forms = execution(CheckId.FORMS, true, false, CheckExecution.Status.NOT_TESTED,
            0, 0, 0, 0, 0, "",
            List.of(testCase(CheckId.FORMS, ConcreteTestCase.Status.SKIPPED,
                ErrorCategory.NOT_APPLICABLE)));
        assertEquals(1, forms.testCases().size());
    }

    @Test
    void aCaseBelongingToAnotherCheckIsRejected() {
        String message = rejection(() -> execution(
            CheckId.LINKS, true, true, CheckExecution.Status.FAILED,
            1, 1, 0, 1, 0, "",
            List.of(testCase(CheckId.SEARCH, ConcreteTestCase.Status.FAILED,
                ErrorCategory.WEBSITE_FINDING))));
        assertTrue(message.contains("belonging to SEARCH"), message);
    }

    @Test
    void anExecutedCheckCarriesItsOwnWebsiteFindings() {
        CheckExecution links = execution(CheckId.LINKS, true, true, CheckExecution.Status.FAILED,
            1, 1, 0, 1, 0, "",
            List.of(testCase(CheckId.LINKS, ConcreteTestCase.Status.FAILED,
                ErrorCategory.WEBSITE_FINDING)));
        assertEquals(ErrorCategory.WEBSITE_FINDING, links.testCases().getFirst().errorCategory());
    }

    // --- numeric rules -------------------------------------------------------------------

    @Test
    void negativeCountsAndDurationsAreRejected() {
        assertTrue(rejection(() -> execution(
            CheckId.LINKS, true, true, CheckExecution.Status.FAILED, -1, 0, 0, 0, 0, "", List.of()
        )).contains("discoveredCount"));

        assertTrue(rejection(() -> new CheckExecution(
            CheckId.LINKS, true, true, CheckExecution.Status.NOT_TESTED, null, null, -5,
            null, 0, 0, 0, 0, 0, "", List.of(), List.of()
        )).contains("durationMs"));
    }

    // --- immutability --------------------------------------------------------------------

    @Test
    void listsAreDefensivelyCopiedAndCannotBeMutated() {
        List<String> refs = new ArrayList<>(List.of("screenshot-001"));
        CheckExecution links = new CheckExecution(
            CheckId.LINKS, true, true, CheckExecution.Status.PASSED, null, null, 0,
            DiscoveryUnit.LINK, 1, 1, 1, 0, 0, "", List.of(), refs
        );

        refs.add("screenshot-002");
        assertEquals(1, links.evidenceRefs().size());
        assertThrows(UnsupportedOperationException.class, () -> links.evidenceRefs().add("x"));
        assertThrows(UnsupportedOperationException.class,
            () -> links.testCases().add(testCase(CheckId.LINKS, ConcreteTestCase.Status.PASSED, null)));
    }

    // --- record semantics ----------------------------------------------------------------

    @Test
    void equalComponentsProduceEqualExecutions() {
        assertEquals(passingLinks(), passingLinks());
        assertEquals(passingLinks().hashCode(), passingLinks().hashCode());
    }

    private static String rejection(Runnable construction) {
        return assertThrows(IllegalArgumentException.class, construction::run).getMessage();
    }
}
