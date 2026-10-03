package com.qanize.automation.checks.links;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Links check actually puts on the wire when a run has signed in.
 *
 * <p>Every test here drives the real probe production builds — the one {@link LinksModule} selects
 * from the run's {@link LinkSession} — against a real HTTP server on loopback, and then reads the
 * headers that server received. That is the only way these properties can be proved. A stand-in
 * probe can be asked what it intended to send; a server can be asked what arrived, which is the
 * question that matters when the answer decides whether a customer's session reaches a third party.
 *
 * <p>No external site is contacted and no external network is used. The servers bind port 0 on
 * 127.0.0.1 and are stopped after each test.
 */
@Tag("unit")
class AuthenticatedLinksTest {

    private final List<HttpServer> started = new ArrayList<>();

    @TempDir
    Path runDirectory;

    @AfterEach
    void stopServersAndClearEvidence() {
        for (HttpServer server : started) {
            server.stop(0);
        }
        started.clear();
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    // --- anonymous runs are untouched ------------------------------------------------------------

    @Test
    void anAnonymousRunSendsNoCookieHeaderAtAll() throws IOException {
        Site site = site().answering("/pricing", 200);

        LinksModule.Result result = run(site, LinkSession.anonymous(),
            page().anchor("Pricing", site.url("/pricing")));

        assertEquals(LinksModule.Outcome.PASSED, result.outcome());
        assertEquals(List.of("/pricing"), site.pathsRequested());
        assertNull(site.cookieHeaderFor("/pricing"), "an anonymous run presents nothing");
    }

    @Test
    void anAnonymousRunStillReportsAGenuinelyBrokenLink() throws IOException {
        // The check that matters most about the anonymous path: it is unchanged. A 404 with no
        // session in play is exactly the finding it has always been.
        Site site = site().answering("/gone", 404);

        LinksModule.Result result = run(site, LinkSession.anonymous(),
            page().anchor("Gone", site.url("/gone")));

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
    }

    // --- a signed-in run -------------------------------------------------------------------------

    @Test
    void aSignedInRunPresentsItsSessionToTheStartOrigin() throws IOException {
        Site site = site().answering("/cart", 200);

        run(site, site.signedInAs("session", "abc123"), page().anchor("Cart", site.url("/cart")));

        assertEquals("session=abc123", site.cookieHeaderFor("/cart"));
    }

    @Test
    void theSessionUsedIsTheOneTheSignInEstablished() throws IOException {
        // Not "a session": the cookies the browser held after signing in, read through the same
        // capture the runner performs, travelling unchanged to the request.
        Site site = site().answering("/cart", 200);
        LinkSession captured = LinkSession.capturedFrom(
            () -> List.of(new org.openqa.selenium.Cookie(
                "session", "from-the-browser", site.host(), "/", null, false, true)),
            URI.create(site.url("/")));

        run(site, captured, page().anchor("Cart", site.url("/cart")));

        assertEquals("session=from-the-browser", site.cookieHeaderFor("/cart"),
            "including the HttpOnly one, which on a real site is usually the session itself");
    }

    @Test
    void aPageServedOnlyToTheSignedInVisitorIsNotReportedAsBroken() throws IOException {
        // The defect, reproduced and then fixed. The server answers 404 to a stranger and 200 to
        // the session — which is what SauceDemo does with /inventory.html.
        Site site = site().answeringOnlyWithSession("/inventory.html", "session", "abc123");
        PageFixture links = page().anchor("All Items", site.url("/inventory.html"));

        LinksModule.Result anonymous = run(site, LinkSession.anonymous(), links);
        assertEquals(LinksModule.Outcome.LINKS_FAILED, anonymous.outcome(),
            "this is the false failure the customer saw");

        LinksModule.Result signedIn = run(site, site.signedInAs("session", "abc123"), links);
        assertEquals(LinksModule.Outcome.PASSED, signedIn.outcome(),
            "the same page, requested as the visitor who signed in");
    }

    @Test
    void theSauceDemoShapedPageIsNotFalselyFailed() throws IOException {
        // The real inventory page's shape: a burger menu whose items are href="#", and one genuine
        // link back to the inventory itself. Before the fragment fix the first "#" was requested;
        // before this slice the real link was requested without the session. Neither may happen.
        Site site = site().answeringOnlyWithSession("/inventory.html", "session", "abc123");
        PageFixture inventory = page();
        for (int item = 1; item <= 20; item++) {
            inventory.anchor("Item " + item, site.url("/inventory.html") + "#");
        }
        inventory.anchor("All Items", site.url("/inventory.html"));

        LinksModule.Result result = run(
            site, site.signedInAs("session", "abc123"), inventory, site.url("/inventory.html"));

        assertEquals(LinksModule.Outcome.PASSED, result.outcome(), result.message());
        assertEquals(List.of("/inventory.html"), site.pathsRequested(),
            "one request: the twenty fragments are jumps within this page");
    }

    // --- where a session may not go --------------------------------------------------------------

    @Test
    void aCrossOriginDestinationReceivesNoCookie() throws IOException {
        // Two servers on two ports. A different effective port is a different origin under the
        // project's rule, so this is the same test as "a different site" without needing DNS.
        Site home = site().answering("/", 200);
        Site elsewhere = site().answering("/partner", 200);

        run(home, home.signedInAs("session", "abc123"),
            page().anchor("Partner", elsewhere.url("/partner")).anchor("Home", home.url("/")));

        assertEquals("session=abc123", home.cookieHeaderFor("/"));
        assertNull(elsewhere.cookieHeaderFor("/partner"), "another origin gets nothing");
    }

    @Test
    void aDifferentHostNameForTheSameMachineStillReceivesNoCookie() throws IOException {
        // 127.0.0.1 and localhost are the same machine and a different host, and the origin rule
        // compares host names. Erring towards not sending is the only safe direction.
        Site site = site().answering("/cart", 200);
        LinkSession session = LinkSession.authenticated(
            URI.create("http://localhost:" + site.port() + "/"),
            List.of(new LinkSession.SessionCookie(
                "session", "abc123", "127.0.0.1", "/", false, false)));

        run(site, session, page().anchor("Cart", site.url("/cart")));

        assertNull(site.cookieHeaderFor("/cart"));
    }

    @Test
    void aSameOriginAddressThatRedirectsToAnotherOriginSendsNoCookieThere() throws IOException {
        // The case initial same-origin filtering cannot catch: the link is on the start origin, so
        // it is served the session — and then the site hands the request to somebody else.
        Site home = site();
        Site elsewhere = site().answering("/landing", 200);
        home.redirecting("/out", elsewhere.url("/landing"));

        LinksModule.Result result = run(home, home.signedInAs("session", "abc123"),
            page().anchor("Offsite", home.url("/out")));

        assertEquals("session=abc123", home.cookieHeaderFor("/out"),
            "the first hop is the start origin and is served the session");
        assertNull(elsewhere.cookieHeaderFor("/landing"),
            "the hop that left the origin carried nothing");
        assertEquals(LinksModule.Outcome.PASSED, result.outcome(),
            "the destination is still followed and still reported — it just arrives as a stranger");
    }

    @Test
    void aRedirectChainThatReturnsToTheStartOriginIsServedAgain() throws IOException {
        // The rule is per hop, not "once we leave, never again": a site that bounces through a
        // sign-on host and back must still be able to answer for the visitor on its own pages.
        Site home = site().answering("/final", 200);
        Site elsewhere = site();
        home.redirecting("/start", elsewhere.url("/via"));
        elsewhere.redirecting("/via", home.url("/final"));

        run(home, home.signedInAs("session", "abc123"),
            page().anchor("Round trip", home.url("/start")));

        assertEquals("session=abc123", home.cookieHeaderFor("/start"));
        assertNull(elsewhere.cookieHeaderFor("/via"));
        assertEquals("session=abc123", home.cookieHeaderFor("/final"));
    }

    @Test
    void aSecureCookieIsNeverSentOverPlainHttp() throws IOException {
        Site site = site().answering("/cart", 200);
        LinkSession session = LinkSession.authenticated(
            URI.create(site.url("/")),
            List.of(new LinkSession.SessionCookie(
                "session", "abc123", site.host(), "/", true, false)));

        run(site, session, page().anchor("Cart", site.url("/cart")));

        assertNull(site.cookieHeaderFor("/cart"), "the server is plain HTTP");
    }

    @Test
    void aCookiePathRestrictionIsPreservedOnTheWire() throws IOException {
        Site site = site().answering("/admin/users", 200).answering("/public", 200);
        LinkSession session = LinkSession.authenticated(
            URI.create(site.url("/")),
            List.of(new LinkSession.SessionCookie(
                "admin", "a1", site.host(), "/admin", false, false)));

        run(site, session,
            page().anchor("Users", site.url("/admin/users")).anchor("Public", site.url("/public")));

        assertEquals("admin=a1", site.cookieHeaderFor("/admin/users"));
        assertNull(site.cookieHeaderFor("/public"));
    }

    // --- the typed fallback ----------------------------------------------------------------------

    @Test
    void aDiscoveryOnlyRunRequestsNothingAndInventsNoFailure() throws IOException {
        // The server answers 404 to everyone. An anonymous run would report a broken link; this
        // run reports that it did not look, which is the truth.
        Site site = site();
        LinksModule.Result result = run(
            site, LinkSession.authenticatedDiscoveryOnly(null),
            page().anchor("Cart", site.url("/cart")).anchor("Orders", site.url("/orders")));

        assertEquals(LinksModule.Outcome.VALIDATION_NOT_PERFORMED, result.outcome());
        assertTrue(result.validationNotPerformed());
        assertFalse(result.passed(), "nothing was verified, so this is not a pass either");
        assertEquals(List.of(), site.pathsRequested(), "not one request was made");
        assertEquals(LinkSession.NO_TRANSFERABLE_SESSION, result.message());
    }

    @Test
    void aDiscoveryOnlyRunStillHandsEveryCandidateToTheCrawler() throws IOException {
        // Discovery worked perfectly. The crawler opening these pages with the signed-in browser is
        // exactly how they do get looked at, so withholding them would lose the run its coverage.
        Site site = site();
        LinksModule.Result result = run(
            site, LinkSession.authenticatedDiscoveryOnly(null),
            page().anchor("Cart", site.url("/cart")).anchor("Orders", site.url("/orders")));

        assertEquals(List.of(site.url("/cart"), site.url("/orders")), result.internalCandidates(),
            "in DOM order, exactly as a validating run hands them over");
    }

    @Test
    void aDiscoveryOnlyRunRecordsEveryLinkAsSkippedAndSaysWhy() throws IOException {
        Site site = site();

        run(site, LinkSession.authenticatedDiscoveryOnly(null),
            page().anchor("Cart", site.url("/cart")).anchor("Orders", site.url("/orders")));

        for (String id : List.of("link-1", "link-2")) {
            String item = itemWithId(id);
            assertTrue(item.contains("\"status\":\"SKIPPED\""), item);
            assertTrue(item.contains("\"metadata.validationMethod\":\"none\""), item);
            assertTrue(item.contains(LinkSession.NO_TRANSFERABLE_SESSION), item);
            assertFalse(item.contains("\"status\":\"FAILED\""), item);
            assertFalse(item.contains("httpStatus"), "no status may be invented: " + item);
        }
    }

    // --- nothing leaks ---------------------------------------------------------------------------

    @Test
    void noCookieNameOrValueReachesAnyRecordedEvidence() throws IOException {
        Site site = site().answeringOnlyWithSession("/inventory.html", "qanize-session", "s3cr3t-value");

        run(site, site.signedInAs("qanize-session", "s3cr3t-value"),
            page().anchor("Inventory", site.url("/inventory.html")));

        List<String> everything = new ArrayList<>(items());
        everything.addAll(steps());
        assertFalse(everything.isEmpty(), "the run recorded something to check");
        for (String line : everything) {
            assertFalse(line.contains("s3cr3t-value"), "a cookie value reached evidence: " + line);
            assertFalse(line.contains("qanize-session"), "a cookie name reached evidence: " + line);
        }
    }

    @Test
    void aFailedAuthenticatedRequestReportsNoCookieValueEither() throws IOException {
        // A destination on the start origin that nothing is listening to: the failure text is
        // whatever the client produced, and it travels through the session's own scrubber first.
        Site site = site();
        int deadPort = site.port();
        site.stopNow();
        LinkSession session = LinkSession.authenticated(
            URI.create("http://127.0.0.1:" + deadPort + "/"),
            List.of(new LinkSession.SessionCookie(
                "session", "s3cr3t-value", "127.0.0.1", "/", false, false)));

        LinksModule.Result result = run(session, "http://127.0.0.1:" + deadPort + "/",
            page().anchor("Cart", "http://127.0.0.1:" + deadPort + "/cart"));

        assertEquals(LinksModule.Outcome.LINKS_FAILED, result.outcome());
        List<String> everything = new ArrayList<>(items());
        everything.addAll(steps());
        everything.add(result.message());
        for (String line : everything) {
            assertFalse(line.contains("s3cr3t-value"), "a cookie value escaped: " + line);
        }
    }

    // --- everything else about Links is unchanged -------------------------------------------------

    @Test
    void fragmentsStillMakeZeroRequestsWhenSignedIn() throws IOException {
        Site site = site();

        LinksModule.Result result = run(site, site.signedInAs("session", "abc123"),
            page().anchor("Top", site.url("/inventory.html") + "#")
                .anchor("Reviews", site.url("/inventory.html") + "#reviews"),
            site.url("/inventory.html"));

        assertEquals(List.of(), site.pathsRequested());
        assertEquals(LinksModule.Outcome.NOTHING_ELIGIBLE, result.outcome());
    }

    @Test
    void candidatesReachTheCrawlerInDomOrderWhenSignedIn() throws IOException {
        Site site = site().answering("/c", 200).answering("/a", 200).answering("/b", 200);

        LinksModule.Result result = run(site, site.signedInAs("session", "abc123"),
            page().anchor("C", site.url("/c")).anchor("A", site.url("/a")).anchor("B", site.url("/b")));

        assertEquals(List.of(site.url("/c"), site.url("/a"), site.url("/b")),
            result.internalCandidates(), "discovery order, not sorted and not reordered");
        assertEquals(List.of("/c", "/a", "/b"), site.pathsRequested());
    }

    @Test
    void twoRunsDoNotShareCookies() throws IOException {
        Site site = site().answering("/cart", 200);

        run(site, site.signedInAs("session", "abc123"), page().anchor("Cart", site.url("/cart")));
        assertEquals("session=abc123", site.cookieHeaderFor("/cart"));

        site.forgetRequests();
        run(site, LinkSession.anonymous(), page().anchor("Cart", site.url("/cart")));

        assertNull(site.cookieHeaderFor("/cart"),
            "the second run inherited nothing from the first");
    }

    // --- driving the module ----------------------------------------------------------------------

    private LinksModule.Result run(Site site, LinkSession session, PageFixture page)
        throws IOException {
        return run(site, session, page, site.url("/"));
    }

    private LinksModule.Result run(Site site, LinkSession session, PageFixture page, String basePage)
        throws IOException {
        return run(session, basePage, page);
    }

    private LinksModule.Result run(LinkSession session, String basePage, PageFixture page)
        throws IOException {
        System.setProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            runDirectory.resolve("evidence.jsonl").toString());
        // The probe production would build for this session, not a stand-in: what these tests are
        // about is which headers reach a server, which only the real one can answer.
        return new LinksModule(
            page, LinksModule.probeFor(session), config(), basePage,
            new LinkExecutionEvidence(), com.qanize.automation.evidence.EvidenceScope.legacy(),
            session).run();
    }

