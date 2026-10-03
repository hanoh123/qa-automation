package com.qanize.automation.model;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract 1.1, section 6: the atomic evidence record. */
@Tag("unit")
class ExecutionStepTest {

    private static ExecutionStep step(int sequence) {
        return new ExecutionStep(
            sequence, Phase.ACTION, ExecutionMethod.NAVIGATE,
            "Opened the destination in the browser.",
            "Pricing", "a[href='/pricing']",
            "The page reaches a usable ready state.", "The server returned HTTP 404.",
            ExecutionStep.Status.FAILED, 502, "screenshot-003"
        );
    }

    // --- enum value sets -----------------------------------------------------------------

    @Test
    void phaseHasExactlyTheApprovedValuesInOrder() {
        assertEquals(
            List.of("SETUP", "ACTION", "WAIT", "DISCOVERY", "ASSERTION", "EVIDENCE", "CLEANUP"),
            Arrays.stream(Phase.values()).map(Enum::name).toList()
        );
    }

    @Test
    void executionMethodHasExactlyTheApprovedValuesInOrder() {
        assertEquals(
            List.of("BROWSER_START", "NAVIGATE", "CLICK", "TYPE", "DOM_INSPECTION",
                    "HTTP_REQUEST", "WAIT_FOR_CONDITION", "ASSERT", "SCREENSHOT", "BROWSER_CLOSE"),
            Arrays.stream(ExecutionMethod.values()).map(Enum::name).toList()
        );
    }

    @Test
    void stepStatusIsNarrowerThanACheckStatus() {
        // A single action cannot be WARNING or NOT_TESTED; only a whole check can.
        assertEquals(
            List.of("PASSED", "FAILED", "SKIPPED"),
            Arrays.stream(ExecutionStep.Status.values()).map(Enum::name).toList()
        );
    }

    // --- required fields -----------------------------------------------------------------

    @Test
    void everyRequiredFieldIsNamedWhenItIsMissing() {
        assertTrue(rejectionMessage(() -> new ExecutionStep(
            1, null, ExecutionMethod.ASSERT, "a", "", "", "", "", ExecutionStep.Status.PASSED, 0, ""
        )).contains("ExecutionStep.phase"));

        assertTrue(rejectionMessage(() -> new ExecutionStep(
            1, Phase.ACTION, null, "a", "", "", "", "", ExecutionStep.Status.PASSED, 0, ""
        )).contains("ExecutionStep.method"));

        assertTrue(rejectionMessage(() -> new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.ASSERT, null, "", "", "", "",
            ExecutionStep.Status.PASSED, 0, ""
        )).contains("ExecutionStep.action"));

        assertTrue(rejectionMessage(() -> new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.ASSERT, "a", "", "", "", "", null, 0, ""
        )).contains("ExecutionStep.status"));
    }

    @Test
    void aBlankRequiredActionIsRejectedBecauseItAssertsNothing() {
        assertTrue(rejectionMessage(() -> new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.ASSERT, "   ", "", "", "", "",
            ExecutionStep.Status.PASSED, 0, ""
        )).contains("must not be blank"));
    }

    // --- optional fields -----------------------------------------------------------------

    @Test
    void optionalTextIsNormalizedToEmptyRatherThanLeftNull() {
        ExecutionStep step = new ExecutionStep(
            1, Phase.SETUP, ExecutionMethod.BROWSER_START, "Started the browser.",
            null, null, null, null, ExecutionStep.Status.PASSED, 0, null
        );

        // Absent and empty must never be two different states for a reader.
        assertEquals("", step.targetLabel());
        assertEquals("", step.sanitizedLocator());
        assertEquals("", step.expectedObservation());
        assertEquals("", step.actualObservation());
        assertEquals("", step.evidenceRef());
    }

    @Test
    void textIsTrimmedSoWhitespaceNeverBecomesAMeaningfulDifference() {
        ExecutionStep step = new ExecutionStep(
            1, Phase.SETUP, ExecutionMethod.BROWSER_START, "  Started the browser.  ",
            "  Home  ", "", "", "", ExecutionStep.Status.PASSED, 0, ""
        );

        assertEquals("Started the browser.", step.action());
        assertEquals("Home", step.targetLabel());
    }

    // --- numeric rules -------------------------------------------------------------------

    @Test
    void sequenceMustBeOneBased() {
        for (int invalid : new int[] {0, -1, Integer.MIN_VALUE}) {
            String message = rejectionMessage(() -> new ExecutionStep(
                invalid, Phase.ACTION, ExecutionMethod.ASSERT, "a", "", "", "", "",
                ExecutionStep.Status.PASSED, 0, ""
            ));
            assertTrue(message.contains("ExecutionStep.sequence"), message);
            assertTrue(message.contains("1-based"), message);
        }
    }

    @Test
    void durationIsMillisecondsAndNeverNegative() {
        String message = rejectionMessage(() -> new ExecutionStep(
            1, Phase.ACTION, ExecutionMethod.ASSERT, "a", "", "", "", "",
            ExecutionStep.Status.PASSED, -1, ""
        ));
        assertTrue(message.contains("ExecutionStep.durationMs"), message);
        assertTrue(message.contains("negative"), message);

        // An unmeasurable duration is zero, not an error.
        ExecutionStep unmeasured = new ExecutionStep(
            1, Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION, "Waited.", "", "", "", "",
            ExecutionStep.Status.SKIPPED, 0, ""
        );
        assertEquals(0L, unmeasured.durationMs());
        assertEquals(502L, step(1).durationMs());
    }

    // --- phase and method combinations ---------------------------------------------------

    @Test
    void everyPhaseAndMethodCombinationIsStructurallyValid() {
        // The model does not police which method belongs to which phase: a NAVIGATE can legitimately
        // occur during SETUP, ACTION or CLEANUP, and inventing a rule here would reject real runs.
        int built = 0;
        for (Phase phase : Phase.values()) {
            for (ExecutionMethod method : ExecutionMethod.values()) {
                ExecutionStep step = new ExecutionStep(
                    1, phase, method, "did something", "", "", "", "",
                    ExecutionStep.Status.PASSED, 0, ""
                );
                assertEquals(phase, step.phase());
                assertEquals(method, step.method());
                built++;
            }
        }
        assertEquals(Phase.values().length * ExecutionMethod.values().length, built);
    }

    // --- record semantics ----------------------------------------------------------------

    @Test
    void equalComponentsProduceEqualStepsAndHashCodes() {
        assertEquals(step(1), step(1));
        assertEquals(step(1).hashCode(), step(1).hashCode());
        assertNotEquals(step(1), step(2));
    }

    private static String rejectionMessage(Runnable construction) {
        return assertThrows(IllegalArgumentException.class, construction::run).getMessage();
    }
}
