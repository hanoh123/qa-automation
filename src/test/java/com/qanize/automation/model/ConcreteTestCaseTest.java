package com.qanize.automation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract 1.1, section 5: one real thing a check acted on. */
@Tag("unit")
class ConcreteTestCaseTest {

    private static ExecutionStep step(int sequence) {
        return new ExecutionStep(
            sequence, Phase.ACTION, ExecutionMethod.NAVIGATE, "Opened the destination.",
            "Pricing", "", "", "", ExecutionStep.Status.PASSED, 10, ""
        );
    }

    private static ConcreteTestCase testCase(
        ConcreteTestCase.Status status, List<ExecutionStep> steps, ErrorCategory errorCategory
    ) {
        return new ConcreteTestCase(
            "links-007", CheckId.LINKS, "Pricing link in the main navigation", status,
            "https://example.com/", "https://example.com/pricing", ExecutionMethod.HTTP_REQUEST,
            Instant.parse("2026-08-13T09:14:26.400Z"), Instant.parse("2026-08-13T09:14:26.902Z"),
            502, "The destination returns a successful response.",
            "The destination returned HTTP 404.", steps, List.of("screenshot-003"), errorCategory
        );
    }

    // --- enum value set ------------------------------------------------------------------

    @Test
    void caseStatusHasExactlyTheApprovedValues() {
        assertEquals(
            List.of("PASSED", "FAILED", "SKIPPED"),
            Arrays.stream(ConcreteTestCase.Status.values()).map(Enum::name).toList()
        );
    }

    @Test
    void errorCategoryHasExactlyTheApprovedValuesInOrder() {
        assertEquals(
            List.of("WEBSITE_FINDING", "CONFIGURATION_ERROR", "INFRASTRUCTURE_ERROR",
                    "NOT_APPLICABLE", "NOT_SELECTED"),
            Arrays.stream(ErrorCategory.values()).map(Enum::name).toList()
        );
    }

    @Test
    void everyErrorCategoryCanBeCarriedByANonPassingCase() {
        for (ErrorCategory category : ErrorCategory.values()) {
            ConcreteTestCase result =
                testCase(ConcreteTestCase.Status.FAILED, List.of(), category);
            assertEquals(category, result.errorCategory(), category.name());
        }
    }

    // --- required fields -----------------------------------------------------------------

    @Test
    void requiredFieldsAreNamedWhenMissing() {
        assertTrue(rejection(() -> new ConcreteTestCase(
            null, CheckId.LINKS, "n", ConcreteTestCase.Status.PASSED, null, null, null, null, null,
            0, null, null, null, null, null
        )).contains("ConcreteTestCase.testCaseId"));

        assertTrue(rejection(() -> new ConcreteTestCase(
            "id", null, "n", ConcreteTestCase.Status.PASSED, null, null, null, null, null,
            0, null, null, null, null, null
        )).contains("ConcreteTestCase.checkId"));

        assertTrue(rejection(() -> new ConcreteTestCase(
            "id", CheckId.LINKS, "  ", ConcreteTestCase.Status.PASSED, null, null, null, null, null,
            0, null, null, null, null, null
        )).contains("ConcreteTestCase.name"));

        assertTrue(rejection(() -> new ConcreteTestCase(
            "id", CheckId.LINKS, "n", null, null, null, null, null, null,
            0, null, null, null, null, null
        )).contains("ConcreteTestCase.status"));
    }

    // --- optional fields -----------------------------------------------------------------

    @Test
    void aMinimalCaseIsValidWithEveryOptionalFieldAbsent() {
        // Section 8.14: only the four fields whose absence would let the report assert something
        // untrue are required.
        ConcreteTestCase minimal = new ConcreteTestCase(
            "smoke-001", CheckId.SMOKE, "Home page", ConcreteTestCase.Status.SKIPPED,
            null, null, null, null, null, 0, null, null, null, null, null
        );

        assertEquals("", minimal.sourceUrl());
        assertEquals("", minimal.target());
        assertEquals("", minimal.expected());
        assertEquals("", minimal.actual());
        assertNull(minimal.executionMethod());
        assertNull(minimal.startedAt());
        assertNull(minimal.completedAt());
        assertNull(minimal.errorCategory());
        assertEquals(List.of(), minimal.steps());
        assertEquals(List.of(), minimal.evidenceRefs());
    }

    @Test
    void emptyStepsMeansNotRecordedRatherThanNoStepsOccurred() {
        // Section 8.2. The model cannot express the difference, so it must not reject the case —
        // a check that crashed before instrumentation still produces a valid record.
        ConcreteTestCase uninstrumented =
            testCase(ConcreteTestCase.Status.PASSED, List.of(), null);
        assertEquals(List.of(), uninstrumented.steps());
    }

    // --- invariant 7: gapless, unique step sequence ---------------------------------------