    private static WebsiteConfig config() {
        return new WebsiteConfig(
            "1.0", "example", URI.create("http://127.0.0.1/"), List.of(), List.of(), "", "",
            null, null, null, 1440, 900, 5, 25, 10, null,
            new WebsiteTestSelectionConfig(false, false, true, false, false, false));
    }

    private List<String> items() throws IOException {
        Path file = runDirectory.resolve("evidence.jsonl");
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private List<String> steps() throws IOException {
        Path file = runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME);
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private String itemWithId(String id) throws IOException {
        List<String> recorded = items();
        return recorded.stream()
            .filter(line -> line.contains("\"id\":\"" + id + "\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no item recorded with id " + id + ": " + recorded));
    }

    private static PageFixture page() {
        return new PageFixture();
    }

    /** The anchors one page reports, in the order the DOM gave them. */
    private static final class PageFixture implements LinksModule.PageLinks {

        private final List<LinksModule.DiscoveredLink> anchors = new ArrayList<>();

        PageFixture anchor(String text, String href) {
            anchors.add(new LinksModule.DiscoveredLink(text, href));
            return this;
        }

        @Override public boolean awaitRenderedContentTimedOut() { return false; }

        @Override public List<LinksModule.DiscoveredLink> discoverLinks() {
            return List.copyOf(anchors);
        }

        @Override public int count(String cssSelector) {
            return cssSelector.startsWith("a[href]") ? anchors.size() : 0;
        }
    }

    // --- a real server, on loopback ---------------------------------------------------------------

    private Site site() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        started.add(server);
        Site site = new Site(server);
        server.createContext("/", site::handle);
        server.start();
        return site;
    }

