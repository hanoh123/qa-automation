package com.qanize.automation.checks.shared;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * Decides which discovered links are safe to follow as navigation.
 *
 * <p>Navigation must never change state, so anything that looks like sign-out, deletion or another
 * mutation is excluded rather than followed. The rules are pure functions of the href and link
 * text so they can be unit tested without a browser.
 */
public final class NavigationLinkInspector {

    /** Why a discovered candidate was not eligible to be followed. */
    public enum Exclusion {
        EMPTY_HREF("The link has no destination."),
        SCRIPT("The link runs a script instead of navigating."),
        MAIL("The link opens an email client."),
        TELEPHONE("The link starts a phone call."),
        FRAGMENT("The link only jumps within the same page."),
        EXTERNAL("The link leaves the tested website."),
        DOWNLOAD("The link downloads a file."),
        STATE_CHANGING("The link may sign out, delete or otherwise change data."),
        DUPLICATE("The destination was already tested.");

        private final String reason;

        Exclusion(String reason) { this.reason = reason; }

        public String reason() { return reason; }
    }

    public record Candidate(
        String text,
        String rawHref,
        URI resolved,
        boolean sameOrigin,
        Exclusion exclusion
    ) {
        public boolean eligible() { return exclusion == null; }
    }

    /**
     * Words that end a session, wherever they appear.
     *
     * <p>A sign-out is dangerous in any position: as a path segment, inside a longer one, in a
     * query or as link text. Following one costs the run its session and every page behind it, so
     * these are matched anywhere and deliberately never narrowed.
     */
    private static final List<List<String>> SIGN_OUT_WORDS = List.of(
        List.of("logout"), List.of("signout"), List.of("log", "out"), List.of("sign", "out")
    );

    /**
     * Words that name a mutation — but only when they name the action, not the page.
     *
     * <p>These used to be matched as substrings of the whole address with the separators stripped
     * out, so {@code /add_remove_elements/} contained "remove" and was refused as a link that
     * "may sign out, delete or otherwise change data". It is a demonstration page about adding and
     * removing DOM elements; opening it with a GET changes nothing at all. The same rule refused
     * {@code /preset/} because it contains "reset".
     *
     * <p>The word alone was never the evidence. What distinguishes {@code /account/delete} from
     * {@code /add_remove_elements/} is grammar: in the first the word <em>is</em> the segment, in
     * the second it is one noun inside a compound name. So a mutation word counts when it is the
     * first or the last whole token of a path segment or of the link text, or appears as a whole
     * token in the query string — {@code ?action=delete} being the shape a mutating link usually
     * takes. Everywhere else it is part of a name.
     *
     * @see #mutationShaped(String, String, String)
     */
    private static final List<List<String>> MUTATION_WORDS = List.of(
        List.of("delete"), List.of("remove"), List.of("destroy"), List.of("deactivate"),
        List.of("unsubscribe"), List.of("reset"), List.of("purge"), List.of("revoke"),
        List.of("terminate"), List.of("cancel", "account"), List.of("close", "account")
    );

    private static final Set<String> DOWNLOAD_SUFFIXES = Set.of(
        ".pdf", ".zip", ".exe", ".dmg", ".csv", ".xlsx", ".xls", ".doc", ".docx",
        ".ppt", ".pptx", ".tar", ".gz", ".rar", ".msi", ".apk", ".iso"
    );

    private NavigationLinkInspector() {
    }

