package com.qanize.automation.checks.shared;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Pins the root cause of the "nothing discovered" runs: discovery used to query the DOM in the
 * same instant {@code driver.get} returned, which on a JavaScript-rendered page is the empty
 * shell. These tests drive the timing directly, so they need no browser and never sleep.
 */
@Tag("unit")
class PageContentReadinessTest {

    /** A counter that reports the given values in order, then repeats the last one. */
    private static java.util.function.IntSupplier counts(int... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }

    @Test
    void serverRenderedContentIsFoundOnTheFirstPollAndNeverPauses() {
        AtomicInteger pauses = new AtomicInteger();
        PageContentReadiness.Result result =
            PageContentReadiness.awaitNonEmpty(counts(12), 20, pauses::incrementAndGet);

        assertEquals(1, result.attempts(), "a page that is already rendered must not be polled twice");
        assertEquals(12, result.count());
        assertFalse(result.timedOut());
        assertTrue(result.found());
        assertEquals(0, pauses.get(), "waiting must cost nothing when the content is already there");
    }

    @Test
    void contentThatAppearsLaterIsStillDiscovered() {
        // This is the regression: the first two polls see the empty SPA shell.
        AtomicInteger pauses = new AtomicInteger();
        PageContentReadiness.Result result =
            PageContentReadiness.awaitNonEmpty(counts(0, 0, 7), 20, pauses::incrementAndGet);

        assertEquals(3, result.attempts());
        assertEquals(7, result.count());
        assertFalse(result.timedOut());
        assertEquals(2, pauses.get(), "one pause between each poll, never after the last");
    }

    @Test
    void aPageThatRendersNothingTimesOutInsteadOfWaitingForever() {
        AtomicInteger pauses = new AtomicInteger();
        PageContentReadiness.Result result =
            PageContentReadiness.awaitNonEmpty(counts(0), 4, pauses::incrementAndGet);

        assertEquals(4, result.attempts());
        assertEquals(0, result.count());
        assertTrue(result.timedOut(), "an empty page must be reported as a timeout, not as content");
        assertFalse(result.found());
        assertEquals(3, pauses.get());
    }

    @Test
    void atLeastOnePollAlwaysHappens() {
        PageContentReadiness.Result result =
            PageContentReadiness.awaitNonEmpty(counts(5), 0, () -> { });

        assertEquals(1, result.attempts());
        assertEquals(5, result.count());
        assertFalse(result.timedOut());
    }
}
