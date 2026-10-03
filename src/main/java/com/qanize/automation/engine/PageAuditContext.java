package com.qanize.automation.engine;

import com.qanize.automation.evidence.SensitiveDataFilter;

/**
 * Who one crawled page is: its place in the crawl, and the two forms of its address.
 *
 * <p><strong>Identity only.</strong> This is durable information about a page, not a scratchpad
 * for whatever a module happens to need. There is no browser here, no driver, no
 * {@code WebElement}, no element handle, no map of loose state and nothing that expires when the
 * page navigates away. Every component is an {@code int} or a {@code String}, which is what makes
 * a context safe to keep in a result long after the page it describes has been left behind — a
 * stored {@code WebElement} would be stale the moment the browser moved on.
 *
 * <p><strong>Two addresses, on purpose.</strong> {@link #url()} is raw, because opening a page
 * means opening exactly what the site linked to. {@link #displayUrl()} has been through
 * {@link SensitiveDataFilter#sanitizeUrl(String)} and is the only one that may be published: a
 * discovered link can carry a session token or reset key in its query string.
 *
 * @param pageNumber     1 for the start page, then the order pages were attempted in
 * @param url            the raw absolute address, for opening the page
 * @param displayUrl     the sanitized address, for anything a person will read
 * @param depth          0 for the start page, one more than its parent for anything else
 * @param discoveredFrom the sanitized address of the page this was found on, or null for the start
 */
public record PageAuditContext(
    int pageNumber,
    String url,
    String displayUrl,
    int depth,
    String discoveredFrom
) {

    public PageAuditContext {
        if (pageNumber < 1) {
            throw new IllegalArgumentException(
                "pageNumber must be at least 1 because the start page is page 1, but was "
                    + pageNumber);
        }
        if (depth < 0) {
            throw new IllegalArgumentException(
                "depth must not be negative because the start page is depth 0, but was " + depth);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
    }

    /**
     * The context for one page the crawler chose, numbered in attempt order.
     *
     * <p>The crawler's {@link Crawler.Page} carries raw addresses because a processor has to open
     * them; the sanitized copies are derived here, once, so nothing downstream has to remember to
     * do it.
     */
    public static PageAuditContext of(Crawler.Page page, int pageNumber) {
        return new PageAuditContext(
            pageNumber,
            page.url(),
            SensitiveDataFilter.sanitizeUrl(page.url()),
            page.depth(),
            SensitiveDataFilter.sanitizeUrl(page.discoveredFrom()));
    }

    /** Whether this is the page the crawl started from. */
    public boolean isStartPage() {
        return depth == 0;
    }

    /** Deliberately excludes the raw address, so logging a context cannot undo the sanitizing. */
    @Override
    public String toString() {
        return "PageAuditContext[pageNumber=" + pageNumber + ", url=" + displayUrl
            + ", depth=" + depth + ", discoveredFrom=" + discoveredFrom + "]";
    }
}
