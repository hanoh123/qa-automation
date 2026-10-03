package com.qanize.automation.evidence;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Which page a piece of evidence belongs to.
 *
 * <p>No browser, no network and no file: a scope is a plain value. These tests pin the two things
 * everything else depends on — that the legacy scope changes absolutely nothing, and that two
 * pages can never produce the same identity.
 */
@Tag("unit")
class EvidenceScopeTest {

    private static final String PAGE = "https://example.com/about";
    private static final String PARENT = "https://example.com/";

    // --- 1. the legacy scope changes nothing ---------------------------------------------------

    @Test
    void theLegacyScopeLeavesEveryIdentityAndTheMetadataExactlyAsTheCheckWroteIt() {
        EvidenceScope legacy = EvidenceScope.legacy();

        assertTrue(legacy.isLegacy());
        assertEquals("smoke-page-1", legacy.itemId("smoke-page-1"));
        assertEquals("link-1", legacy.itemId("link-1"));
        assertEquals("link-skipped-3", legacy.itemId("link-skipped-3"));
        assertEquals("links-discovery", legacy.testCaseId("links-discovery"));
        assertEquals("smoke-page-1", legacy.testCaseId("smoke-page-1"));
        assertEquals(Map.of(), legacy.pageMetadata(), "a legacy item gains no metadata at all");
        assertNull(legacy.pageId());

        // Byte for byte: an item built under the legacy scope serializes identically.
        TestedItem plain = item().meta("linkType", "internal").build();
        TestedItem enriched = legacy.enrich(item().meta("linkType", "internal")).build();
        assertEquals(EvidenceRecorder.toJsonLine(plain), EvidenceRecorder.toJsonLine(enriched),
            "the legacy scope must not alter one byte of the recorded line");
    }

    @Test
    void theLegacyScopeIsOneSharedInstanceBecauseItHoldsNothing() {
        assertSame(EvidenceScope.legacy(), EvidenceScope.legacy());
    }

    private static TestedItem.Builder item() {
        return TestedItem.builder("LINKS", TestedItem.ItemType.LINK)
            .id("link-1")
            .name("Pricing")
            .pageUrl(PARENT)
            .status(TestedItem.Status.PASSED);
    }

    // --- 2 & 3. two pages, two identities ------------------------------------------------------

    @Test
    void twoPagesProduceDifferentTestedItemIdsForTheSameCheckIdentity() {
        EvidenceScope first = EvidenceScope.forPage(1, PARENT, 0, null);
        EvidenceScope second = EvidenceScope.forPage(2, PAGE, 1, PARENT);

        assertEquals("page-001/smoke-page-1", first.itemId("smoke-page-1"));
        assertEquals("page-002/smoke-page-1", second.itemId("smoke-page-1"));
        assertEquals("page-001/link-1", first.itemId("link-1"));
        assertEquals("page-002/link-1", second.itemId("link-1"));

        assertFalse(first.itemId("link-1").equals(second.itemId("link-1")),
            "the collision this whole mechanism exists to prevent");
    }

    @Test
    void twoPagesProduceDifferentExecutionTestCaseIds() {
        assertEquals("page-001/links-discovery",
            EvidenceScope.forPage(1, PARENT, 0, null).testCaseId("links-discovery"));
        assertEquals("page-002/links-discovery",
            EvidenceScope.forPage(2, PAGE, 1, PARENT).testCaseId("links-discovery"));
    }

    @Test
    void thePageIdIsZeroPaddedSoIdentitiesSortInVisitingOrder() {
        assertEquals("page-001", EvidenceScope.pageId(1));
        assertEquals("page-009", EvidenceScope.pageId(9));
        assertEquals("page-010", EvidenceScope.pageId(10));
        assertEquals("page-100", EvidenceScope.pageId(100));
        assertEquals("page-1000", EvidenceScope.pageId(1000), "wider rather than truncated");

        // Plain text sorting matches visiting order, which zero padding is the whole point of.
        List<String> sorted = java.util.stream.Stream.of(
                EvidenceScope.pageId(10), EvidenceScope.pageId(2), EvidenceScope.pageId(1))
            .sorted().toList();
        assertEquals(List.of("page-001", "page-002", "page-010"), sorted);
    }

    @Test
    void theSeparatorAppearsExactlyOnceAtAKnownOffset() {
        String scoped = EvidenceScope.forPage(7, PAGE, 1, PARENT).itemId("link-1");

        assertEquals("page-007/link-1", scoped);
        assertEquals(1, scoped.split("/", -1).length - 1,
            "the page id cannot contain the separator, so there is exactly one");
        assertEquals("page-007", scoped.substring(0, scoped.indexOf(EvidenceScope.SEPARATOR)));
    }

    @Test
    void aBlankModuleIdentityIsLeftAloneRatherThanTurnedIntoADanglingPrefix() {
        EvidenceScope scope = EvidenceScope.forPage(1, PAGE, 0, null);

        assertNull(scope.itemId(null));
        assertEquals("", scope.itemId(""));
    }