    /**
     * Classifies raw hrefs against the base URL, preserving discovery order and marking each
     * candidate either eligible or excluded with a customer-facing reason.
     */
    public static List<Candidate> classify(URI baseUrl, List<String> texts, List<String> hrefs) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seenDestinations = new LinkedHashSet<>();
        for (int index = 0; index < hrefs.size(); index++) {
            String href = hrefs.get(index);
            String text = index < texts.size() ? texts.get(index) : null;
            candidates.add(classifyOne(baseUrl, text, href, seenDestinations));
        }
        return List.copyOf(candidates);
    }

    private static Candidate classifyOne(URI baseUrl, String text, String href, Set<String> seen) {
        if (href == null || href.isBlank()) {
            return new Candidate(text, href, null, false, Exclusion.EMPTY_HREF);
        }
        String trimmed = href.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")) return new Candidate(text, trimmed, null, false, Exclusion.SCRIPT);
        if (lower.startsWith("mailto:")) return new Candidate(text, trimmed, null, false, Exclusion.MAIL);
        if (lower.startsWith("tel:")) return new Candidate(text, trimmed, null, false, Exclusion.TELEPHONE);
        // A fragment reference written as a bare "#..." in the markup. This is not the only shape
        // one arrives in — see the same-document check further down.
        if (lower.startsWith("#")) return new Candidate(text, trimmed, null, false, Exclusion.FRAGMENT);

        URI resolved;
        try {
            resolved = baseUrl.resolve(trimmed);
        } catch (IllegalArgumentException failure) {
            return new Candidate(text, trimmed, null, false, Exclusion.EMPTY_HREF);
        }

        boolean sameOrigin = resolved.getHost() != null
            && baseUrl.getHost() != null
            && baseUrl.getHost().equalsIgnoreCase(resolved.getHost());
        if (!sameOrigin) return new Candidate(text, trimmed, resolved, false, Exclusion.EXTERNAL);

        String path = resolved.getPath() == null ? "" : resolved.getPath().toLowerCase(Locale.ROOT);
        for (String suffix : DOWNLOAD_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return new Candidate(text, trimmed, resolved, true, Exclusion.DOWNLOAD);
            }
        }

        // Two rules, because two different things were being conflated. A sign-out is refused
        // wherever its word appears; a mutation word is refused where it names an action rather
        // than a page. Erring towards exclusion is still the default for anything that genuinely
        // looks like an action: a link we decline to follow is only a missed check, never an
        // unintended state change.
        if (signOutShaped(path, resolved.getQuery(), text)
            || mutationShaped(path, resolved.getQuery(), text)) {
            return new Candidate(text, trimmed, resolved, true, Exclusion.STATE_CHANGING);
        }

        String normalized = normalize(resolved);

        // A fragment reference does not have to reach us as a bare "#...". A browser reports an
        // anchor's href as the resolved DOM property, so `<a href="#">` on /inventory.html arrives
        // here as "https://site/inventory.html#" — absolute, same-origin, and indistinguishable
        // from a real destination by prefix alone. Same-document is therefore decided by identity:
        // the shared normalizer already drops the fragment, so a destination whose identity is the
        // page we are standing on, reached through a URL that carried a fragment, jumps within this
        // page and nothing else.
        //
        // Placed before `seen`, and deliberately so. These references never consume the page's own
        // identity, which is what stops the first of twenty "#" links from being requested and the
        // other nineteen from being reported as duplicates of it — and what leaves a genuine link
        // back to this page still eligible to be tested on its own merits.
        //
        // getRawFragment() rather than a search for '#': it is null exactly when the URL had no
        // fragment component, and "" when it ended in a bare "#", which is the SauceDemo case.
        if (resolved.getRawFragment() != null && normalized.equals(normalize(baseUrl))) {
            return new Candidate(text, trimmed, resolved, true, Exclusion.FRAGMENT);
        }

        if (!seen.add(normalized)) {
            return new Candidate(text, trimmed, resolved, true, Exclusion.DUPLICATE);
        }
        return new Candidate(text, trimmed, resolved, true, null);
    }

    /** Fragments and trailing slashes do not identify a different page for navigation purposes. */
    public static String normalize(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        return scheme + "://" + host + path + query;
    }

    /**
     * Where an address lives: scheme + host + effective port, compared exactly.
     *
     * <p>A different question from {@link #normalize(URI)}, which asks whether two addresses are
     * the same <em>page</em>. This asks whether they are the same <em>place</em> — the question
     * that decides where a crawl may send traffic and where a signed-in session may be presented.
     * Both callers share this one definition on purpose: a second copy that drifted would mean a
     * cookie reaching somewhere the crawler had already decided was a third party.
     *
     * <ul>
     *   <li>scheme and host are lowercased, so {@code HTTP://Example.COM} and
     *       {@code http://example.com} are one origin;</li>
     *   <li>the effective port is the explicit one, else 443 for {@code https} and 80 for
     *       {@code http}, so {@code https://host} and {@code https://host:443} are one origin;</li>
     *   <li>a different scheme, a different port or a different host — a subdomain included — is a
     *       different origin.</li>
     * </ul>
     */
    public static String origin(URI uri) {
        if (uri == null) return "";
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        return scheme + "://" + host + ":" + port;
    }

    /**
     * The same address with its fragment removed, and nothing else changed.
     *
     * <p>This is not a second way of identifying a destination and must never be used as one —
     * {@link #normalize(URI)} remains the only identity. This answers a different question: what to
     * put on the wire. A fragment is a client-side instruction that no HTTP request ever carries,
     * so sending one is at best noise in a report and at worst a destination recorded as something
     * other than what was actually requested.
     *
     * <p>Everything else is preserved byte for byte — case, trailing slash, query, percent-encoding
     * — because those can all change what a server answers, and this method is not entitled to
     * change what is requested. Truncating the text rather than rebuilding through a multi-argument
     * {@code URI} constructor is what guarantees that.
     */
    public static URI withoutFragment(URI uri) {
        if (uri == null || uri.getRawFragment() == null) return uri;
        String text = uri.toString();
        int hash = text.indexOf('#');
        return hash < 0 ? uri : URI.create(text.substring(0, hash));
    }

    /**
     * Whether anything about this link says it ends a session.
     *
     * <p>Matched anywhere in the path, the query or the text, because there is no position in
     * which following a sign-out is acceptable.
     */
    private static boolean signOutShaped(String path, String query, String text) {
        for (List<String> word : SIGN_OUT_WORDS) {
            if (containsRun(tokens(path), word)
                || containsRun(tokens(query), word)
                || containsRun(tokens(text), word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a mutation word is naming the action this link performs.
     *
     * <p>Three places count, and each is a place where the word is doing the naming:
     *
     * <ul>
     *   <li><strong>A path segment it begins or ends.</strong> {@code /account/delete},
     *       {@code /delete-account}, {@code /items/1/remove} and {@code /user_delete} all put the
     *       word at one end of a segment, which is how a route names what it does.
     *       {@code /add_remove_elements} does not: "remove" sits between "add" and "elements",
     *       which is a name, not an instruction.</li>
     *   <li><strong>A whole token of the query.</strong> {@code ?action=delete} and
     *       {@code ?delete=1} are the other common way to write a mutating link.</li>
     *   <li><strong>The start or end of the link text.</strong> "Delete", "Remove item" and
     *       "Confirm delete" read as actions; "Add/Remove Elements" reads as a page name.</li>
     * </ul>
     *
     * <p>Whole tokens throughout, never substrings: that is what keeps {@code /preset} from
     * matching "reset" and {@code ?filter=deleted} from matching "delete".
     */
    private static boolean mutationShaped(String path, String query, String text) {
        List<List<String>> segments = new ArrayList<>();
        for (String segment : safeLower(path).split("/")) {
            if (!segment.isBlank()) segments.add(tokens(segment));
        }
        List<String> queryTokens = tokens(query);
        List<String> textTokens = tokens(text);
        for (List<String> word : MUTATION_WORDS) {
            for (List<String> segment : segments) {
                if (startsWithRun(segment, word) || endsWithRun(segment, word)) return true;
            }
            if (containsRun(queryTokens, word)) return true;
            if (startsWithRun(textTokens, word) || endsWithRun(textTokens, word)) return true;
        }
        return false;
    }

    /** Lowercase alphanumeric tokens, in order. Any other character is a separator. */
    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    /** Whether {@code tokens} begins with exactly this run of tokens. */
    private static boolean startsWithRun(List<String> tokens, List<String> run) {
        return tokens.size() >= run.size() && tokens.subList(0, run.size()).equals(run);
    }

    /** Whether {@code tokens} ends with exactly this run of tokens. */
    private static boolean endsWithRun(List<String> tokens, List<String> run) {
        return tokens.size() >= run.size()
            && tokens.subList(tokens.size() - run.size(), tokens.size()).equals(run);
    }

    /** Whether this run of tokens appears anywhere in {@code tokens}, in order and adjacent. */
    private static boolean containsRun(List<String> tokens, List<String> run) {
        if (run.isEmpty() || tokens.size() < run.size()) return false;
        for (int start = 0; start + run.size() <= tokens.size(); start++) {
            if (tokens.subList(start, start + run.size()).equals(run)) return true;
        }
        return false;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
