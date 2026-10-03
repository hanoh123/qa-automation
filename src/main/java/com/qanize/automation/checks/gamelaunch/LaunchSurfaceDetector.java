package com.qanize.automation.checks.gamelaunch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import com.qanize.automation.checks.shared.PageContentReadiness;
import com.qanize.automation.checks.shared.SelectorUtils;

/**
 * Decides what a launch actually opened, by comparing the page against itself.
 *
 * <p>The question this answers is harder than it looks. A catalogue already contains overlays,
 * frames and promotional panels before anything is clicked, so "an overlay is present" proves
 * nothing. Only a <em>change</em> proves a launch, which is why every detection is made against a
 * snapshot taken immediately before the click and why a surface that already existed can never be
 * reported as one that just opened.
 *
 * <p><strong>It observes and nothing else.</strong> No click, no navigation, no refresh, no window
 * close, no frame switch. Detection that changed the page would destroy the evidence it was asked
 * to gather, and would do so on a live operator's site.
 *
 * <p><strong>It never reads an iframe's address.</strong> A launch URL is signed and carries the
 * session in its query, so {@code src} is not read here, not recorded and not returned. The only
 * address this class will report is the top-level one, reduced to {@code scheme://host/path} with
 * a flag saying a query was dropped — see {@link SafeLocation}.
 *
 * <p>Site-neutral: every selector comes from {@link WebsiteGameLaunchConfig}, and no route, brand
 * or operator selector appears in this file.
 */
public final class LaunchSurfaceDetector {

    /** What opened. Exactly one of these describes any single launch. */
    public enum SurfaceType {

        /** A new browsing context appeared. Cleanup must close it and go back. */
        NEW_WINDOW,

        /** The tab itself went somewhere else. Cleanup must return by the supplied URL. */
        SAME_TAB_NAVIGATION,

        /** A frame that was not there before now matches the configured surface selector. */
        IFRAME,

        /** A non-frame surface that was not there before now matches that selector. */
        MODAL_OVERLAY,

        /** Nothing new appeared before the attempts ran out. */
        NONE,

        /**
         * More than one top-level surface appeared, and no honest single answer exists.
         *
         * <p>A new window <em>and</em> a navigation in the original tab is the shape that produces
         * this. Picking one would mean cleaning up the wrong thing and reporting a game as tested
         * when a different surface was examined.
         */
        AMBIGUOUS
    }

    /**
     * A top-level address, safe to record.
     *
     * @param value        {@code scheme://host/path}, with no query and no fragment, or null
     * @param queryOmitted whether the original address carried a query or fragment that was dropped
     */
    public record SafeLocation(String value, boolean queryOmitted) {

        static final SafeLocation UNKNOWN = new SafeLocation(null, false);

        /** Whether two locations describe the same page, ignoring anything that was dropped. */
        boolean sameAs(SafeLocation other) {
            return Objects.equals(value, other.value);
        }
    }

    /**
     * The page as it stood immediately before the launch click.
     *
     * <p>Counts and handles only: no element, no driver and no address beyond the safe one. A
     * snapshot outlives the click that follows it, so anything live in here would be stale by the
     * time it was compared.
     */
    public record Snapshot(
        Set<String> windowHandles,
        String activeHandle,
        SafeLocation location,
        int surfaceMatches,
        boolean surfaceIncludedFrame
    ) {

        public Snapshot {
            windowHandles = Set.copyOf(windowHandles);
        }
    }

    /**
     * What detection concluded.
     *
     * @param newWindowHandle the handle that appeared, present only for {@link SurfaceType#NEW_WINDOW}
     * @param location        the top-level address after the launch, already reduced
     * @param attempts        how many polls it took, so a slow surface is distinguishable from a fast one
     */
    public record Detection(
        SurfaceType type,
        Optional<String> newWindowHandle,
        SafeLocation location,
        int attempts,
        String detail
    ) {

        public boolean surfaceOpened() {
            return type != SurfaceType.NONE && type != SurfaceType.AMBIGUOUS;
        }
    }

    private final WebsiteGameLaunchConfig config;

