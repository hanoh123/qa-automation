package com.qanize.automation.checks.links;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Cookie;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide where a signed-in session may be presented, proved without a network.
 *
 * <p>Every one of these is a rule whose failure is silent and expensive: a cookie sent one origin
 * too far does not throw, does not show up in a report and does not fail a build — it just hands a
 * customer's session to somebody else. So they are pinned individually, as rules, rather than
 * inferred from the behaviour of a request.
 */
@Tag("unit")
class LinkSessionTest {

    private static final URI START = URI.create("https://shop.example.com/inventory.html");

    private static LinkSession.SessionCookie cookie(String name, String value) {
        return new LinkSession.SessionCookie(name, value, "shop.example.com", "/", false, false);
    }

    private static LinkSession signedIn() {
        return LinkSession.authenticated(START, List.of(cookie("session", "abc123")));
    }

    // --- the three states ------------------------------------------------------------------------

    @Test
    void anAnonymousSessionCarriesNothingAndPresentsNothing() {
        LinkSession session = LinkSession.anonymous();

        assertEquals(LinkSession.Mode.ANONYMOUS, session.mode());
        assertFalse(session.authenticated());
        assertTrue(session.validatesOverHttp(), "an anonymous run still requests destinations");
        assertEquals(0, session.cookieCount());
        assertNull(session.cookieHeaderFor(START));
    }

    @Test
    void aDiscoveryOnlySessionRequestsNothingAtAll() {
        LinkSession session = LinkSession.authenticatedDiscoveryOnly(null);

        assertEquals(LinkSession.Mode.AUTHENTICATED_DISCOVERY_ONLY, session.mode());
        assertFalse(session.validatesOverHttp(), "this is the whole point of the state");
        assertFalse(session.authenticated());
        assertNull(session.cookieHeaderFor(START));
        assertEquals(LinkSession.NO_TRANSFERABLE_SESSION, session.reason());
    }

    @Test
    void anAuthenticatedSessionWithNothingToCarryIsRefusedRatherThanCreated() {
        // The alternative would be an "authenticated" session that silently sends no cookie, which
        // requests exactly as an anonymous visitor while claiming not to. A caller with no usable
        // cookie has to choose discovery-only and say so.
        assertThrows(IllegalArgumentException.class,
            () -> LinkSession.authenticated(START, List.of()));
    }

    // --- origin ----------------------------------------------------------------------------------

    @Test
    void theStartOriginReceivesTheSession() {
        String header = signedIn().cookieHeaderFor(URI.create("https://shop.example.com/cart"));

        assertEquals("session=abc123", header);
    }

    @Test
    void anotherHostReceivesNothing() {
        assertNull(signedIn().cookieHeaderFor(URI.create("https://other.example.com/cart")));
    }

