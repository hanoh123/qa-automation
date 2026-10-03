package com.qanize.automation.evidence;

import java.util.List;

import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionStep;

/**
 * One line of {@code execution-evidence.jsonl}, exactly as it was persisted.
 *
 * <p><strong>This is a persistence type, not a domain model.</strong> It is deliberately not a
 * replacement for {@link ExecutionStep}, {@code ConcreteTestCase} or {@code CheckExecution}: it
 * holds the committed {@link ExecutionStep} untouched and adds only the three envelope fields the
 * writer puts around it, which the step record has nowhere to keep — the record discriminator, the
 * correlation keys, the error category and the truncation note.
 *
 * <p>It exists because discarding those fields on read would lose information the producer took
 * care to persist. It does not attempt to become a test case or a check: the data for those does
 * not exist in this file, and inventing it is what this slice refuses to do.
 *
 * @param record        the discriminator, always {@code EXECUTION_STEP} for a line that parses here
 * @param checkId       which check produced the step
 * @param testCaseId    the check's own id for the thing it acted on
 * @param step          the committed contract 1.1 step, reconstructed unchanged
 * @param errorCategory the envelope's category; {@code null} for a passing step, by producer rule
 * @param truncatedFields names of fields the writer shortened; empty when nothing was cut
 */
public record ExecutionStepEnvelope(
    String record,
    CheckId checkId,
    String testCaseId,
    ExecutionStep step,
    ErrorCategory errorCategory,
    List<String> truncatedFields
) {

    public ExecutionStepEnvelope {
        truncatedFields = truncatedFields == null ? List.of() : List.copyOf(truncatedFields);
    }

    /** Whether the writer shortened any field on this line. */
    public boolean truncated() {
        return !truncatedFields.isEmpty();
    }
}
