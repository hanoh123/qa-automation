package com.qanize.automation.checks.shared;

import java.util.Objects;

import com.qanize.automation.evidence.SensitiveDataFilter;

/**
 * The address a check asked for, and the address the browser actually has open.
 *
 * <p>A real run submitted a private dashboard, the site bounced the browser to its public home
 * page, and every link, form and navigation candidate discovered afterwards was recorded against
 * the dashboard address. The report read as proof that a dashboard had been tested when it had
 * never been opened. The cause was one line repeated in four places:
 *
 * <pre>{@code
 *   String basePage = config.baseUrl().toString();   // what was asked for
 *   page.openUrl(basePage);                          // the browser may land elsewhere
 *   new LinksModule(page, config, basePage, ...)     // ...and this is the source page
 * }</pre>
 *
 * <p>This is the replacement for that line. It opens the page and then asks the browser where it
 * is, so the source page a check reports is the document it actually read.
 *
 * <h2>Two addresses, never one</h2>
 *
 * <p>{@link #requested()} is what the user submitted and is never overwritten -- the run summary,
 * the reproduction steps and the "you asked for" line all still need it. {@link #landed()} is where
 * the browser ended up and is what a discovery belongs to. They are equal in the ordinary case,
 * which is why {@link #redirected()} exists rather than a caller comparing strings.
 *
 * <p><strong>Read once, at the open.</strong> Not later: a check that navigates away (Navigation
 * opens each destination) must not have its earlier discoveries re-attributed to wherever the
 * browser finished. The value is captured here, immediately after the open and its readiness
 * handling, and handed to the module as a plain string.
 *
 * <p><strong>It never invents an address.</strong> A browser that answers blank, answers
 * {@code about:blank}, answers a {@code data:} document, or refuses the read at all leaves the
 * requested address in place. A check still has to be attributed to something, and naming what was
 * asked for is honest in a way that a guess is not.
 *
 * <p><strong>Sanitized.</strong> A landing address is exactly where a return parameter, a session
 * value or a one-time token shows up, and this value is about to become the {@code pageUrl} of
 * every item the check produces.
 *
 * <p>It opens the page and reads one property. It holds no driver, inspects nothing, and decides
 * nothing about the page's health -- that is the Basic website check's job.
 */
public record OpenedPage(String requested, String landed) {

    /** Answers that mean "the browser is not on a document we can name". */
    private static final String[] NOT_A_PAGE = { "about:", "data:", "chrome-error:" };

    public OpenedPage {
        Objects.requireNonNull(requested, "requested must not be null");
        Objects.requireNonNull(landed, "landed must not be null");
    }

    /**
     * Opens the requested address and records where the browser ended up.
     *
     * <p>A failure to open is the caller's to handle exactly as before: it propagates, and this
     * method records nothing. Checks that contain their own navigation failure keep doing so.
     *
     * @param page      the page to open, already configured with the run's timeout
     * @param requested the address the user submitted
     */
    public static OpenedPage open(BasePage page, String requested) {
        Objects.requireNonNull(page, "page must not be null");
        page.openUrl(requested);
        return where(page, requested);
    }

    /**
     * The same reading, for a caller that has already opened the page itself.
     *
     * <p>Used by the crawler, which owns its own open so it can time it and contain its failures.
     */
    public static OpenedPage where(BasePage page, String requested) {
        Objects.requireNonNull(page, "page must not be null");
        String current;
        try {
            current = page.getCurrentUrl();
        } catch (RuntimeException unreadable) {
            // A driver that will not say where it is leaves the requested address in place rather
            // than ending the check: the check can still run, and a guess would be worse.
            return new OpenedPage(requested, requested);
        }
        return new OpenedPage(requested, usable(current) ? safe(current, requested) : requested);
    }

    private static boolean usable(String current) {
        if (current == null || current.isBlank()) return false;
        String lower = current.trim().toLowerCase(java.util.Locale.ROOT);
        for (String prefix : NOT_A_PAGE) {
            if (lower.startsWith(prefix)) return false;
        }
        return true;
    }

    /** Sanitized, and never blank: a filter that removed everything falls back to the request. */
    private static String safe(String current, String requested) {
        String sanitized = SensitiveDataFilter.sanitizeUrl(current);
        return sanitized == null || sanitized.isBlank() ? requested : sanitized;
    }

    /** Whether the browser ended up somewhere other than the address that was asked for. */
    public boolean redirected() {
        return !requested.equals(landed);
    }
}
