package com.qanize.automation.checks.shared;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * A skipped check must explain itself, and it must only describe what it actually measured.
 *
 * <p>The regression these tests pin: the Forms check reported "0 links on this website" on a page
 * where the Links check had just validated four same-origin destinations, because Forms passed
 * placeholder zeros for metrics it never computed.
 */
@Tag("unit")
class DiscoveryDiagnosticsTest {

    // ---------- Forms: must not describe link classification at all ----------

    @Test
    void formsDiagnosticsCannotReportSameOriginOrNavigationCounts() {
        Map<String, String> metadata =
            new DiscoveryDiagnostics.ForForms(4, 10, 0, 0, false).asMetadata();

        assertFalse(metadata.containsKey("discovery.sameOriginAnchors"),
            "Forms never classifies links, so it must not publish a same-origin count");
        assertFalse(metadata.containsKey("discovery.navigationAnchors"),
            "Forms never inspects navigation regions, so it must not publish that count");
    }

    @Test
    void aMissingFormIsExplainedAsAMissingFormNotAsNavigationBehaviour() {
        // The exact shape of the reported run: 4 links, 10 buttons, no form, no frames.
        String message = new DiscoveryDiagnostics.ForForms(4, 10, 0, 0, false)
            .explain("No forms were discovered on the submitted page.");

        assertTrue(message.contains("no form to inspect"), message);
        // The button count is a real measurement and may be reported; what must never appear is
        // the claim that buttons explain the result, which is reasoning about navigation.
        assertTrue(message.contains("10 button(s)"), message);
        assertFalse(message.contains("buttons rather than links"),
            "a missing form must not be blamed on buttons: " + message);
        assertFalse(message.contains("navigation"),
            "a missing form must not describe navigation: " + message);
        assertFalse(message.contains("on this website"), message);
        assertFalse(message.contains("navigation regions"), message);
    }

    @Test
    void formsStillReportsTheCountsItGenuinelyMeasured() {
        String message = new DiscoveryDiagnostics.ForForms(4, 10, 0, 2, false)
            .explain("No forms were discovered on the submitted page.");

        assertTrue(message.contains("0 form(s)"), message);
        assertTrue(message.contains("4 link(s)"), message);
        assertTrue(message.contains("10 button(s)"), message);
        assertTrue(message.contains("2 embedded frame(s)"), message);
    }

    @Test
    void formsPrefersRenderingAndFrameCausesOverTheEmptyPageCause() {
        assertTrue(new DiscoveryDiagnostics.ForForms(0, 0, 0, 0, true)
            .explain("x").contains("drawn by JavaScript"));
        assertTrue(new DiscoveryDiagnostics.ForForms(4, 1, 0, 3, false)
            .explain("x").contains("embedded frames"));
    }

    // ---------- Links and Navigation: real measurements preserved ----------

    @Test
    void linkDiagnosticsPreserveEveryMeasuredValue() {
        Map<String, String> metadata =
            new DiscoveryDiagnostics.ForLinks(9, 4, 2, 6, 3, true).asMetadata();

        assertEquals("9", metadata.get("discovery.anchors"));
        assertEquals("4", metadata.get("discovery.sameOriginAnchors"));
        assertEquals("2", metadata.get("discovery.navigationAnchors"));
        assertEquals("6", metadata.get("discovery.buttons"));
        assertEquals("3", metadata.get("discovery.iframes"));
    }

    @Test
    void linkCausesStayOrderedFromRenderingToFiltering() {
        assertTrue(new DiscoveryDiagnostics.ForLinks(0, 0, 0, 0, 0, true)
            .explain("x").contains("drawn by JavaScript"));
        assertTrue(new DiscoveryDiagnostics.ForLinks(0, 0, 0, 0, 3, false)
            .explain("x").contains("embedded frames"));
        assertTrue(new DiscoveryDiagnostics.ForLinks(0, 0, 0, 14, 0, false)
            .explain("x").contains("buttons rather than links"));
        assertTrue(new DiscoveryDiagnostics.ForLinks(0, 0, 0, 0, 0, false)
            .explain("x").contains("no links at all"));
        assertTrue(new DiscoveryDiagnostics.ForLinks(9, 0, 0, 0, 0, false)
            .explain("x").contains("leaves this website"));
        assertTrue(new DiscoveryDiagnostics.ForLinks(9, 9, 4, 0, 0, false)
            .explain("x").contains("excluded by the safety rules"));
    }

    // ---------- Shared invariants ----------

    @Test
    void contentWaitTimedOutIsPublishedExplicitlyByBothVariants() {
        assertEquals("true", new DiscoveryDiagnostics.ForLinks(0, 0, 0, 0, 0, true)
            .asMetadata().get(DiscoveryDiagnostics.CONTENT_WAIT_TIMED_OUT_KEY));
        assertEquals("false", new DiscoveryDiagnostics.ForForms(4, 10, 0, 0, false)
            .asMetadata().get(DiscoveryDiagnostics.CONTENT_WAIT_TIMED_OUT_KEY));
    }

    @Test
    void metadataCarriesCountsAndFlagsOnlyNeverPageContent() {
        for (DiscoveryDiagnostics diagnostics : java.util.List.of(
            new DiscoveryDiagnostics.ForLinks(9, 4, 2, 6, 3, true),
            new DiscoveryDiagnostics.ForForms(4, 10, 1, 2, false)
        )) {
            diagnostics.asMetadata().forEach((key, value) -> {
                assertTrue(key.startsWith("discovery."), "unexpected key: " + key);
                assertTrue(value.matches("\\d+|true|false"),
                    "only counts and flags may be recorded: " + key + "=" + value);
            });
        }
    }
}
