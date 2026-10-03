package com.qanize.automation.engine;

/**
 * The limits one crawl runs under.
 *
 * <p>Every value here is a brake. A crawl of a customer's website is something we do <em>to</em>
 * someone else's server, so the policy is deliberately restrictive by default and every field has
 * to be widened on purpose rather than by omission.
 *
 * <p><strong>Origin is not one of those values.</strong> A crawl only ever visits the start URL's
 * own origin, and that is a property of the {@link Crawler} rather than a setting here. Making it
 * configurable would mean offering a mode that sends a customer's crawl to third-party servers
 * that never agreed to be crawled, so the option does not exist. External links are still
 * discovered and HTTP-validated by the Links check; they are simply never opened as pages.
 *
 * <p><strong>Validated on construction.</strong> An impossible policy is rejected where it is
 * built, not discovered halfway through a run when a queue behaves oddly. There is no way to hold
 * an invalid {@code CrawlPolicy}.
 *
 * <p>This type carries no browser, no driver, no URL and no state. It is a plain value, so a
 * policy can be logged, compared and unit tested without any of the machinery it governs.
 */
public record CrawlPolicy(int maxPages, int maxDepth) {

    /**
     * The default page budget, counting the start page.
     *
     * <p>Ten is small on purpose: it is enough to prove a crawl works and small enough that an
     * accidental run against a live site is harmless.
     */
    public static final int DEFAULT_MAX_PAGES = 10;

    /**
     * The default depth budget, where the start page is depth {@code 0}.
     *
     * <p>Three levels reaches a typical section page and its children. The page budget is intended
     * to be the binding limit in practice; depth is the guard against a deep, narrow trap such as
     * a paginated archive or a calendar that links to the next month forever.
     */
    public static final int DEFAULT_MAX_DEPTH = 3;

    /**
     * @throws IllegalArgumentException when a limit could never produce a sensible crawl
     */
    public CrawlPolicy {
        // A crawl that cannot visit its own start page is a configuration mistake, not a crawl of
        // zero pages, so it is rejected rather than quietly returning an empty result.
        if (maxPages < 1) {
            throw new IllegalArgumentException(
                "maxPages must be at least 1 because the start page counts towards it, but was "
                    + maxPages);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException(
                "maxDepth must not be negative because the start page is depth 0, but was "
                    + maxDepth);
        }
    }

    /** The restrictive defaults: ten pages, three levels deep. */
    public static CrawlPolicy defaults() {
        return new CrawlPolicy(DEFAULT_MAX_PAGES, DEFAULT_MAX_DEPTH);
    }

    public CrawlPolicy withMaxPages(int newMaxPages) {
        return new CrawlPolicy(newMaxPages, maxDepth);
    }

    public CrawlPolicy withMaxDepth(int newMaxDepth) {
        return new CrawlPolicy(maxPages, newMaxDepth);
    }
}
