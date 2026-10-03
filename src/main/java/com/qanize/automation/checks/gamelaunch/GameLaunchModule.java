package com.qanize.automation.checks.gamelaunch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.Interactive;

import com.qanize.automation.checks.shared.SafePointerClick;
import com.qanize.automation.checks.shared.SelectorUtils;
import com.qanize.automation.model.TestedItem;

/**
 * Opens games one at a time through their configured launch control, and can prove what it did
 * to each one.
 *
 * <p>This check clicks on a real gambling site, and the control it clicks sits among controls
 * that spend real money. Almost every rule below exists because of that adjacency. Opening a game
 * is a technical smoke test — the surface is loaded, looked at and closed — and nothing inside it
 * is ever touched: no spin, no bet, no stake, no autoplay, no buy-feature, no cashier.
 *
 * <p><strong>Only the configured launch control is ever clicked.</strong> Never the card, never a
 * button chosen by position, never "the other button", and never a fallback when the launch control
 * is missing — a missing launch control makes a game ineligible, not a candidate for something else.
 * There is exactly one launch click in this class, it is the launch control's own
 * {@link WebElement#click()}, and it is never repeated: a second click on an unknown page
 * can open, stake or confirm something the analyzer could no longer describe. The blocker close
 * below still goes through {@link SafePointerClick#clickOnce}, as does every other click in the
 * project; the launch is the one deliberate exception, and it is pinned by a test.
 *
 * <p><strong>Every card is resolved twice.</strong> Once to hover it, and again immediately before
 * the click, because a catalogue re-renders between the two and the element that was hovered may
 * no longer be the element at that position. A vanished card is skipped rather than approximated.
 *
 * <p><strong>Cleanup always follows a click.</strong> If the list does not come back, the run stops
 * rather than inspecting later games through whatever is still on screen; a game that merely failed
 * does not stop anything, provided the list was restored.
 *
 * <p>Site-neutral: selectors, budget and the return address all arrive from outside, and no route,
 * brand, provider or game name appears in this file. Nothing here reads a frame address, a cookie,
 * a header or a response body, and nothing here plays a game.
 */
public final class GameLaunchModule {

    /** Why a run stopped early, or that it did not. */
    public enum RunStatus {

        /** Every eligible game within the budget was attempted. */
        COMPLETED,

        /**
         * The run could not continue truthfully: the configuration is unusable, the list was never
         * available, or cleanup could not bring it back. Reported through the project's existing
         * configuration-error path rather than as a website finding.
         */
        RUN_SETUP_PROBLEM
    }

    /**
     * The outcome word published for one game, which is not always its contract status.
     *
     * <p>A game whose cleanup could not bring the list back is reported {@code SKIPPED} — it was
     * not tested — while its published outcome says {@code RUN_SETUP_PROBLEM}, because the reason
     * is the run rather than the website. Saying {@code FAILED} there would blame an operator for
     * a problem in the analyzer's own recovery.
     */
    public static final String RUN_SETUP_PROBLEM_OUTCOME = "RUN_SETUP_PROBLEM";

    /**
     * What this run can truthfully say about the signed-in session.
     *
     * <p>Three answers, not two, because "not verified" and "there was nothing to verify" are
     * different facts and reporting either as the other is a false claim. The earlier boolean
     * could only say yes or no, and it said yes whenever the sign-in form was not on screen —
     * which is also what a closed login modal looks like on a site nobody ever signed in to.
     */
    public enum SessionState {

        /** Login was not part of this run, so no session existed to survive a launch. */
        NOT_APPLICABLE,

        /** The configured signed-in indicator was visible when it was asked for. */
        VERIFIED,

        /** It was missing, hidden or unreadable after the wait. Never inferred from an absence. */
        NOT_VERIFIED
    }

