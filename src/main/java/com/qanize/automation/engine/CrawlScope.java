package com.qanize.automation.engine;

/**
 * How much of the website one run visits.
 *
 * <p>A request selects an engine, not a page count, and these are the only two answers it can
 * turn into. The crawler's own budget is the customer's {@code maxPages}; a run that did not ask
 * to crawl but did ask for Game Launch still needs a signed-in browser sitting on the start page
 * and nothing beyond it — visiting ten pages of somebody else's website to open one game would be
 * work the customer never asked for.
 *
 * <p>Its own type rather than a nested one, and deliberately not a number. The CLI decides this
 * from the request and hands it to {@code CrawlerRunCoordinator}; making it a page count there
 * would put a budget decision in the class whose job is argument parsing, and the engine is the
 * only place that knows what a budget means.
 */
public enum CrawlScope {

    /** The crawl the run was configured for, under the configured page budget. */
    CONFIGURED_BUDGET,

    /** The start page and nothing past it. One page, one driver, one sign-in. */
    START_PAGE_ONLY
}