    // --- 6. the page metadata ------------------------------------------------------------------

    @Test
    void thePageMetadataCarriesIdNumberUrlDepthAndParent() {
        Map<String, String> metadata = EvidenceScope.forPage(2, PAGE, 1, PARENT).pageMetadata();

        assertEquals("page-002", metadata.get(EvidenceScope.META_ID));
        assertEquals("2", metadata.get(EvidenceScope.META_NUMBER));
        assertEquals(PAGE, metadata.get(EvidenceScope.META_URL));
        assertEquals("1", metadata.get(EvidenceScope.META_DEPTH));
        assertEquals(PARENT, metadata.get(EvidenceScope.META_DISCOVERED_FROM));
        assertEquals(5, metadata.size(), "no other key is published: " + metadata);

        // Every key sits under the reserved namespace no check uses.
        metadata.keySet().forEach(key -> assertTrue(key.startsWith(EvidenceScope.META_PREFIX), key));
    }

    @Test
    void theStartPageHasNoParentKeyRatherThanAnEmptyOne() {
        Map<String, String> metadata = EvidenceScope.forPage(1, PARENT, 0, null).pageMetadata();

        assertEquals("page-001", metadata.get(EvidenceScope.META_ID));
        assertEquals("0", metadata.get(EvidenceScope.META_DEPTH));
        assertFalse(metadata.containsKey(EvidenceScope.META_DISCOVERED_FROM),
            "an absent parent stays absent rather than becoming a blank field: " + metadata);
        assertEquals(4, metadata.size());
    }

    @Test
    void thePageMetadataReachesTheRecordedLineUnderTheFlatContract() {
        TestedItem scoped = EvidenceScope.forPage(2, PAGE, 1, PARENT).enrich(item()).build();
        String line = EvidenceRecorder.toJsonLine(scoped);

        // Flattened as metadata.<key>, exactly as contract 1.0 already serializes metadata. No
        // field was added, so an old reader simply sees keys it does not recognise.
        assertTrue(line.contains("\"metadata.page.id\":\"page-002\""), line);
        assertTrue(line.contains("\"metadata.page.number\":\"2\""), line);
        assertTrue(line.contains("\"metadata.page.depth\":\"1\""), line);
        assertTrue(line.contains("\"metadata.page.url\":\"" + PAGE + "\""), line);
        assertTrue(line.contains("\"metadata.page.discoveredFrom\":\"" + PARENT + "\""), line);
    }

    // --- 7 & 8. module metadata wins -----------------------------------------------------------

    @Test
    void enrichingPreservesTheChecksOwnMetadata() {
        TestedItem enriched = EvidenceScope.forPage(3, PAGE, 1, PARENT)
            .enrich(item().meta("linkType", "internal").meta("validationMethod", "HTTP GET"))
            .build();

        assertEquals("internal", enriched.metadata().get("linkType"));
        assertEquals("HTTP GET", enriched.metadata().get("validationMethod"));
        assertEquals("page-003", enriched.metadata().get(EvidenceScope.META_ID));
    }

    @Test
    void aScopeCannotOverwriteAKeyTheCheckAlreadyOwns() {
        // The reserved namespace makes this collision unreachable in practice; the guard is what
        // makes it impossible rather than merely unlikely. A check's value always survives.
        TestedItem enriched = EvidenceScope.forPage(4, PAGE, 1, PARENT)
            .enrich(item()
                .meta(EvidenceScope.META_ID, "owned-by-the-check")
                .meta(EvidenceScope.META_DEPTH, "99"))
            .build();

        assertEquals("owned-by-the-check", enriched.metadata().get(EvidenceScope.META_ID),
            "the check wrote it first, so the check keeps it");
        assertEquals("99", enriched.metadata().get(EvidenceScope.META_DEPTH));
        assertEquals("4", enriched.metadata().get(EvidenceScope.META_NUMBER),
            "the keys the check did not claim are still filled");
    }

    // --- 9. nothing sensitive ------------------------------------------------------------------

    @Test
    void addressesAreSanitizedOnTheWayInSoAScopeCannotCarryAToken() {
        String secret = "abcdef0123456789abcdef";
        EvidenceScope scope = EvidenceScope.forPage(
            2,
            "https://example.com/dash?session_token=" + secret + "&page=2#access_token=" + secret,
            1,
            "https://example.com/hub?api_key=" + secret);

        assertFalse(scope.pageUrl().contains(secret), scope.pageUrl());
        assertTrue(scope.pageUrl().contains("session_token=[redacted]"), scope.pageUrl());
        assertTrue(scope.pageUrl().contains("page=2"),
            "an ordinary parameter survives, so the report stays truthful: " + scope.pageUrl());
        assertFalse(scope.pageUrl().contains(secret + "&"), scope.pageUrl());
        assertFalse(scope.discoveredFrom().contains(secret), scope.discoveredFrom());
        assertFalse(scope.toString().contains(secret), scope.toString());

        String line = EvidenceRecorder.toJsonLine(scope.enrich(item()).build());
        assertFalse(line.contains(secret), "no raw token reaches the recorded line: " + line);
    }

