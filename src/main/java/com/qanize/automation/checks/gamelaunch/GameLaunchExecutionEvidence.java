package com.qanize.automation.checks.gamelaunch;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.qanize.automation.evidence.CheckStepRecorder;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.model.TestedItem;
import com.qanize.automation.model.ErrorCategory;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;

/**
 * What the Game Launch check did, written down in terms that are safe to publish.
 *
 * <p>Built on {@link CheckStepRecorder} like every other check, and guarded for the same reason:
 * a problem writing evidence must never decide whether a game passed.
 *
 * <p><strong>An allowlist, not a filter.</strong> {@link #publishable} names the fields that may
 * leave this check, one by one, and builds the map from those names alone. Nothing is ever copied
 * from a page, an element, a response or a caller's map — which is the only way to be sure that a
 * signed launch URL, a session cookie or an authorization header cannot arrive by accident. A
 * field that is not on the list does not get published by being renamed.
 *
 * <p>The one address this check records is the top-level one, already reduced by
 * {@link LaunchSurfaceDetector.SafeLocation} to {@code scheme://host/path}, with a companion flag
 * saying a query was dropped. Frame addresses are never read, so there is none to record.
 */
public final class GameLaunchExecutionEvidence {

    /** The contract 1.0 case this check records against. One case, one game at a time. */
    public static final String LAUNCH_TEST_CASE_ID = "game-launch";

    private static final String CHECK_ID = "GAME_LAUNCH";

    /** Labels are short structural strings, never prose and never page content. */
    static final int MAX_STRUCTURAL_TEXT = 120;

    /** Guarded: persistence must never decide a game's outcome. */
    private final CheckStepRecorder steps =
        CheckStepRecorder.guarded(CHECK_ID, "game-launch-step-record-failed");

    private final EvidenceScope scope;

    public GameLaunchExecutionEvidence() {
        this(EvidenceScope.legacy());
    }

    public GameLaunchExecutionEvidence(EvidenceScope scope) {
        this.scope = Objects.requireNonNull(scope,
            "scope must not be null; use GameLaunchExecutionEvidence() for the legacy scope");
    }

    /** This check's case id, qualified by page. */
    public String testCaseId() {
        return scope.testCaseId(LAUNCH_TEST_CASE_ID);
    }

    // --- the steps ---------------------------------------------------------------------------------

    /** A candidate the check never touched, and why. */
    public ExecutionStep skippedCandidate(String displayName, String reason) {
        return append(Phase.DISCOVERY, ExecutionMethod.DOM_INSPECTION,
            "Left a game alone because it could not be launched safely.",
            structural(displayName),
            "A game is only opened when its launch control is present, enabled and reachable.",
            structural(reason),
            ExecutionStep.Status.SKIPPED, 0, ErrorCategory.NOT_APPLICABLE);
    }

    /** The pointer was moved over the card so the site could reveal its controls. */
    public ExecutionStep hoveredCard(String displayName, long durationMs) {
        return append(Phase.ACTION, ExecutionMethod.CLICK,
            "Moved the pointer over the game card to reveal its controls.",
            structural(displayName),
            "The card reveals its launch control on hover.",
            "The pointer was moved over the card.",
            ExecutionStep.Status.PASSED, durationMs, null);
    }

    /** The one click this check performs, on the configured launch control and nothing else. */
    public ExecutionStep clickedLaunchControl(String displayName, String launchSelector, long durationMs) {
        return append(Phase.ACTION, ExecutionMethod.CLICK,
            "Clicked the configured launch control once.",
            structural(launchSelector),
            "The launch control opens the game.",
            "Clicked once for " + structural(displayName) + ". No second attempt is ever made.",
            ExecutionStep.Status.PASSED, durationMs, null);
    }

    /** A launch surface appeared and settled. */
    public ExecutionStep surfaceBecameReady(
        LaunchSurfaceDetector.SurfaceType surface, String safeLocation, boolean queryOmitted,
        long durationMs
    ) {
        return append(Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the game surface to open and become usable.",
            surface.name(),
            "A game surface opens and becomes usable.",
            "Opened as " + surface.name() + location(safeLocation, queryOmitted),
            ExecutionStep.Status.PASSED, durationMs, null);
    }