    /** What happened to one game. */
    public record GameResult(
        GameCardInspector.GameCandidate candidate,
        TestedItem.Status status,
        LaunchSurfaceDetector.SurfaceType surface,
        String reason,
        boolean launchClicked,
        SessionState session,
        boolean cleanupSucceeded,
        LaunchNetworkProbe.Observation network,
        Map<String, String> evidence
    ) { }

    /** What happened to the run. */
    public record Result(RunStatus status, String setupProblem, List<GameResult> games) {

        public Result {
            games = List.copyOf(games);
        }

        public boolean completed() {
            return status == RunStatus.COMPLETED;
        }
    }

    /**
    /** How the pointer is moved over a card. The project's one pointer mechanism by default. */
    @FunctionalInterface
    public interface HoverAction {
        void hover(WebElement card);
    }

    private final WebsiteGameLaunchConfig config;
    private final GameCardInspector inspector;
    private final LaunchSurfaceDetector detector;
    private final GameLaunchCleanup cleanup;
    private final SafePointerClick pointer;
    private final HoverAction hover;
    /** Whether this run signed in, and therefore whether there is a session to verify at all. */
    private final boolean loginSelected;
    private final GameLaunchExecutionEvidence evidence;
    private final LaunchNetworkProbe network;
    private final int surfaceAttempts;
    private final Runnable pause;
    /** Diagnostic only. Never consulted by any launch decision. */
    private final LaunchClickWitness.Installer witness;
    /** Diagnostic only. Never consulted by any launch decision. */
    private final BrowserErrorProbe browserErrors;
    /** Diagnostic only: one picture when no surface appeared. Never a launch decision either. */
    private final Runnable failureCapture;

    /** The production entry point. Hover uses the same {@link Actions} pointer the project uses. */
    public GameLaunchModule(
        WebDriver driver,
        WebsiteGameLaunchConfig config,
        boolean loginSelected,
        GameLaunchExecutionEvidence evidence,
        int surfaceAttempts,
        Runnable pause
    ) {
        this(driver, config, loginSelected, evidence, surfaceAttempts, pause, () -> { });
    }

