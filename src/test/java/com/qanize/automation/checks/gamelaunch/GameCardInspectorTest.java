package com.qanize.automation.checks.gamelaunch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a game catalogue without touching it.
 *
 * <p>Two things are being proved here, and the second is the one that matters. The first is
 * ordinary: identities are built consistently, duplicates collapse, the budget is honoured. The
 * second is that nothing a candidate carries can ever be clicked — because the control sitting
 * next to the one this check will eventually use is a real-money button, and a stale reference is
 * exactly how automation arrives at the wrong one.
 *
 * <p>The page below is a fake DOM, in the shape the project's other browser tests use: hand-written
 * {@code WebElement}s, no mocking framework, no network, no driver. Every query is counted, so
 * "did it look again?" is something the tests read rather than assume. No test path clicks, hovers,
 * navigates, scrolls or executes script; {@link FakeElement#click()} fails the test if it is ever
 * reached.
 */
@Tag("unit")
class GameCardInspectorTest {

    private static final String CARD = "app-game-item";
    private static final String NAME = ".game-name";
    private static final String PROVIDER = ".provider";
    private static final String LAUNCH = "button.btn-launch";

    // --- order, identity and normalization --------------------------------------------------------

    @Test
    void cardsKeepTheOrderTheCatalogueRendersThemIn() {
        FakeCatalogue page = catalogue(
            card("Aviator", "Spribe"),
            card("Safari Sam", "BetSoft"),
            card("Roulette", "ElCasino"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(List.of("Aviator", "Safari Sam", "Roulette"),
            found.stream().map(GameCardInspector.GameCandidate::displayName).toList());
        assertEquals(List.of(0, 1, 2),
            found.stream().map(GameCardInspector.GameCandidate::domIndex).toList());
    }

    @Test
    void identityIgnoresSurroundingWhitespaceInnerSpacingAndCase() {
        FakeCatalogue page = catalogue(
            card("  Safari   Sam \n", " BetSoft "),
            card("SAFARI SAM", "betsoft"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(1, found.size(), "the same game written two ways is one game");
        assertEquals("safari sam|betsoft", found.getFirst().identityKey());
        // The display text keeps the page's own casing; only the identity is folded.
        assertEquals("Safari Sam", found.getFirst().displayName());
        assertEquals("BetSoft", found.getFirst().provider());
    }

    @Test
    void aTurkishDefaultLocaleCannotChangeAnIdentity() {
        // A locale-sensitive fold turns "I" into a dotless "ı" in Turkish, which would give the
        // same card a different identity on a differently configured machine.
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            List<GameCardInspector.GameCandidate> found =
                inspector(10).discover(catalogue(card("MYSTIC HIVE", "BetSoft")));
            assertEquals("mystic hive|betsoft", found.getFirst().identityKey());
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void theSameNameFromTwoProvidersIsTwoGames() {
        FakeCatalogue page = catalogue(
            card("Roulette", "ElCasino"),
            card("Roulette", "PragmaticPlay"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(2, found.size());
        assertEquals(List.of("roulette|elcasino", "roulette|pragmaticplay"),
            found.stream().map(GameCardInspector.GameCandidate::identityKey).toList());
    }

    @Test
    void aRepeatedCardKeepsItsFirstPositionAndDropsTheLaterOne() {
        FakeCatalogue page = catalogue(
            card("Aviator", "Spribe"),
            card("Safari Sam", "BetSoft"),
            card("Aviator", "Spribe"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(2, found.size());
        assertEquals(0, found.getFirst().domIndex(), "the first occurrence is the one kept");
        assertEquals("Safari Sam", found.get(1).displayName());
    }

    @Test
    void aMissingOrBlankProviderStillProducesAUsableIdentity() {
        FakeCatalogue page = catalogue(
            card("Aviator", "   "),
            cardWithoutProviderElement("Danfo Crash"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(List.of("aviator|", "danfo crash|"),
            found.stream().map(GameCardInspector.GameCandidate::identityKey).toList());
        assertEquals("", found.getFirst().provider());
        assertTrue(found.stream().allMatch(GameCardInspector.GameCandidate::identifiedByName));
    }

    // --- the unnamed card ----------------------------------------------------------------------

    @Test
    void aCardWithNoReadableNameFallsBackToItsPositionRatherThanAnEmptyKey() {
        FakeCatalogue page = catalogue(
            card("Aviator", "Spribe"),
            card("   ", "BetSoft"),
            cardWithoutNameElement("Tada"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(3, found.size(), "two unnamed cards are two cards, not one empty identity");
        assertEquals("dom-index:1|betsoft", found.get(1).identityKey());
        assertEquals("dom-index:2|tada", found.get(2).identityKey());
        assertFalse(found.get(1).identifiedByName());
        assertTrue(found.getFirst().identifiedByName());
    }

    // --- the launch budget -----------------------------------------------------------------------

    @Test
    void theBudgetCapsUniqueCandidatesAndKeepsTheLeadingOnes() {
        FakeCatalogue page = catalogue(
            card("One", "P"), card("Two", "P"), card("Three", "P"),
            card("Four", "P"), card("Five", "P"));

        assertEquals(List.of("One"), names(inspector(WebsiteGameLaunchConfig.MIN_GAMES).discover(page)));
        assertEquals(List.of("One", "Two", "Three"),
            names(inspector(WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES).discover(page)));
        assertEquals(List.of("One", "Two", "Three", "Four", "Five"),
            names(inspector(WebsiteGameLaunchConfig.MAX_GAMES).discover(page)));
    }

    @Test
    void duplicatesDoNotConsumeTheBudget() {
        // Three cards, two of them the same game: the budget of two must still yield two games.
        FakeCatalogue page = catalogue(
            card("Aviator", "Spribe"),
            card("Aviator", "Spribe"),
            card("Safari Sam", "BetSoft"));

        assertEquals(List.of("Aviator", "Safari Sam"), names(inspector(2).discover(page)));
    }

    // --- eligibility ------------------------------------------------------------------------------

    @Test
    void aCardWithNoDemoTriggerIsIneligible() {
        FakeCatalogue page = catalogue(cardWithoutDemo("Live Roulette", "ElCasino"));

        GameCardInspector.GameCandidate only = inspector(10).discover(page).getFirst();

        assertFalse(only.launchControlPresent());
        assertFalse(only.launchControlEnabled());
        assertFalse(only.eligibleForLaunch());
    }

    @Test
    void aDisabledDemoTriggerIsIneligible() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe").launchEnabled(false));

        GameCardInspector.GameCandidate only = inspector(10).discover(page).getFirst();

        assertTrue(only.launchControlPresent());
        assertFalse(only.launchControlEnabled());
        assertFalse(only.eligibleForLaunch());
    }

    @Test
    void aDemoTriggerHiddenUntilHoverIsStillEligible() {
        // This catalogue keeps the launch control at opacity 0 until the card is hovered. Requiring
        // visibility here would reject every card on the site the check exists for.
        FakeCatalogue page = catalogue(card("Aviator", "Spribe").launchDisplayed(false));

        GameCardInspector.GameCandidate only = inspector(10).discover(page).getFirst();

        assertTrue(only.launchControlPresent());
        assertTrue(only.launchControlEnabled());
        assertTrue(only.eligibleForLaunch());
        assertEquals(0, page.displayedChecks, "visibility is never consulted during discovery");
    }

    // --- nothing is retained ----------------------------------------------------------------------

    @Test
    void noCandidateCanCarryAnElementAtAll() throws ReflectiveOperationException {
        // Not "does not happen to hold one" — cannot. Every component of the record is a value.
        for (var component : GameCardInspector.GameCandidate.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertTrue(type.isPrimitive() || type == String.class,
                component.getName() + " must be a scalar, not " + type.getName());
        }
        for (var field : GameCardInspector.class.getDeclaredFields()) {
            assertFalse(WebElement.class.isAssignableFrom(field.getType()),
                field.getName() + " must not hold an element");
            assertFalse(SearchContext.class.isAssignableFrom(field.getType()),
                field.getName() + " must not hold a search context");
        }
    }

    // --- re-resolution ----------------------------------------------------------------------------

    @Test
    void resolvingAlwaysQueriesTheCatalogueAgain() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"));
        GameCardInspector inspector = inspector(10);

        GameCardInspector.GameCandidate candidate = inspector.discover(page).getFirst();
        int afterDiscovery = page.cardQueries;
        inspector.resolve(page, candidate);
        inspector.resolve(page, candidate);

        assertEquals(afterDiscovery + 2, page.cardQueries, "one fresh query per resolution");
    }

    @Test
    void aReRenderedCardIsReturnedInsteadOfTheOneDiscoverySaw() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate candidate = inspector.discover(page).getFirst();
        WebElement asDiscovered = page.cards.getFirst();

        // The catalogue re-renders: same game, brand new element, as a real Angular list does.
        page.replaceAll(card("Aviator", "Spribe"));
        GameCardInspector.ResolvedCard resolved = inspector.resolve(page, candidate).orElseThrow();

        assertNotSame(asDiscovered, resolved.card(), "the element from discovery must not be reused");
        assertSame(page.cards.getFirst(), resolved.card(), "the element must come from this query");
        assertEquals("aviator|spribe", resolved.candidate().identityKey());
        assertTrue(resolved.launchControl().isPresent());
    }

    @Test
    void aNamedCardIsFoundAfterTheCatalogueReordersAroundIt() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"), card("Safari Sam", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate aviator = inspector.discover(page).getFirst();

        page.replaceAll(card("Safari Sam", "BetSoft"), card("Aviator", "Spribe"));
        GameCardInspector.ResolvedCard resolved = inspector.resolve(page, aviator).orElseThrow();

        assertEquals("Aviator", resolved.candidate().displayName());
        assertEquals(1, resolved.candidate().domIndex(), "its position now, not the one remembered");
    }

    @Test
    void aCardThatVanishedResolvesToNothing() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"), card("Safari Sam", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate aviator = inspector.discover(page).getFirst();

        page.replaceAll(card("Safari Sam", "BetSoft"));

        assertTrue(inspector.resolve(page, aviator).isEmpty());
    }

    @Test
    void anUnnamedCandidateNeverResolvesToANamedCardThatTookItsPlace() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"), card("  ", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate unnamed = inspector.discover(page).get(1);

        // A real game now occupies position 1. Launching it would launch something nobody chose.
        page.replaceAll(card("Aviator", "Spribe"), card("Mystic Hive", "BetSoft"));

        assertTrue(inspector.resolve(page, unnamed).isEmpty());
    }

    @Test
    void anUnnamedCandidateResolvesWhenItsPositionAndProviderStillMatch() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"), card("  ", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate unnamed = inspector.discover(page).get(1);

        page.replaceAll(card("Aviator", "Spribe"), card("", "BetSoft"));

        assertTrue(inspector.resolve(page, unnamed).isPresent());
    }

    @Test
    void anUnnamedCandidateWhosePositionNoLongerExistsResolvesToNothing() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"), card("  ", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate unnamed = inspector.discover(page).get(1);

        page.replaceAll(card("Aviator", "Spribe"));

        assertTrue(inspector.resolve(page, unnamed).isEmpty());
    }

    @Test
    void aCardThatGoesStaleMidReadKeepsItsPositionAndIsReportedUnusable() {
        FakeCatalogue page = catalogue(
            card("Aviator", "Spribe"),
            card("Vanishing", "BetSoft").goesStale(),
            card("Safari Sam", "BetSoft"));

        List<GameCardInspector.GameCandidate> found = inspector(10).discover(page);

        assertEquals(3, found.size());
        assertEquals(List.of(0, 1, 2),
            found.stream().map(GameCardInspector.GameCandidate::domIndex).toList());
        assertFalse(found.get(1).eligibleForLaunch());
        assertEquals("", found.get(1).displayName());
        assertEquals("Safari Sam", found.get(2).displayName(), "the cards after it are not renumbered");
    }

    // --- an unconfigured check discovers nothing rather than guessing -------------------------------

    @Test
    void withNoCardSelectorNothingIsDiscoveredAndNothingIsQueried() {
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"));
        GameCardInspector inspector = new GameCardInspector(WebsiteGameLaunchConfig.disabled());

        assertTrue(inspector.discover(page).isEmpty());
        assertEquals(0, page.cardQueries, "an unconfigured check must not go looking");
    }

    @Test
    void withNoDemoSelectorEveryCardIsIneligibleRatherThanAssumedLaunchable() {
        WebsiteGameLaunchConfig noDemo = new WebsiteGameLaunchConfig(
            false, 10, CARD, NAME, PROVIDER, null, null, null, null);

        GameCardInspector.GameCandidate only =
            new GameCardInspector(noDemo).discover(catalogue(card("Aviator", "Spribe"))).getFirst();

        assertFalse(only.eligibleForLaunch());
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static GameCardInspector inspector(int maxGames) {
        return new GameCardInspector(new WebsiteGameLaunchConfig(
            false, maxGames, CARD, NAME, PROVIDER, LAUNCH, null, null, null));
    }

    private static List<String> names(List<GameCardInspector.GameCandidate> candidates) {
        return candidates.stream().map(GameCardInspector.GameCandidate::displayName).toList();
    }

    private static FakeCatalogue catalogue(FakeElement... cards) {
        return new FakeCatalogue(cards);
    }

    // --- a name the page renders only on hover ------------------------------------------------

    @Test
    void aCardWhoseNameIsRevealedOnHoverKeepsOneIdentityThroughout() {
        // The defect, reproduced. Discovery read rendered text, found nothing, and fell back to
        // the card's position. The hover then painted the name, the very same element started
        // answering with it, and the resolver -- correctly, given what it was told -- decided a
        // named card had taken the slot and refused to open anything.
        FakeCatalogue page = catalogue(hoverRevealCard("Aviator", "Spribe"));
        GameCardInspector inspector = inspector(10);

        GameCardInspector.GameCandidate discovered = inspector.discover(page).getFirst();

        assertTrue(discovered.identifiedByName(),
            "a name the document holds identifies the card whether or not it is painted");
        assertEquals("aviator|spribe", discovered.identityKey());
        assertEquals("Aviator", discovered.displayName());
    }

    @Test
    void theSameCardResolvesAfterTheHoverThatRevealedItsName() {
        FakeCatalogue page = catalogue(hoverRevealCard("Aviator", "Spribe"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate discovered = inspector.discover(page).getFirst();

        // The only thing that happens between discovery and resolution.
        page.cards.getFirst().hovered();

        Optional<GameCardInspector.ResolvedCard> resolved = inspector.resolve(page, discovered);

        assertTrue(resolved.isPresent(), "the hover revealed a name; it did not replace the card");
        assertEquals(discovered.identityKey(), resolved.get().candidate().identityKey());
        assertTrue(resolved.get().launchControl().isPresent());
    }

    @Test
    void aDifferentCardAtTheSamePositionIsStillRejectedAfterAHover() {
        // The safety property that must survive the fix. Identity, not position, is what decides,
        // so the slot being occupied by something else is not an invitation to open it.
        FakeCatalogue page = catalogue(
            hoverRevealCard("Aviator", "Spribe"), hoverRevealCard("Mystic Hive", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate chosen = inspector.discover(page).getFirst();

        // The list re-rendered and a different game now sits where the chosen one was.
        page.replaceAll(card("Fortune Tiger", "PG Soft"), card("Mystic Hive", "BetSoft"));

        assertTrue(inspector.resolve(page, chosen).isEmpty(),
            "position 0 is occupied by another game, which is not the game that was chosen");
    }

    @Test
    void aCardThatMovedIsStillFoundByTheNameTheDocumentHolds() {
        FakeCatalogue page = catalogue(
            hoverRevealCard("Aviator", "Spribe"), hoverRevealCard("Mystic Hive", "BetSoft"));
        GameCardInspector inspector = inspector(10);
        GameCardInspector.GameCandidate chosen = inspector.discover(page).getFirst();

        // Same two games, opposite order, brand new elements.
        page.replaceAll(hoverRevealCard("Mystic Hive", "BetSoft"), hoverRevealCard("Aviator", "Spribe"));

        Optional<GameCardInspector.ResolvedCard> resolved = inspector.resolve(page, chosen);

        assertTrue(resolved.isPresent());
        assertEquals("aviator|spribe", resolved.get().candidate().identityKey());
        assertEquals(1, resolved.get().candidate().domIndex(), "found where it moved to");
    }

    @Test
    void aCardWithNoNameAnywhereIsStillIdentifiedByItsPosition() {
        // The index identity is not dead code and must not become a name-shaped one. A card with
        // nothing to read stays positional, and keeps the stricter rules that come with that.
        FakeCatalogue page = catalogue(card("Aviator", "Spribe"), cardWithoutNameElement("BetSoft"));
        GameCardInspector inspector = inspector(10);

        GameCardInspector.GameCandidate unnamed = inspector.discover(page).get(1);

        assertFalse(unnamed.identifiedByName());
        assertEquals("dom-index:1|betsoft", unnamed.identityKey());
    }

    @Test
    void aDriverThatCannotServeThePropertyFallsBackToRenderedText() {
        // Older drivers answer getDomProperty with UnsupportedOperationException. A catalogue that
        // renders its names has nothing to lose by that, and must keep working.
        FakeCatalogue page = catalogue(cardServedByAnOlderDriver("Aviator", "Spribe"));
        GameCardInspector inspector = inspector(10);

        GameCardInspector.GameCandidate discovered = inspector.discover(page).getFirst();

        assertEquals("aviator|spribe", discovered.identityKey());
    }

    private static FakeElement card(String name, String provider) {
        return new FakeElement(name, provider, true, true);
    }

    /** A card whose name is in the document but not on the screen until the pointer arrives. */
    private static FakeElement hoverRevealCard(String name, String provider) {
        return card(name, provider).nameHiddenUntilHover();
    }

    /** A card served by a driver that does not implement the DOM-property endpoint. */
    private static FakeElement cardServedByAnOlderDriver(String name, String provider) {
        FakeElement element = card(name, provider);
        element.domPropertySupported = false;
        return element;
    }

    private static FakeElement cardWithoutDemo(String name, String provider) {
        return new FakeElement(name, provider, false, false);
    }

    private static FakeElement cardWithoutNameElement(String provider) {
        FakeElement element = new FakeElement(null, provider, true, true);
        element.nameElementPresent = false;
        return element;
    }

    private static FakeElement cardWithoutProviderElement(String name) {
        FakeElement element = new FakeElement(name, null, true, true);
        element.providerElementPresent = false;
        return element;
    }

    /**
     * A catalogue that answers the card selector and counts how often it was asked.
     *
     * <p>Only {@code findElements} is supported. Everything a discovery step must never do — get,
     * navigate, switch window, execute script — is simply not implemented, so a test that strayed
     * into one would not compile or would fail loudly.
     */
    private static final class FakeCatalogue implements SearchContext {

        private final List<FakeElement> cards = new ArrayList<>();
        private int cardQueries;
        private int displayedChecks;

        private FakeCatalogue(FakeElement... initial) {
            replaceAll(initial);
        }

        /** The Angular list re-renders: the same games arrive as brand new elements. */
        private void replaceAll(FakeElement... replacement) {
            cards.clear();
            for (FakeElement element : replacement) {
                element.page = this;
                cards.add(element);
            }
        }

        @Override
        public List<WebElement> findElements(By by) {
            assertEquals("By.cssSelector: " + CARD, by.toString(), "only the configured card selector");
            cardQueries++;
            return List.copyOf(cards);
        }

        @Override
        public WebElement findElement(By by) {
            List<WebElement> matches = findElements(by);
            if (matches.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return matches.getFirst();
        }
    }

    /** One card, or one control inside a card. Reading it is all these tests ever do. */
    private static final class FakeElement implements WebElement {

        private static final Point ORIGIN = new Point(0, 0);
        private static final Dimension SIZE = new Dimension(200, 120);

        private FakeCatalogue page;
        private final String text;
        private final String providerText;
        /**
         * Whether the panel holding this card's name is being rendered.
         *
         * <p>False models the catalogue at rest: the name is in the document the whole time, but
         * the panel around it sits at {@code opacity: 0}, so rendered text is empty. Hovering
         * renders it. Nothing about the element changes -- only what the screen shows.
         */
        private boolean nameRendered = true;
        /** False models a driver that answers the DOM-property endpoint with an exception. */
        private boolean domPropertySupported = true;
        private final boolean launchPresent;
        private boolean launchEnabled;
        private boolean launchDisplayed = true;
        private boolean nameElementPresent = true;
        private boolean providerElementPresent = true;
        private boolean stale;
        private final boolean control;

        private FakeElement(String name, String provider, boolean launchPresent, boolean launchEnabled) {
            this(name == null ? "" : name, provider == null ? "" : provider,
                launchPresent, launchEnabled, false);
        }

        private FakeElement(
            String text, String providerText, boolean launchPresent, boolean launchEnabled, boolean control
        ) {
            this.text = text;
            this.providerText = providerText;
            this.launchPresent = launchPresent;
            this.launchEnabled = launchEnabled;
            this.control = control;
        }

        /** The name is in the DOM but not on the screen, as a hover-reveal catalogue leaves it. */
        private FakeElement nameHiddenUntilHover() { this.nameRendered = false; return this; }

        /** What the hover does, and the only thing it does. */
        private FakeElement hovered() { this.nameRendered = true; return this; }

        private FakeElement launchEnabled(boolean value) { this.launchEnabled = value; return this; }
        private FakeElement launchDisplayed(boolean value) { this.launchDisplayed = value; return this; }
        private FakeElement goesStale() { this.stale = true; return this; }

        @Override
        public List<WebElement> findElements(By by) {
            if (stale) throw new StaleElementReferenceException("the card re-rendered");
            String selector = by.toString().replace("By.cssSelector: ", "");
            if (NAME.equals(selector)) {
                if (!nameElementPresent) return List.of();
                FakeElement name = child(text, true);
                name.nameRendered = nameRendered;
                name.domPropertySupported = domPropertySupported;
                return List.of(name);
            }
            if (PROVIDER.equals(selector)) {
                if (!providerElementPresent) return List.of();
                FakeElement provider = child(providerText, true);
                provider.domPropertySupported = domPropertySupported;
                return List.of(provider);
            }
            if (LAUNCH.equals(selector)) {
                if (!launchPresent) return List.of();
                FakeElement control = child("Play", launchDisplayed);
                control.launchEnabledControl = launchEnabled;
                return List.of(control);
            }
            return List.of();
        }

        private boolean launchEnabledControl = true;

        private FakeElement child(String value, boolean displayed) {
            FakeElement element = new FakeElement(value, "", false, false, true);
            element.page = page;
            element.launchDisplayed = displayed;
            return element;
        }

        /** Rendered text. Empty while the panel around it is not being painted. */
        @Override public String getText() { return nameRendered ? text : ""; }

        /** What the document holds, whether or not any of it is on screen. */
        @Override
        public String getDomProperty(String name) {
            if (!domPropertySupported) throw new UnsupportedOperationException("getDomProperty");
            return "textContent".equals(name) ? text : null;
        }
        @Override public boolean isEnabled() { return control ? launchEnabledControl : true; }

        @Override
        public boolean isDisplayed() {
            if (page != null) page.displayedChecks++;
            return launchDisplayed;
        }

        // Everything below is either inert or a tripwire. A discovery step touches none of it.
        @Override public void click() {
            throw new AssertionError("discovery must never click");
        }
        @Override public void submit() {
            throw new AssertionError("discovery must never submit");
        }
        @Override public void sendKeys(CharSequence... keys) {
            throw new AssertionError("discovery must never type");
        }
        @Override public void clear() {
            throw new AssertionError("discovery must never clear");
        }
        @Override public WebElement findElement(By by) {
            List<WebElement> matches = findElements(by);
            if (matches.isEmpty()) throw new org.openqa.selenium.NotFoundException("none");
            return matches.getFirst();
        }
        @Override public String getTagName() { return control ? "button" : "div"; }
        @Override public String getAttribute(String name) { return null; }
        @Override public boolean isSelected() { return false; }
        @Override public String getCssValue(String propertyName) { return ""; }
        @Override public Point getLocation() { return ORIGIN; }
        @Override public Dimension getSize() { return SIZE; }
        @Override public Rectangle getRect() { return new Rectangle(ORIGIN, SIZE); }
        @Override public <X> X getScreenshotAs(OutputType<X> target) {
            throw new AssertionError("discovery must never take a screenshot");
        }
    }

    @Test
    void theFakeCardWouldFailTheTestIfAnythingClickedIt() {
        // The tripwire above is only worth having if it actually trips.
        FakeElement element = card("Aviator", "Spribe");
        assertThrows(AssertionError.class, element::click);
        assertThrows(AssertionError.class, () -> element.getScreenshotAs(OutputType.BASE64));
    }
}
