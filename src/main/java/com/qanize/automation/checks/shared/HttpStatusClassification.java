package com.qanize.automation.checks.shared;

/**
 * What one HTTP response status says about a destination.
 *
 * <p>The Links check used to ask one question of every status — "is it below 400?" — and report
 * anything else as a link that did not resolve. A run of the-internet reported
 * {@code /basic_auth} and {@code /digest_auth} as broken links; both are healthy pages that
 * answered exactly as designed, because nobody presented credentials. Calling that a defect on the
 * customer's website is the opposite of what the report is for, and it is the reason a status now
 * has a classification rather than a threshold.
 *
 * <p><strong>Three different things live above 400.</strong> A destination that refuses an
 * unauthenticated request is protected, not broken. A destination that refuses because too many
 * requests arrived is rate limiting the analyzer, not failing its visitors. A destination that is
 * gone, or that answered with a server error, is the thing the check exists to find. Only the last
 * of those is a finding about the website.
 *
 * <p><strong>A pure function of one integer.</strong> No URL is inspected, no path is read, no
 * page content is consulted, and nothing about a run reaches this class — so the same status is
 * always classified the same way and the whole rule can be unit tested. Deciding "this looks like
 * a login page" from an address is exactly the inference this project has repeatedly removed.
 */
public enum HttpStatusClassification {

    /** The destination answered. Nothing is wrong with it. */
    SUCCESS,

    /**
     * The destination exists and declined to serve this request without credentials.
     *
     * <p>401 (unauthenticated), 403 (forbidden for this caller) and 407 (a proxy wants credentials).
     * Nothing has been established about the page behind the challenge, so this is neither a pass
     * nor a failure: it is a destination the run could not check.
     *
     * <p>403 is included deliberately. A bot-protection layer, a geo-block and a permission rule
     * all answer 403, and none of them means the link is dead — which is precisely why it must not
     * be reported as one.
     */
    AUTH_REQUIRED,

    /**
     * The destination asked the analyzer to slow down (429).
     *
     * <p>A statement about how fast this run asked, not about whether the link works for a person.
     */
    RATE_LIMITED,

    /**
     * The destination is genuinely unusable: it is missing, gone, or the server failed.
     *
     * <p>404 and 410 name the two that say the page is not there, and every 5xx says the server
     * could not produce it. Every other client error at or above 400 lands here too, because a
     * destination that rejects an ordinary GET a visitor's browser would make is a destination that
     * visitor cannot reach.
     */
    BROKEN,

    /** No response arrived at all, so no status can be classified. */
    NO_RESPONSE;

    /**
     * Classifies one status code, or the absence of one.
     *
     * @param statusCode the status the destination answered with, or null when no response arrived
     */
    public static HttpStatusClassification of(Integer statusCode) {
        if (statusCode == null) return NO_RESPONSE;
        int status = statusCode;
        if (status < 400) return SUCCESS;
        return switch (status) {
            case 401, 403, 407 -> AUTH_REQUIRED;
            case 429 -> RATE_LIMITED;
            default -> BROKEN;
        };
    }

    /** Whether this status is a defect on the website the run is reporting on. */
    public boolean brokenDestination() {
        return this == BROKEN;
    }

    /**
     * Whether the destination answered without establishing anything about its health.
     *
     * <p>The check requested it, got an answer, and still cannot say whether the page behind that
     * answer is usable. Reported as a safe skip with the reason, never as a pass and never as a
     * failure.
     */
    public boolean unverified() {
        return this == AUTH_REQUIRED || this == RATE_LIMITED;
    }

    /**
     * The customer-facing sentence for this classification, naming the status it came from.
     *
     * <p>Written here so the Links check, the page-health check and any future caller say the same
     * thing about the same status rather than three paraphrases of it.
     *
     * @param statusCode the status that produced this classification, used only to quote it back
     */
    public String reason(Integer statusCode) {
        String observed = statusCode == null ? "no response" : "HTTP " + statusCode;
        return switch (this) {
            case SUCCESS -> "The destination responded with " + observed + ".";
            case AUTH_REQUIRED -> "The destination requires authentication and answered " + observed
                + ", so whether the page behind it is healthy was not established.";
            case RATE_LIMITED -> "The destination answered " + observed
                + " because too many requests were made, so it was not checked.";
            case BROKEN -> "The destination responded with " + observed + ".";
            case NO_RESPONSE -> "The request could not be completed.";
        };
    }
}
