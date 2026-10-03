package com.qanize.automation.checks.links;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.qanize.automation.checks.shared.NavigationLinkInspector;
import com.qanize.automation.checks.shared.PageStatusProbe;

import org.openqa.selenium.WebDriver;

/**
 * Whether the Links check is allowed to speak as the signed-in visitor, and what it needs to.
 *
 * <p>Links is the one check that never touches its subject with a browser: it reads the page's
 * anchors and then requests each destination over HTTP. That division is deliberate and stays. What
 * it cost, until now, was the sign-in. The browser held the session; the HTTP client was a separate
 * object that had never heard of it. So on a site that serves a page only to a signed-in visitor,
 * the browser loaded the page and the link check was told the page did not exist — and reported
 * that as a defect in the customer's website.
 *
 * <p>This is the fact that was missing, carried explicitly from the one place that knows it to the
 * one place that needs it.
 *
 * <h2>The three states, and why the third exists</h2>
 *
 * <ul>
 *   <li>{@link Mode#ANONYMOUS} — no sign-in was performed. Every existing run is this, and its
 *       behaviour is unchanged down to the HTTP client it uses.</li>
 *   <li>{@link Mode#BROWSER_AUTHENTICATED} — a sign-in succeeded <em>and</em> the browser handed
 *       over a session this check can actually present. Destinations on the start origin are
 *       requested as the signed-in visitor.</li>
 *   <li>{@link Mode#AUTHENTICATED_DISCOVERY_ONLY} — a sign-in succeeded and the session could
 *       <em>not</em> be carried. Links still discovers, normalizes, deduplicates and hands its
 *       candidates to the crawler, but it issues no request at all.</li>
 * </ul>
 *
 * <p>The third state is the whole point of making this typed. The tempting shortcut — "we could not
 * carry the session, so request anonymously" — is exactly the bug: it produces confident 404s about
 * pages that are perfectly healthy for the customer. A run that cannot validate says so; it does
 * not quietly validate something else. There is no path from {@code BROWSER_AUTHENTICATED} to
 * {@code ANONYMOUS}.
 *
 * <p>The distinction is decided from the <em>outcome</em> of a sign-in that actually ran against
 * the website. Being configured for Login, or having selected the check, decides nothing here.
 *
 * <h2>Where a cookie may be sent</h2>
 *
 * <p>To the start origin, and nowhere else. "Origin" is the project's existing definition —
 * {@link NavigationLinkInspector#origin(URI)}, scheme + host + effective port, compared exactly —
 * so no second rule can drift away from the one the crawler admits pages by. On top of that,
 * {@link #cookieHeaderFor(URI)} applies the cookie's own domain, path and {@code secure}
 * restrictions, so a cookie the browser would not have sent is not sent here either.
 *
 * <p>The rule is evaluated per request, not once per run. That matters because a same-origin
 * address can redirect somewhere else: the caller asks again for every hop, so the first hop that
 * leaves the origin gets no cookie, and neither does anything after it.
 *
 * <h2>What it never does</h2>
 *
 * <p>Nothing here is static, ambient or shared. There is no cookie store, no {@code ThreadLocal},
 * no system property, no environment variable. An instance is built for one run, reaches the one
 * module that needs it as a constructor argument, and becomes garbage when that run ends — so two
 * consecutive runs cannot see each other's session because there is nowhere for one to persist.
 *
 * <p>No cookie name or value is ever rendered. {@link #toString()} says how many cookies there are
 * and not one thing about them, {@link SessionCookie#toString()} says nothing at all, and
 * {@link #redactFrom(String)} exists so a caller can scrub a message it is about to record even
 * when it believes there is nothing to scrub.
 */
public final class LinkSession {

    /**
     * The sentence a report shows when a signed-in run could not carry its session.
     *
     * <p>A fixed constant, deliberately. Deriving it from whatever went wrong would put browser
     * text — which can carry a session value — into a customer-facing report.
     */
    public static final String NO_TRANSFERABLE_SESSION =
        "The sign-in succeeded, but the browser session could not be carried to the link check, "
            + "so these destinations were not requested.";

