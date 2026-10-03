package com.qanize.automation.checks.shared;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;


@Tag("unit")
class NavigationLinkInspectorTest {

    private static final URI BASE = URI.create("https://example.com/");

    /** A page that is not the site root, so "the page we are on" is distinguishable from it. */
    private static final URI PAGE = URI.create("https://example.com/inventory.html");

    private static List<NavigationLinkInspector.Candidate> classify(String... hrefs) {
        return NavigationLinkInspector.classify(BASE, List.of(), List.of(hrefs));
    }

    private static List<NavigationLinkInspector.Candidate> classifyOn(URI base, String... hrefs) {
        return NavigationLinkInspector.classify(base, List.of(), List.of(hrefs));
    }

    @Test
    void sameOriginLinksAreEligible() {
        List<NavigationLinkInspector.Candidate> candidates = classify("/pricing", "/about", "https://example.com/contact");

        assertEquals(3, candidates.size());
        assertTrue(candidates.stream().allMatch(NavigationLinkInspector.Candidate::eligible));
    }

    @Test
    void duplicateDestinationsAreTestedOnlyOnce() {
        List<NavigationLinkInspector.Candidate> candidates =
            classify("/pricing", "/pricing/", "/pricing#plans", "https://example.com/pricing");

        assertTrue(candidates.get(0).eligible());
        for (int index = 1; index < candidates.size(); index++) {
            assertEquals(NavigationLinkInspector.Exclusion.DUPLICATE, candidates.get(index).exclusion());
        }
    }

    @Test
    void externalOriginsAreExcluded() {
        assertEquals(
            NavigationLinkInspector.Exclusion.EXTERNAL,
            classify("https://other.example.org/page").getFirst().exclusion()
        );
    }

    @Test
    void unsafeSchemesAndFragmentsAreExcluded() {
        assertEquals(NavigationLinkInspector.Exclusion.SCRIPT, classify("javascript:void(0)").getFirst().exclusion());
        assertEquals(NavigationLinkInspector.Exclusion.MAIL, classify("mailto:a@example.com").getFirst().exclusion());
        assertEquals(NavigationLinkInspector.Exclusion.TELEPHONE, classify("tel:+123456").getFirst().exclusion());
        assertEquals(NavigationLinkInspector.Exclusion.FRAGMENT, classify("#section").getFirst().exclusion());
        assertEquals(NavigationLinkInspector.Exclusion.EMPTY_HREF, classify("   ").getFirst().exclusion());
    }

    @Test
    void stateChangingLinksAreNeverFollowed() {
        for (String href : List.of("/logout", "/sign-out", "/account/delete", "/items/1/remove", "/reset-password")) {
            assertEquals(
                NavigationLinkInspector.Exclusion.STATE_CHANGING,
                classify(href).getFirst().exclusion(),
                () -> href + " must not be followed"
            );
        }
    }

    @Test
    void aPageWhoseNameMerelyContainsAMutationWordIsNotCalledDestructive() {
        // The defect, from a real run of the-internet: /add_remove_elements/ was refused as a link
        // that "may sign out, delete or otherwise change data". It is a demonstration page about
        // adding and removing DOM elements, and opening it with a GET changes nothing at all.
        List<NavigationLinkInspector.Candidate> candidates = NavigationLinkInspector.classify(
            BASE, List.of("Add/Remove Elements"), List.of("/add_remove_elements/"));

        assertNull(candidates.getFirst().exclusion(),
            "the word is part of a page's name, not the name of an action");
        assertTrue(candidates.getFirst().eligible());
    }

    @Test
    void aMutationWordInsideALongerNameIsNotAnActionEither() {
        for (String href : List.of(
            "/add_remove_elements/", "/preset/", "/remote-control", "/deleted-items-report",
            "/removal_policy_overview")) {
            assertNull(
                NavigationLinkInspector.classify(BASE, List.of(""), List.of(href))
                    .getFirst().exclusion(),
                () -> href + " names a page, not an action");
        }
    }

