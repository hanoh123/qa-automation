package com.qanize.automation.engine;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The identity of one crawled page.
 *
 * <p>No browser and no network: a context is a plain value, and these tests exist mainly to pin
 * the two properties that make it safe to keep in a result — it holds nothing that expires, and it
 * cannot publish a raw address.
 */
@Tag("unit")
class PageAuditContextTest {

    @Test
    void aContextCarriesThePagesPlaceInTheCrawlAndBothFormsOfItsAddress() {
        PageAuditContext context = PageAuditContext.of(
            new Crawler.Page("https://example.com/about", 1, "https://example.com/"), 2);

        assertEquals(2, context.pageNumber());
        assertEquals("https://example.com/about", context.url());
        assertEquals("https://example.com/about", context.displayUrl());
        assertEquals(1, context.depth());
        assertEquals("https://example.com/", context.discoveredFrom());
        assertFalse(context.isStartPage());
    }

    @Test
    void theStartPageIsPageOneAtDepthZeroWithNoParent() {
        PageAuditContext context =
            PageAuditContext.of(new Crawler.Page("https://example.com/", 0, null), 1);

        assertEquals(1, context.pageNumber());
        assertEquals(0, context.depth());
        assertNull(context.discoveredFrom(), "the start page was not discovered from anywhere");
        assertTrue(context.isStartPage());
    }

    // --- nothing that expires ------------------------------------------------------------------

    @Test
    void aContextHoldsOnlyDurableIdentityAndNeverABrowserHandle() {
        // A WebElement, a driver or a loose state map would all be stale or unbounded the moment
        // the browser moved on. Pinned by type so adding one is a deliberate act.
        List<String> shape = Arrays.stream(PageAuditContext.class.getRecordComponents())
            .map(component -> component.getName() + ":" + component.getType().getSimpleName())
            .toList();

        assertEquals(List.of(
            "pageNumber:int",
            "url:String",
            "displayUrl:String",
            "depth:int",
            "discoveredFrom:String"), shape, "the whole shape of a page context: " + shape);

        for (RecordComponent component : PageAuditContext.class.getRecordComponents()) {
            String type = component.getType().getName();
            assertFalse(type.startsWith("org.openqa.selenium"),
                component.getName() + " is a Selenium type: " + type);
            assertFalse(java.util.Map.class.isAssignableFrom(component.getType()),
                component.getName() + " is a loose state map: " + type);
        }
    }

    // --- nothing sensitive ---------------------------------------------------------------------

    @Test
    void theDisplayAddressIsSanitizedWhileTheRawOneStaysUsable() {
        String secret = "abcdef0123456789abcdef";
        PageAuditContext context = PageAuditContext.of(new Crawler.Page(
            "https://example.com/dash?session_token=" + secret + "&page=2", 1,
            "https://example.com/hub?api_key=" + secret), 2);

        // Raw, because opening the page means opening exactly what the site linked to.
        assertTrue(context.url().contains(secret), "the raw address is still usable");

        assertFalse(context.displayUrl().contains(secret), context.displayUrl());
        assertTrue(context.displayUrl().contains("session_token=[redacted]"), context.displayUrl());
        assertTrue(context.displayUrl().contains("page=2"),
            "an ordinary parameter survives, so the report stays truthful: " + context.displayUrl());
        assertFalse(context.discoveredFrom().contains(secret), context.discoveredFrom());
        assertFalse(context.toString().contains(secret),
            "logging a context must not undo the sanitizing: " + context);
    }

    // --- impossible identities fail clearly ----------------------------------------------------

    @Test
    void anImpossibleIdentityIsRejectedWhereItIsBuilt() {
        IllegalArgumentException page = assertThrows(IllegalArgumentException.class,
            () -> new PageAuditContext(0, "https://example.com/", "https://example.com/", 0, null));
        assertTrue(page.getMessage().contains("pageNumber"), page.getMessage());

        IllegalArgumentException depth = assertThrows(IllegalArgumentException.class,
            () -> new PageAuditContext(1, "https://example.com/", "https://example.com/", -1, null));
        assertTrue(depth.getMessage().contains("depth"), depth.getMessage());

        assertThrows(IllegalArgumentException.class,
            () -> new PageAuditContext(1, "  ", "https://example.com/", 0, null));
    }
}