    /** How this run may validate links. */
    public enum Mode {
        /** No sign-in was performed. Links requests destinations as an anonymous visitor. */
        ANONYMOUS,
        /** A sign-in succeeded and its session travelled. Links requests as that visitor. */
        BROWSER_AUTHENTICATED,
        /** A sign-in succeeded and its session did not travel. Links requests nothing. */
        AUTHENTICATED_DISCOVERY_ONLY
    }

    private static final LinkSession ANONYMOUS_SESSION =
        new LinkSession(Mode.ANONYMOUS, null, List.of(), null);

    private final Mode mode;
    private final String origin;
    private final List<SessionCookie> cookies;
    private final String reason;

    private LinkSession(Mode mode, String origin, List<SessionCookie> cookies, String reason) {
        this.mode = mode;
        this.origin = origin;
        this.cookies = List.copyOf(cookies);
        this.reason = reason;
    }

    /** No sign-in. The state every pre-existing run is in, and the default everywhere. */
    public static LinkSession anonymous() {
        return ANONYMOUS_SESSION;
    }

    /**
     * A sign-in succeeded and handed over a session that can be presented over HTTP.
     *
     * @param startUrl the address the crawl starts from; its origin is the only one cookies reach
     * @param cookies  what the browser held, already reduced to this project's own carrier
     * @throws IllegalArgumentException when no usable cookie was supplied — a caller that cannot
     *     produce one must choose {@link #authenticatedDiscoveryOnly(String)} rather than an
     *     authenticated session that would silently send nothing
     */
    public static LinkSession authenticated(URI startUrl, List<SessionCookie> cookies) {
        Objects.requireNonNull(startUrl, "startUrl must not be null");
        Objects.requireNonNull(cookies, "cookies must not be null");
        List<SessionCookie> usable = cookies.stream().filter(Objects::nonNull).toList();
        if (usable.isEmpty()) {
            throw new IllegalArgumentException(
                "an authenticated session must carry at least one usable cookie");
        }
        return new LinkSession(
            Mode.BROWSER_AUTHENTICATED, NavigationLinkInspector.origin(startUrl), usable, null);
    }

    /**
     * A sign-in succeeded and its session could not be carried.
     *
     * @param reason the customer-facing sentence; must say nothing a browser supplied
     */
    public static LinkSession authenticatedDiscoveryOnly(String reason) {
        return new LinkSession(
            Mode.AUTHENTICATED_DISCOVERY_ONLY, null, List.of(),
            reason == null || reason.isBlank() ? NO_TRANSFERABLE_SESSION : reason);
    }

    /**
     * Reads the signed-in browser's session, once, immediately after the sign-in succeeded.
     *
     * <p>The only place a Selenium cookie is ever touched. Each one is copied into this project's
     * own immutable carrier and the Selenium object is dropped, so nothing downstream can reach a
     * browser through a cookie it was handed.
     *
     * <p>Every way this can come up empty — the browser refusing the request, a jar with nothing in
     * it, a name or value that could not be put in a header safely — ends at the same place:
     * {@link Mode#AUTHENTICATED_DISCOVERY_ONLY}, with the fixed sentence. Never anonymous, and
     * never a reason built out of what went wrong.
     */
    public static LinkSession capturedFrom(WebDriver driver, URI startUrl) {
        if (driver == null) {
            return authenticatedDiscoveryOnly(NO_TRANSFERABLE_SESSION);
        }
        // Resolved inside the capture below, not here: asking a closed or crashed browser for its
        // options throws exactly like asking it for its cookies does, and both belong on the same
        // side of the guard.
        return capturedFrom(() -> driver.manage().getCookies(), startUrl);
    }