    /** The click happened and nothing usable followed. A finding about the website. */
    public ExecutionStep surfaceDidNotOpen(
        LaunchSurfaceDetector.SurfaceType surface, String detail, long durationMs
    ) {
        return append(Phase.WAIT, ExecutionMethod.WAIT_FOR_CONDITION,
            "Waited for the game surface to open and become usable.",
            surface.name(),
            "A game surface opens and becomes usable.",
            structural(detail),
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING);
    }

    /**
     * What the launch click itself did, recorded only when no surface appeared.
     *
     * <p>Diagnostic. It reports a reading, never a verdict: the failure has already been recorded
     * by the step above, and this one exists so the next person does not have to guess between a
     * click that never arrived and a page that ignored one. Numbers and fixed words only — see
     * {@link LaunchDiagnostics}, which is the one place that decides what may appear here.
     */
    public ExecutionStep launchDiagnosed(LaunchDiagnostics diagnostics, long durationMs) {
        return append(Phase.EVIDENCE, ExecutionMethod.DOM_INSPECTION,
            "Read what the launch click itself did.",
            diagnostics.diagnosis().name(),
            "The launch click reaches the control and the page acts on it.",
            diagnostics.sentence(),
            ExecutionStep.Status.PASSED, durationMs, null);
    }

    /** The browser reported a refused or failed response while the game was opening. */
    public ExecutionStep networkReportedAFailure(
        LaunchNetworkProbe.Observation observation, long durationMs
    ) {
        return append(Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Checked what the browser reported about the network during the launch.",
            observation.label(),
            "The browser reports no failed or refused response while the game opens.",
            structural(observation.detail()),
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING);
    }

    /** The site showed the error the configuration named. */
    public ExecutionStep errorWasVisible(String errorSelector, long durationMs) {
        return append(Phase.ASSERTION, ExecutionMethod.ASSERT,
            "Looked for the configured error message on the opened surface.",
            structural(errorSelector),
            "No error message is shown after a game opens.",
            "The configured error element was visible.",
            ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING);
    }

    /** The sign-in survived the launch, or it did not. */
    public ExecutionStep sessionChecked(GameLaunchModule.SessionState state, long durationMs) {
        return switch (state) {
            // Nothing signed in, so nothing to survive. Recorded as skipped rather than passed,
            // because "passed" would claim a session was checked when none existed.
            case NOT_APPLICABLE -> append(Phase.ASSERTION, ExecutionMethod.ASSERT,
                "No sign-in was part of this run, so no session was checked.",
                null,
                "A run without Login has no signed-in session to preserve.",
                "Session verification does not apply to this run.",
                ExecutionStep.Status.SKIPPED, durationMs, null);
            case VERIFIED -> append(Phase.ASSERTION, ExecutionMethod.ASSERT,
                "Confirmed the signed-in session survived the game launch.",
                null,
                "The configured signed-in indicator is visible after the launch.",
                "The signed-in indicator was visible.",
                ExecutionStep.Status.PASSED, durationMs, null);
            case NOT_VERIFIED -> append(Phase.ASSERTION, ExecutionMethod.ASSERT,
                "Confirmed the signed-in session survived the game launch.",
                null,
                "The configured signed-in indicator is visible after the launch.",
                "The signed-in indicator was missing, hidden or unreadable.",
                ExecutionStep.Status.FAILED, durationMs, ErrorCategory.WEBSITE_FINDING);
        };
    }

    /**
     * One configured blocker over the catalogue, dismissed once.
     *
     * <p>The selector is a structural string the caller supplied, never page content.
     */
    public ExecutionStep blockingOverlayClosed(String closeSelector, long durationMs) {
        return append(Phase.ACTION, ExecutionMethod.CLICK,
            "Closed a popup covering the game list.",
            closeSelector,
            "The game list is not covered by a popup.",
            "The configured close control was clicked once and the popup closed.",
            ExecutionStep.Status.PASSED, durationMs, null);
    }

