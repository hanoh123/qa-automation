package com.qanize.automation.checks.gamelaunch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;

import com.qanize.automation.checks.shared.SafePointerClick;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.model.TestedItem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening demo games next to a real-money button.
 *
 * <p>Most of what follows is about the click that does not happen. A catalogue puts "Play Now" and
 * a Demo control side by side, the same size, inside the same overlay, so a check that clicks by position,
 * clicks the card, or falls back when the demo control is missing would spend somebody's money and
 * report it as a test. Each of those is asserted here as an absence — zero clicks, or one click on
 * a named element — rather than assumed from reading the code.
 *
 * <p>The second theme is honesty about what happened afterwards. A game that failed is a finding
 * and the run continues; a list that did not come back is not something later games can be
 * reported against, so the run stops.
 *
 * <p>Fake browser throughout. No network, no UAT, no real driver, and the fake fails the test if
 * anything calls {@code navigate()}, {@code quit()} or clicks a card.
 */
@Tag("unit")
class GameLaunchModuleTest {

    private static final String CARD = "app-game-item";
    private static final String NAME = ".game-name";
    private static final String PROVIDER = ".provider";
    private static final String LAUNCH = "button.btn-launch";
    /** A second, real control in the same card. Whichever one is configured, only it is clicked. */
    private static final String OTHER_LAUNCH = "button.btn-other";
    private static final String SURFACE = ".game-surface";
    private static final String ERROR = ".game-error";
    private static final String CLOSE = "a.close";
    private static final String INDICATOR = "[data-test='signed-in']";
    private static final String BLOCKER = "app-popup .dismiss";
    private static final String LIST_URL = "https://example.test/lobby";

    private static final Path GAMELAUNCH_MAIN =
        Path.of("src/main/java/com/qanize/automation/checks/gamelaunch");

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

    // --- opening the list ---------------------------------------------------------------------------

    @Test
    void theListIsOpenedAtTheSuppliedAddressBeforeAnythingIsDiscovered() {
        // The caller's session has been somewhere else — a crawl leaves it on its last page.
        Lobby lobby = new Lobby("https://example.test/a-page-the-crawl-ended-on");
        // The catalogue exists only once the supplied address has actually been loaded, so a run
        // that discovered against the page it started on would find nothing at all.
        lobby.onNavigate = () -> {
            if (LIST_URL.equals(lobby.url) && lobby.cards.isEmpty()) lobby.addCard("Aviator", "Spribe");
        };
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(1, result.games().size(), "the list was open before discovery ran");
        assertEquals(LIST_URL, lobby.url);
    }

    @Test
    void theListIsGivenTimeToAppearBeforeItIsCalledEmpty() {
        Lobby lobby = lobby();
        int[] pauses = {0};
        GameLaunchModule module = moduleWithPause(lobby, () -> {
            pauses[0]++;
            if (pauses[0] == 1) lobby.addCard("Aviator", "Spribe");
        });
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module.run(lobby, LIST_URL);

        assertEquals(1, result.games().size(), "a list that arrived late was still discovered");
        assertTrue(pauses[0] >= 1, "the check waited rather than declaring the list empty at once");
    }

    @Test
    void aBlankListAddressIsASetupProblemAndNothingIsDiscovered() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");