    /**
     * The same capture, over anything that can produce a cookie jar.
     *
     * <p>The seam exists so the rules above can be proved without standing up a browser — and,
     * more usefully, so that "a browser that refuses" and "a browser with an empty jar" are
     * ordinary cases a test can arrange rather than situations one has to hope are handled.
     */
    static LinkSession capturedFrom(CookieJar jar, URI startUrl) {
        if (jar == null || startUrl == null) {
            return authenticatedDiscoveryOnly(NO_TRANSFERABLE_SESSION);
        }
        List<SessionCookie> captured = new ArrayList<>();
        try {
            for (org.openqa.selenium.Cookie cookie : jar.cookies()) {
                SessionCookie carried = SessionCookie.transferable(cookie);
                if (carried != null) {
                    captured.add(carried);
                }
            }
        } catch (RuntimeException sessionUnavailable) {
            // Deliberately not reported, logged or attached: whatever the browser said about its
            // own cookie jar is the last text that should be allowed near a report.
            return authenticatedDiscoveryOnly(NO_TRANSFERABLE_SESSION);
        }
        if (captured.isEmpty()) {
            return authenticatedDiscoveryOnly(NO_TRANSFERABLE_SESSION);
        }
        try {
            return authenticated(startUrl, captured);
        } catch (RuntimeException unusable) {
            return authenticatedDiscoveryOnly(NO_TRANSFERABLE_SESSION);
        }
    }

    /** Whatever the browser currently holds. May throw; the caller treats that as no session. */
    @FunctionalInterface
    interface CookieJar {
        java.util.Collection<org.openqa.selenium.Cookie> cookies();
    }

    public Mode mode() {
        return mode;
    }

    /** Whether this run may issue HTTP requests at all. False only for discovery-only. */
    public boolean validatesOverHttp() {
        return mode != Mode.AUTHENTICATED_DISCOVERY_ONLY;
    }

    /** Whether requests carry a signed-in session. */
    public boolean authenticated() {
        return mode == Mode.BROWSER_AUTHENTICATED;
    }

    /** Why nothing was requested, or null when something was. */
    public String reason() {
        return reason;
    }

    /**
     * The status probe a page-health check may use on this run, which is none once a sign-in
     * happened.
     *
     * <p>A page that renders no title leaves the browser unable to say whether the document is a
     * broken page or an authentication challenge, and only the server knows. The request that
     * would answer that carries no session — so on a signed-in run it would be answered for a
     * visitor the customer is not, which is the same reason this class refuses to let Links
     * request destinations as a stranger. The answer is therefore given here, by the object that
     * already owns what this run may present, rather than by a caller reading a mode off it.
     *
     * @param timeout how long that one request may take
     * @return an anonymous probe on an anonymous run, {@link PageStatusProbe#NONE} otherwise
     */
    public PageStatusProbe pageStatusProbe(Duration timeout) {
        return mode == Mode.ANONYMOUS ? PageStatusProbe.anonymous(timeout) : PageStatusProbe.NONE;
    }

    /** How many cookies travel. A count is safe to say; the cookies are not. */
    public int cookieCount() {
        return cookies.size();
    }

    /**
     * The {@code Cookie} header for one destination, or null when this destination gets none.
     *
     * <p>Null is the answer for every reason at once, and that is intentional: an anonymous run, a
     * discovery-only run, another origin, a plain-HTTP hop holding only secure cookies, a path the
     * cookie does not cover. A caller that sets the header only when this returns non-null cannot
     * leak a session by forgetting one of those cases.
     */
    public String cookieHeaderFor(URI destination) {
        if (mode != Mode.BROWSER_AUTHENTICATED || destination == null) {
            return null;
        }
        // The project's one origin rule. Not host equality: a different scheme or a different
        // effective port is a different origin and gets nothing.
        if (!origin.equals(NavigationLinkInspector.origin(destination))) {
            return null;
        }
        boolean overHttps = "https".equalsIgnoreCase(destination.getScheme());
        String host = destination.getHost() == null
            ? "" : destination.getHost().toLowerCase(Locale.ROOT);
        String path = requestPath(destination);

        StringBuilder header = new StringBuilder();
        for (SessionCookie cookie : cookies) {
            // A secure cookie is one the browser would refuse to send in the clear, and so is this.
            if (cookie.secure() && !overHttps) continue;
            if (!cookie.domainMatches(host)) continue;
            if (!cookie.pathMatches(path)) continue;
            if (!header.isEmpty()) header.append("; ");
            header.append(cookie.name()).append('=').append(cookie.value());
        }
        return header.isEmpty() ? null : header.toString();
    }