    /**
     * One origin that records what it was asked, and what it was asked with.
     *
     * <p>Unrecognised paths answer 404 deliberately: a request that should never have been made
     * therefore shows up as a failing link, which is exactly how the original defect presented.
     */
    private static final class Site {

        private final HttpServer server;
        private final Map<String, Integer> statuses = new LinkedHashMap<>();
        private final Map<String, String> redirects = new LinkedHashMap<>();
        private final Map<String, String[]> sessionGated = new LinkedHashMap<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final Map<String, String> cookieHeaders =
            Collections.synchronizedMap(new LinkedHashMap<>());

        Site(HttpServer server) {
            this.server = server;
        }

        Site answering(String path, int status) {
            statuses.put(path, status);
            return this;
        }

        Site redirecting(String path, String location) {
            redirects.put(path, location);
            return this;
        }

        /** 200 when the named cookie arrives with that value, 404 to everyone else. */
        Site answeringOnlyWithSession(String path, String name, String value) {
            sessionGated.put(path, new String[] {name, value});
            return this;
        }

        LinkSession signedInAs(String name, String value) {
            return LinkSession.authenticated(URI.create(url("/")), List.of(
                new LinkSession.SessionCookie(name, value, host(), "/", false, false)));
        }

        String host() {
            return "127.0.0.1";
        }

        int port() {
            return server.getAddress().getPort();
        }

        String url(String path) {
            return "http://127.0.0.1:" + port() + path;
        }

        List<String> pathsRequested() {
            return List.copyOf(paths);
        }

        /** The Cookie header that arrived for that path, or null when none did. */
        String cookieHeaderFor(String path) {
            return cookieHeaders.get(path);
        }

        void forgetRequests() {
            paths.clear();
            cookieHeaders.clear();
        }

        void stopNow() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                cookieHeaders.put(path, cookie);
            }
            try {
                String location = redirects.get(path);
                if (location != null) {
                    exchange.getResponseHeaders().add("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                String[] gate = sessionGated.get(path);
                if (gate != null) {
                    boolean present = cookie != null && cookie.contains(gate[0] + "=" + gate[1]);
                    exchange.sendResponseHeaders(present ? 200 : 404, -1);
                    return;
                }
                exchange.sendResponseHeaders(statuses.getOrDefault(path, 404), -1);
            } finally {
                exchange.close();
            }
        }
    }
}