    /** Cleanup ran and the list is back, or it is not. */
    public ExecutionStep cleanupFinished(GameLaunchCleanup.Result cleanup, long durationMs) {
        return append(Phase.EVIDENCE, ExecutionMethod.DOM_INSPECTION,
            "Returned to the game list after the game closed.",
            cleanup.outcome().name(),
            "The game list is available again before the next game is opened.",
            structural(cleanup.detail()),
            cleanup.restored() ? ExecutionStep.Status.PASSED : ExecutionStep.Status.FAILED,
            durationMs,
            cleanup.restored() ? null : ErrorCategory.INFRASTRUCTURE_ERROR);
    }

    /** The configuration cannot support a run at all. */
    public ExecutionStep configurationUnusable(String reason) {
        return append(Phase.SETUP, ExecutionMethod.DOM_INSPECTION,
            "Checked that the Game Launch configuration can be used.",
            null,
            "The configured selectors resolve a usable game list.",
            structural(reason),
            ExecutionStep.Status.FAILED, 0, ErrorCategory.CONFIGURATION_ERROR);
    }

    // --- the published record ------------------------------------------------------------------------

    /**
     * The only fields this check publishes about one game, named one at a time.
     *
     * <p>Deliberately parameters rather than a map: there is no argument here that could carry a
     * cookie, a token, a header, a response body or a frame address, because there is nowhere to
     * put one. The location has already been reduced by the detector; this method re-states that a
     * query was dropped rather than trusting the caller to mention it.
     */
    public static Map<String, String> publishable(
        String identityKey,
        String displayName,
        String provider,
        String gameOutcome,
        LaunchSurfaceDetector.SurfaceType surface,
        String safeLocation,
        boolean queryOmitted,
        String network,
        String reason,
        String sessionState,
        boolean cleanupSucceeded
    ) {
        Map<String, String> safe = new LinkedHashMap<>();
        put(safe, "identityKey", identityKey);
        put(safe, "displayName", displayName);
        put(safe, "provider", provider);
        put(safe, "gameOutcome", gameOutcome);
        put(safe, "surface", surface == null ? null : surface.name());
        put(safe, "location", safeLocation);
        safe.put("locationQueryOmitted", Boolean.toString(queryOmitted));
        put(safe, "network", network);
        put(safe, "reason", reason);
        // A word, not a boolean: "not applicable" and "not verified" are different facts, and a
        // boolean could only ever have carried one of them.
        put(safe, "sessionPreserved", sessionState);
        safe.put("cleanupSucceeded", Boolean.toString(cleanupSucceeded));
        return Map.copyOf(safe);
    }

    /**
     * The same allowlist, plus the launch diagnostics.
     *
     * <p>Every added value is a number, a fixed word or an address this project had already
     * reduced to {@code scheme://host/path}. Nothing from the page and nothing the browser
     * phrased itself passes through here, which is why they can be named one by one exactly as
     * the original eleven are.
     */
    public static Map<String, String> publishable(
        String identityKey,
        String displayName,
        String provider,
        String gameOutcome,
        LaunchSurfaceDetector.SurfaceType surface,
        String safeLocation,
        boolean queryOmitted,
        String network,
        String reason,
        String sessionState,
        boolean cleanupSucceeded,
        LaunchDiagnostics diagnostics
    ) {
        Map<String, String> safe = new LinkedHashMap<>(publishable(
            identityKey, displayName, provider, gameOutcome, surface, safeLocation, queryOmitted,
            network, reason, sessionState, cleanupSucceeded));
        if (diagnostics != null) {
            put(safe, "clickObserved", diagnostics.clickObserved());
            put(safe, "clickTrusted", diagnostics.clickTrusted());
            put(safe, "documentClick", diagnostics.documentClick());
            put(safe, "clickLandedOutside", diagnostics.clickLandedOutside());
            put(safe, "targetConnectedBefore", diagnostics.targetConnectedBefore());
            put(safe, "targetConnectedAfter", diagnostics.targetConnectedAfter());
            // Four integers each, produced by LaunchClickWitness.Rect and nothing else.
            put(safe, "targetRectBefore", diagnostics.targetRectBefore());
            put(safe, "targetRectAfter", diagnostics.targetRectAfter());
            put(safe, "targetRectMoved", diagnostics.targetRectMoved());
            put(safe, "locationBefore", diagnostics.locationBefore());
            safe.put("locationBeforeQueryOmitted",
                Boolean.toString(diagnostics.locationBeforeQueryOmitted()));
            safe.put("surfaceBefore", Integer.toString(diagnostics.surfaceBefore()));
            safe.put("surfaceAfter", Integer.toString(diagnostics.surfaceAfter()));
            safe.put("surfaceAttempts", Integer.toString(diagnostics.attempts()));
            safe.put("launchElapsedMs", Long.toString(diagnostics.elapsedMs()));
            put(safe, "browserError", diagnostics.browserError());
            put(safe, "browserErrorKind", diagnostics.browserErrorKind());
            put(safe, "launchDiagnosis", diagnostics.diagnosis().name());
        }
        return Map.copyOf(safe);
    }