    /**
     * The same text with every cookie value removed.
     *
     * <p>Belt and braces for anything about to be recorded. Nothing here is expected to contain a
     * session value — the header is never rendered and the values reach no message — but "expected"
     * is not a guarantee, and a value that escaped once is in a report forever.
     */
    public String redactFrom(String text) {
        if (text == null || cookies.isEmpty()) {
            return text;
        }
        String scrubbed = text;
        for (SessionCookie cookie : cookies) {
            if (!cookie.value().isEmpty()) {
                scrubbed = scrubbed.replace(cookie.value(), "[redacted]");
            }
        }
        return scrubbed;
    }

    /** Says the mode and a count. Never a name, never a value, never an origin's credentials. */
    @Override
    public String toString() {
        return "LinkSession[mode=" + mode + ", cookies=" + cookies.size() + "]";
    }

    /** The request path a cookie's path is matched against. An empty path is the root. */
    private static String requestPath(URI destination) {
        String path = destination.getPath();
        return path == null || path.isBlank() ? "/" : path;
    }

    /**
     * One cookie, reduced to what an HTTP request needs and nothing a browser could be reached by.
     *
     * <p>Immutable, and deliberately not a Selenium type: a {@code org.openqa.selenium.Cookie}
     * handed downstream is an invitation to put it back into a browser, and its {@code toString()}
     * prints the value.
     *
     * <p>{@code httpOnly} is carried but never used to filter. It restricts what page scripts may
     * read; it says nothing about HTTP, where the cookie is exactly as valid as any other. Dropping
     * HttpOnly cookies here would discard the session cookie on most real sites.
     */
    public record SessionCookie(
        String name,
        String value,
        String domain,
        String path,
        boolean secure,
        boolean httpOnly
    ) {

        public SessionCookie {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(value, "value must not be null");
            domain = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
            path = path == null || path.isBlank() ? "/" : path;
        }

        /**
         * This cookie as something that can safely be put in a header, or null when it cannot.
         *
         * <p>The check is not cosmetic. A name or value holding a control character, a newline or a
         * separator would be rejected by the HTTP client — and the exception it throws quotes the
         * offending text, which is how a session value ends up in a log. Screening here means that
         * exception can never be raised, so there is no message to leak.
         */
        static SessionCookie transferable(org.openqa.selenium.Cookie cookie) {
            if (cookie == null || !headerSafe(cookie.getName()) || !headerSafe(cookie.getValue())) {
                return null;
            }
            if (cookie.getName().isEmpty()) {
                return null;
            }
            return new SessionCookie(
                cookie.getName(),
                cookie.getValue(),
                cookie.getDomain(),
                cookie.getPath(),
                cookie.isSecure(),
                cookie.isHttpOnly());
        }

        /**
         * RFC 6265 domain matching: the exact host, or a host below the cookie's domain.
         *
         * <p>A cookie for another host is never sent, even though the jar it came from belonged to
         * this document — a page can hold cookies it did not set.
         */
        boolean domainMatches(String host) {
            if (host == null || host.isEmpty()) return false;
            if (domain.isEmpty()) return true;
            String bare = domain.startsWith(".") ? domain.substring(1) : domain;
            return host.equals(bare) || host.endsWith("." + bare);
        }

        /** RFC 6265 path matching: the same path, a directory prefix, or a prefix ending in "/". */
        boolean pathMatches(String requestPath) {
            if (path.equals("/") || path.equals(requestPath)) return true;
            if (!requestPath.startsWith(path)) return false;
            return path.endsWith("/") || requestPath.charAt(path.length()) == '/';
        }

        private static boolean headerSafe(String value) {
            if (value == null) return false;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < 0x20 || character > 0x7E || character == ';' || character == ',') {
                    return false;
                }
            }
            return true;
        }

        /**
         * Says nothing.
         *
         * <p>A record's generated {@code toString()} prints every component, which for this record
         * means printing the session. This exists so that logging the object by accident — in a
         * message, an exception, a debugger, a collection's own rendering — cannot disclose it.
         */
        @Override
        public String toString() {
            return "SessionCookie[redacted]";
        }
    }
}
