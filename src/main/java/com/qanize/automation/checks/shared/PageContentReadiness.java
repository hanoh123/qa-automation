package com.qanize.automation.checks.shared;

import java.util.function.IntSupplier;


/**
 * Waits for a client-rendered page to actually put content in the DOM.
 *
 * <p>{@code driver.get(url)} returns as soon as the HTML document reaches readyState "complete".
 * For a JavaScript-rendered application that is the empty shell: the links, forms and navigation
 * are added afterwards by the framework. Querying immediately therefore finds nothing and the
 * check is skipped as "nothing discovered", which reads like the site has no links when in fact
 * the automation simply looked too early.
 *
 * <p>The polling is expressed against an {@link IntSupplier} and a caller-supplied pause rather
 * than a WebDriver so the timing behaviour can be unit tested deterministically, without a browser
 * and without sleeping.
 */
public final class PageContentReadiness {

    /** Outcome of waiting. {@code timedOut} distinguishes "empty page" from "still rendering". */
    public record Result(int attempts, int count, boolean timedOut) {
        public boolean found() { return count > 0; }
    }

    private PageContentReadiness() {
    }

    /**
     * Polls {@code counter} until it reports at least one element or the attempts run out.
     *
     * <p>The first poll happens immediately, so a server-rendered page costs no extra time. The
     * pause runs only between polls, never after the last one.
     *
     * @param counter     how many elements of interest are currently in the DOM
     * @param maxAttempts total number of polls, at least one
     * @param pause       what to do between polls
     */
    public static Result awaitNonEmpty(IntSupplier counter, int maxAttempts, Runnable pause) {
        int attemptLimit = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            int count = counter.getAsInt();
            if (count > 0) {
                return new Result(attempt, count, false);
            }
            if (attempt < attemptLimit) {
                pause.run();
            }
        }
        return new Result(attemptLimit, 0, true);
    }
}