    @Test
    void aSubdomainIsAnotherHostAndReceivesNothing() {
        // A subdomain is where this is most tempting to get wrong, and where getting it wrong hands
        // the session to whoever can put content on one.
        assertNull(signedIn().cookieHeaderFor(URI.create("https://cdn.shop.example.com/asset")));
        assertNull(signedIn().cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void anotherSchemeIsAnotherOriginAndReceivesNothing() {
        assertNull(signedIn().cookieHeaderFor(URI.create("http://shop.example.com/cart")));
    }

    @Test
    void anotherPortIsAnotherOriginAndReceivesNothing() {
        assertNull(signedIn().cookieHeaderFor(URI.create("https://shop.example.com:8443/cart")));
    }

    @Test
    void theImpliedPortIsTheSameOriginAsTheWrittenOne() {
        // https://host and https://host:443 are one place, so a link written either way is served
        // the session. This is the effective-port half of the project's origin rule.
        assertNotNull(signedIn().cookieHeaderFor(URI.create("https://shop.example.com:443/cart")));
    }

    @Test
    void theOriginRuleIsTheProjectsOwnAndIsNotRestatedHere() {
        // Not a second definition: the same method the crawler admits pages by. If these ever
        // disagree, a cookie could reach an origin the crawl had already ruled a third party.
        assertEquals(
            com.qanize.automation.checks.shared.NavigationLinkInspector.origin(START),
            com.qanize.automation.checks.shared.NavigationLinkInspector.origin(
                URI.create("HTTPS://Shop.Example.COM:443/anything?x=1")),
            "scheme and host are lowercased and the port is the effective one");
        assertNotNull(signedIn().cookieHeaderFor(URI.create("HTTPS://Shop.Example.COM/cart")));
    }

    // --- the cookie's own restrictions -----------------------------------------------------------

    @Test
    void aSecureCookieIsNeverSentInTheClear() {
        LinkSession session = LinkSession.authenticated(
            URI.create("http://shop.example.com/"),
            List.of(new LinkSession.SessionCookie(
                "session", "abc123", "shop.example.com", "/", true, false)));

        assertNull(session.cookieHeaderFor(URI.create("http://shop.example.com/cart")),
            "a browser would refuse this, and so must we");
    }

    @Test
    void aNonSecureCookieTravelsOverPlainHttp() {
        LinkSession session = LinkSession.authenticated(
            URI.create("http://shop.example.com/"),
            List.of(new LinkSession.SessionCookie(
                "session", "abc123", "shop.example.com", "/", false, false)));

        assertEquals("session=abc123", session.cookieHeaderFor(URI.create("http://shop.example.com/cart")));
    }

    @Test
    void anHttpOnlyCookieIsStillSent() {
        // HttpOnly restricts what page scripts may read. It says nothing about HTTP, where the
        // cookie is as valid as any other — and on most real sites it is the session cookie, so
        // filtering it here would mean never authenticating anything.
        LinkSession session = LinkSession.authenticated(START, List.of(
            new LinkSession.SessionCookie("session", "abc123", "shop.example.com", "/", false, true)));

        assertEquals("session=abc123", session.cookieHeaderFor(START));
    }

    @Test
    void aCookiesPathRestrictionIsPreserved() {
        LinkSession session = LinkSession.authenticated(START, List.of(
            new LinkSession.SessionCookie("admin", "a1", "shop.example.com", "/admin", false, false)));

        assertNull(session.cookieHeaderFor(URI.create("https://shop.example.com/public")));
        assertNull(session.cookieHeaderFor(URI.create("https://shop.example.com/administrator")),
            "a shared prefix is not a path match");
        assertEquals("admin=a1", session.cookieHeaderFor(URI.create("https://shop.example.com/admin")));
        assertEquals("admin=a1",
            session.cookieHeaderFor(URI.create("https://shop.example.com/admin/users")));
    }

    @Test
    void aCookieSetForAnotherDomainIsNotForwarded() {
        // A page can hold cookies it did not set. Being in the jar is not permission to send it.
        LinkSession session = LinkSession.authenticated(START, List.of(
            new LinkSession.SessionCookie("ads", "x", "tracker.example.net", "/", false, false),
            cookie("session", "abc123")));

        assertEquals("session=abc123", session.cookieHeaderFor(START),
            "only the cookie that belongs to this host travels");
    }

    @Test
    void aDomainWideCookieReachesTheHostBelowIt() {
        LinkSession session = LinkSession.authenticated(START, List.of(
            new LinkSession.SessionCookie("session", "abc123", ".example.com", "/", false, false)));

        assertEquals("session=abc123", session.cookieHeaderFor(START));
    }

    @Test
    void severalCookiesTravelTogetherInOneHeader() {
        LinkSession session = LinkSession.authenticated(
            START, List.of(cookie("session", "abc123"), cookie("csrf", "zz9")));

        assertEquals("session=abc123; csrf=zz9", session.cookieHeaderFor(START));
    }

    // --- capture -------------------------------------------------------------------------------

    @Test
    void aSignedInBrowserSuppliesTheSession() {
        LinkSession session = LinkSession.capturedFrom(
            jarHolding(cookieOf("session", "abc123", "shop.example.com", "/", false, true)), START);

        assertEquals(LinkSession.Mode.BROWSER_AUTHENTICATED, session.mode());
        assertEquals(1, session.cookieCount());
        assertEquals("session=abc123", session.cookieHeaderFor(START));
    }

    @Test
    void aBrowserWithNoCookiesChoosesDiscoveryOnlyRatherThanAnonymous() {
        // A site can authenticate without cookies at all. There is then nothing to present, and
        // requesting anyway would collect answers about pages the customer does not have.
        LinkSession session = LinkSession.capturedFrom(jarHolding(), START);

        assertEquals(LinkSession.Mode.AUTHENTICATED_DISCOVERY_ONLY, session.mode());
        assertFalse(session.validatesOverHttp());
    }

    @Test
    void aBrowserThatRefusesItsCookieJarChoosesDiscoveryOnlyRatherThanAnonymous() {
        LinkSession session = LinkSession.capturedFrom(refusingJar(), START);

        assertEquals(LinkSession.Mode.AUTHENTICATED_DISCOVERY_ONLY, session.mode());
        assertEquals(LinkSession.NO_TRANSFERABLE_SESSION, session.reason(),
            "the reason is fixed text, never built out of what the browser said");
    }

    @Test
    void captureNeverProducesAnAnonymousSession() {
        // The one transition that must not exist. Every failure path above ends at discovery-only;
        // none of them ends at "request as a stranger", which is the bug this slice removes.
        for (LinkSession session : List.of(
            LinkSession.capturedFrom(jarHolding(), START),
            LinkSession.capturedFrom(refusingJar(), START),
            LinkSession.capturedFrom((org.openqa.selenium.WebDriver) null, START),
            LinkSession.capturedFrom(jarHolding(cookieOf("a", "b", "h", "/", false, false)), null),
            LinkSession.capturedFrom(
                jarHolding(cookieOf("only", "has\r\nnewline", "shop.example.com", "/", false, false)),
                START))) {
            assertFalse(session.mode() == LinkSession.Mode.ANONYMOUS,
                "capture produced " + session.mode() + " — anonymous must be unreachable from here");
        }
    }

    @Test
    void aCookieThatCouldNotSafelyGoInAHeaderIsDropped() {
        // Not cosmetic: the HTTP client rejects such a value by throwing an exception that quotes
        // it, which is how a session ends up in a log. Screening here means that never happens.
        LinkSession session = LinkSession.capturedFrom(jarHolding(
            cookieOf("good", "fine", "shop.example.com", "/", false, false),
            cookieOf("broken", "has\r\nnewline", "shop.example.com", "/", false, false),
            cookieOf("also-broken", "semi;colon", "shop.example.com", "/", false, false)),
            START);

        assertEquals(1, session.cookieCount());
        assertEquals("good=fine", session.cookieHeaderFor(START));
    }

    // --- nothing is ever rendered ----------------------------------------------------------------

    @Test
    void neitherTheSessionNorACookieEverRendersItsValue() {
        LinkSession session = signedIn();
        LinkSession.SessionCookie one = cookie("session", "abc123");

        for (String rendered : List.of(
            session.toString(), one.toString(), List.of(one).toString(), String.valueOf(session))) {
            assertFalse(rendered.contains("abc123"), "a value was rendered: " + rendered);
            assertFalse(rendered.contains("session=") && rendered.contains("abc"), rendered);
        }
        assertTrue(session.toString().contains("BROWSER_AUTHENTICATED"),
            "the mode is safe to say, and useful: " + session);
    }

    @Test
    void redactionRemovesAValueFromTextThatShouldNeverHaveHadOne() {
        LinkSession session = signedIn();

        String scrubbed = session.redactFrom("java.io.IOException: sent Cookie: session=abc123");

        assertFalse(scrubbed.contains("abc123"), scrubbed);
        assertTrue(scrubbed.contains("[redacted]"), scrubbed);
        assertEquals("nothing to scrub", session.redactFrom("nothing to scrub"));
        assertNull(session.redactFrom(null));
    }

    @Test
    void twoRunsHoldSeparateSessions() {
        LinkSession first = LinkSession.capturedFrom(
            jarHolding(cookieOf("session", "first-run", "shop.example.com", "/", false, false)),
            START);
        LinkSession second = LinkSession.capturedFrom(jarHolding(), START);

        assertEquals("session=first-run", first.cookieHeaderFor(START));
        assertNull(second.cookieHeaderFor(START), "the second run inherited nothing");
        assertEquals(LinkSession.Mode.AUTHENTICATED_DISCOVERY_ONLY, second.mode());
        // Nothing static could carry one into the other: there is nowhere for a session to live.
        for (java.lang.reflect.Field field : LinkSession.class.getDeclaredFields()) {
            if (field.isSynthetic() || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                "a mutable static would be shared by every run: " + field);
            assertFalse(java.util.Collection.class.isAssignableFrom(field.getType())
                    && !field.getName().equals("ANONYMOUS_SESSION"),
                "no static collection may accumulate across runs: " + field);
        }
    }

    // --- a cookie jar, without a browser ---------------------------------------------------------

    /** A real Selenium cookie, built through the constructor Selenium actually offers. */
    private static Cookie cookieOf(
        String name, String value, String domain, String path, boolean secure, boolean httpOnly
    ) {
        return new Cookie(name, value, domain, path, null, secure, httpOnly);
    }

    private static LinkSession.CookieJar jarHolding(Cookie... cookies) {
        List<Cookie> jar = List.of(cookies);
        return () -> jar;
    }

    /** A browser that has crashed, closed or lost its session answers like this. */
    private static LinkSession.CookieJar refusingJar() {
        return () -> {
            throw new IllegalStateException("no such session: value=abc123");
        };
    }
}