    /**
     * The production entry point, with somewhere for a failure picture to go.
     *
     * @param failureCapture run once when no surface appeared. Diagnostic only: whatever it does
     *     or fails to do cannot change the verdict, which is why it is a plain {@link Runnable}
     *     and why the module never asks it anything.
     */
    public GameLaunchModule(
        WebDriver driver,
        WebsiteGameLaunchConfig config,
        boolean loginSelected,
        GameLaunchExecutionEvidence evidence,
        int surfaceAttempts,
        Runnable pause,
        Runnable failureCapture
    ) {
        this(config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(driver), surfaceAttempts, pause),
            new SafePointerClick(driver),
            card -> new Actions(driver).moveToElement(card).perform(),
            loginSelected, evidence, LaunchNetworkProbe.usingDevTools(), surfaceAttempts, pause,
            LaunchClickWitness.on(driver), BrowserErrorProbe.on(driver), failureCapture);
    }

    /** The seam a test uses. Not part of the public API. */
    GameLaunchModule(
        WebsiteGameLaunchConfig config,
        GameCardInspector inspector,
        LaunchSurfaceDetector detector,
        GameLaunchCleanup cleanup,
        SafePointerClick pointer,
        HoverAction hover,
        boolean loginSelected,
        GameLaunchExecutionEvidence evidence,
        LaunchNetworkProbe network,
        int surfaceAttempts,
        Runnable pause
    ) {
        this(config, inspector, detector, cleanup, pointer, hover, loginSelected, evidence,
            network, surfaceAttempts, pause,
            LaunchClickWitness.Installer.NONE, BrowserErrorProbe.NONE, () -> { });
    }

    /** The seam a test uses to drive the diagnostics. Not part of the public API. */
    GameLaunchModule(
        WebsiteGameLaunchConfig config,
        GameCardInspector inspector,
        LaunchSurfaceDetector detector,
        GameLaunchCleanup cleanup,
        SafePointerClick pointer,
        HoverAction hover,
        boolean loginSelected,
        GameLaunchExecutionEvidence evidence,
        LaunchNetworkProbe network,
        int surfaceAttempts,
        Runnable pause,
        LaunchClickWitness.Installer witness,
        BrowserErrorProbe browserErrors,
        Runnable failureCapture
    ) {
        this.witness = Objects.requireNonNull(witness);
        this.browserErrors = Objects.requireNonNull(browserErrors);
        this.failureCapture = Objects.requireNonNull(failureCapture);
        this.config = Objects.requireNonNull(config);
        this.inspector = Objects.requireNonNull(inspector);
        this.detector = Objects.requireNonNull(detector);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.pointer = Objects.requireNonNull(pointer);
        this.hover = Objects.requireNonNull(hover);
        this.loginSelected = loginSelected;
        this.evidence = Objects.requireNonNull(evidence);
        this.network = Objects.requireNonNull(network);
        this.surfaceAttempts = Math.max(1, surfaceAttempts);
        this.pause = Objects.requireNonNull(pause);
    }

    /**
     * Opens each eligible game through its launch control, in catalogue order, within the budget.
     *
     * <p><strong>The list is opened before anything is discovered.</strong> The caller's session
     * has already been somewhere — a crawl leaves it on whatever page it visited last — so
     * discovering against "wherever the browser happens to be" would report on a page nobody
     * chose. The one address this check ever loads is {@code listUrl}, and it is the same address
     * cleanup returns to afterwards.
     *
     * @param driver  the session the caller already has; this class never creates another
     * @param listUrl the configured address of the game list. It is supplied by the caller and
     *     never read back from the browser: a site may route the list through one address and
     *     settle on another, and loading the address it settled on can render an empty list. No
     *     route is assumed, appended or guessed here.
     */
    public Result run(WebDriver driver, String listUrl) {
        Objects.requireNonNull(driver, "driver must not be null");

        if (!SelectorUtils.hasUsableSelector(config.cardSelector())
            || !SelectorUtils.hasUsableSelector(config.launchTriggerSelector())) {
            return setupProblem("Game Launch needs both a card selector and a launch control selector.");
        }
        // Checked before the browser is asked for anything, so a run that cannot report the
        // session truthfully never clicks a launch control at all.
        if (loginSelected
            && !SelectorUtils.hasUsableSelector(config.authenticatedIndicatorSelector())) {
            return setupProblem("Game Launch needs a signed-in indicator selector when Login is "
                + "selected, because otherwise it cannot tell whether the session survived.");
        }
        if (listUrl == null || listUrl.isBlank()) {
            return setupProblem("Game Launch needs the configured start address to open the game list.");
        }
        // The handoff. Login submits a form and reads an address; neither proves a session exists,
        // and navigating on that assumption is how a run reaches a catalogue as an anonymous
        // visitor and reports whatever it finds there against a site it never signed in to. The
        // indicator the configuration already names is the proof, and it is waited for here --
        // before the browser is sent anywhere -- so a session that never arrived costs no
        // navigation and no click.
        if (loginSelected && verifySession(driver) != SessionState.VERIFIED) {
            evidence.sessionChecked(SessionState.NOT_VERIFIED, 0);
            return setupProblem("The sign-in did not produce a signed-in session: the configured "
                + "signed-in indicator never appeared, so no game was opened.");
        }
        try {
            driver.get(listUrl);
        } catch (WebDriverException refused) {
            return setupProblem("The browser refused to open the configured start address.");
        }
        awaitCatalogue(driver);
        String blocked = prepareCatalogue(driver);
        if (blocked != null) return setupProblem(blocked);

        List<GameCardInspector.GameCandidate> candidates = inspector.discover(driver);
        if (candidates.isEmpty()) {
            return setupProblem("The configured card selector matched no games.");
        }

        List<GameResult> games = new ArrayList<>();
        for (GameCardInspector.GameCandidate candidate : candidates) {
            // No pre-hover verdict. A card whose launch control only exists once the card is
            // hovered cannot be judged before the hover, and judging it there is what skipped
            // every game on this catalogue: the control was read as absent, the card was called
            // unlaunchable, and the hover that would have revealed it never happened. A matched
            // card is enough to enter the flow; whether it can be launched is decided in play(),
            // after the hover, on the control itself.

            // Before the click, every time. A session that has already gone means the next click
            // would happen as an anonymous visitor, and whatever it produced would be reported
            // against a website the run was never configured to test.
            if (loginSelected && verifySession(driver) != SessionState.VERIFIED) {
                Instant lostAt = Instant.now();
                GameResult untested = skip(candidate,
                    "the signed-in session was not verified before the launch");
                games.add(untested);
                evidence.sessionChecked(SessionState.NOT_VERIFIED, 0);
                evidence.recordGame(games.size(), untested.candidate().displayName(),
                    untested.candidate().provider(), untested.status(), untested.reason(),
                    untested.evidence(), lostAt);
                evidence.configurationUnusable(
                    "The signed-in session could not be verified, so no further game could be "
                        + "opened truthfully.");
                return new Result(RunStatus.RUN_SETUP_PROBLEM,
                    "The signed-in session could not be verified before a launch.", games);
            }

            Instant gameStartedAt = Instant.now();
            GameResult played = play(driver, candidate, listUrl);
            games.add(played);
            evidence.recordGame(games.size(), played.candidate().displayName(),
                played.candidate().provider(), played.status(), played.reason(),
                played.evidence(), gameStartedAt);

            // A game that merely failed is a finding. A list that did not come back is not
            // something later games can be reported against, so the run stops there.
            if (played.launchClicked() && !played.cleanupSucceeded()) {
                evidence.configurationUnusable(
                    "The game list could not be restored after a launch, so no further game "
                        + "could be opened truthfully.");
                return new Result(RunStatus.RUN_SETUP_PROBLEM,
                    "The game list could not be restored after a launch.", games);
            }

            // The catalogue is prepared again before the next game: cleanup reloads the list, and
            // a reload brings the same overlay back with it.
            String blockedAgain = prepareCatalogue(driver);
            if (blockedAgain != null) {
                evidence.configurationUnusable(blockedAgain);
                return new Result(RunStatus.RUN_SETUP_PROBLEM, blockedAgain, games);
            }
        }
        return new Result(RunStatus.COMPLETED, null, games);
    }

    private GameResult play(
        WebDriver driver, GameCardInspector.GameCandidate candidate, String returnUrl
    ) {
        // The W3C input state lives in the session, not in an Actions object, and it is keyed by
        // the source id every Actions here shares. A press that was never released -- a pointer up
        // lost to the document the sign-in click navigated away from -- therefore survives into
        // this attempt, and the launch below would issue its press against a button the browser
        // already believes is down. Cleared once, here, before anything touches the page.
        //
        // A browser that refuses to clear it ends the attempt: continuing would mean clicking a
        // live site with an input state this run cannot describe, and no click at all is the only
        // honest answer to that.
        if (driver instanceof Interactive interactive) {
            try {
                interactive.resetInputState();
            } catch (WebDriverException refused) {
                return skip(candidate, "the browser would not clear its input state before the "
                    + "launch, so no launch control was clicked");
            }
        }

        // Resolve once to hover. Nothing from discovery is reused; this element is this moment's.
        Optional<GameCardInspector.ResolvedCard> forHover = inspector.resolve(driver, candidate);
        if (forHover.isEmpty()) {
            return skip(candidate, "the card was gone before it could be opened");
        }
        try {
            hover.hover(forHover.get().card());
            evidence.hoveredCard(candidate.displayName(), 0);
        } catch (WebDriverException hoverFailed) {
            return skip(candidate, "the card could not be hovered, so its launch control never appeared");
        }

        // Resolve again, and keep resolving for a bounded moment. The hover itself re-renders this
        // catalogue: the element just hovered may already be detached, and the control the hover
        // reveals arrives on the page's schedule rather than this method's. Each attempt re-reads
        // the card from the DOM, so the name and provider come back as the hovered card now shows
        // them -- a name that was unreadable while the card sat unhovered is readable here -- and
        // the control is this moment's element rather than a remembered one.
        GameCardInspector.GameCandidate afterHover = candidate;
        Optional<WebElement> launchControl = Optional.empty();
        for (int attempt = 0; attempt < surfaceAttempts; attempt++) {
            if (attempt > 0) pause.run();
            Optional<GameCardInspector.ResolvedCard> forClick;
            try {
                forClick = inspector.resolve(driver, candidate);
            } catch (WebDriverException changedWhileReading) {
                // A catalogue that re-renders between the query and the read takes its cards with
                // it. That is this one game's problem, not the run's: every game already opened
                // keeps its verdict, the games after it are still attempted, and nothing here is
                // retried -- a second look at a page that just changed is a second unknown.
                return skip(afterHover,
                    "the page changed while the card was being read, so the game was left alone");
            }
            if (forClick.isEmpty()) {
                return skip(afterHover, "the card was gone before it could be opened");
            }
            afterHover = forClick.get().candidate();
            Optional<WebElement> found = forClick.get().launchControl();
            // Present, and reachable. ownsItsClickPoint is the single definition of the rest:
            // displayed, enabled, centred, and the element a pointer would actually land on.
            if (found.isPresent() && pointer.ownsItsClickPoint(found.get())) {
                launchControl = found;
                break;
            }
        }
        if (launchControl.isEmpty()) {
            // Nothing was clicked, and nothing will be. A card that never offers a usable control
            // is skipped exactly as it always was -- the difference is that it was hovered first.
            return skip(afterHover, "the launch control was not reachable after the hover");
        }

        LaunchSurfaceDetector.Snapshot before;
        try {
            before = detector.snapshot(driver);
        } catch (WebDriverException changedBeforeTheClick) {
            // Without a before-picture there is nothing to compare a surface against, so a launch
            // from here could not be described truthfully. The game is left alone rather than
            // clicked blind.
            return skip(afterHover,
                "the page changed before the launch could be observed, so the game was left alone");
        }

        // Observation opens immediately before the click and closes after readiness, so it covers
        // the launch and nothing else. An unavailable browser yields a session that reports
        // nothing, which is why there is no availability branch here.
        // Drained before the launch so that "new" means new. Diagnostic only.
        browserErrors.read();
        long launchStarted = System.nanoTime();
        // Passive, capture-phase, and removed in the same try-with-resources that installed it.
        // It cannot cancel, reorder or delay the click below; it only reports whether the page
        // received one.
        try (LaunchClickWitness.Watch watched = witness.watch(launchControl.get());
             LaunchNetworkProbe.Session watching = network.begin(driver)) {
            // ── The one launch click. Never retried, whatever follows. ──
            try {
                launchControl.get().click();
            } catch (ElementClickInterceptedException coveredByAnotherElement) {
                // The browser refused before the event left the pointer: something else owned the
                // point this control occupies. That is this one card's problem, and the narrowest
                // possible answer is the right one. Nothing is clicked, dismissed, scrolled or
                // retried from here -- a second attempt on a page that just proved it is covered
                // would be a click at an element nobody resolved -- and the guard is deliberately
                // this one exception rather than WebDriverException, so a stale element, an
                // unknown error or any other browser failure still ends the run as loudly as
                // before. The witness and the network session close on the way out.
                return skip(afterHover,
                    "another page element covered the launch control, so the game was left alone");
            }

            // Immediately, and before anything waits for anything. Connectivity and geometry read
            // after the surface poll would describe a page that had ten seconds to change, which
            // is exactly the confusion this sample exists to remove. The listener stays installed
            // until the try-with-resources closes; only the reading is taken here.
            LaunchClickWitness.Observation clicked = watched.sampleNow();

            evidence.clickedLaunchControl(
                afterHover.displayName(), config.launchTriggerSelector(),
                elapsedMs(launchStarted));
            return afterClick(
                driver, afterHover, before, returnUrl, watching, clicked, launchStarted);
        }
    }

    /** Wall-clock milliseconds around one real operation. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * The surface match count as it stands now, read through the detector's own existing reader.
     *
     * <p>Diagnostic only, and deliberately not part of detection: the verdict has already been
     * decided when this runs. A browser that refuses the read yields the count the launch started
     * from, so the published pair says "unchanged" rather than inventing a number.
     */
    private int countSurfaces(WebDriver driver, LaunchSurfaceDetector.Snapshot before) {
        try {
            return detector.snapshot(driver).surfaceMatches();
        } catch (RuntimeException unreadable) {
            return before.surfaceMatches();
        }
    }

    private GameResult afterClick(
        WebDriver driver,
        GameCardInspector.GameCandidate candidate,
        LaunchSurfaceDetector.Snapshot before,
        String returnUrl,
        LaunchNetworkProbe.Session watching,
        LaunchClickWitness.Observation clicked,
        long launchStarted
    ) {
        long detectStarted = System.nanoTime();
        LaunchSurfaceDetector.Detection detection =
            detector.detect(driver, before, surfaceAttempts, pause);
        long detectMs = elapsedMs(detectStarted);

        // Read once readiness has been decided, so the observation covers the whole launch.
        LaunchNetworkProbe.Observation observed = watching.observe();
        // Diagnostics, all read after the decision above and none of them feeding into it. The
        // second snapshot is the detector's own existing reader, asked once more so the final
        // match count can be published beside the one it started from. The click observation is
        // not read here: it was taken at the click itself and handed in already.
        BrowserErrorProbe.Reading errors = browserErrors.read();
        int surfaceAfter = countSurfaces(driver, before);
        LaunchDiagnostics diagnostics = LaunchDiagnostics.of(
            clicked, errors, before, surfaceAfter, detection, elapsedMs(launchStarted));

        String failure = null;
        if (!detection.surfaceOpened()) {
            evidence.surfaceDidNotOpen(detection.type(), detection.detail(), detectMs);
            evidence.launchDiagnosed(diagnostics, 0);
            // One picture, taken while the page is still whatever the click left behind. It is
            // redacted by the same service every other capture in this project goes through, and
            // nothing below reads it or waits for it.
            failureCapture.run();
            failure = detection.detail();
        } else if (errorIsVisible(driver)) {
            evidence.errorWasVisible(config.errorSelector(), 0);
            failure = "the site showed the configured error after the game opened";
        } else if (observed.failsTheGame()) {
            // A surface that opened over a refused response is not a working game.
            evidence.networkReportedAFailure(observed, 0);
            failure = observed.detail();
        } else {
            evidence.surfaceBecameReady(detection.type(),
                detection.location().value(), detection.location().queryOmitted(), detectMs);
        }

        // Always, after every performed click, whatever happened above.
        GameLaunchCleanup.Result restored =
            cleanup.restore(driver, before, detection, returnUrl);
        evidence.cleanupFinished(restored, 0);

        // After cleanup, not before it: while a game is still open the top-level page may be
        // covered or replaced, and asking there would measure the analyzer's own timing rather
        // than the website's session. Asked only when there was a sign-in to survive.
        SessionState session = loginSelected ? verifySession(driver) : SessionState.NOT_APPLICABLE;
        evidence.sessionChecked(session, 0);
        if (failure == null && session == SessionState.NOT_VERIFIED) {
            failure = "the signed-in session did not survive the game launch";
        }

        // Three outcomes, and the order matters. A run that cannot restore its list did not test
        // this game, so the game is SKIPPED and the run stops; only a genuine website problem is
        // FAILED; everything else passed.
        TestedItem.Status status;
        String outcome;
        String reason;
        if (!restored.restored()) {
            status = TestedItem.Status.SKIPPED;
            outcome = RUN_SETUP_PROBLEM_OUTCOME;
            reason = restored.detail();
        } else if (failure != null) {
            status = TestedItem.Status.FAILED;
            outcome = TestedItem.Status.FAILED.name();
            reason = failure;
        } else {
            status = TestedItem.Status.PASSED;
            outcome = TestedItem.Status.PASSED.name();
            reason = null;
        }

        return new GameResult(candidate, status, detection.type(), reason,
            true, session, restored.restored(), observed,
            publish(candidate, outcome, detection, observed, reason,
                session, restored.restored(), diagnostics));
    }

    /** Whether the configured error element is on screen. Its text is never read or published. */
    private boolean errorIsVisible(WebDriver driver) {
        if (!SelectorUtils.hasUsableSelector(config.errorSelector())) return false;
        return driver.findElements(SelectorUtils.css(config.errorSelector()))
            .stream().anyMatch(WebElement::isDisplayed);
    }

    private GameResult skip(GameCardInspector.GameCandidate candidate, String reason) {
        evidence.skippedCandidate(candidate.displayName(), reason);
        // A game nothing was done to cannot have lost a session, so it reports the state this run
        // is entitled to claim and nothing more.
        SessionState untouched =
            loginSelected ? SessionState.NOT_VERIFIED : SessionState.NOT_APPLICABLE;
        return new GameResult(candidate, TestedItem.Status.SKIPPED, null, reason,
            false, untouched, true, LaunchNetworkProbe.Observation.NOT_REPORTED,
            publish(candidate, TestedItem.Status.SKIPPED.name(), null,
                LaunchNetworkProbe.Observation.NOT_REPORTED, reason, untouched, true,
                LaunchDiagnostics.NONE));
    }

    /**
     * Whether the site is still showing this run as signed in.
     *
     * <p><strong>A positive element, polled.</strong> The configured indicator has to be present
     * and displayed; missing, hidden, or a query the browser refused all mean the same thing —
     * not verified. This deliberately replaces an earlier heuristic that watched the sign-in
     * field disappear and called that proof, which it never was: a login form that is merely
     * closed looks identical to one that was never needed.
     *
     * <p>A refused query can never come back {@code VERIFIED}. Nothing here reads a cookie, a
     * token, storage or any credential, and the element's text is never read — only whether it is
     * on screen.
     */
    private SessionState verifySession(WebDriver driver) {
        if (!SelectorUtils.hasUsableSelector(config.authenticatedIndicatorSelector())) {
            return SessionState.NOT_VERIFIED;
        }
        By indicator = SelectorUtils.css(config.authenticatedIndicatorSelector());
        for (int attempt = 0; attempt < surfaceAttempts; attempt++) {
            try {
                if (driver.findElements(indicator).stream().anyMatch(WebElement::isDisplayed)) {
                    return SessionState.VERIFIED;
                }
            } catch (WebDriverException unreadable) {
                // Unreadable is not signed in. The next pass asks again; if every pass is refused
                // the answer stays NOT_VERIFIED, because this method has no other way out.
            }
            pause.run();
        }
        return SessionState.NOT_VERIFIED;
    }

    /**
     * Dismisses one configured blocker over the catalogue, or explains why the run cannot go on.
     *
     * <p>A full-viewport promotional overlay intercepts every pointer event, so without this the
     * hover and the launch click would both land on the overlay and every game would be reported as
     * a website failure that never happened.
     *
     * <p>The rules are deliberately unforgiving. Only the configured close control is ever a
     * target — never the overlay body, never a card, never "the other button". Exactly one visible
     * match is a control; two are an ambiguity this check will not resolve by guessing. The click
     * happens once and is never retried: if the blocker is still there afterwards the run stops,
     * because clicking repeatedly at an unknown page is how an analyzer ends up doing something
     * nobody asked for.
     *
     * @return null when the catalogue is usable, or the reason it is not
     */
    private String prepareCatalogue(WebDriver driver) {
        if (!SelectorUtils.hasUsableSelector(config.blockingOverlayCloseSelector())) return null;
        By closeControl = SelectorUtils.css(config.blockingOverlayCloseSelector());
        List<WebElement> visible;
        try {
            visible = visibleMatches(driver, closeControl);
        } catch (WebDriverException unreadable) {
            return "The configured blocking popup close control could not be read.";
        }
        // The normal case by a wide margin: no overlay, nothing to do.
        if (visible.isEmpty()) return null;
        if (visible.size() > 1) {
            return "More than one blocking popup close control is visible, so which one covers "
                + "the game list cannot be established.";
        }
        WebElement control = visible.getFirst();
        if (!pointer.ownsItsClickPoint(control)) {
            return "The configured blocking popup close control is not reachable, so the game "
                + "list is still covered.";
        }
        try {
            // ── The one catalogue-blocker click in this codebase. Never retried. ──
            pointer.clickOnce(control);
        } catch (WebDriverException refused) {
            return "The browser refused to close the blocking popup covering the game list.";
        }
        evidence.blockingOverlayClosed(config.blockingOverlayCloseSelector(), 0);
        // Closing is not instant. A popup that fades, animates or unmounts a frame later is still
        // present the moment after the click, and reading once at that moment reported a popup that
        // was on its way out as one that refused to go. Polled with the same attempts-and-pause the
        // surface detector uses, so there is one waiting idiom in this class rather than two.
        for (int attempt = 0; attempt < surfaceAttempts; attempt++) {
            try {
                if (visibleMatches(driver, closeControl).isEmpty()) return null;
            } catch (WebDriverException unreadable) {
                return "The blocking popup covering the game list could not be re-read after closing.";
            }
            pause.run();
        }
        return "The blocking popup covering the game list did not close.";
    }

    private static List<WebElement> visibleMatches(WebDriver driver, By locator) {
        return driver.findElements(locator).stream().filter(WebElement::isDisplayed).toList();
    }

    /**
     * Waits for the list to answer, using the same budget and the same pause as a launch.
     *
     * <p>Returns either way: an empty catalogue is not decided here. It is the ordinary
     * "matched no games" answer below, which is the truthful message for a selector that found
     * nothing whether the page was slow or simply had no games on it.
     */
    private void awaitCatalogue(WebDriver driver) {
        By cards = LaunchSurfaceDetector.cardLocator(config);
        for (int attempt = 0; attempt < surfaceAttempts; attempt++) {
            try {
                if (!driver.findElements(cards).isEmpty()) return;
            } catch (WebDriverException stillLoading) {
                // A page mid-navigation can refuse a query. The next pass asks again.
            }
            pause.run();
        }
    }

    private Result setupProblem(String reason) {
        evidence.configurationUnusable(reason);
        return new Result(RunStatus.RUN_SETUP_PROBLEM, reason, List.of());
    }

    private static Map<String, String> publish(
        GameCardInspector.GameCandidate candidate,
        String gameOutcome,
        LaunchSurfaceDetector.Detection detection,
        LaunchNetworkProbe.Observation network,
        String reason,
        SessionState session,
        boolean cleanupSucceeded,
        LaunchDiagnostics diagnostics
    ) {
        return GameLaunchExecutionEvidence.publishable(
            candidate.identityKey(),
            candidate.displayName(),
            candidate.provider(),
            gameOutcome,
            detection == null ? null : detection.type(),
            detection == null ? null : detection.location().value(),
            detection != null && detection.location().queryOmitted(),
            network.label(),
            reason,
            session.name(),
            cleanupSucceeded,
            diagnostics);
    }
}