    @Test
    void aMutationWordThatNamesTheActionIsStillRefused() {
        // The protection that must survive: the word at either end of a segment, in the query, or
        // at either end of the link text is a link that does something.
        for (String href : List.of(
            "/account/delete", "/delete-account", "/items/1/remove", "/user_delete",
            "/reset-password", "/subscriptions/unsubscribe", "/admin/delete/user/5",
            "/profile?action=delete", "/cart?remove=7")) {
            assertEquals(
                NavigationLinkInspector.Exclusion.STATE_CHANGING,
                NavigationLinkInspector.classify(BASE, List.of(""), List.of(href))
                    .getFirst().exclusion(),
                () -> href + " must not be followed"
            );
        }
    }

    @Test
    void linkTextThatReadsAsAnActionIsStillRefusedAndAPageNameIsNot() {
        for (String text : List.of("Delete", "Remove item", "Confirm delete", "Deactivate")) {
            assertEquals(
                NavigationLinkInspector.Exclusion.STATE_CHANGING,
                NavigationLinkInspector.classify(BASE, List.of(text), List.of("/go"))
                    .getFirst().exclusion(),
                () -> text + " reads as an action");
        }
        for (String text : List.of("Add/Remove Elements", "Deleted items report", "Remote control")) {
            assertNull(
                NavigationLinkInspector.classify(BASE, List.of(text), List.of("/go"))
                    .getFirst().exclusion(),
                () -> text + " reads as a page name");
        }
    }

    @Test
    void aSignOutIsStillRefusedWhereverItsWordAppears() {
        // Unchanged, and deliberately not narrowed: following one costs the run its session.
        for (String href : List.of("/logout", "/sign-out", "/auth/logout/all", "/x?do=signout")) {
            assertEquals(
                NavigationLinkInspector.Exclusion.STATE_CHANGING,
                NavigationLinkInspector.classify(BASE, List.of(""), List.of(href))
                    .getFirst().exclusion(),
                () -> href + " ends a session");
        }
    }

    @Test
    void stateChangingLinkTextIsAlsoRespected() {
        List<NavigationLinkInspector.Candidate> candidates =
            NavigationLinkInspector.classify(BASE, List.of("Sign out"), List.of("/session/end"));

        assertEquals(NavigationLinkInspector.Exclusion.STATE_CHANGING, candidates.getFirst().exclusion());
    }

    @Test
    void fileDownloadsAreExcluded() {
        assertEquals(NavigationLinkInspector.Exclusion.DOWNLOAD, classify("/files/report.pdf").getFirst().exclusion());
        assertEquals(NavigationLinkInspector.Exclusion.DOWNLOAD, classify("/archive.zip").getFirst().exclusion());
    }

    // --- same-document references, in the shape a browser actually reports them ----------------
    //
    // A browser resolves an anchor's href before handing it over, so `<a href="#">` on
    // /inventory.html is read as "https://example.com/inventory.html#". Every one of these cases
    // arrives absolute; none of them starts with '#'.

    @Test
    void aBrowserResolvedBareFragmentIsAFragmentReference() {
        NavigationLinkInspector.Candidate candidate =
            classifyOn(PAGE, "https://example.com/inventory.html#").getFirst();

        assertEquals(NavigationLinkInspector.Exclusion.FRAGMENT, candidate.exclusion());
        assertFalse(candidate.eligible(), "a same-document reference is not a destination to test");
    }

    @Test
    void aBrowserResolvedNamedFragmentIsAlsoAFragmentReference() {
        assertEquals(
            NavigationLinkInspector.Exclusion.FRAGMENT,
            classifyOn(PAGE, "https://example.com/inventory.html#reviews").getFirst().exclusion()
        );
    }

