package com.qanize.automation.checks.gamelaunch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;

import com.qanize.automation.checks.shared.SelectorUtils;

/**
 * Reads the game catalogue. It never touches it.
 *
 * <p>Discovery only: this class queries the configured selectors, reads text and two booleans, and
 * returns value objects. It does not click, hover, scroll, navigate, execute script, switch
 * windows or wait. Nothing here can change the page it is looking at, which is what makes it safe
 * to run against a live operator's site before any launch behaviour exists.
 *
 * <p><strong>Text is read from the document, not from the screen.</strong> A catalogue that reveals
 * a card's name on hover keeps that name in the DOM the whole time and merely stops rendering it,
 * so identifying a card by rendered text would give the same card two different identities either
 * side of a hover. See {@link #documentText}.
 *
 * <p><strong>No element outlives a call.</strong> A {@link GameCandidate} holds strings, an int and
 * two flags — never a {@link WebElement}, a locator result, a {@link SearchContext} or a driver.
 * That is deliberate rather than tidy: a catalogue re-renders while it is being read (lazy images
 * settle, a category loads, a promo closes), and every element captured during discovery is stale
 * by the time anything would use it. Clicking a stale reference is how automation ends up
 * interacting with the wrong card — here, a card sitting one position away from a real-money
 * control. Re-resolution therefore starts from a fresh query every single time.
 *
 * <p>Site-neutral by construction: every selector comes from {@link WebsiteGameLaunchConfig}, and
 * no route, brand or selector of any operator appears in this file.
 */
public final class GameCardInspector {

    /**
     * Marks an identity that could not be built from a game name.
     *
     * <p>A card with no readable name still exists and still occupies a position, so it gets an
     * identity that says exactly what it is rather than an empty key that would collide with every
     * other unnamed card. The prefix is not a legal start for normalized page text, so a named
     * card can never produce a key that looks like one of these.
     */
    static final String INDEX_IDENTITY_PREFIX = "dom-index:";

    private final WebsiteGameLaunchConfig config;

    public GameCardInspector(WebsiteGameLaunchConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * One discovered card, described by value.
     *
     * <p>Every field is a scalar the reporting layer can publish or a flag the launch step can act
     * on. There is nowhere in this record to put an element, so a candidate cannot carry a stale
     * DOM reference even by accident.
     *
     * @param identityKey    normalized name and provider, or an index identity when unnamed
     * @param displayName    the name as the page shows it, whitespace-tidied, case preserved
     * @param provider       the provider as the page shows it, whitespace-tidied, case preserved
     * @param domIndex       the card's position in the catalogue at the moment it was discovered
     * @param launchControlPresent   whether the configured launch control exists inside this card
     * @param launchControlEnabled   whether that trigger reports itself enabled
     */
    public record GameCandidate(
        String identityKey,
        String displayName,
        String provider,
        int domIndex,
        boolean launchControlPresent,
        boolean launchControlEnabled
    ) {

        public GameCandidate {
            identityKey = identityKey == null ? "" : identityKey;
            displayName = displayName == null ? "" : displayName;
            provider = provider == null ? "" : provider;
        }

        /**
         * Whether a launch step may use this card at all.
         *
         * <p>Presence and enablement only. Visibility is deliberately not part of this answer: a
         * catalogue may keep its launch control hidden until the card is hovered, so requiring it to
         * be visible during discovery would reject every card on such a site.
         */
        public boolean eligibleForLaunch() {
            return launchControlPresent && launchControlEnabled;
        }

        /** Whether this candidate is identified by its name rather than by its position. */
        public boolean identifiedByName() {
            return !identityKey.startsWith(INDEX_IDENTITY_PREFIX);
        }
    }

    /**
     * A freshly resolved card and its launch control.
     *
     * <p><strong>Ephemeral. Use it in the same step that obtained it and then let it go.</strong>
     * These elements come from the query that produced this object and are valid only until the
     * page next re-renders. Storing one in a field, a collection or a candidate defeats the whole
     * point of re-resolution. {@code launchControl} is empty when the card no longer offers one.
     */
    public record ResolvedCard(GameCandidate candidate, WebElement card, Optional<WebElement> launchControl) {
    }

    /**
     * Every card the catalogue currently shows, deduplicated and capped.
     *
     * <p>DOM order is preserved throughout: the list is built in query order, the first occurrence
     * of a repeated identity is the one that survives, and the cap keeps the leading candidates
     * rather than an arbitrary selection. A card the browser reports as stale mid-read is kept at
     * its position and reported as ineligible, so one vanishing card cannot renumber the rest.
     *
     * @param context the catalogue to read; never navigated, clicked or modified
     */
    public List<GameCandidate> discover(SearchContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!SelectorUtils.hasUsableSelector(config.cardSelector())) {
            return List.of();
        }

        List<WebElement> cards = context.findElements(SelectorUtils.css(config.cardSelector()));
        Map<String, GameCandidate> byIdentity = new LinkedHashMap<>();
        for (int index = 0; index < cards.size() && byIdentity.size() < config.maxGames(); index++) {
            GameCandidate candidate = describe(cards.get(index), index);
            // First occurrence wins, so a repeated identity never displaces the card that was
            // discovered first and never reorders what survives.
            byIdentity.putIfAbsent(candidate.identityKey(), candidate);
        }
        return List.copyOf(new ArrayList<>(byIdentity.values()));
    }

