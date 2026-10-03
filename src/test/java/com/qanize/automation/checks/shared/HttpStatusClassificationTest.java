package com.qanize.automation.checks.shared;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a status means, stated once so two checks cannot disagree about it.
 *
 * <p>The defect this exists for: a run of the-internet reported {@code /basic_auth} and
 * {@code /digest_auth} as links that "did not resolve successfully". Both answered 401, which is
 * the correct answer to an unauthenticated request and says nothing whatsoever about whether the
 * page behind it is healthy.
 */
@Tag("unit")
class HttpStatusClassificationTest {

    @Test
    void anythingBelowFourHundredIsStillASuccess() {
        for (int status : List.of(200, 201, 204, 301, 302, 304, 399)) {
            assertEquals(
                HttpStatusClassification.SUCCESS,
                HttpStatusClassification.of(status),
                () -> status + " must remain a success");
        }
    }

    @Test
    void anAuthenticationChallengeIsNeverABrokenLink() {
        for (int status : List.of(401, 403, 407)) {
            HttpStatusClassification classification = HttpStatusClassification.of(status);
            assertEquals(HttpStatusClassification.AUTH_REQUIRED, classification,
                () -> status + " is a protected destination");
            assertFalse(classification.brokenDestination(),
                () -> status + " must not be reported as broken");
            assertTrue(classification.unverified(),
                () -> status + " establishes nothing about the page behind it");
        }
    }

    @Test
    void theStatusesThatMeanTheLinkIsDeadAreStillBroken() {
        // The two that say the page is not there, and the server errors the check exists to find.
        for (int status : List.of(404, 410, 500, 502, 503, 504)) {
            HttpStatusClassification classification = HttpStatusClassification.of(status);
            assertEquals(HttpStatusClassification.BROKEN, classification,
                () -> status + " is a genuinely broken destination");
            assertTrue(classification.brokenDestination(), () -> status + " is a website finding");
            assertFalse(classification.unverified(), () -> status + " was established, not skipped");
        }
    }

    @Test
    void anyOtherClientErrorIsBrokenRatherThanQuietlyExcused() {
        // Widening the protected class beyond the statuses that genuinely mean "credentials" would
        // hide real defects, so 400, 405 and 409 keep the verdict they have always had.
        for (int status : List.of(400, 405, 409, 418, 451)) {
            assertEquals(HttpStatusClassification.BROKEN, HttpStatusClassification.of(status),
                () -> status + " stays a failure");
        }
    }

    @Test
    void beingToldToSlowDownIsNotAStatementAboutTheWebsite() {
        HttpStatusClassification classification = HttpStatusClassification.of(429);

        assertEquals(HttpStatusClassification.RATE_LIMITED, classification);
        assertFalse(classification.brokenDestination(), "429 is about this run's pace, not the site");
        assertTrue(classification.unverified());
    }

    @Test
    void noResponseIsNotAStatus() {
        HttpStatusClassification classification = HttpStatusClassification.of(null);

        assertEquals(HttpStatusClassification.NO_RESPONSE, classification);
        assertFalse(classification.unverified(),
            "nothing arrived, so this is not a destination that answered and could not be checked");
        assertFalse(classification.brokenDestination(),
            "a request that never completed has told us nothing about the destination");
    }

    @Test
    void everyReasonQuotesTheStatusItCameFromAndNamesWhatItMeans() {
        assertTrue(HttpStatusClassification.of(401).reason(401).contains("HTTP 401"));
        assertTrue(HttpStatusClassification.of(401).reason(401).contains("requires authentication"));
        assertTrue(HttpStatusClassification.of(429).reason(429).contains("too many requests"));
        assertTrue(HttpStatusClassification.of(404).reason(404).contains("HTTP 404"));
        assertEquals(
            "The request could not be completed.",
            HttpStatusClassification.of(null).reason(null));
    }
}