    @Test
    void everySameDocumentReferenceIsAFragmentAndNoneIsADuplicate() {
        // The SauceDemo shape: twenty `href="#"` anchors on one page. Reporting the first as a
        // broken link and the other nineteen as duplicates of it is exactly the failure this
        // guards; so is calling any of them a duplicate at all, which would name a destination
        // that was never tested in the first place.
        List<String> hrefs = new ArrayList<>();
        for (int index = 0; index < 20; index++) hrefs.add("https://example.com/inventory.html#");

        List<NavigationLinkInspector.Candidate> candidates =
            NavigationLinkInspector.classify(PAGE, List.of(), hrefs);

        assertEquals(20, candidates.size());
        for (NavigationLinkInspector.Candidate candidate : candidates) {
            assertEquals(NavigationLinkInspector.Exclusion.FRAGMENT, candidate.exclusion(),
                () -> "every copy is a same-document reference, not a duplicate: " + candidate);
        }
    }

    @Test
    void aSameDocumentReferenceDoesNotConsumeThePagesOwnDestination() {
        // The consequence that matters. Before the fix the first "#" reference claimed the page's
        // identity, so a real link back to the same page — a menu's "All Items" — was reported as
        // a duplicate of something that had itself been requested by mistake.
        List<NavigationLinkInspector.Candidate> candidates = classifyOn(
            PAGE, "https://example.com/inventory.html#", "https://example.com/inventory.html");

        assertEquals(NavigationLinkInspector.Exclusion.FRAGMENT, candidates.get(0).exclusion());
        assertTrue(candidates.get(1).eligible(),
            "the real link to this page is still a destination worth testing");
    }

    @Test
    void aFragmentOnAnotherPageIsStillARealDestination() {
        // Same origin, carries a fragment, but it is not this document: it is a page that has to
        // be requested like any other.
        assertTrue(classifyOn(PAGE, "https://example.com/pricing#plans").getFirst().eligible());
    }

    @Test
    void aLinkToThisPageWithNoFragmentIsStillARealDestination() {
        assertTrue(classifyOn(PAGE, "https://example.com/inventory.html").getFirst().eligible(),
            "only a fragment makes a reference same-document; the address alone does not");
    }

    @Test
    void sameDocumentIdentityComesFromTheSharedNormalizer() {
        // Not string equality against the base. The shared normalizer already treats a trailing
        // slash as the same page, and the fragment rule inherits that rather than deciding
        // identity a second way of its own.
        assertEquals(
            NavigationLinkInspector.Exclusion.FRAGMENT,
            classifyOn(URI.create("https://example.com/dir/"), "https://example.com/dir#top")
                .getFirst().exclusion()
        );
    }

    @Test
    void aDifferentQueryOnThisPathIsNotTheSameDocument() {
        // The normalizer keeps the query, so this is a different page that happens to share a
        // path — and must be requested rather than dismissed as a jump within this one.
        assertTrue(
            classifyOn(URI.create("https://example.com/search?q=a"), "https://example.com/search?q=b#hit")
                .getFirst().eligible()
        );
    }

    @Test
    void aStateChangingLinkIsStillExcludedAsStateChangingEvenAsAFragment() {
        // Ordering, stated as behaviour: erring towards the safety rule is what keeps a sign-out
        // from ever being followed, whatever else is also true of its href.
        List<NavigationLinkInspector.Candidate> candidates = NavigationLinkInspector.classify(
            PAGE, List.of("Sign out"), List.of("https://example.com/inventory.html#"));

        assertEquals(
            NavigationLinkInspector.Exclusion.STATE_CHANGING, candidates.getFirst().exclusion());
    }

    // --- the wire form -------------------------------------------------------------------------

    @Test
    void withoutFragmentRemovesTheFragmentAndNothingElse() {
        assertEquals(
            "https://example.com/inventory.html",
            NavigationLinkInspector.withoutFragment(
                URI.create("https://example.com/inventory.html#")).toString());
        assertEquals(
            "https://example.com/a/?q=x%20y",
            NavigationLinkInspector.withoutFragment(
                URI.create("https://example.com/a/?q=x%20y#top")).toString(),
            "a trailing slash, a query and its encoding all survive: they change what a server answers");
    }

