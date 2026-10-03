package com.qanize.automation.model;

import java.time.Instant;

/**
 * One problem with the run itself, in the words the operator reads.
 *
 * <p><strong>{@code diagnostic} is the one field that is not for them.</strong> A run that died
 * left a summary saying only "internal error", which is true and useless: the next person could
 * not tell a stale element from a refused command without reproducing it. This carries the
 * smallest thing that answers that — an exception's simple class name and, at most, one frame
 * from source this project wrote — and never a message, an address, a selector or anything a page
 * put there. It is optional, blank on every existing error, and written to the artifact only when
 * something filled it in, so contract 1.0 summaries recorded before it existed stay valid and
 * parse unchanged.
 */
public record RunError(
    String category,
    String code,
    String userMessage,
    String suggestedAction,
    Instant timestamp,
    String diagnostic
) {

    /** The shape every existing caller uses: a run error nobody could say anything technical about. */
    public RunError(
        String category,
        String code,
        String userMessage,
        String suggestedAction,
        Instant timestamp
    ) {
        this(category, code, userMessage, suggestedAction, timestamp, "");
    }

    public RunError {
        diagnostic = diagnostic == null ? "" : diagnostic.trim();
    }

    /** Whether there is anything technical to show. Blank is absent, never an empty line. */
    public boolean hasDiagnostic() {
        return !diagnostic.isBlank();
    }
}
