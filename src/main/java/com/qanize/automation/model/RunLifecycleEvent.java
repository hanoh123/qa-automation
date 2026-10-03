package com.qanize.automation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One notable transition in a run.
 *
 * <p>Contract 1.1, section 3. Named {@code RunLifecycleEvent} after the section's own title,
 * rather than {@code RunEvent}, so it cannot be confused with {@link RunLifecycle} — that enum is
 * a terminal state for the whole run, this is one moment inside it.
 *
 * <p>{@code sequence} is the ordering authority, not {@code timestamp}: two events can share a
 * millisecond, and re-sorting a timeline by clock time would silently reorder them (section 8.3).
 *
 * <p>This record holds no credential values. {@code sanitizedTechnicalData} may carry credential
 * environment variable <em>names</em>; redaction of values happens before construction, at the
 * recorder boundary (section 8.6).
 */
public record RunLifecycleEvent(
    int sequence,
    Instant timestamp,
    Phase phase,
    String action,
    Status status,
    long durationMs,
    String plainLanguageMessage,
    Map<String, String> sanitizedTechnicalData
) {

    /** Lifecycle events describe transitions, so they start as well as conclude. */
    public enum Status {
        STARTED,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    public RunLifecycleEvent {
        sequence = Invariants.requirePositiveSequence(sequence, "RunLifecycleEvent.sequence");
        timestamp = Invariants.requireField(timestamp, "RunLifecycleEvent.timestamp");
        phase = Invariants.requireField(phase, "RunLifecycleEvent.phase");
        action = Invariants.requireText(action, "RunLifecycleEvent.action");
        status = Invariants.requireField(status, "RunLifecycleEvent.status");
        plainLanguageMessage =
            Invariants.requireText(plainLanguageMessage, "RunLifecycleEvent.plainLanguageMessage");
        durationMs = Invariants.requireNonNegative(durationMs, "RunLifecycleEvent.durationMs");
        sanitizedTechnicalData = Invariants.immutableMap(
            sanitizedTechnicalData, "RunLifecycleEvent.sanitizedTechnicalData");
    }

    /**
     * Invariant 7 at run scope: the timeline is unique and gapless from 1.
     *
     * <p>A single event cannot verify this — only the list can. Contract 1.1 publishes the list as
     * {@code summary.events[]}, and the record that will own it does not exist until the summary
     * is assembled. Until then this is the enforcement point, so the invariant is checkable now
     * rather than deferred to a slice that might forget it.
     *
     * @return an immutable copy in sequence order
     */
    public static List<RunLifecycleEvent> validateTimeline(List<RunLifecycleEvent> events) {
        List<RunLifecycleEvent> copy = Invariants.immutableList(events, "events");
        List<Integer> sequences = new ArrayList<>(copy.size());
        for (RunLifecycleEvent event : copy) {
            sequences.add(event.sequence());
        }
        Invariants.requireGaplessSequence(sequences, "events[].sequence");
        return copy;
    }
}