    @Test
    void orderedStepsAreAcceptedAndKeptInOrder() {
        ConcreteTestCase result = testCase(
            ConcreteTestCase.Status.PASSED, List.of(step(1), step(2), step(3)), null);

        assertEquals(List.of(1, 2, 3), result.steps().stream().map(ExecutionStep::sequence).toList());
    }

    @Test
    void aGapInTheStepSequenceIsRejected() {
        String message = rejection(() -> testCase(
            ConcreteTestCase.Status.PASSED, List.of(step(1), step(3)), null));
        assertTrue(message.contains("gapless"), message);
        assertTrue(message.contains("expected 2"), message);
    }

    @Test
    void aDuplicateStepSequenceIsRejected() {
        String message = rejection(() -> testCase(
            ConcreteTestCase.Status.PASSED, List.of(step(1), step(1)), null));
        assertTrue(message.contains("gapless"), message);
    }

    @Test
    void stepsThatDoNotStartAtOneAreRejected() {
        String message = rejection(() -> testCase(
            ConcreteTestCase.Status.PASSED, List.of(step(2), step(3)), null));
        assertTrue(message.contains("expected 1"), message);
    }

    @Test
    void outOfOrderStepsAreRejectedRatherThanSilentlySorted() {
        // Re-sorting would repair the symptom and hide a recorder defect.
        assertTrue(rejection(() -> testCase(
            ConcreteTestCase.Status.PASSED, List.of(step(2), step(1)), null)).contains("gapless"));
    }

    // --- invariant 5: a passing case has nothing to categorize ----------------------------

    @Test
    void aPassingCaseMayNotCarryAnErrorCategory() {
        for (ErrorCategory category : ErrorCategory.values()) {
            String message = rejection(() ->
                testCase(ConcreteTestCase.Status.PASSED, List.of(), category));
            assertTrue(message.contains("ConcreteTestCase.errorCategory"), message);
            assertTrue(message.contains("PASSED"), message);
        }
    }

    @Test
    void aPassingCaseWithoutAnErrorCategoryIsValid() {
        assertNull(testCase(ConcreteTestCase.Status.PASSED, List.of(), null).errorCategory());
    }

    @Test
    void aFailingCaseWithoutAnErrorCategoryIsStillValid() {
        // The design requires PASSED implies null; it does not require the converse, because a
        // failure whose cause is not yet classified must remain expressible.
        assertNull(testCase(ConcreteTestCase.Status.FAILED, List.of(), null).errorCategory());
    }

    // --- immutability --------------------------------------------------------------------

    @Test
    void listsAreDefensivelyCopiedAtConstruction() {
        List<ExecutionStep> steps = new ArrayList<>(List.of(step(1)));
        List<String> refs = new ArrayList<>(List.of("screenshot-001"));
        ConcreteTestCase result = new ConcreteTestCase(
            "links-1", CheckId.LINKS, "A link", ConcreteTestCase.Status.PASSED,
            null, null, null, null, null, 0, null, null, steps, refs, null
        );

        steps.add(step(2));
        refs.add("screenshot-002");

        assertEquals(1, result.steps().size(), "the record must not observe later mutation");
        assertEquals(1, result.evidenceRefs().size());
    }

    @Test
    void exposedListsCannotBeMutated() {
        ConcreteTestCase result = testCase(ConcreteTestCase.Status.PASSED, List.of(step(1)), null);

        assertThrows(UnsupportedOperationException.class, () -> result.steps().add(step(2)));
        assertThrows(UnsupportedOperationException.class, () -> result.evidenceRefs().add("x"));
    }

    @Test
    void aNullElementInAListIsRejectedWithItsPosition() {
        List<String> refs = new ArrayList<>();
        refs.add("screenshot-001");
        refs.add(null);

        String message = rejection(() -> new ConcreteTestCase(
            "links-1", CheckId.LINKS, "A link", ConcreteTestCase.Status.PASSED,
            null, null, null, null, null, 0, null, null, null, refs, null
        ));
        assertTrue(message.contains("evidenceRefs[1]"), message);
    }

    // --- record semantics ----------------------------------------------------------------

    @Test
    void equalComponentsProduceEqualCases() {
        ConcreteTestCase left = testCase(ConcreteTestCase.Status.FAILED, List.of(step(1)),
            ErrorCategory.WEBSITE_FINDING);
        ConcreteTestCase right = testCase(ConcreteTestCase.Status.FAILED, List.of(step(1)),
            ErrorCategory.WEBSITE_FINDING);

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertNotEquals(left, testCase(ConcreteTestCase.Status.SKIPPED, List.of(step(1)),
            ErrorCategory.NOT_APPLICABLE));
    }

    private static String rejection(Runnable construction) {
        return assertThrows(IllegalArgumentException.class, construction::run).getMessage();
    }
}