    /**
     * Finds one previously discovered card again, in the catalogue as it stands now.
     *
     * <p>Always a new query. The candidate handed in carries no element, so there is nothing to
     * reuse and nothing that could be stale; whatever comes back belongs to this call.
     *
     * <p>A named candidate is matched by its identity, wherever it has moved to. An unnamed one is
     * matched by position, and only when the card at that position is still unnamed and still
     * reports the same provider — a named card that has taken over the slot is a different game,
     * and returning it would launch something nobody selected.
     *
     * @return the resolved card, or empty when it is gone or no longer the same card
     */
    public Optional<ResolvedCard> resolve(SearchContext context, GameCandidate candidate) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!SelectorUtils.hasUsableSelector(config.cardSelector())) {
            return Optional.empty();
        }

        List<WebElement> cards = context.findElements(SelectorUtils.css(config.cardSelector()));
        return candidate.identifiedByName()
            ? resolveByIdentity(cards, candidate)
            : resolveByPosition(cards, candidate);
    }

    private Optional<ResolvedCard> resolveByIdentity(
        List<WebElement> cards, GameCandidate candidate
    ) {
        for (int index = 0; index < cards.size(); index++) {
            WebElement card = cards.get(index);
            GameCandidate current = describe(card, index);
            if (current.identityKey().equals(candidate.identityKey())) {
                return Optional.of(resolved(current, card));
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedCard> resolveByPosition(
        List<WebElement> cards, GameCandidate candidate
    ) {
        int index = candidate.domIndex();
        if (index < 0 || index >= cards.size()) {
            return Optional.empty();
        }
        WebElement card = cards.get(index);
        GameCandidate current = describe(card, index);
        // Still nameless, still the same provider. Anything else at this position is another card.
        if (current.identifiedByName() || !current.identityKey().equals(candidate.identityKey())) {
            return Optional.empty();
        }
        return Optional.of(resolved(current, card));
    }

    private ResolvedCard resolved(GameCandidate candidate, WebElement card) {
        return new ResolvedCard(candidate, card, firstMatch(card, config.launchTriggerSelector()));
    }

    /** Reads one card into values. The element is not retained beyond this method. */
    private GameCandidate describe(WebElement card, int domIndex) {
        String name;
        String provider;
        boolean launchPresent;
        boolean launchEnabled;
        try {
            name = tidy(scopedText(card, config.nameSelector()));
            provider = tidy(scopedText(card, config.providerSelector()));
            List<WebElement> triggers = scopedMatches(card, config.launchTriggerSelector());
            launchPresent = !triggers.isEmpty();
            // Enablement only — never isDisplayed(). A catalogue that reveals its launch control on
            // hover keeps it hidden until then, and a hidden control is not an unusable one.
            launchEnabled = triggers.stream().anyMatch(WebElement::isEnabled);
        } catch (StaleElementReferenceException vanished) {
            // The card went away while it was being read. It keeps its position so the cards after
            // it are not renumbered, and it is reported as unreadable rather than as a usable game.
            return new GameCandidate(
                indexIdentity(domIndex, ""), "", "", domIndex, false, false);
        }
        String identity = name.isEmpty()
            ? indexIdentity(domIndex, provider)
            : normalize(name) + "|" + normalize(provider);
        return new GameCandidate(identity, name, provider, domIndex, launchPresent, launchEnabled);
    }

    private static String indexIdentity(int domIndex, String provider) {
        return INDEX_IDENTITY_PREFIX + domIndex + "|" + normalize(provider);
    }

    private static List<WebElement> scopedMatches(WebElement card, String selector) {
        return SelectorUtils.hasUsableSelector(selector)
            ? card.findElements(SelectorUtils.css(selector))
            : List.of();
    }

    private static Optional<WebElement> firstMatch(WebElement card, String selector) {
        List<WebElement> matches = scopedMatches(card, selector);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    private static String scopedText(WebElement card, String selector) {
        List<WebElement> matches = scopedMatches(card, selector);
        return matches.isEmpty() ? "" : documentText(matches.getFirst());
    }

    /**
     * The text the document holds, rather than the text the screen happens to be rendering.
     *
     * <p>This distinction is the whole reason a hover-reveal catalogue could not be re-resolved.
     * {@code getText()} returns <em>rendered</em> text, so an element inside a panel the page keeps
     * at {@code opacity: 0} reads as empty however complete its content is. A card whose name lives
     * in such a panel therefore looked nameless during discovery and named immediately after the
     * hover revealed it — the same card, two different identities, and the second one was rejected
     * as an impostor that had taken the slot.
     *
     * <p>{@code textContent} does not move when the panel does. It is the same string before and
     * after the hover, which is exactly what an identity has to be. This is a read of a DOM
     * property over the same W3C endpoint every other read here uses: nothing is executed, nothing
     * is clicked, and this class still cannot change the page it is looking at.
     *
     * <p>A {@link StaleElementReferenceException} is deliberately <strong>not</strong> caught. It
     * means the card vanished mid-read, and {@link #describe} is the one place that is allowed to
     * decide what that means.
     */
    private static String documentText(WebElement element) {
        String documentText;
        try {
            documentText = element.getDomProperty("textContent");
        } catch (UnsupportedOperationException notImplemented) {
            // A driver that does not serve the property. Rendered text is the older answer and
            // still a correct one for anything the page is actually showing.
            return element.getText();
        }
        // Blank means there is no text to identify by, in which case rendered text cannot have any
        // either; asking for it costs one round trip and keeps older drivers behaving as before.
        return documentText == null || documentText.isBlank() ? element.getText() : documentText;
    }

    /** The text as a person reads it: no stray whitespace, original casing kept. */
    private static String tidy(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    /**
     * The text as an identity: tidied, then folded to one case in a fixed locale.
     *
     * <p>{@link Locale#ROOT} rather than the default locale, because the default one turns a
     * capital I into a dotless i in Turkish, and an identity that changes with the machine's
     * regional settings would match a different card on a different machine.
     */
    private static String normalize(String value) {
        return tidy(value).toLowerCase(Locale.ROOT);
    }
}