    public LaunchSurfaceDetector(WebsiteGameLaunchConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Records the page as it is now, before anything is clicked.
     *
     * <p>Take this immediately before the click. A snapshot taken earlier would miss an overlay
     * that opened in between and would then report it as the launch.
     */
    public Snapshot snapshot(WebDriver driver) {
        Objects.requireNonNull(driver, "driver must not be null");
        List<WebElement> surfaces = surfaceMatches(driver);
        return new Snapshot(
            new LinkedHashSet<>(driver.getWindowHandles()),
            driver.getWindowHandle(),
            safeLocation(driver.getCurrentUrl()),
            surfaces.size(),
            includesFrame(surfaces));
    }

    /**
     * Watches for a surface to appear, and says which one did.
     *
     * <p>Polling is expressed as attempts and a caller-supplied pause, the shape
     * {@link PageContentReadiness} already established, so the timing is deterministic and a test
     * needs neither a browser nor a sleep.
     *
     * <p><strong>The priority, in one place.</strong> When several signals are present at once they
     * are resolved in this order, and only this order:
     * <ol>
     *   <li>two or more top-level signals — a new window plus a navigation, or two new windows —
     *       is {@link SurfaceType#AMBIGUOUS}, because cleanup could not be right for both;</li>
     *   <li>{@link SurfaceType#NEW_WINDOW}, the strongest single signal and the only one that
     *       leaves a browsing context behind if it is missed;</li>
     *   <li>{@link SurfaceType#SAME_TAB_NAVIGATION}, which has already taken the catalogue away;</li>
     *   <li>{@link SurfaceType#IFRAME}, when the new surface includes a frame;</li>
     *   <li>{@link SurfaceType#MODAL_OVERLAY}, when it does not.</li>
     * </ol>
     * A frame nested inside a newly opened overlay is therefore reported as a frame, which is what
     * cleanup has to handle first.
     *
     * @param before      the snapshot taken before the click
     * @param maxAttempts total polls, at least one
     * @param pause       what to do between polls; never runs after the last one
     */
    public Detection detect(WebDriver driver, Snapshot before, int maxAttempts, Runnable pause) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(before, "before-click snapshot must not be null");
        Objects.requireNonNull(pause, "pause must not be null");

        int attemptLimit = Math.max(1, maxAttempts);
        Detection latest = null;
        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            latest = classify(driver, before, attempt);
            if (latest.type() != SurfaceType.NONE) {
                return latest;
            }
            if (attempt < attemptLimit) {
                pause.run();
            }
        }
        return latest;
    }

    private Detection classify(WebDriver driver, Snapshot before, int attempt) {
        Set<String> handlesNow = new LinkedHashSet<>(driver.getWindowHandles());
        List<String> opened = handlesNow.stream()
            .filter(handle -> !before.windowHandles().contains(handle))
            .toList();
        SafeLocation now = safeLocation(driver.getCurrentUrl());
        boolean navigated = !now.sameAs(before.location());

        // More than one top-level answer is no answer. Reported rather than guessed at.
        if (opened.size() > 1) {
            return new Detection(SurfaceType.AMBIGUOUS, Optional.empty(), now, attempt,
                opened.size() + " new windows opened; which one is the game cannot be established");
        }
        if (!opened.isEmpty() && navigated) {
            return new Detection(SurfaceType.AMBIGUOUS, Optional.of(opened.getFirst()), now, attempt,
                "a new window opened and the original tab navigated");
        }
        if (!opened.isEmpty()) {
            return new Detection(SurfaceType.NEW_WINDOW, Optional.of(opened.getFirst()), now, attempt,
                "a new window opened");
        }
        if (navigated) {
            return new Detection(SurfaceType.SAME_TAB_NAVIGATION, Optional.empty(), now, attempt,
                "the tab navigated away from the list");
        }

        List<WebElement> surfaces = surfaceMatches(driver);
        // A surface that was already on the page before the click is not something the click
        // opened. Only growth counts, which is the whole reason a snapshot is taken.
        if (surfaces.size() > before.surfaceMatches()) {
            boolean frame = includesFrame(surfaces);
            return new Detection(
                frame ? SurfaceType.IFRAME : SurfaceType.MODAL_OVERLAY,
                Optional.empty(), now, attempt,
                frame ? "a new frame surface appeared" : "a new overlay surface appeared");
        }
        return new Detection(SurfaceType.NONE, Optional.empty(), now, attempt,
            "no new surface appeared");
    }

    private List<WebElement> surfaceMatches(WebDriver driver) {
        return SelectorUtils.hasUsableSelector(config.gameSurfaceSelector())
            ? driver.findElements(SelectorUtils.css(config.gameSurfaceSelector()))
            : List.of();
    }

    /**
     * Whether any current match is a frame.
     *
     * <p>Tag name only. The frame's {@code src} is never read, because a launch address is signed
     * and carries the session inside it.
     */
    private static boolean includesFrame(List<WebElement> surfaces) {
        for (WebElement surface : surfaces) {
            String tag = surface.getTagName();
            if (tag != null && FRAME_TAGS.contains(tag.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> FRAME_TAGS = Set.of("iframe", "frame");

    /**
     * Reduces a top-level address to the part that is safe to keep.
     *
     * <p>Scheme, host and path survive; the query and the fragment are dropped whole rather than
     * filtered, and {@code queryOmitted} says so. This is deliberately stricter than the project's
     * general URL sanitizer, which keeps parameters it does not recognise as sensitive: a game
     * launch address is signed end to end, so there is no parameter on it worth the risk of
     * publishing. Credentials in the authority are dropped with it.
     */
    static SafeLocation safeLocation(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return SafeLocation.UNKNOWN;
        String value = rawUrl.replaceAll("[\\r\\n]+", " ").trim();

        int cut = indexOfAny(value, '?', '#');
        boolean omitted = cut >= 0;
        if (omitted) value = value.substring(0, cut);

        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) {
            // Not an absolute address. Nothing can be split off safely, so nothing is reported.
            return new SafeLocation(null, omitted);
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = value.indexOf('/', authorityStart);
        if (authorityEnd < 0) authorityEnd = value.length();
        String authority = value.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(0, authorityStart)
                + authority.substring(at + 1)
                + value.substring(authorityEnd);
        }
        return new SafeLocation(value, omitted);
    }

    private static int indexOfAny(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** The configured card list, used by cleanup to confirm the catalogue came back. */
    static By cardLocator(WebsiteGameLaunchConfig config) {
        return SelectorUtils.css(config.cardSelector());
    }
}
