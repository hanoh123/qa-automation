package com.qanize.automation.evidence;

import java.util.Map;

import com.qanize.automation.checks.shared.DiscoveryDiagnostics;
import com.qanize.automation.model.TestedItem;

/**
 * Persists what a check saw when it found nothing to test.
 *
 * <p>Without this the only trace of a skipped check was the sentence in its skip reason, so a
 * reader had to infer facts such as whether the page-content wait timed out from the wording.
 * The counts are written as ordinary {@code discovery.*} metadata on a single record, at the
 * point where they are measured, so no consumer has to recompute them.
 *
 * <p>The record is written with status {@link TestedItem.Status#SKIPPED}. It is never PASSED:
 * nothing was verified. Because {@code RunSummaryFactory} counts tested items as passed + failed,
 * a skipped record leaves {@code itemsTested} and {@code itemsPassed} at zero and cannot turn a
 * skipped check into a passing one.
 */
public final class DiscoveryEvidence {

    /**
     * Marks a record that explains an absence rather than describing a discovered target.
     *
     * <p>Without it the reconciler counted every evidence record of a type as a discovered item, so
     * Forms reported "No forms were discovered" in its own reason and "Discovered: 1" in its counts
     * -- the one being counted was this explanation. Nothing about the record changed; it now says
     * what it is, and the discovered total excludes it.
     */
    public static final String SYNTHETIC_META_KEY = "discoveryRecord";

    private DiscoveryEvidence() {
    }

    /**
     * Records the single diagnostic item for a check that discovered nothing to test.
     *
     * @param testType    the check reporting the skip, e.g. {@code FORMS}
     * @param id          stable identifier for the record, e.g. {@code forms-discovery}
     * @param name        customer-facing label for what was inspected
     * @param pageUrl     the page that was inspected
     * @param action      what the check actually did
     * @param expected    what would have made this check runnable
     * @param reason      the full skip reason, already including the diagnostic sentence
     * @param diagnostics measured counts for this check only
     */
    public static void recordNothingDiscovered(
        String testType,
        String id,
        String name,
        String pageUrl,
        String action,
        String expected,
        String reason,
        DiscoveryDiagnostics diagnostics
    ) {
        TestedItem.Builder item = TestedItem.builder(testType, TestedItem.ItemType.PAGE)
            .id(id)
            .name(name)
            .pageUrl(pageUrl)
            .target(pageUrl)
            .action(action)
            .expected(expected)
            .actual(reason)
            .status(TestedItem.Status.SKIPPED)
            .message(reason)
            .completedAt(EvidenceRecorder.nowIso())
            .meta(SYNTHETIC_META_KEY, "true");
        if (diagnostics != null) {
            for (Map.Entry<String, String> entry : diagnostics.asMetadata().entrySet()) {
                item.meta(entry.getKey(), entry.getValue());
            }
        }
        EvidenceRecorder.record(item.build());
    }
}