    // --- 12. no ambient state ------------------------------------------------------------------

    @Test
    void thereIsNoGlobalMutableOrThreadLocalCurrentPage() throws IOException {
        for (Field field : EvidenceScope.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isFinal(field.getModifiers()),
                field.getName() + " is static but not final, so it is global mutable state");
            assertFalse(ThreadLocal.class.isAssignableFrom(field.getType()),
                field.getName() + " is a ThreadLocal");
        }

        // Comments are stripped first: the class documentation names ThreadLocal in order to say
        // it is not used, and a guard that a doc sentence can trip is a guard that gets deleted.
        String code = code(Files.readString(
            Path.of("src/main/java/com/qanize/automation/evidence/EvidenceScope.java")));
        assertFalse(code.contains("ThreadLocal"), "a scope is handed over, never ambient");
        assertFalse(code.contains("static EvidenceScope current"), code);
        assertFalse(code.contains("void setCurrent"), code);

        // And a scope is immutable: no instance field can be reassigned.
        for (Field field : EvidenceScope.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()),
                field.getName() + " must be final for a scope to be immutable");
        }
    }

    /** Source with block and line comments removed, so a guard cannot match documentation. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    @Test
    void twoScopesWithTheSameIdentityAreEqualAndDifferentPagesAreNot() {
        assertEquals(EvidenceScope.forPage(1, PAGE, 0, null), EvidenceScope.forPage(1, PAGE, 0, null));
        assertFalse(EvidenceScope.forPage(1, PAGE, 0, null)
            .equals(EvidenceScope.forPage(2, PAGE, 0, null)));
    }

    // --- the address that was asked for, and the one that came back ----------------------------

    @Test
    void aPageThatLandedWhereItWasSentPublishesOneAddress() {
        // The common case. A second key here would make every ordinary page look redirected.
        assertNull(EvidenceScope.forPage(1, PAGE, PAGE, 0, null).landedUrl());
        assertFalse(EvidenceScope.forPage(1, PAGE, PAGE, 0, null)
            .pageMetadata().containsKey(EvidenceScope.META_LANDED_URL));
        assertFalse(EvidenceScope.forPage(1, PAGE, null, 0, null)
            .pageMetadata().containsKey(EvidenceScope.META_LANDED_URL));
    }

    @Test
    void aPageThatWasRedirectedKeepsBothAddresses() {
        // The Whop shape: a dashboard was asked for and a public home page came back. Neither
        // address may overwrite the other -- that overwrite is the whole bug.
        EvidenceScope scope =
            EvidenceScope.forPage(1, "https://site.test/dashboard/x/", "https://site.test/", 0, null);

        assertEquals("https://site.test/dashboard/x/", scope.pageUrl());
        assertEquals("https://site.test/", scope.landedUrl());
        assertEquals("https://site.test/dashboard/x/",
            scope.pageMetadata().get(EvidenceScope.META_URL));
        assertEquals("https://site.test/",
            scope.pageMetadata().get(EvidenceScope.META_LANDED_URL));
    }

    @Test
    void aLandingAddressIsSanitizedLikeEveryOtherOne() {
        // A landing address is exactly where a return parameter or a session value shows up, and it
        // is about to become the pageUrl of every item the page produces.
        EvidenceScope scope = EvidenceScope.forPage(
            1, "https://site.test/private/", "https://site.test/?next=%2Fprivate%2F&token=abc", 0, null);

        String landed = scope.pageMetadata().get(EvidenceScope.META_LANDED_URL);
        assertFalse(landed.contains("token=abc"), landed);
        assertFalse(landed.contains("abc"), landed);
    }

    @Test
    void theFourArgumentFormStillBehavesExactlyAsItAlwaysDid() {
        assertEquals(
            EvidenceScope.forPage(2, PAGE, 1, "https://example.com/from"),
            EvidenceScope.forPage(2, PAGE, null, 1, "https://example.com/from"));
    }

    // --- impossible scopes fail clearly --------------------------------------------------------

    @Test
    void anImpossibleScopeIsRejectedWhereItIsBuilt() {
        IllegalArgumentException number = assertThrows(IllegalArgumentException.class,
            () -> EvidenceScope.forPage(0, PAGE, 0, null));
        assertTrue(number.getMessage().contains("pageNumber"), number.getMessage());

        IllegalArgumentException depth = assertThrows(IllegalArgumentException.class,
            () -> EvidenceScope.forPage(1, PAGE, -1, null));
        assertTrue(depth.getMessage().contains("depth"), depth.getMessage());

        assertThrows(IllegalArgumentException.class, () -> EvidenceScope.forPage(1, "  ", 0, null));
    }
}
