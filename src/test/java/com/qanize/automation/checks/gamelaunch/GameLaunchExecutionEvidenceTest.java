package com.qanize.automation.checks.gamelaunch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.model.ExecutionStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a game check is allowed to say out loud.
 *
 * <p>The risk here is specific. A launch address on a gaming site is signed and carries the
 * session inside its query; a metadata map copied from anywhere near a browser can carry a cookie,
 * a token or a header. So the published record is built from named parameters and an allowlist,
 * and these tests attack it the way a leak would arrive: by handing it values that contain exactly
 * those things and proving they do not come out the other side.
 */
@Tag("unit")
class GameLaunchExecutionEvidenceTest {

    @TempDir
    Path runDirectory;

    @BeforeEach
    void pointTheRecorderAtATemporaryFile() {
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("execution-evidence.jsonl").toString());
    }

    @AfterEach
    void clearTheRecorder() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    // --- the allowlist --------------------------------------------------------------------------

    @Test
    void onlyTheNamedFieldsAreEverPublished() {
        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "aviator|spribe", "Aviator", "Spribe", "PASSED",
            LaunchSurfaceDetector.SurfaceType.IFRAME,
            "https://games.example.test/launch", true,
            "REPORTED_OK", null, "VERIFIED", true);

        assertTrue(GameLaunchExecutionEvidence.PUBLISHABLE_KEYS.containsAll(published.keySet()),
            "every published key must be on the allowlist: " + published.keySet());
        assertEquals("aviator|spribe", published.get("identityKey"));
        assertEquals("Aviator", published.get("displayName"));
        assertEquals("Spribe", published.get("provider"));
        assertEquals("PASSED", published.get("gameOutcome"));
        assertEquals("IFRAME", published.get("surface"));
        assertEquals("VERIFIED", published.get("sessionPreserved"));
        assertEquals("true", published.get("cleanupSucceeded"));
    }

    @Test
    void theAllowlistHasExactlyTheAgreedFields() {
        // Pinned so a field cannot be added to the published record without this test saying so.
        // The eleven original values, then the eighteen launch diagnostics -- each one a number, a
        // word from a fixed vocabulary, four integers describing a rectangle, or an address this
        // project had already reduced. The seven after "clickTrusted" were added when one boolean
        // proved unable to tell "no click happened" from "a click happened somewhere else" and
        // "the control was gone by then".
        assertEquals(List.of(
            "identityKey", "displayName", "provider", "gameOutcome", "surface",
            "location", "locationQueryOmitted", "network", "reason",
            "sessionPreserved", "cleanupSucceeded",
            "clickObserved", "clickTrusted", "documentClick", "clickLandedOutside",
            "targetConnectedBefore", "targetConnectedAfter",
            "targetRectBefore", "targetRectAfter", "targetRectMoved",
            "locationBefore", "locationBeforeQueryOmitted",
            "surfaceBefore", "surfaceAfter", "surfaceAttempts", "launchElapsedMs",
            "browserError", "browserErrorKind", "launchDiagnosis"),
            GameLaunchExecutionEvidence.PUBLISHABLE_KEYS);
    }

    @Test
    void thePublishedRecordCannotBeExtendedByACaller() {
        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "PASSED", null, null, false, "NOT_REPORTED", null, "VERIFIED", true);

        assertThrowsUnsupported(() -> published.put("authorization", "Bearer secret"));
        assertThrowsUnsupported(() -> published.put("cookie", "session=abc"));
    }

    @Test
    void aQueryThatWasDroppedIsAlwaysStated() {
        Map<String, String> omitted = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "PASSED", null, "https://example.test/play", true, "NOT_REPORTED", null, "VERIFIED", true);
        Map<String, String> kept = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "PASSED", null, "https://example.test/lobby", false, "NOT_REPORTED", null, "VERIFIED", true);

        assertEquals("true", omitted.get("locationQueryOmitted"));
        assertEquals("false", kept.get("locationQueryOmitted"));
    }

    @Test
    void theOnlyAddressThatCanBePublishedIsOneThatWasAlreadyReduced() {
        // The detector reduces before this class is reached, and this is what it produces.
        LaunchSurfaceDetector.SafeLocation safe = LaunchSurfaceDetector.safeLocation(
            "https://games.example.test/launch?token=abc&sig=deadbeef");
        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "PASSED", LaunchSurfaceDetector.SurfaceType.IFRAME,
            safe.value(), safe.queryOmitted(), "HTTP_ERROR 403", null, "VERIFIED", true);

        String everything = String.join(" ", published.values());
        assertFalse(everything.contains("token"));
        assertFalse(everything.contains("abc"));
        assertFalse(everything.contains("sig"));
        assertFalse(everything.contains("?"));
        assertEquals("https://games.example.test/launch", published.get("location"));
    }

    @Test
    void aBlankFieldIsLeftOutRatherThanPublishedEmpty() {
        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "   ", "PASSED", null, null, false, "NOT_REPORTED", null, "VERIFIED", true);

        assertFalse(published.containsKey("provider"));
        assertFalse(published.containsKey("location"));
        assertFalse(published.containsKey("reason"));
    }

    @Test
    void aLongValueIsCappedRatherThanCarriedWhole() {
        String long1 = "x".repeat(400);
        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", long1, "p", "PASSED", null, null, false, "NOT_REPORTED", null, "VERIFIED", true);

        assertTrue(published.get("displayName").length()
            <= GameLaunchExecutionEvidence.MAX_STRUCTURAL_TEXT + 1);
    }

    @Test
    void newlinesCannotForgeASecondEvidenceLine() {
        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "Aviator\n{\"checkId\":\"LOGIN\"}", "p", "PASSED", null, null, false,
            "NOT_REPORTED", null, "VERIFIED", true);

        assertFalse(published.get("displayName").contains("\n"));
    }

    // --- the steps -----------------------------------------------------------------------------------

    @Test
    void everyStepIsRecordedAgainstThisChecksOwnCase() throws IOException {
        GameLaunchExecutionEvidence evidence = new GameLaunchExecutionEvidence();

        assertNotNull(evidence.skippedCandidate("Aviator", "no usable demo control"));
        assertNotNull(evidence.hoveredCard("Aviator", 12));
        assertNotNull(evidence.clickedLaunchControl("Aviator", "button.demo", 30));
        assertNotNull(evidence.surfaceBecameReady(
            LaunchSurfaceDetector.SurfaceType.IFRAME, "https://example.test/play", true, 40));
        assertNotNull(evidence.sessionChecked(GameLaunchModule.SessionState.VERIFIED, 5));

        assertEquals(5, evidence.recordedSteps());
        String written = Files.readString(runDirectory.resolve("execution-evidence.jsonl"));
        assertTrue(written.contains("\"checkId\":\"GAME_LAUNCH\""), written);
        assertTrue(written.contains(GameLaunchExecutionEvidence.LAUNCH_TEST_CASE_ID), written);
    }

    @Test
    void aFailedLaunchIsRecordedAsAWebsiteFindingNotAnAnalyzerProblem() throws IOException {
        GameLaunchExecutionEvidence evidence = new GameLaunchExecutionEvidence();

        ExecutionStep step = evidence.surfaceDidNotOpen(
            LaunchSurfaceDetector.SurfaceType.NONE, "no new surface appeared", 900);

        assertEquals(ExecutionStep.Status.FAILED, step.status());
        assertTrue(Files.readString(runDirectory.resolve("execution-evidence.jsonl"))
            .contains("WEBSITE_FINDING"));
    }

    @Test
    void anUnusableConfigurationIsRecordedAsAConfigurationError() throws IOException {
        new GameLaunchExecutionEvidence()
            .configurationUnusable("the card selector matched no games");

        assertTrue(Files.readString(runDirectory.resolve("execution-evidence.jsonl"))
            .contains("CONFIGURATION_ERROR"));
    }

    @Test
    void aRecordingFailureNeverDecidesAGamesOutcome() {
        // Guarded, like every other check: an unwritable evidence path must not throw into the run.
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("no-such-directory").resolve("x").resolve("y")
                .resolve("evidence.jsonl").toString());
        GameLaunchExecutionEvidence evidence = new GameLaunchExecutionEvidence();

        // Must not throw. A null step is the guarded recorder's way of saying it could not write.
        evidence.clickedLaunchControl("Aviator", "button.demo", 10);
    }

    private static void assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("the published record must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // exactly right
        }
    }
}