    @Test
    void withoutFragmentLeavesAnAddressWithNoFragmentExactlyAsItWas() {
        URI address = URI.create("https://Example.com/A/?q=1");

        assertSame(address, NavigationLinkInspector.withoutFragment(address));
    }

    @Test
    void withoutFragmentIsNotAnIdentityAndMustNotBeUsedAsOne() {
        // The two answer different questions and are not interchangeable. If this ever stops
        // holding, one of them has started normalizing what the other requests.
        URI address = URI.create("https://Example.com/a/#top");

        assertNotEquals(
            NavigationLinkInspector.normalize(address),
            NavigationLinkInspector.withoutFragment(address).toString()
        );
        assertEquals("https://example.com/a", NavigationLinkInspector.normalize(address));
        assertEquals("https://Example.com/a/", NavigationLinkInspector.withoutFragment(address).toString());
    }

    // --- origin ----------------------------------------------------------------------------------
    //
    // A different question from normalization, and the one that decides where traffic — and a
    // signed-in session — may be sent. It lives here so the crawler and the link check share one
    // definition: two copies that drifted would mean a cookie reaching an origin the crawl had
    // already judged to be a third party.

    @Test
    void anOriginIsSchemeHostAndEffectivePort() {
        assertEquals("https://example.com:443",
            NavigationLinkInspector.origin(URI.create("https://example.com/a/b?x=1#top")));
        assertEquals("http://example.com:80",
            NavigationLinkInspector.origin(URI.create("http://example.com/")));
    }

    @Test
    void theWrittenDefaultPortIsTheSameOriginAsTheImpliedOne() {
        assertEquals(
            NavigationLinkInspector.origin(URI.create("https://example.com/")),
            NavigationLinkInspector.origin(URI.create("https://example.com:443/")));
        assertEquals(
            NavigationLinkInspector.origin(URI.create("http://example.com/")),
            NavigationLinkInspector.origin(URI.create("http://example.com:80/")));
    }

    @Test
    void anOriginComparesSchemeAndHostWithoutCase() {
        assertEquals(
            NavigationLinkInspector.origin(URI.create("https://example.com/")),
            NavigationLinkInspector.origin(URI.create("HTTPS://Example.COM/")));
    }

    @Test
    void aDifferentSchemePortOrHostIsADifferentOrigin() {
        String origin = NavigationLinkInspector.origin(URI.create("https://example.com/"));

        for (String elsewhere : List.of(
            "http://example.com/", "https://example.com:8443/",
            "https://other.example.com/", "https://sub.example.com/", "https://example.org/")) {
            assertNotEquals(origin, NavigationLinkInspector.origin(URI.create(elsewhere)),
                elsewhere + " must not be the same place as https://example.com/");
        }
    }

    @Test
    void theSamePageIdentityIsNotTheSamePlace() {
        // The two rules answer different questions and must never stand in for one another:
        // normalization keeps a query and ignores a port, origin does the reverse.
        URI address = URI.create("https://example.com:8443/a?x=1");

        assertNotEquals(
            NavigationLinkInspector.normalize(address), NavigationLinkInspector.origin(address));
        assertEquals("https://example.com/a?x=1", NavigationLinkInspector.normalize(address));
        assertEquals("https://example.com:8443", NavigationLinkInspector.origin(address));
    }

    @Test
    void normalizationIgnoresTrailingSlashAndFragment() {
        assertEquals(
            NavigationLinkInspector.normalize(URI.create("https://example.com/a/")),
            NavigationLinkInspector.normalize(URI.create("https://example.com/a#top"))
        );
        assertFalse(NavigationLinkInspector.normalize(URI.create("https://example.com/a?x=1"))
            .equals(NavigationLinkInspector.normalize(URI.create("https://example.com/a"))));
    }
}
