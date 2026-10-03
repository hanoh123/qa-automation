package com.qanize.automation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract 1.1, section 3: one notable transition in a run. */
@Tag("unit")
class RunLifecycleEventTest {

    private static RunLifecycleEvent event(int sequence) {
        return new RunLifecycleEvent(
            sequence, Instant.parse("2026-08-13T09:14:22.481Z"), Phase.SETUP,
            "BROWSER_STARTED", RunLifecycleEvent.Status.SUCCEEDED, 1840,
            "Opened a Chrome browser at 1440x900.",
            Map.of("browser", "chrome")
        );
    }

    // --- enum value set ------------------------------------------------------------------

    @Test
    void lifecycleEventStatusHasExactlyTheApprovedValuesInOrder() {
        assertEquals(
            List.of("STARTED", "SUCCEEDED", "FAILED", "SKIPPED"),
            Arrays.stream(RunLifecycleEvent.Status.values()).map(Enum::name).toList()
        );
    }

    @Test
    void theEventStatusIsDistinctFromTheRunLifecycleEnum() {
        // RunLifecycle is a terminal state for the whole run; this is one moment inside it.
        assertEquals(
            List.of("COMPLETED", "FAILED", "CANCELLED"),
            Arrays.stream(RunLifecycle.values()).map(Enum::name).toList()
        );
        assertNotEquals(
            Arrays.stream(RunLifecycle.values()).map(Enum::name).toList(),
            Arrays.stream(RunLifecycleEvent.Status.values()).map(Enum::name).toList()
        );
    }

    // --- required fields -----------------------------------------------------------------

    @Test
    void everyRequiredFieldIsNamedWhenMissing() {
        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, null, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, 0, "m", null
        )).contains("RunLifecycleEvent.timestamp"));

        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, null, "A", RunLifecycleEvent.Status.STARTED, 0, "m", null
        )).contains("RunLifecycleEvent.phase"));

        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, null, RunLifecycleEvent.Status.STARTED, 0, "m", null
        )).contains("RunLifecycleEvent.action"));

        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", null, 0, "m", null
        )).contains("RunLifecycleEvent.status"));

        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, 0, "  ", null
        )).contains("RunLifecycleEvent.plainLanguageMessage"));
    }

    // --- optional fields -----------------------------------------------------------------

    @Test
    void anInstantaneousEventWithoutTechnicalDataIsValid() {
        RunLifecycleEvent minimal = new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.CLEANUP, "BROWSER_CLOSED",
            RunLifecycleEvent.Status.SUCCEEDED, 0, "Closed the browser.", null
        );

        assertEquals(0L, minimal.durationMs());
        assertEquals(Map.of(), minimal.sanitizedTechnicalData());
    }

    // --- numeric rules -------------------------------------------------------------------

    @Test
    void sequenceMustBeOneBased() {
        String message = rejection(() -> event(0));
        assertTrue(message.contains("RunLifecycleEvent.sequence"), message);
        assertTrue(message.contains("1-based"), message);
    }

    @Test
    void durationIsNeverNegative() {
        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, -1, "m", null
        )).contains("durationMs"));
    }

    // --- map handling --------------------------------------------------------------------

    @Test
    void technicalDataIsDefensivelyCopiedAndCannotBeMutated() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("browser", "chrome");
        RunLifecycleEvent result = new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "BROWSER_STARTED",
            RunLifecycleEvent.Status.SUCCEEDED, 5, "Started.", data
        );

        data.put("viewport", "1440x900");

        assertEquals(1, result.sanitizedTechnicalData().size(), "later mutation must not be observed");
        assertThrows(UnsupportedOperationException.class,
            () -> result.sanitizedTechnicalData().put("x", "y"));
    }

    @Test
    void technicalDataPreservesInsertionOrderSoOutputIsDeterministic() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("browser", "chrome");
        data.put("viewport", "1440x900");
        data.put("driver", "chromedriver");

        RunLifecycleEvent result = new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "BROWSER_STARTED",
            RunLifecycleEvent.Status.SUCCEEDED, 5, "Started.", data
        );

        assertEquals(
            List.of("browser", "viewport", "driver"),
            new ArrayList<>(result.sanitizedTechnicalData().keySet())
        );
    }

    @Test
    void aBlankKeyOrNullValueInTechnicalDataIsRejected() {
        Map<String, String> blankKey = new LinkedHashMap<>();
        blankKey.put("  ", "value");
        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, 0, "m", blankKey
        )).contains("blank key"));

        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("browser", null);
        assertTrue(rejection(() -> new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, 0, "m", nullValue
        )).contains("browser"));
    }

    // --- invariant 7 at run scope ---------------------------------------------------------

    @Test
    void aGaplessTimelineIsAcceptedAndReturnedImmutable() {
        List<RunLifecycleEvent> timeline =
            RunLifecycleEvent.validateTimeline(List.of(event(1), event(2), event(3)));

        assertEquals(3, timeline.size());
        assertThrows(UnsupportedOperationException.class, () -> timeline.add(event(4)));
    }

    @Test
    void anEmptyTimelineIsValid() {
        assertEquals(List.of(), RunLifecycleEvent.validateTimeline(List.of()));
        assertEquals(List.of(), RunLifecycleEvent.validateTimeline(null));
    }

    @Test
    void aGapInTheTimelineIsRejectedAtTheOffendingPosition() {
        String message = rejection(() ->
            RunLifecycleEvent.validateTimeline(List.of(event(1), event(3))));
        assertTrue(message.contains("events[].sequence"), message);
        assertTrue(message.contains("expected 2"), message);
        assertTrue(message.contains("position 1"), message);
    }

    @Test
    void aDuplicateSequenceIsRejected() {
        assertTrue(rejection(() ->
            RunLifecycleEvent.validateTimeline(List.of(event(1), event(1)))).contains("gapless"));
    }

    @Test
    void aTimelineIsNotSilentlyReorderedByTimestamp() {
        // Two events can share a millisecond, so sequence is the only ordering authority. An
        // out-of-order list is a recorder defect and must surface as one.
        assertTrue(rejection(() ->
            RunLifecycleEvent.validateTimeline(List.of(event(2), event(1)))).contains("gapless"));
    }

    // --- record semantics ----------------------------------------------------------------

    @Test
    void equalComponentsProduceEqualEvents() {
        assertEquals(event(1), event(1));
        assertEquals(event(1).hashCode(), event(1).hashCode());
        assertNotEquals(event(1), event(2));
    }

    @Test
    void technicalDataEqualityIgnoresInsertionOrder() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("a", "1");
        forward.put("b", "2");
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("b", "2");
        reversed.put("a", "1");

        RunLifecycleEvent left = new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, 0, "m", forward);
        RunLifecycleEvent right = new RunLifecycleEvent(
            1, Instant.EPOCH, Phase.SETUP, "A", RunLifecycleEvent.Status.STARTED, 0, "m", reversed);

        assertEquals(left, right);
    }

    private static String rejection(Runnable construction) {
        return assertThrows(IllegalArgumentException.class, construction::run).getMessage();
    }
}
