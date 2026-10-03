package com.qanize.automation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared validation for the contract 1.1 evidence records.
 *
 * <p>Every message names the offending field, because a validation failure inside a run must be
 * diagnosable from the message alone — the record that failed to build is, by definition, not in
 * the report.
 *
 * <p>Package-private on purpose: these are the model's own rules, not an API. Callers construct
 * records and receive a precise exception; they do not get to choose which rules apply.
 */
final class Invariants {

    private Invariants() {
    }

    static <T> T requireField(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /** A required string that is blank asserts nothing, so it is treated as absent. */
    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required and must not be blank");
        }
        return value.trim();
    }

    /** Optional text is normalized to "" so absent and empty are never two different states. */
    static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    static int requirePositiveSequence(int sequence, String field) {
        if (sequence < 1) {
            throw new IllegalArgumentException(field + " must be 1-based, but was " + sequence);
        }
        return sequence;
    }

    /** Durations are milliseconds and are never negative (section 8.5). */
    static long requireNonNegative(long durationMs, String field) {
        if (durationMs < 0) {
            throw new IllegalArgumentException(field + " must not be negative, but was " + durationMs);
        }
        return durationMs;
    }

    static int requireNonNegativeCount(int count, String field) {
        if (count < 0) {
            throw new IllegalArgumentException(field + " must not be negative, but was " + count);
        }
        return count;
    }

    /** Defensive immutable copy. A null list means "not recorded", which is an empty list. */
    static <T> List<T> immutableList(List<T> values, String field) {
        if (values == null) return List.of();
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == null) {
                throw new IllegalArgumentException(field + "[" + index + "] must not be null");
            }
        }
        return List.copyOf(values);
    }

    /**
     * Defensive immutable copy preserving insertion order.
     *
     * <p>{@link Map#copyOf} would also be immutable but leaves iteration order unspecified, which
     * would make the serialized output non-deterministic between runs for no benefit.
     */
    static Map<String, String> immutableMap(Map<String, String> values, String field) {
        if (values == null || values.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException(field + " must not contain a blank key");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                    field + "[" + entry.getKey() + "] must not be null");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Invariant 7: sequence numbers are unique and gapless within their scope.
     *
     * <p>Checked against position rather than against a running counter, so both a duplicate and a
     * gap are reported at the element that first breaks the sequence.
     */
    static void requireGaplessSequence(List<Integer> sequences, String field) {
        for (int index = 0; index < sequences.size(); index++) {
            int expected = index + 1;
            int actual = sequences.get(index);
            if (actual != expected) {
                throw new IllegalArgumentException(
                    field + " must be gapless and 1-based: expected " + expected
                        + " at position " + index + ", but was " + actual);
            }
        }
    }
}