        GameLaunchModule.Result result = module(lobby).run(lobby, "   ");

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertTrue(result.games().isEmpty());
        assertEquals(0, lobby.cardQueries, "nothing was read from a page nobody chose");
        assertEquals(0, lobby.controlClicks);
    }

    @Test
    void aBrowserThatRefusesTheListAddressIsASetupProblemRatherThanAFailedGame() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onNavigate = () -> { throw new WebDriverException("the address could not be loaded"); };

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertTrue(result.games().isEmpty());
        assertEquals(0, lobby.controlClicks);
    }

    // --- games that are never touched -------------------------------------------------------------

    @Test
    void aCardWithNoLaunchControlIsSkippedWithoutBeingTouchedAtAll() {
        Lobby lobby = lobby();
        lobby.addCard("Live Roulette", "ElCasino").withoutLaunchControl();
        Recording hover = new Recording();

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.SKIPPED, only(result).status());
        assertEquals(0, lobby.controlClicks);
        assertFalse(only(result).launchClicked());
        // Hovered once, then skipped. A card whose control only exists after the hover cannot be
        // judged before it, so the hover is what the decision is made from -- but nothing is
        // clicked, which is the guarantee that matters and is unchanged.
        assertEquals(1, hover.hovers, "the card is hovered before it is judged");
        assertEquals("the launch control was not reachable after the hover", only(result).reason());
    }

    @Test
    void aCardWithADisabledLaunchControlIsSkippedWithoutBeingTouchedAtAll() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe").launchEnabled(false);
        Recording hover = new Recording();

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.SKIPPED, only(result).status());
        assertEquals(0, lobby.controlClicks);
        assertEquals(1, hover.hovers, "the card is hovered before it is judged");
        assertEquals("the launch control was not reachable after the hover", only(result).reason());
    }

    @Test
    void anIneligibleCardDoesNotSpendTheLaunchBudgetMeantForRealAttempts() {
        Lobby lobby = lobby();
        lobby.addCard("Unplayable", "P").withoutLaunchControl();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        lobby.onNavigate = lobby::clearSurfaces;

        GameLaunchModule.Result result = module(lobby, new Recording(), 3).run(lobby, LIST_URL);

        assertEquals(3, result.games().size());
        assertEquals(2, lobby.controlClicks, "only the two launchable games were opened");
    }

    @Test
    void aCardThatVanishesBeforeTheClickIsSkippedWithoutASingleClick() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        Recording hover = new Recording();
        // The catalogue re-renders the moment the pointer arrives, and the game is gone.
        hover.onHover = () -> lobby.cards.clear();

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.SKIPPED, only(result).status());
        assertEquals(0, lobby.controlClicks);
        assertTrue(only(result).reason().contains("gone"));
    }

    @Test
    void aHoverTheBrowserRefusesIsSkippedWithoutASingleClick() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        Recording hover = new Recording();
        hover.refuse = true;

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.SKIPPED, only(result).status());
        assertEquals(0, lobby.controlClicks);
    }

    @Test
    void aLaunchControlThatIsNotReachableAfterTheHoverIsSkipped() {
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        Recording hover = new Recording();
        // Revealed, but something is over it. Clicking blind is exactly what must not happen.
        hover.onHover = () -> card.launchControl.displayed = false;

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.SKIPPED, only(result).status());
        assertEquals(0, lobby.controlClicks);
    }

    // --- the one click ------------------------------------------------------------------------------

    @Test
    void aSuccessfulLaunchClicksTheConfiguredControlExactlyOnce() {
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.PASSED, only(result).status());
        assertEquals(1, lobby.controlClicks, "exactly one click, never a second");
        assertEquals(1, lobby.clickedElements.size());
        assertSame(card.launchControl, lobby.clickedElements.getFirst(),
            "the element clicked is the configured demo control and nothing else");
    }

    @Test
    void theCardIsResolvedFreshImmediatelyBeforeTheClick() {
        Lobby lobby = lobby();
        FakeCard original = lobby.addCard("Aviator", "Spribe");
        Recording hover = new Recording();
        // The list re-renders after the hover: same game, brand new elements.
        hover.onHover = () -> lobby.replaceCards("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.PASSED, only(result).status());
        assertEquals(1, lobby.controlClicks);
        WebElement clicked = lobby.clickedElements.getFirst();
        assertFalse(clicked == original.launchControl, "the element from before the hover must not be reused");
        assertSame(lobby.cards.getFirst().launchControl, clicked, "the click used the current element");
    }

    @Test
    void aLaunchThatOpenedNothingIsNotClickedAgain() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        // Nothing opens, ever.

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.FAILED, only(result).status());
        assertEquals(1, lobby.controlClicks, "one click; a timeout is never a reason to click again");
    }

    @Test
    void theBudgetLimitsHowManyGamesAreOpened() {
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");
        lobby.addCard("Three", "P");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        // Cleanup removes the surface again, as closing it would.
        lobby.onNavigate = lobby::clearSurfaces;

        GameLaunchModule.Result result = module(lobby, new Recording(), 2).run(lobby, LIST_URL);

        assertEquals(2, result.games().size());
        assertEquals(2, lobby.controlClicks);
    }

    // --- the four surfaces ---------------------------------------------------------------------------

    @Test
    void aNewWindowCountsAsALaunchAndIsClosedAfterwards() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.openWindow("game-window");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(LaunchSurfaceDetector.SurfaceType.NEW_WINDOW, game.surface());
        assertEquals(1, lobby.closes);
        assertTrue(game.cleanupSucceeded());
    }

    @Test
    void aSameTabNavigationCountsAsALaunchAndTheListIsLoadedBack() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.url = "https://example.test/play";

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(LaunchSurfaceDetector.SurfaceType.SAME_TAB_NAVIGATION, game.surface());
        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(LIST_URL, lobby.url);
    }

    @Test
    void anIframeSurfaceCountsAsALaunch() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(LaunchSurfaceDetector.SurfaceType.IFRAME, game.surface());
        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(1, lobby.frameSwitches, "cleanup returned to the top document");
    }

    @Test
    void anOverlaySurfaceCountsAsALaunch() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.closeControls.add(new FakeNode(lobby, "a"));
        lobby.onPointerClick = () -> lobby.addSurface("div");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(LaunchSurfaceDetector.SurfaceType.MODAL_OVERLAY, game.surface());
        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(2, lobby.controlClicks, "one launch click and one close click");
    }

    // --- failures -------------------------------------------------------------------------------------

    @Test
    void aVisibleConfiguredErrorFailsTheGame() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> {
            lobby.addSurface("iframe");
            lobby.errors.add(new FakeNode(lobby, "div"));
        };

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.FAILED, game.status());
        assertTrue(game.reason().contains("error"));
    }

    // --- the authentication handoff ----------------------------------------------------------------

    @Test
    void theCatalogueIsNotOpenedUntilTheSignedInIndicatorHasBeenSeen() {
        // Login submits a form and reads an address; neither proves a session. A run that navigates
        // on that assumption reaches the catalogue as an anonymous visitor and reports whatever it
        // finds there against a site it never signed in to.
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        int[] navigations = {0};
        lobby.onNavigate = () -> navigations[0]++;
        // The indicator is never shown: this run was never signed in.

        GameLaunchModule.Result result = signedInModule(lobby, INDICATOR).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertEquals(0, navigations[0], "the catalogue is not opened before the session is proved");
        assertEquals(0, lobby.controlClicks, "and no game is clicked");
        assertTrue(result.setupProblem().contains("signed-in indicator"), result.setupProblem());
    }

    @Test
    void anIndicatorThatArrivesAMomentAfterTheSignInIsWaitedFor() {
        // The site renders its header after the sign-in settles. Asking once, immediately, reports
        // a perfectly good session as missing.
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        int[] pauses = {0};
        GameLaunchModule module = signedInModuleWithPause(lobby, INDICATOR, () -> {
            pauses[0]++;
            if (pauses[0] == 1) lobby.showIndicator(INDICATOR);
        });

        GameLaunchModule.Result result = module.run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertTrue(pauses[0] >= 1, "the indicator was waited for rather than judged at once");
        assertEquals(GameLaunchModule.SessionState.VERIFIED, only(result).session());
        assertEquals(1, lobby.controlClicks);
    }

    @Test
    void anIndicatorThatNeverArrivesStopsTheRunWithoutOpeningAGame() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        int[] pauses = {0};
        GameLaunchModule module = signedInModuleWithPause(lobby, INDICATOR, () -> pauses[0]++);

        GameLaunchModule.Result result = module.run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertTrue(pauses[0] >= 1, "it was waited for before being given up on");
        assertEquals(0, lobby.controlClicks, "nothing is clicked without a proved session");
        assertTrue(result.games().isEmpty(), "and no game is reported as attempted");
    }

    @Test
    void aLostSessionFailsTheGame() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showIndicator(INDICATOR);
        // The launch signs the visitor out: the indicator the site shows only to a signed-in
        // visitor is gone afterwards, which is the only evidence this check will accept.
        lobby.onPointerClick = () -> { lobby.addSurface("iframe"); lobby.hideIndicator(); };

        GameLaunchModule.GameResult game = only(signedInModule(lobby, INDICATOR).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.FAILED, game.status());
        assertEquals(GameLaunchModule.SessionState.NOT_VERIFIED, game.session());
        assertTrue(game.reason().contains("session"));
    }

    @Test
    void aSurvivingSessionIsVerifiedByAVisibleIndicatorRatherThanByAnAbsence() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showIndicator(INDICATOR);
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(signedInModule(lobby, INDICATOR).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(GameLaunchModule.SessionState.VERIFIED, game.session());
        assertEquals("VERIFIED", game.evidence().get("sessionPreserved"));
    }

    @Test
    void anIndicatorThatIsPresentButHiddenIsNotProofOfASession() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showIndicator(INDICATOR);
        lobby.onPointerClick = () -> { lobby.addSurface("iframe"); lobby.indicatorDisplayed = false; };

        GameLaunchModule.GameResult game = only(signedInModule(lobby, INDICATOR).run(lobby, LIST_URL));

        assertEquals(GameLaunchModule.SessionState.NOT_VERIFIED, game.session());
    }

    @Test
    void aRefusedIndicatorQueryIsNeverReadAsASession() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showIndicator(INDICATOR);
        lobby.onPointerClick = () -> { lobby.addSurface("iframe"); lobby.indicatorRefuses = true; };

        GameLaunchModule.GameResult game = only(signedInModule(lobby, INDICATOR).run(lobby, LIST_URL));

        assertEquals(GameLaunchModule.SessionState.NOT_VERIFIED, game.session(),
            "a query the browser refused can never come back verified");
    }

    @Test
    void aRunWithoutLoginSaysSessionVerificationDoesNotApply() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(GameLaunchModule.SessionState.NOT_APPLICABLE, game.session());
        assertEquals("NOT_APPLICABLE", game.evidence().get("sessionPreserved"),
            "a run that never signed in must not claim a session was checked");
    }

    @Test
    void aSignedInRunWithNoIndicatorSelectorIsASetupProblemAndClicksNothing() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");

        GameLaunchModule.Result result =
            module(lobby, new Recording(), config(3, null, null), true,
                LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertTrue(result.games().isEmpty());
        assertEquals(0, lobby.controlClicks, "no launch control is clicked without the safety setup");
    }

    @Test
    void aSessionThatIsAlreadyGoneStopsTheRunBeforeTheNextClick() {
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");
        // Never signed in as far as the site is concerned: the indicator is not there at all.
        GameLaunchModule.Result result = signedInModule(lobby, INDICATOR).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        // The run now stops before the catalogue is opened at all, rather than one skipped game in:
        // the session is proved before any navigation, so there is no game to report as attempted.
        // What this test has always guaranteed -- the second game is never reached and nothing is
        // clicked -- is unchanged and stricter.
        assertTrue(result.games().isEmpty(), "no game is attempted without a proved session");
        assertEquals(0, lobby.controlClicks);
    }

    // --- a popup over the catalogue ---------------------------------------------------------------

    @Test
    void aBlockingPopupIsClosedOnceBeforeAnyCardIsTouched() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showBlocker(BLOCKER);
        lobby.onBlockerClick = lobby::hideBlocker;
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(lobby, new Recording(), config(3, null, BLOCKER),
            false, LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(1, lobby.blockerClicks, "closed once, never retried");
        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status());
    }

    @Test
    void noBlockingPopupIsTheNormalCaseAndCostsNothing() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(lobby, new Recording(), config(3, null, BLOCKER),
            false, LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(0, lobby.blockerClicks);
    }

    @Test
    void aPopupThatWillNotCloseStopsTheRunBeforeAnyLaunchClick() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showBlocker(BLOCKER);
        // The close control is clicked once and the popup stays. There is no second attempt.

        GameLaunchModule.Result result = module(lobby, new Recording(), config(3, null, BLOCKER),
            false, LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertEquals(1, lobby.blockerClicks, "one attempt, never a retry");
        assertEquals(0, lobby.controlClicks, "no launch control is clicked behind a blocker");
    }

    // --- a card that only offers its launch control once it is hovered -----------------------------

    @Test
    void aLaunchControlThatExistsOnlyAfterTheHoverIsFoundAndUsed() {
        // The live catalogue: Play Now lives in an overlay the card reveals on hover. Deciding
        // before the hover read it as absent and skipped every game; the decision now happens
        // after.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe").withoutLaunchControl();
        Recording hover = new Recording();
        hover.onHover = card::gainsLaunchControl;
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result =
            module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status(),
            result.games().getFirst().reason());
        assertEquals(1, hover.hovers, "hovered once, before anything was decided");
        assertEquals(1, lobby.controlClicks, "exactly one launch click");
    }

    @Test
    void aLaunchControlDisabledBeforeTheHoverAndEnabledAfterItIsUsed() {
        // Present all along, but not usable until the hover. Enablement is judged after the hover
        // for the same reason presence is.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe").launchEnabled(false);
        Recording hover = new Recording();
        hover.onHover = () -> card.launchNode().enabled = true;
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(lobby, hover).run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status(),
            result.games().getFirst().reason());
        assertEquals(1, lobby.controlClicks);
    }

    @Test
    void aPopupThatClosesAMomentLaterIsWaitedOutRatherThanCalledStuck() {
        // Closing is not instant. Reading once, immediately after the click, reported a popup that
        // was mid-animation as one that had refused to close, and the run stopped on a catalogue
        // that was about to be perfectly usable.
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showBlocker(BLOCKER);
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        int[] pauses = {0};
        GameLaunchModule module = moduleWithPause(lobby, () -> {
            pauses[0]++;
            if (pauses[0] == 1) lobby.hideBlocker();
        }, config(3, null, BLOCKER));

        GameLaunchModule.Result result = module.run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(1, lobby.blockerClicks, "still closed once, and still never retried");
        assertTrue(pauses[0] >= 1, "the popup was given time to go rather than judged at once");
        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status());
    }

    @Test
    void aLaunchControlInsertedAfterItsCardIsWaitedForRatherThanCalledUnlaunchable() {
        // The live skip: the card rendered before the element holding its launch control did, so a
        // single immediate read saw a card with no control inside it and called the game
        // unlaunchable -- permanently, because eligibility is decided once and the hover that
        // would have revealed the control never happens for an ineligible card.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe").withoutLaunchControl();
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        int[] pauses = {0};
        GameLaunchModule module = moduleWithPause(lobby, () -> {
            pauses[0]++;
            if (pauses[0] == 1) card.gainsLaunchControl();
        }, config(3, null, null));

        GameLaunchModule.Result result = module.run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(TestedItem.Status.PASSED, result.games().getFirst().status(),
            result.games().getFirst().reason());
        assertTrue(pauses[0] >= 1, "the card was given time to finish rendering");
        assertEquals(1, lobby.controlClicks, "one launch click, after the control arrived");
    }

    @Test
    void aCardThatGenuinelyOffersNoLaunchControlIsStillSkippedWithoutAClick() {
        // The waiting must not turn into acceptance. A catalogue whose cards really have no launch
        // control ends the wait with the same answer it always gave, and touches nothing.
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe").withoutLaunchControl();
        int[] pauses = {0};
        GameLaunchModule module = moduleWithPause(lobby, () -> pauses[0]++, config(3, null, null));

        GameLaunchModule.Result result = module.run(lobby, LIST_URL);

        assertEquals(TestedItem.Status.SKIPPED, result.games().getFirst().status());
        assertEquals(0, lobby.controlClicks, "nothing is clicked on a card with no control");
    }

    @Test
    void twoVisibleCloseControlsAreAnAmbiguityRatherThanAGuess() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.showBlocker(BLOCKER);
        lobby.showBlocker(BLOCKER);

        GameLaunchModule.Result result = module(lobby, new Recording(), config(3, null, BLOCKER),
            false, LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertEquals(0, lobby.blockerClicks, "nothing is clicked when it is not clear what it is");
        assertEquals(0, lobby.controlClicks);
    }

    @Test
    void aPopupThatComesBackWithTheReloadedListIsClosedAgainBeforeTheNextGame() {
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");
        lobby.onBlockerClick = lobby::hideBlocker;
        // Every load of the list serves the overlay again, exactly as a real one would, and puts
        // the cards back.
        lobby.onNavigate = () -> {
            lobby.clearSurfaces();
            lobby.showBlocker(BLOCKER);
            lobby.replaceCards("One", "P", "Two", "P");
        };
        // A launch that empties the list, so cleanup has to load the address again.
        lobby.onPointerClick = () -> { lobby.addSurface("iframe"); lobby.cards.clear(); };

        GameLaunchModule.Result result = module(lobby, new Recording(), config(3, null, BLOCKER),
            false, LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(2, result.games().size());
        assertEquals(2, lobby.controlClicks, "both games were opened");
        assertTrue(lobby.blockerClicks >= 2, "the overlay was dismissed again after the reload");
    }

    @Test
    void cleanupIsAttemptedAfterEveryPerformedClickIncludingAFailedOne() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.cards.clear();
        lobby.addCard("Aviator", "Spribe");
        // Nothing opens. Cleanup must still run and must still confirm the list.

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.FAILED, game.status());
        assertTrue(game.launchClicked());
        assertTrue(game.cleanupSucceeded(), "the list was still there, so cleanup succeeded");
    }

    @Test
    void aFailedGameDoesNotStopTheOnesAfterItWhenTheListCameBack() {
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertTrue(result.completed());
        assertEquals(2, result.games().size());
        assertTrue(result.games().stream()
            .allMatch(game -> game.status() == TestedItem.Status.FAILED));
        assertEquals(2, lobby.controlClicks);
    }

    @Test
    void aListThatCannotBeRestoredStopsTheRunAsASetupProblem() {
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");
        // The launch takes the list away and the return address does not bring it back.
        lobby.onPointerClick = () -> { lobby.addSurface("iframe"); lobby.cards.clear(); };

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertEquals(1, result.games().size(), "the second game was never opened");
        assertEquals(1, lobby.controlClicks);

        GameLaunchModule.GameResult stopped = result.games().getFirst();
        assertFalse(stopped.cleanupSucceeded());
        // The exact contract. The game was not tested, so it is SKIPPED — never FAILED, which
        // would blame the operator's site for the analyzer's own failure to recover. The published
        // outcome says what really happened, and no fourth status was invented to say it.
        assertEquals(TestedItem.Status.SKIPPED, stopped.status());
        assertEquals(GameLaunchModule.RUN_SETUP_PROBLEM_OUTCOME,
            stopped.evidence().get("gameOutcome"));
        assertEquals(List.of("PASSED", "FAILED", "SKIPPED"),
            java.util.Arrays.stream(TestedItem.Status.values()).map(Enum::name).toList(),
            "there are three statuses and this slice added none");
    }

    @Test
    void cleanupReturnsToTheSuppliedAddressNotToWhereTheBrowserEndedUp() {
        // A launch may leave the browser on an address that does not serve the list — loading it
        // back would produce an empty catalogue and every later game would be reported against
        // nothing. The address the caller supplied is the one that is known to work.
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> {
            lobby.addSurface("iframe");
            lobby.cards.clear();
            lobby.url = "https://example.test/after-a-redirect";
        };
        lobby.onNavigate = () -> lobby.addCard("Aviator", "Spribe");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(LIST_URL, lobby.url, "cleanup loaded the supplied address");
        assertTrue(game.cleanupSucceeded());
    }

    @Test
    void aSuppliedAddressIsUsedEvenWhenTheBrowserNeverLeftTheList() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> { lobby.addSurface("iframe"); lobby.cards.clear(); };
        lobby.onNavigate = () -> lobby.addCard("Aviator", "Spribe");
        String elsewhere = "https://example.test/not-the-list";
        lobby.url = elsewhere;

        only(module(lobby).run(lobby, LIST_URL));

        assertEquals(LIST_URL, lobby.url);
        assertFalse(elsewhere.equals(lobby.url), "never the address the browser happened to be on");
    }

    @Test
    void anUnusableConfigurationIsASetupProblemAndNothingIsClicked() {
        Lobby lobby = lobby();
        WebsiteGameLaunchConfig noLaunchControl = new WebsiteGameLaunchConfig(
            false, 3, CARD, NAME, PROVIDER, null, SURFACE, ERROR, CLOSE);

        GameLaunchModule.Result result = new GameLaunchModule(
            noLaunchControl, new GameCardInspector(noLaunchControl), new LaunchSurfaceDetector(noLaunchControl),
            new GameLaunchCleanup(noLaunchControl, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby), new Recording(), false,
            new GameLaunchExecutionEvidence(), LaunchNetworkProbe.disabled(), 3, () -> { })
            .run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertEquals(0, lobby.controlClicks);
    }

    @Test
    void anEmptyListIsASetupProblemRatherThanAPassingRunOfNoGames() {
        Lobby lobby = lobby();

        GameLaunchModule.Result result = module(lobby).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM, result.status());
        assertTrue(result.games().isEmpty());
    }

    // --- the list is re-read between games ---------------------------------------------------------------

    @Test
    void theListIsQueriedAgainBeforeEachGame() {
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");

        module(lobby).run(lobby, LIST_URL);

        // One discovery query, then two resolutions per game, plus cleanup's list confirmations.
        assertTrue(lobby.cardQueries >= 1 + 2 * 2,
            "each game resolves the list twice before touching it: " + lobby.cardQueries);
    }

    @Test
    void onlyTheSuppliedDriverIsEverUsed() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        module(lobby).run(lobby, LIST_URL);

        assertEquals(0, lobby.quits, "the supplied session is never torn down");
        // Lobby.navigate() throws; reaching here proves history navigation never happened.
    }

    // --- evidence ---------------------------------------------------------------------------------------

    @Test
    void publishedEvidenceCarriesOnlyAllowlistedFields() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> {
            lobby.addSurface("iframe");
            lobby.url = "https://games.example.test/launch?token=abc&sig=deadbeef";
        };

        Map<String, String> evidence = only(module(lobby).run(lobby, LIST_URL)).evidence();

        assertTrue(GameLaunchExecutionEvidence.PUBLISHABLE_KEYS.containsAll(evidence.keySet()));
        String everything = String.join(" ", evidence.values());
        assertFalse(everything.contains("token"), everything);
        assertFalse(everything.contains("deadbeef"), everything);
        assertFalse(everything.contains("?"), everything);
    }

    @Test
    void aBrowserThatReportsNothingLeavesTheGamesOutcomeUnchanged() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(
            lobby, new Recording(), config(3), false, LaunchNetworkProbe.disabled())
            .run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, game.network().status());
        assertEquals("NOT_REPORTED", game.evidence().get("network"));
    }

    @Test
    void anHttpErrorDuringTheLaunchFailsTheGameEvenWhenTheSurfaceOpened() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(
            lobby, new Recording(), config(3), false, reporting(observations -> {
                observations.responseStatus(200);
                observations.responseStatus(403);
            })).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.FAILED, game.status());
        assertEquals(LaunchNetworkProbe.Status.HTTP_ERROR, game.network().status());
        assertEquals("HTTP_ERROR 403", game.evidence().get("network"));
        assertTrue(game.cleanupSucceeded(), "cleanup still ran after the failure");
    }

    @Test
    void aFailedDocumentLoadDuringTheLaunchFailsTheGame() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(
            lobby, new Recording(), config(3), false,
            reporting(LaunchNetworkProbe.Observations::loadingFailed)).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.FAILED, game.status());
        assertEquals(LaunchNetworkProbe.Status.LOADING_FAILED, game.network().status());
    }

    @Test
    void aCleanBrowserReportStillPassesTheGame() {
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(
            lobby, new Recording(), config(3), false,
            reporting(observations -> observations.responseStatus(200))).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals("REPORTED_OK", game.evidence().get("network"));
    }

    @Test
    void anIneligibleGameIsNeverObservedBecauseItIsNeverLaunched() {
        Lobby lobby = lobby();
        lobby.addCard("Live Roulette", "ElCasino").withoutLaunchControl();
        int[] attachments = {0};

        only(module(lobby, new Recording(), config(3), false,
            new LaunchNetworkProbe(driver -> { attachments[0]++; return java.util.Optional.empty(); }))
            .run(lobby, LIST_URL));

        assertEquals(0, attachments[0], "no click means nothing to observe");
    }

    /** A probe whose session reports whatever the test seeded, once per game. */
    private static LaunchNetworkProbe reporting(
        java.util.function.Consumer<LaunchNetworkProbe.Observations> seed
    ) {
        return new LaunchNetworkProbe(driver -> {
            LaunchNetworkProbe.Observations observations = new LaunchNetworkProbe.Observations();
            seed.accept(observations);
            return java.util.Optional.of(new LaunchNetworkProbe.Session() {
                @Override public LaunchNetworkProbe.Observation observe() {
                    return observations.classify();
                }
                @Override public void close() { observations.clear(); }
            });
        });
    }

    @Test
    void aNeighbouringDemoControlIsNeverTheOneThatIsClicked() {
        // A catalogue puts the normal launch control and a Demo control side by side, the same
        // size, in the same overlay. The configured selector decides, and nothing else: the decoy
        // fails the test if it is ever the pointer's target.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(1, lobby.controlClicks);
        assertTrue(lobby.clickedElements.contains(card.launchControl),
            "the configured launch control is the one that was clicked");
        assertFalse(lobby.clickedElements.contains(card.demoDecoy),
            "the neighbouring Demo control must never be selected");
    }

    @Test
    void nothingInsideTheOpenedGameSurfaceIsEverTouched() {
        // Once the surface is detected the check looks and leaves. The fake fails the test if any
        // element inside the surface is clicked, and counts how often the frame was entered.
        Lobby lobby = lobby();
        lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.PASSED, game.status());
        assertEquals(1, lobby.controlClicks, "the launch click, and nothing after it");
        for (WebElement surface : lobby.surfaces) {
            assertFalse(lobby.clickedElements.contains(surface),
                "the game surface itself is never clicked");
        }
    }

    // --- a catalogue that re-renders under one game -----------------------------------------------

    /** Arms a browser failure on the nth hover, so it lands on that game and no other. */
    private static Recording hoverThatBreaks(int nthHover, Runnable arm) {
        Recording hover = new Recording();
        int[] seen = {0};
        hover.onHover = () -> {
            if (++seen[0] == nthHover) arm.run();
        };
        return hover;
    }

    @Test
    void aCardThatVanishesWhileItIsBeingReadSkipsThatGameAndNotTheRun() {
        // The live-grid shape: the grid re-rendered between the hover and the re-resolution, the
        // exception escaped every guard, and a run that had already opened three games reported
        // nothing at all. One game is now one game's problem.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        StaleElementReferenceException vanished = new StaleElementReferenceException("re-rendered");

        GameLaunchModule.Result result = module(
            lobby, hoverThatBreaks(1, () -> lobby.failNextCardQuery = vanished), 1)
            .run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status(), "the run survived");
        GameLaunchModule.GameResult game = only(result);
        assertEquals(TestedItem.Status.SKIPPED, game.status());
        assertFalse(game.launchClicked(), "nothing was clicked for the game that vanished");
        assertEquals("the page changed while the card was being read, so the game was left alone",
            game.reason());
        assertEquals(0, card.launchNode().clicks, "zero launch clicks for the affected game");
        assertEquals(0, lobby.controlClicks);
    }

    @Test
    void aPageThatChangesBeforeTheSnapshotSkipsThatGameAndNotTheRun() {
        // Without a before-picture a surface cannot be described, so the game is left alone rather
        // than clicked blind.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        WebDriverException moved = new WebDriverException("the page changed");

        GameLaunchModule.Result result = module(
            lobby, hoverThatBreaks(1, () -> lobby.failNextSurfaceQuery = moved), 1)
            .run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        GameLaunchModule.GameResult game = only(result);
        assertEquals(TestedItem.Status.SKIPPED, game.status());
        assertFalse(game.launchClicked(), "nothing was clicked for the affected game");
        assertEquals(
            "the page changed before the launch could be observed, so the game was left alone",
            game.reason());
        assertEquals(0, card.launchNode().clicks, "zero launch clicks for the affected game");
        assertEquals(0, lobby.controlClicks);
    }

    @Test
    void theGamesEitherSideOfARerenderStillComplete() {
        // The whole point of the guard: three games, the middle one loses its card, and the run
        // still reports a verdict for the other two instead of losing everything.
        Lobby lobby = lobby();
        lobby.addCard("One", "P");
        lobby.addCard("Two", "P");
        lobby.addCard("Three", "P");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        GameLaunchModule.Result result = module(
            lobby,
            hoverThatBreaks(2, () -> lobby.failNextCardQuery =
                new StaleElementReferenceException("re-rendered")),
            3).run(lobby, LIST_URL);

        assertEquals(GameLaunchModule.RunStatus.COMPLETED, result.status());
        assertEquals(3, result.games().size(), "every discovered game is accounted for");
        assertEquals(TestedItem.Status.PASSED, result.games().get(0).status());
        assertEquals(TestedItem.Status.SKIPPED, result.games().get(1).status(),
            "only the middle game was lost");
        assertEquals(TestedItem.Status.PASSED, result.games().get(2).status());
        assertFalse(result.games().get(1).launchClicked());
        assertEquals(2, lobby.controlClicks, "two clicks, for the two games that survived");
    }

    @Test
    void theNewGuardsCoverTheTwoReadsAndNeverTheClick() throws IOException {
        // The boundary itself. Two catches were added, both around a read, both before the click,
        // and the click is still the one call whose failure is allowed to end the run.
        String module = codeOf(Path.of(
            "src/main/java/com/qanize/automation/checks/gamelaunch/GameLaunchModule.java"));

        int resolveGuard = module.indexOf("catch (WebDriverException changedWhileReading)");
        int snapshotGuard = module.indexOf("catch (WebDriverException changedBeforeTheClick)");
        int click = module.indexOf("launchControl.get().click();");
        assertTrue(resolveGuard > 0 && snapshotGuard > 0 && click > 0,
            "both guards and the click are present");
        assertTrue(resolveGuard < click, "the re-resolution guard closes before the click");
        assertTrue(snapshotGuard < click, "and so does the snapshot guard");
        assertEquals(0L, java.util.regex.Pattern
            .compile("catch\\s*\\([^)]*\\)\\s*\\{[^}]*launchControl\\.get\\(\\)\\.click")
            .matcher(module).results().count(), "no catch block contains the click");
    }

    // --- a card with two launch controls ----------------------------------------------------------

    @Test
    void onlyTheConfiguredControlIsClickedWhenTheCardOffersTwo() {
        // A real catalogue card carries more than one way in. Whichever control the configuration
        // names, the other one is a neighbour -- and a neighbour is never clicked, never resolved
        // as a fallback, and never reported as the thing that opened the game.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        module(lobby, new Recording(), configTriggering(LAUNCH), false,
            LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(1, card.launchNode().clicks, "the configured control, exactly once");
        assertEquals(0, card.otherControl.clicks, "the other control is never touched");
        assertFalse(lobby.clickedElements.contains(card.otherControl));
        assertFalse(lobby.clickedElements.contains(card.demoDecoy));
        assertFalse(lobby.clickedElements.contains(card), "and never the card itself");
    }

    @Test
    void namingTheOtherControlSwitchesWhichOneIsClickedAndNothingElse() {
        // The mirror image, which is what makes the first test mean anything: pointing the
        // configuration at the other control moves the single click and leaves every guard intact.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Aviator", "Spribe");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        module(lobby, new Recording(), configTriggering(OTHER_LAUNCH), false,
            LaunchNetworkProbe.disabled()).run(lobby, LIST_URL);

        assertEquals(1, card.otherControl.clicks, "the newly configured control, exactly once");
        assertEquals(0, card.launchNode().clicks, "and the previously configured one is now a neighbour");
        assertFalse(lobby.clickedElements.contains(card.launchNode()));
        assertFalse(lobby.clickedElements.contains(card.demoDecoy));
        assertFalse(lobby.clickedElements.contains(card), "still never the card itself");
        assertEquals(1, lobby.controlClicks, "still exactly one control click in the whole run");
    }

    // --- the launch diagnostics -------------------------------------------------------------------

    @Test
    void aLaunchThatOpenedNothingIsDiagnosedPhotographedAndStillClickedExactlyOnce() {
        // The whole slice, at the level that matters: the diagnostics run, they say which of the
        // three explanations the numbers support, a picture is taken -- and the one pointer click
        // is still one pointer click.
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        RecordingWitness witness = new RecordingWitness(true, true, true);
        CountingCapture capture = new CountingCapture();

        GameLaunchModule.Result result =
            diagnosingModule(lobby, witness, capture).run(lobby, LIST_URL);

        assertEquals(1, lobby.controlClicks, "still exactly one real pointer click");
        assertEquals(1, capture.captures, "one picture, taken because no surface appeared");
        assertEquals(1, witness.installed, "one listener, installed once");
        assertEquals(1, witness.closed, "and removed once the result was in hand");

        Map<String, String> published = result.games().getFirst().evidence();
        assertEquals("true", published.get("clickObserved"));
        assertEquals("true", published.get("clickTrusted"));
        assertEquals("CLICK_DELIVERED_PAGE_DID_NOT_ACT", published.get("launchDiagnosis"));
        assertEquals(LIST_URL, published.get("locationBefore"));
        assertEquals("0", published.get("surfaceBefore"));
        assertEquals("0", published.get("surfaceAfter"));
        assertTrue(Integer.parseInt(published.get("surfaceAttempts")) >= 1);
    }

    @Test
    void aClickThePageNeverReceivedIsReportedAsUndelivered() {
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        RecordingWitness witness = new RecordingWitness(true, false, false);

        GameLaunchModule.Result result =
            diagnosingModule(lobby, witness, new CountingCapture()).run(lobby, LIST_URL);

        assertEquals("false",
            result.games().getFirst().evidence().get("clickObserved"));
        assertEquals("CLICK_NOT_DELIVERED",
            result.games().getFirst().evidence().get("launchDiagnosis"));
        assertEquals(1, lobby.controlClicks, "the diagnosis changes nothing about the click");
    }

    @Test
    void theInputStateIsClearedFirst_beforeTheHoverTheWitnessAndTheClick() {
        // The whole point of the clear is that it happens before anything else this attempt does.
        // A press left over from the sign-in click would otherwise still be held when the launch
        // presses, and the launch would be pressing a button the browser already believes is down.
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        Recording hover = new Recording();
        hover.onHover = () -> lobby.timeline.add("hover");
        RecordingWitness witness = new RecordingWitness(true, true, true, lobby.timeline);
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        diagnosingModule(lobby, witness, new CountingCapture(), hover).run(lobby, LIST_URL);

        int reset = lobby.timeline.indexOf("reset");
        int hovered = lobby.timeline.indexOf("hover");
        int installed = lobby.timeline.indexOf("witness");
        int clicked = lobby.timeline.indexOf("click");
        assertTrue(reset >= 0 && hovered >= 0 && installed >= 0 && clicked >= 0,
            "every step happened: " + lobby.timeline);
        assertTrue(reset < hovered, "cleared before the hover: " + lobby.timeline);
        assertTrue(hovered < installed, "hovered before the listener went on: " + lobby.timeline);
        assertTrue(installed < clicked, "listening before the click: " + lobby.timeline);
        assertEquals(1, lobby.resets, "cleared once for this attempt, and not again");
    }

    @Test
    void everyLaunchAttemptClearsTheInputStateExactlyOnce() {
        // Once per attempt, not once per run: the attempt before this one ended in a click, and
        // whatever that click left behind is this attempt's problem.
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        lobby.addCard("Bike Light", "");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        module(lobby, new Recording(), 2).run(lobby, LIST_URL);

        assertEquals(2, lobby.controlClicks, "both games were opened");
        assertEquals(2, lobby.resets, "and each attempt cleared the input state exactly once");
    }

    @Test
    void aBrowserThatWillNotClearTheInputStateEndsTheAttemptWithNoClickAtAll() {
        // Continuing would mean clicking a live site with an input state this run cannot describe.
        // Nothing is hovered, nothing is clicked, and the game says it was not tested.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        lobby.resetRefusal = new WebDriverException("the session refused to clear its input state");
        Recording hover = new Recording();

        GameLaunchModule.GameResult game = only(module(lobby, hover).run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.SKIPPED, game.status(), "not tested, and not blamed on the site");
        assertFalse(game.launchClicked(), "no launch was claimed");
        assertEquals(0, card.launchNode().clicks, "zero launch clicks");
        assertEquals(0, lobby.controlClicks, "and nothing else was clicked either");
        assertEquals(0, lobby.pointerSequences, "no pointer sequence was issued");
        assertEquals(0, hover.hovers, "not even a hover");
        assertEquals(1, lobby.resets, "tried once; a refusal is never retried");
    }

    @Test
    void theLaunchIsTheControlsOwnClickIssuedExactlyOnceAndNoPointerSequence() {
        // The change itself, at the level that matters: the game opens through the launch
        // control's own click(), that click happens once, and no pointer sequence is issued for it.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");

        module(lobby).run(lobby, LIST_URL);

        assertEquals(1, card.launchNode().clicks, "the control's own click, exactly once");
        assertEquals(0, lobby.pointerSequences,
            "no Actions sequence was issued anywhere in this run");
        assertEquals(1, lobby.controlClicks, "and exactly one control was clicked in total");
    }

    @Test
    void aLaunchClickThatWasRefusedTravelsOutwardsAndIsNeverRepeated() {
        // Loud beats silent, and it must stay loud through the witness's try-with-resources. The
        // browser's own failure reaches the caller unchanged, and nothing clicks a second time.
        // This is the general rule; the one named exception to it is pinned directly below.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        WebDriverException refused = new WebDriverException("unknown error");
        card.launchNode().refusal = refused;

        WebDriverException thrown = assertThrows(
            WebDriverException.class, () -> module(lobby).run(lobby, LIST_URL));

        assertSame(refused, thrown, "the browser's own failure, not a rewritten one");
        assertEquals(1, card.launchNode().clicks, "clicked once; a refusal is never retried");
        assertEquals(0, lobby.controlClicks, "and a refused click is not counted as a click");
    }

    @Test
    void aStaleLaunchControlStillEndsTheRunByIdentity() {
        // A stale element means the page moved under a control the run had already resolved and
        // checked. That is not the same fact as "something is covering it", nothing here can say
        // what the click would have reached, and the narrow guard below must not widen to swallow
        // it.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        StaleElementReferenceException stale =
            new StaleElementReferenceException("the control is no longer attached");
        card.launchNode().refusal = stale;

        StaleElementReferenceException thrown = assertThrows(
            StaleElementReferenceException.class, () -> module(lobby).run(lobby, LIST_URL));

        assertSame(stale, thrown, "unchanged, and still by identity");
        assertEquals(1, card.launchNode().clicks, "one attempt, then out");
    }

    // --- a launch control something else was covering -------------------------------------------

    @Test
    void anInterceptedLaunchClickLeavesTheGameAloneInsteadOfEndingTheRun() {
        // The observed failure. The browser refused before the event left the pointer, because
        // another element owned the point the control occupies. One game is left alone; the run
        // is not.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        card.launchNode().refusal =
            new ElementClickInterceptedException("something is in the way");

        GameLaunchModule.Result result =
            assertDoesNotThrow(() -> module(lobby).run(lobby, LIST_URL));

        GameLaunchModule.GameResult game = only(result);
        assertEquals(TestedItem.Status.SKIPPED, game.status());
        assertFalse(game.launchClicked(), "an intercepted click never reached the page");
        assertEquals("another page element covered the launch control, so the game was left alone",
            game.reason());
        assertEquals(1, card.launchNode().clicks, "exactly one attempt, and never a second");
        assertEquals(0, lobby.controlClicks, "nothing was actually clicked");
        assertEquals(0, lobby.pointerSequences, "and no fallback pointer sequence was issued");
    }

    @Test
    void anInterceptedGameEntersNoDetectionNoVerificationAndNoCleanup() {
        // Nothing happened to the page, so nothing downstream of a launch may run against it.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        card.launchNode().refusal =
            new ElementClickInterceptedException("something is in the way");

        GameLaunchModule.GameResult game = only(module(lobby).run(lobby, LIST_URL));

        assertEquals(1, lobby.timeline.stream().filter("surface-query"::equals).count(),
            "only the before-picture; detection never polled: " + lobby.timeline);
        assertEquals(GameLaunchModule.SessionState.NOT_APPLICABLE, game.session(),
            "no session was verified for a game nothing was done to");
        assertEquals(0, lobby.frameSwitches, "cleanup never returned to the top document");
        assertEquals(0, lobby.closes, "and never closed a window");
        assertEquals(LIST_URL, lobby.url, "the browser was never navigated away and back");
        assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, game.network().status());
    }

    @Test
    void theWitnessAndNetworkSessionStillCloseWhenTheClickIsIntercepted() {
        // The guard returns from inside a try-with-resources. Both resources must still unwind.
        Lobby lobby = lobby();
        FakeCard card = lobby.addCard("Backpack", "");
        card.launchNode().refusal =
            new ElementClickInterceptedException("something is in the way");
        RecordingWitness witness = new RecordingWitness(true, true, true, lobby.timeline);
        int[] networkClosed = {0};
        LaunchNetworkProbe probe = new LaunchNetworkProbe(driver ->
            java.util.Optional.of(new LaunchNetworkProbe.Session() {
                @Override public LaunchNetworkProbe.Observation observe() {
                    throw new AssertionError("nothing is observed when nothing was clicked");
                }
                @Override public void close() { networkClosed[0]++; }
            }));

        WebsiteGameLaunchConfig config = config(1);
        GameLaunchModule module = new GameLaunchModule(
            config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby),
            new Recording(),
            false,
            new GameLaunchExecutionEvidence(),
            probe,
            2,
            () -> { },
            witness,
            BrowserErrorProbe.NONE,
            new CountingCapture());

        GameLaunchModule.GameResult game = only(module.run(lobby, LIST_URL));

        assertEquals(TestedItem.Status.SKIPPED, game.status());
        assertEquals(1, witness.installed, "the witness was installed for the attempt");
        assertEquals(1, witness.closed, "and closed again on the way out");
        assertEquals(0, witness.sampled, "there was no click to sample");
        assertEquals(1, networkClosed[0], "the network session closed too");
        assertEquals(1, card.launchNode().clicks);
    }

    @Test
    void anInterceptedMiddleGameStopsNeitherTheGamesBeforeItNorAfterIt() {
        // Four passed, four skipped, and the ninth ended the run: this is that run in miniature.
        Lobby lobby = lobby();
        lobby.addCard("One", "");
        FakeCard covered = lobby.addCard("Two", "");
        lobby.addCard("Three", "");
        covered.launchNode().refusal =
            new ElementClickInterceptedException("something is in the way");
        lobby.showBlocker(BLOCKER);
        lobby.hideBlocker();
        List<String> order = new ArrayList<>();
        lobby.onBlockerQuery = () -> order.add("prepare");
        lobby.onPointerClick = () -> { order.add("launch"); lobby.addSurface("iframe"); };

        GameLaunchModule.Result result = assertDoesNotThrow(() ->
            module(lobby, new Recording(), config(3, null, BLOCKER), false,
                LaunchNetworkProbe.disabled()).run(lobby, LIST_URL));

        assertEquals(3, result.games().size());
        assertEquals(TestedItem.Status.PASSED, result.games().get(0).status());
        assertEquals(TestedItem.Status.SKIPPED, result.games().get(1).status());
        assertEquals(TestedItem.Status.PASSED, result.games().get(2).status(),
            "the game after the covered one was still attempted");
        assertFalse(result.games().get(1).launchClicked());
        assertEquals(2, lobby.controlClicks, "two real launches, and the covered one is not one");
        assertEquals(1, covered.launchNode().clicks, "the covered card was attempted exactly once");
        // The existing catalogue preparation runs before the run and after every game, including
        // the game that was left alone -- so the third candidate is reached through it.
        assertEquals(
            List.of("prepare", "launch", "prepare", "prepare", "launch", "prepare"), order,
            "preparation ran before the third game: " + order);
        assertEquals(4, lobby.blockerQueries);
    }

    @Test
    void theInterceptedGuardHoldsOnlyTheLaunchStatementAndOffersNoFallback() throws IOException {
        // The boundary, asserted on the source itself: one try holding the single launch
        // statement, one catch naming one exception type, and a body that does nothing but hand
        // back the skipped result.
        String module = codeOf(Path.of(
            "src/main/java/com/qanize/automation/checks/gamelaunch/GameLaunchModule.java"));

        java.util.regex.Matcher guard = java.util.regex.Pattern.compile(
            "try\\s*\\{\\s*launchControl\\.get\\(\\)\\.click\\(\\);\\s*\\}\\s*"
                + "catch\\s*\\(\\s*ElementClickInterceptedException\\s+\\w+\\s*\\)\\s*\\{([^{}]*)\\}")
            .matcher(module);
        assertTrue(guard.find(), "the try holds the single launch statement and nothing else");
        String body = guard.group(1).replaceAll("\\s+", " ").trim();
        assertEquals(
            "return skip(afterHover, \"another page element covered the launch control, "
                + "so the game was left alone\");",
            body, "the catch returns the skipped result and does nothing else");
        assertFalse(guard.find(), "and there is exactly one such guard");

        // No retry, no fallback, no second mechanism, and nothing dismissed from inside it.
        for (String forbidden : List.of(
            "executeScript", "JavascriptExecutor", "new Actions", "Thread.sleep",
            "navigate()", "clickOnce", "hover", "inspector.resolve")) {
            assertFalse(body.contains(forbidden), "the catch must not reach for " + forbidden);
        }

        assertEquals(2L, java.util.regex.Pattern.compile("ElementClickInterceptedException")
            .matcher(module).results().count(),
            "the type appears exactly twice: its import, and that one catch");
        assertEquals(1L, java.util.regex.Pattern.compile("launchControl\\.get\\(\\)\\.click")
            .matcher(module).results().count(), "still exactly one launch click in the class");
        assertEquals(0L, java.util.regex.Pattern
            .compile("catch\\s*\\([^)]*\\)\\s*\\{[^{}]*launchControl\\.get\\(\\)\\.click")
            .matcher(module).results().count(), "no catch block contains the click");
    }

    @Test
    void theClickIsReadTheMomentItReturnsAndNotAfterTenSecondsOfPolling() {
        // The correction that made this slice necessary. Connectivity and geometry read after the
        // surface poll describe a page that has had the whole detection budget to change, which
        // would have made the question -- was the control still there when the click returned? --
        // unanswerable. The sample must sit between the click and the first poll after it.
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        RecordingWitness witness = new RecordingWitness(true, true, true, lobby.timeline);

        diagnosingModule(lobby, witness, new CountingCapture()).run(lobby, LIST_URL);

        assertEquals(1, witness.sampled, "sampled once, at the click");
        int click = lobby.timeline.indexOf("click");
        int sample = lobby.timeline.indexOf("sample");
        assertTrue(click >= 0 && sample >= 0, "both happened: " + lobby.timeline);
        assertEquals(click + 1, sample,
            "nothing at all may come between the click and its reading: " + lobby.timeline);
        assertTrue(lobby.timeline.subList(sample, lobby.timeline.size()).contains("surface-query"),
            "and the surface polling still happens, after it: " + lobby.timeline);
    }

    @Test
    void theListenerIsRemovedEvenWhenTheLaunchThrows() {
        // It is attached to someone else's site. Nothing this check does may leave it there.
        // Cleanup here has nothing to close, which is the path the SauceDemo run actually took.
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        RecordingWitness witness = new RecordingWitness(true, true, true);

        diagnosingModule(lobby, witness, new CountingCapture()).run(lobby, LIST_URL);

        assertEquals(witness.installed, witness.closed, "every listener installed was removed");
    }

    @Test
    void aSuccessfulLaunchIsNotPhotographed() {
        // The picture is for a launch that produced nothing to look at. A working one already has
        // its surface, and photographing every game on a live site is not a diagnostic.
        Lobby lobby = lobby();
        lobby.addCard("Backpack", "");
        lobby.onPointerClick = () -> lobby.addSurface("iframe");
        CountingCapture capture = new CountingCapture();

        diagnosingModule(lobby, new RecordingWitness(true, true, true), capture)
            .run(lobby, LIST_URL);

        assertEquals(0, capture.captures, "nothing failed, so nothing was photographed");
    }

    /** A witness whose answers the test chooses, counting install, sample and removal. */
    private static final class RecordingWitness implements LaunchClickWitness.Installer {

        private static final LaunchClickWitness.Rect BOX =
            new LaunchClickWitness.Rect(true, 0, 0, 100, 40);

        private final LaunchClickWitness.Observation answer;
        private final List<String> timeline;
        private int installed;
        private int sampled;
        private int closed;

        private RecordingWitness(boolean available, boolean observed, boolean trusted) {
            this(available, observed, trusted, new ArrayList<>());
        }

        private RecordingWitness(
            boolean available, boolean observed, boolean trusted, List<String> timeline
        ) {
            this.answer = available
                ? new LaunchClickWitness.Observation(
                    true, observed || trusted, trusted, observed, false, true, true, BOX, BOX)
                : LaunchClickWitness.Observation.UNAVAILABLE;
            this.timeline = timeline;
        }

        @Override
        public LaunchClickWitness.Watch watch(WebElement target) {
            installed++;
            timeline.add("witness");
            return new LaunchClickWitness.Watch() {
                @Override public LaunchClickWitness.Observation sampleNow() {
                    sampled++;
                    timeline.add("sample");
                    return answer;
                }
                @Override public LaunchClickWitness.Observation read() { return answer; }
                @Override public void close() { closed++; }
            };
        }
    }

    /** Counts pictures without taking one. */
    private static final class CountingCapture implements Runnable {

        private int captures;

        @Override
        public void run() {
            captures++;
        }
    }

    private static GameLaunchModule diagnosingModule(
        Lobby lobby, RecordingWitness witness, CountingCapture capture
    ) {
        return diagnosingModule(lobby, witness, capture, new Recording());
    }

    private static GameLaunchModule diagnosingModule(
        Lobby lobby, RecordingWitness witness, CountingCapture capture, Recording hover
    ) {
        WebsiteGameLaunchConfig config = config(1);
        return new GameLaunchModule(
            config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby),
            hover,
            false,
            new GameLaunchExecutionEvidence(),
            LaunchNetworkProbe.disabled(),
            2,
            () -> { },
            witness,
            BrowserErrorProbe.NONE,
            capture);
    }

    // --- architectural guards ------------------------------------------------------------------------------

    @Test
    void productionContainsOnlyTheTwoIntentionalPointerCallSites() throws IOException {
        Map<String, Long> clickSites = sourceCounts("pointer\\.clickOnce\\(");

        // One in the module and one in cleanup, and each one is a different, named act: closing a
        // popup that covers the list, and closing a launched surface. The game launch itself no
        // longer goes through the pointer -- it uses the control's own click, pinned separately
        // above -- so a third pointer call site would be something nobody decided to do.
        assertEquals(1L, clickSites.getOrDefault("GameLaunchModule.java", 0L),
            "one catalogue-blocker close, and adding a second must fail here");
        assertEquals(1L, clickSites.getOrDefault("GameLaunchCleanup.java", 0L),
            "cleanup closes the launched surface through the same pointer, at most once");
        assertEquals(0L, clickSites.getOrDefault("GameCardInspector.java", 0L));
        assertEquals(0L, clickSites.getOrDefault("LaunchSurfaceDetector.java", 0L));

        String module = codeOf(Path.of(
            "src/main/java/com/qanize/automation/checks/gamelaunch/GameLaunchModule.java"));
        assertEquals(0L, java.util.regex.Pattern.compile("pointer\\.clickOnce\\(launchControl")
            .matcher(module).results().count(), "the launch no longer uses the pointer");
        assertEquals(1L, java.util.regex.Pattern.compile("pointer\\.clickOnce\\(control")
            .matcher(module).results().count(), "exactly one closes a blocker");
        assertEquals(0L, java.util.regex.Pattern.compile("clickOnce")
            .matcher(module).results().count() - 1, "and there is no second");
    }

    @Test
    void noProductionSourceKnowsAboutPlayNowOrRealMoney() throws IOException {
        for (Path source : productionSources()) {
            String text = codeOf(source).toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : List.of(
                "play-now", "playnow", "play now", "real-money", "realmoney", "real money",
                "stake", "deposit", "wager", "spin(")) {
                assertFalse(text.contains(forbidden),
                    source.getFileName() + " must not mention " + forbidden);
            }
        }
    }

    @Test
    void noProductionSourceKnowsAnyInGameControl() throws IOException {
        // Opening a game is allowed; playing one is not. None of these words may appear in any
        // executable line of this check, so there is nothing for a future edit to reach for.
        for (Path source : productionSources()) {
            String text = codeOf(source).toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : List.of(
                "spin", "bet", "stake", "wager", "deposit", "autoplay", "auto-play",
                "buyfeature", "buy-feature", "cashier", "wallet", "balance", "demo")) {
                // Whole words only: "became" is not "bet", and a substring ban would forbid
                // ordinary English rather than the controls this rule is about.
                assertFalse(
                    java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(forbidden) + "\\b")
                        .matcher(text).find(),
                    source.getFileName() + " must not mention " + forbidden);
            }
        }
    }

    @Test
    void noProductionSourceUsesHistoryNavigationOrAnUnsanctionedElementClick() throws IOException {
        // One raw element click exists on purpose, on the launch control, and nowhere else. It is
        // named here rather than allowed by a loosened rule, so a second one still fails.
        for (Path source : productionSources()) {
            String text = codeOf(source);
            assertFalse(text.contains("navigate().back()"),
                source.getFileName() + " must never use browser history");
            long raw = java.util.regex.Pattern.compile("\\.click\\(\\);")
                .matcher(text).results().count();
            if (source.getFileName().toString().equals("GameLaunchModule.java")) {
                assertEquals(1L, raw, "the game launch is the one sanctioned element click");
                assertEquals(1L, java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote("launchControl.get().click();"))
                    .matcher(text).results().count(),
                    "and it is the launch control's own click, written exactly that way");
            } else {
                assertEquals(0L, raw,
                    source.getFileName() + " must click only through the shared pointer");
            }
        }
    }

    @Test
    void theLaunchIsRecordedAsClickedOnlyAfterTheClickReturned() throws IOException {
        // Structural, because the ordering is the guarantee: nothing may claim a click happened
        // until the call that performs it has returned, and nothing may catch it in between.
        String module = codeOf(Path.of(
            "src/main/java/com/qanize/automation/checks/gamelaunch/GameLaunchModule.java"));

        int clicked = module.indexOf("launchControl.get().click();");
        int recorded = module.indexOf("evidence.clickedLaunchControl(");
        assertTrue(clicked >= 0 && recorded >= 0, "both the click and its record are present");
        assertTrue(clicked < recorded, "the record may only follow the click that earned it");

        // Exactly one catch stands between the two, and it leaves the method. A catch that fell
        // through to the line below would let the run claim a click it never made, so the rule
        // has not been relaxed -- it has been stated precisely.
        String between = module.substring(clicked, recorded);
        assertEquals(1L, java.util.regex.Pattern.compile("catch\\s*\\(")
            .matcher(between).results().count(),
            "one catch between the click and its record, and no more");
        assertTrue(between.contains("catch (ElementClickInterceptedException"),
            "and it is the intercepted-click guard, not a broader one");
        assertTrue(between.contains("return skip(afterHover,"),
            "which returns rather than falling through to the record below");
    }

    @Test
    void noProductionSourceReadsAFrameAddressOrASessionArtefact() throws IOException {
        for (Path source : productionSources()) {
            String text = codeOf(source);
            for (String forbidden : List.of(
                "\"src\"", "getCookies", "manage().getCookies", "localStorage", "sessionStorage")) {
                assertFalse(text.contains(forbidden),
                    source.getFileName() + " must not read " + forbidden);
            }
        }
    }

    @Test
    void noProductionSourceNamesASiteRouteBrandOrGame() throws IOException {
        for (Path source : productionSources()) {
            String text = codeOf(source).toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : List.of("/casino", "/games", "aviator", "spribe")) {
                assertFalse(text.contains(forbidden),
                    source.getFileName() + " must stay site-neutral, found " + forbidden);
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private static List<Path> productionSources() throws IOException {
        try (Stream<Path> files = Files.list(GAMELAUNCH_MAIN)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * One source file with its comments removed.
     *
     * <p>These guards are about what the code does, not about what the documentation is allowed to
     * discuss: a class that explains why it never clicks Play Now must be able to say so. Stripping
     * is deliberately simple — it only ever removes text, so it can hide a violation but can never
     * invent one, and a hidden violation would still be caught by the behavioural tests above.
     */
    private static String codeOf(Path source) throws IOException {
        String text = Files.readString(source);
        text = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        // Not preceded by a colon, so the "://" inside a URL-parsing literal survives.
        text = text.replaceAll("(?m)(?<!:)//.*$", " ");
        return text;
    }

    private static Map<String, Long> sourceCounts(String pattern) throws IOException {
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (Path source : productionSources()) {
            long hits = compiled.matcher(codeOf(source)).results().count();
            counts.put(source.getFileName().toString(), hits);
        }
        return counts;
    }

    private static GameLaunchModule.GameResult only(GameLaunchModule.Result result) {
        assertEquals(1, result.games().size(), "expected exactly one game: " + result.games());
        return result.games().getFirst();
    }

    private static WebsiteGameLaunchConfig config(int maxGames) {
        return config(maxGames, null, null);
    }

    private static WebsiteGameLaunchConfig config(
        int maxGames, String indicator, String blocker
    ) {
        return new WebsiteGameLaunchConfig(
            false, maxGames, CARD, NAME, PROVIDER, LAUNCH, SURFACE, ERROR, CLOSE, indicator, blocker);
    }

    /** The same configuration with a different launch control named. */
    private static WebsiteGameLaunchConfig configTriggering(String trigger) {
        return new WebsiteGameLaunchConfig(
            false, 1, CARD, NAME, PROVIDER, trigger, SURFACE, ERROR, CLOSE, null, null);
    }

    private static GameLaunchModule module(Lobby lobby) {
        return module(lobby, new Recording());
    }

    private static GameLaunchModule module(Lobby lobby, Recording hover) {
        return module(lobby, hover, 3);
    }

    private static GameLaunchModule module(Lobby lobby, Recording hover, int maxGames) {
        return module(lobby, hover, maxGames, LaunchNetworkProbe.disabled());
    }

    private static GameLaunchModule module(
        Lobby lobby, Recording hover, int maxGames, LaunchNetworkProbe probe
    ) {
        return module(lobby, hover, config(maxGames), false, probe);
    }

    /** A run that signed in, with the indicator the check is required to poll. */
    /** A signed-in run whose every pause is observable, for the handoff wait. */
    private static GameLaunchModule signedInModuleWithPause(
        Lobby lobby, String indicator, Runnable pause
    ) {
        WebsiteGameLaunchConfig config = config(3, indicator, null);
        return new GameLaunchModule(
            config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby),
            new Recording(),
            true,
            new GameLaunchExecutionEvidence(),
            LaunchNetworkProbe.disabled(),
            3,
            pause);
    }

    private static GameLaunchModule signedInModule(Lobby lobby, String indicator) {
        return module(lobby, new Recording(), config(3, indicator, null), true,
            LaunchNetworkProbe.disabled());
    }

    private static GameLaunchModule module(
        Lobby lobby, Recording hover, WebsiteGameLaunchConfig config, boolean loginSelected,
        LaunchNetworkProbe probe
    ) {
        return new GameLaunchModule(
            config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby),
            hover,
            loginSelected,
            new GameLaunchExecutionEvidence(),
            probe,
            2,
            () -> { });
    }

    /** The same module, with a pause the test can watch. */
    private static GameLaunchModule moduleWithPause(Lobby lobby, Runnable pause) {
        WebsiteGameLaunchConfig config = config(3);
        return new GameLaunchModule(
            config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby),
            new Recording(),
            false,
            new GameLaunchExecutionEvidence(),
            LaunchNetworkProbe.disabled(),
            2,
            pause);
    }

    private static GameLaunchModule moduleWithPause(
        Lobby lobby, Runnable pause, WebsiteGameLaunchConfig config
    ) {
        return new GameLaunchModule(
            config,
            new GameCardInspector(config),
            new LaunchSurfaceDetector(config),
            new GameLaunchCleanup(config, new SafePointerClick(lobby), 2, () -> { }),
            new SafePointerClick(lobby),
            new Recording(),
            false,
            new GameLaunchExecutionEvidence(),
            LaunchNetworkProbe.disabled(),
            2,
            pause);
    }

    private static Lobby lobby() {
        return new Lobby(LIST_URL);
    }

    /** A hover that can record, mutate the page, or refuse — but never clicks. */
    private static final class Recording implements GameLaunchModule.HoverAction {
        Runnable onHover;
        boolean refuse;
        int hovers;

        @Override
        public void hover(WebElement card) {
            hovers++;
            if (refuse) throw new WebDriverException("the pointer could not reach the card");
            if (onHover != null) onHover.run();
        }
    }

    /** A catalogue that answers queries and records every action taken against it. */
    static final class Lobby implements WebDriver, Interactive {

        String url;
        final Set<String> handles = new LinkedHashSet<>();
        String active = "lobby";
        final List<FakeCard> cards = new ArrayList<>();
        final List<FakeNode> surfaces = new ArrayList<>();
        final List<FakeNode> errors = new ArrayList<>();
        final List<FakeNode> closeControls = new ArrayList<>();
        final List<FakeNode> indicators = new ArrayList<>();
        final List<FakeNode> blockers = new ArrayList<>();
        final List<WebElement> clickedElements = new ArrayList<>();
        String indicatorSelector;
        String blockerSelector;
        boolean indicatorDisplayed = true;
        boolean indicatorRefuses;
        int blockerClicks;
        Runnable onBlockerClick;
        /** Every read of the blocking-popup selector, i.e. every catalogue preparation. */
        int blockerQueries;
        /** Fired on each of those reads, so a test can prove when preparation happened. */
        Runnable onBlockerQuery;
        int controlClicks;
        /** Pointer sequences actually issued through Interactive.perform. The launch issues none. */
        int pointerSequences;
        /** How many times the session's input state was cleared. Once per launch attempt. */
        int resets;
        /** What the browser throws instead of clearing its input state, when a test says so. */
        RuntimeException resetRefusal;
        /** Thrown by the next card query and then disarmed. Models a catalogue re-rendering. */
        RuntimeException failNextCardQuery;
        /** Thrown by the next surface query and then disarmed. */
        RuntimeException failNextSurfaceQuery;
        /** What happened, in order, so a test can prove when a reading was taken. */
        final List<String> timeline = new ArrayList<>();
        int cardQueries;
        int closes;
        int frameSwitches;
        int quits;
        Runnable onPointerClick;
        Runnable onNavigate;

        Lobby(String url) {
            this.url = url;
            handles.add(active);
        }

        FakeCard addCard(String name, String provider) {
            FakeCard card = new FakeCard(this, name, provider);
            cards.add(card);
            return card;
        }

        void replaceCards(String... namesAndProviders) {
            cards.clear();
            for (int index = 0; index + 1 < namesAndProviders.length; index += 2) {
                addCard(namesAndProviders[index], namesAndProviders[index + 1]);
            }
        }

        void addSurface(String tag) { surfaces.add(new FakeNode(this, tag)); }

        void showIndicator(String selector) {
            indicatorSelector = selector;
            indicatorDisplayed = true;
            if (indicators.isEmpty()) indicators.add(new FakeNode(this, "span", ""));
        }

        void hideIndicator() { indicators.clear(); }

        void showBlocker(String selector) {
            blockerSelector = selector;
            blockers.add(new FakeNode(this, "button", ""));
        }

        void hideBlocker() { blockers.clear(); }
        void clearSurfaces() { surfaces.clear(); }
        void openWindow(String handle) { handles.add(handle); }

        @Override
        public List<WebElement> findElements(By by) {
            String selector = by.toString().replace("By.cssSelector: ", "");
            if (CARD.equals(selector)) {
                cardQueries++;
                if (failNextCardQuery != null) {
                    RuntimeException armed = failNextCardQuery;
                    failNextCardQuery = null;
                    throw armed;
                }
                return List.copyOf(cards);
            }
            if (selector.equals(indicatorSelector)) {
                if (indicatorRefuses) throw new WebDriverException("the page is still settling");
                for (FakeNode node : indicators) node.displayed = indicatorDisplayed;
                return List.copyOf(indicators);
            }
            if (selector.equals(blockerSelector)) {
                blockerQueries++;
                if (onBlockerQuery != null) onBlockerQuery.run();
                return List.copyOf(blockers);
            }
            if (SURFACE.equals(selector)) {
                timeline.add("surface-query");
                if (failNextSurfaceQuery != null) {
                    RuntimeException armed = failNextSurfaceQuery;
                    failNextSurfaceQuery = null;
                    throw armed;
                }
                return List.copyOf(surfaces);
            }
            if (ERROR.equals(selector)) return List.copyOf(errors);
            if (CLOSE.equals(selector)) return List.copyOf(closeControls);
            return List.of();
        }

        @Override public WebElement findElement(By by) {
            List<WebElement> matches = findElements(by);
            if (matches.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return matches.getFirst();
        }
        @Override public Set<String> getWindowHandles() { return Set.copyOf(handles); }
        @Override public String getWindowHandle() { return active; }
        @Override public String getCurrentUrl() { return url; }
        @Override public String getPageSource() { return "<html></html>"; }
        @Override public String getTitle() { return "lobby"; }
        @Override public void close() { closes++; handles.remove(active); }
        @Override public void quit() { quits++; throw new AssertionError("the session is never torn down"); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }

        @Override
        public void get(String destination) {
            url = destination;
            if (onNavigate != null) onNavigate.run();
        }

        @Override
        public Navigation navigate() {
            throw new AssertionError("navigate() is never used; the return URL is explicit");
        }

        @Override
        public TargetLocator switchTo() {
            return new TargetLocator() {
                @Override public WebDriver defaultContent() { frameSwitches++; return Lobby.this; }
                @Override public WebDriver window(String handle) { active = handle; return Lobby.this; }
                @Override public WebDriver frame(int index) { frameSwitches++; return Lobby.this; }
                @Override public WebDriver frame(String nameOrId) { frameSwitches++; return Lobby.this; }
                @Override public WebDriver frame(WebElement element) { frameSwitches++; return Lobby.this; }
                @Override public WebDriver parentFrame() { frameSwitches++; return Lobby.this; }
                @Override public WebDriver newWindow(org.openqa.selenium.WindowType type) {
                    throw new AssertionError("this check never opens a window itself");
                }
                @Override public org.openqa.selenium.Alert alert() { throw new UnsupportedOperationException(); }
                @Override public WebElement activeElement() { throw new UnsupportedOperationException(); }
            };
        }

        /** The one pointer mechanism. Records which element the action targeted. */
        @Override
        public void perform(java.util.Collection<Sequence> actions) {
            List<WebElement> targets = new ArrayList<>();
            for (Sequence sequence : actions) {
                collectTargets(sequence.encode(), targets);
            }
            clickedElements.addAll(targets);
            // The blocker's close control is a separate, counted call site: a test that could not
            // tell it apart from the launch click could not prove either one happened once.
            if (targets.stream().anyMatch(blockers::contains)) {
                blockerClicks++;
                if (onBlockerClick != null) onBlockerClick.run();
                return;
            }
            pointerSequences++;
            controlClicks++;
            timeline.add("click");
            if (onPointerClick != null) onPointerClick.run();
        }

        @Override
        public void resetInputState() {
            resets++;
            timeline.add("reset");
            if (resetRefusal != null) throw resetRefusal;
        }

        @SuppressWarnings("unchecked")
        private static void collectTargets(Object encoded, List<WebElement> into) {
            if (encoded instanceof WebElement element) {
                if (!into.contains(element)) into.add(element);
                return;
            }
            if (encoded instanceof Map<?, ?> map) {
                map.values().forEach(value -> collectTargets(value, into));
                return;
            }
            if (encoded instanceof Iterable<?> items) {
                items.forEach(value -> collectTargets(value, into));
            }
        }
    }

    /** One card, with the two controls a real catalogue puts side by side. */
    static final class FakeCard implements WebElement {

        private final Lobby lobby;
        private final FakeNode name;
        private final FakeNode provider;
        FakeNode launchControl;
        /** The other real control in the same card, resolvable and clickable but never configured. */
        final FakeNode otherControl;
        /** A neighbouring Demo control, present only to prove it is never chosen. Clicking it fails. */
        final FakeNode demoDecoy;

        FakeCard(Lobby lobby, String displayName, String providerName) {
            this.lobby = lobby;
            this.name = new FakeNode(lobby, "span", displayName);
            this.provider = new FakeNode(lobby, "span", providerName);
            this.launchControl = new FakeNode(lobby, "button", "Play");
            this.otherControl = new FakeNode(lobby, "button", "");
            this.demoDecoy = new FakeNode(lobby, "button", "Demo");
        }

        FakeCard withoutLaunchControl() { this.launchControl = null; return this; }

        /** The element holding the launch control is inserted after the card, as Angular does. */
        /** The name only becomes readable once the card is hovered, as a hidden overlay's does. */
        FakeCard revealName(String displayName) { this.name.setText(displayName); return this; }

        /** The launch control this card is holding, for a test that mutates it mid-flow. */
        FakeNode launchNode() { return launchControl; }

        FakeCard gainsLaunchControl() {
            this.launchControl = new FakeNode(lobby, "button", "Play");
            return this;
        }
        FakeCard launchEnabled(boolean value) { this.launchControl.enabled = value; return this; }

        @Override
        public List<WebElement> findElements(By by) {
            String selector = by.toString().replace("By.cssSelector: ", "");
            if (NAME.equals(selector)) return List.of(name);
            if (PROVIDER.equals(selector)) return List.of(provider);
            if (LAUNCH.equals(selector)) return launchControl == null ? List.of() : List.of(launchControl);
            if (OTHER_LAUNCH.equals(selector)) return List.of(otherControl);
            return List.of();
        }

        @Override public WebElement findElement(By by) {
            List<WebElement> matches = findElements(by);
            if (matches.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return matches.getFirst();
        }
        @Override public void click() { throw new AssertionError("the card itself is never clicked"); }
        @Override public String getTagName() { return "app-game-item"; }
        @Override public String getAttribute(String attribute) { return null; }
        @Override public boolean isSelected() { return false; }
        @Override public boolean isEnabled() { return true; }
        @Override public String getText() { return ""; }
        @Override public boolean isDisplayed() { return true; }
        @Override public void clear() { throw new AssertionError("nothing is cleared"); }
        @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("nothing is typed"); }
        @Override public void submit() { throw new AssertionError("nothing is submitted"); }
        @Override public String getCssValue(String propertyName) { return ""; }
        @Override public Point getLocation() { return new Point(0, 0); }
        @Override public Dimension getSize() { return new Dimension(200, 120); }
        @Override public Rectangle getRect() { return new Rectangle(new Point(0, 0), new Dimension(200, 120)); }
        @Override public <X> X getScreenshotAs(OutputType<X> target) {
            throw new AssertionError("this check takes no screenshot of its own");
        }
    }

    /** One control or surface element. */
    static final class FakeNode implements WebElement {

        private final Lobby lobby;
        private final String tag;
        private String text;
        boolean enabled = true;
        boolean displayed = true;
        /** How many times this element's own click() was called. Never more than one. */
        int clicks;
        /** What this element's own click() throws instead of succeeding, when a test says so. */
        RuntimeException refusal;

        FakeNode(Lobby lobby, String tag) { this(lobby, tag, ""); }

        FakeNode(Lobby lobby, String tag, String text) {
            this.lobby = lobby;
            this.tag = tag;
            this.text = text;
        }

        @Override public String getTagName() { return tag; }
        @Override public String getText() { return text; }

        /** The rendered text a browser would report, which an unhovered overlay reports as "". */
        void setText(String value) { this.text = value; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public boolean isDisplayed() { return displayed; }

        @Override
        public String getAttribute(String attribute) {
            if ("src".equals(attribute)) {
                throw new AssertionError("a frame address must never be read");
            }
            return null;
        }

        /**
         * The launch control's own click, which is how the game launch now opens a game.
         *
         * <p>Every other control in this project is still clicked through the shared pointer, so
         * the two rules that were assertions before are assertions still: the neighbouring decoy
         * is never chosen, and a game surface is never clicked at all.
         */
        @Override
        public void click() {
            if ("Demo".equals(text)) {
                throw new AssertionError("the Demo decoy must never be clicked");
            }
            if (lobby.surfaces.contains(this)) {
                throw new AssertionError("the game surface itself is never clicked");
            }
            clicks++;
            if (refusal != null) throw refusal;
            lobby.controlClicks++;
            lobby.clickedElements.add(this);
            lobby.timeline.add("click");
            if (lobby.onPointerClick != null) lobby.onPointerClick.run();
        }

        @Override public boolean isSelected() { return false; }
        @Override public void clear() { throw new AssertionError("nothing is cleared"); }
        @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("nothing is typed"); }
        @Override public void submit() { throw new AssertionError("nothing is submitted"); }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) {
            throw new org.openqa.selenium.NotFoundException("none");
        }
        @Override public String getCssValue(String propertyName) { return ""; }
        @Override public Point getLocation() { return new Point(0, 0); }
        @Override public Dimension getSize() { return new Dimension(100, 30); }
        @Override public Rectangle getRect() { return new Rectangle(new Point(0, 0), new Dimension(100, 30)); }
        @Override public <X> X getScreenshotAs(OutputType<X> target) {
            throw new AssertionError("no screenshot here");
        }
    }
}
