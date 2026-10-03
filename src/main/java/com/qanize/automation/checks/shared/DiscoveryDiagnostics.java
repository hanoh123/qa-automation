package com.qanize.automation.checks.shared;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * What the automation actually saw on the page, so "nothing discovered" is never a dead end.
 *
 * <p>Each check reports only the metrics it genuinely measured. An earlier version took every
 * count in one constructor, which let a check that never classifies links pass literal zeros for
 * "links on this website" — those placeholders were then rendered as measured facts and
 * contradicted the Links check on the same page. The variants below make that unrepresentable:
 * {@link ForForms} has no same-origin or navigation fields at all, so it cannot describe them.
 *
 * <p>Counts only. No URL, attribute or text is captured, so nothing user-specific and no
 * credential can travel through these records.
 */
public sealed interface DiscoveryDiagnostics {

    /** A sentence for a tester, combining the check's own reason with what was observed. */
    String explain(String baseReason);

    /** Flat metadata for the evidence record. Values are counts and flags, never page content. */
    Map<String, String> asMetadata();

    String CONTENT_WAIT_TIMED_OUT_KEY = "discovery.contentWaitTimedOut";

    /**
     * Diagnostics for the checks that discover and classify links: Navigation and Links.
     *
     * <p>These checks resolve every href against the site address, so same-origin and
     * navigation-region counts are real measurements here.
     */
    record ForLinks(
        int anchors,
        int sameOriginAnchors,
        int navigationAnchors,
        int buttons,
        int iframes,
        boolean contentWaitTimedOut
    ) implements DiscoveryDiagnostics {

        @Override
        public String explain(String baseReason) {
            return baseReason + " Discovered " + anchors + " link(s) (" + sameOriginAnchors
                + " on this website, " + navigationAnchors + " in navigation regions), "
                + buttons + " button(s) and " + iframes + " embedded frame(s). " + cause();
        }

        private String cause() {
            if (anchors == 0 && contentWaitTimedOut) {
                return "No links appeared before the page-content timeout, so they are most likely "
                    + "drawn by JavaScript after load, or require a sign-in.";
            }
            if (anchors == 0 && iframes > 0) {
                return "The page has embedded frames, and content inside a frame is not inspected, "
                    + "so the visible links may live inside one of them.";
            }
            if (anchors == 0 && buttons > 0) {
                return "The page uses buttons rather than links, which usually means navigation "
                    + "happens in JavaScript instead of through addresses that can be followed.";
            }
            if (anchors == 0) {
                return "The page exposed no links at all.";
            }
            if (sameOriginAnchors == 0) {
                return "Every link found leaves this website, and only same-site links are followed.";
            }
            return "Every link found was excluded by the safety rules; each exclusion is listed "
                + "with its own reason.";
        }

        @Override
        public Map<String, String> asMetadata() {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("discovery.anchors", Integer.toString(anchors));
            metadata.put("discovery.sameOriginAnchors", Integer.toString(sameOriginAnchors));
            metadata.put("discovery.navigationAnchors", Integer.toString(navigationAnchors));
            metadata.put("discovery.buttons", Integer.toString(buttons));
            metadata.put("discovery.iframes", Integer.toString(iframes));
            metadata.put(CONTENT_WAIT_TIMED_OUT_KEY, Boolean.toString(contentWaitTimedOut));
            return metadata;
        }
    }

    /**
     * Diagnostics for the Forms check.
     *
     * <p>Forms never resolves hrefs against the site address, so it cannot honestly report how
     * many links are same-origin or sit in a navigation region. Those fields do not exist here,
     * and the explanation talks about forms rather than about navigation.
     */
    record ForForms(
        int anchors,
        int buttons,
        int forms,
        int iframes,
        boolean contentWaitTimedOut
    ) implements DiscoveryDiagnostics {

        @Override
        public String explain(String baseReason) {
            return baseReason + " Discovered " + forms + " form(s), " + anchors + " link(s), "
                + buttons + " button(s) and " + iframes + " embedded frame(s). " + cause();
        }

        private String cause() {
            if (forms > 0) {
                return "Forms were present and inspected.";
            }
            if (contentWaitTimedOut) {
                return "Nothing appeared before the page-content timeout, so any form is most "
                    + "likely drawn by JavaScript after load, or requires a sign-in.";
            }
            if (iframes > 0) {
                return "The page has embedded frames, and content inside a frame is not inspected, "
                    + "so a form may live inside one of them.";
            }
            return "This page has no form to inspect, which is a normal result for a page that "
                + "only presents content.";
        }

        @Override
        public Map<String, String> asMetadata() {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("discovery.anchors", Integer.toString(anchors));
            metadata.put("discovery.buttons", Integer.toString(buttons));
            metadata.put("discovery.forms", Integer.toString(forms));
            metadata.put("discovery.iframes", Integer.toString(iframes));
            metadata.put(CONTENT_WAIT_TIMED_OUT_KEY, Boolean.toString(contentWaitTimedOut));
            return metadata;
        }
    }
}