    /** The allowlist, readable by a test so it cannot drift silently. */
    public static final java.util.List<String> PUBLISHABLE_KEYS = java.util.List.of(
        "identityKey", "displayName", "provider", "gameOutcome", "surface",
        "location", "locationQueryOmitted", "network", "reason",
        "sessionPreserved", "cleanupSucceeded",
        // The launch diagnostics. Numbers, fixed words, two rectangles as four integers each and
        // already-reduced addresses only.
        "clickObserved", "clickTrusted", "documentClick", "clickLandedOutside",
        "targetConnectedBefore", "targetConnectedAfter",
        "targetRectBefore", "targetRectAfter", "targetRectMoved",
        "locationBefore", "locationBeforeQueryOmitted",
        "surfaceBefore", "surfaceAfter", "surfaceAttempts", "launchElapsedMs",
        "browserError", "browserErrorKind", "launchDiagnosis");

    /**
     * Publishes one game as a contract tested item.
     *
     * <p>The metadata is the allowlisted map and nothing else — it is copied key by key from a map
     * this class built from named parameters, so there is no path by which an arbitrary map, a
     * header or a launch address could reach the artifact.
     *
     * @param status the contract status; a game whose cleanup broke the run is SKIPPED, because it
     *               was not tested and saying it failed would blame the website for a run that
     *               stopped
     */
    public void recordGame(
        int index,
        String displayName,
        String provider,
        TestedItem.Status status,
        String reason,
        Map<String, String> published,
        Instant startedAt
    ) {
        TestedItem.Builder item = TestedItem.builder(CHECK_ID, TestedItem.ItemType.GAME)
            .id(scope.itemId("game-" + index))
            .name(structural(displayName) == null ? "Game " + index : structural(displayName))
            .action("Opened the game from the configured list through its launch control.")
            .expected("The game opens and becomes usable without an error.")
            .actual(structural(reason) == null
                ? "The game opened and became usable."
                : structural(reason))
            .status(status)
            .startedAt(startedAt.toString())
            .completedAt(Instant.now().toString())
            .durationMs(java.time.Duration.between(startedAt, Instant.now()).toMillis());
        String safeProvider = structural(provider);
        if (safeProvider != null) item.meta("provider", safeProvider);
        for (String key : PUBLISHABLE_KEYS) {
            String value = published.get(key);
            if (value != null) item.meta(key, value);
        }
        EvidenceRecorder.record(scope.enrich(item).build());
    }

    private static void put(Map<String, String> target, String key, String value) {
        String safe = structural(value);
        if (safe != null) target.put(key, safe);
    }

    /** Keeps a structural value short and single-line. Never applied to a page's content. */
    static String structural(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.replaceAll("[\\r\\n]+", " ").trim();
        return trimmed.length() <= MAX_STRUCTURAL_TEXT
            ? trimmed
            : trimmed.substring(0, MAX_STRUCTURAL_TEXT) + "…";
    }

    private static String location(String safeLocation, boolean queryOmitted) {
        if (safeLocation == null) return ".";
        return " at " + safeLocation + (queryOmitted ? " (query omitted)." : ".");
    }

    private ExecutionStep append(
        Phase phase, ExecutionMethod method, String action, String targetLabel,
        String expected, String actual, ExecutionStep.Status status, long durationMs,
        ErrorCategory errorCategory
    ) {
        return steps.append(
            testCaseId(), phase, method, action, targetLabel,
            expected, actual, status, durationMs, errorCategory);
    }

    public int recordedSteps() {
        return steps.recordedSteps(testCaseId());
    }
}
