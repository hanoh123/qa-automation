package com.qanize.automation.runtime;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;
import com.qanize.automation.engine.CrawlRunSummary;
import com.qanize.automation.engine.CrawlRunner;
import com.qanize.automation.engine.CrawlScope;
import com.qanize.automation.model.CheckId;

/**
 * Runs one crawl in this process, with this run's artifact destinations installed around it.
 *
 * <p><strong>Why this is not in the CLI.</strong> The CLI parses arguments, writes progress and
 * decides an exit code. Crawler orchestration is a different job with a different failure mode:
 * a system property that is not restored, or a configuration loaded from somewhere other than
 * this run's generated file, are both invisible in the run that causes them and only surface in
 * the <em>next</em> run. Keeping that job in one small class is what makes it provable.
 *
 * <p><strong>The configuration is loaded by path, never by property.</strong>
 * {@link WebsiteConfigLoader#loadSelected()} reads the global {@code siteConfig} system property,
 * which is how the forked Maven path tells the JUnit layer which site to test. In process, that
 * property belongs to whoever set it last — possibly a previous run, possibly nobody — so this
 * class calls {@link WebsiteConfigLoader#load(java.nio.file.Path)} with
 * {@link AutomationRunPaths#propertiesConfigFile()} and touches {@code siteConfig} neither to
 * read it nor to set it. Two runs in one JVM therefore cannot exchange configurations, and there
 * is no second parser: this is the same loader, entered through the overload that already exists.
 *
 * <p><strong>The scope covers exactly the crawl.</strong> {@link RunArtifactPropertyScope} is
 * opened before the crawler can write its first piece of evidence or its first screenshot, and
 * closed by try-with-resources on every way out — a clean return, a prevented sign-in (which is
 * an ordinary typed return), a {@link RuntimeException} and an {@link Error}. The fold and the
 * summary happen after it closes, because both read this run's files through
 * {@link AutomationRunPaths} rather than through the properties.
 */
final class CrawlerRunCoordinator {

    /**
     * The one call into the crawler, so a test can watch what it was handed without a browser.
     *
     * <p>Deliberately narrower than {@link CrawlRunner}: a configuration in, a typed result out.
     * Production supplies a method reference to a real {@link CrawlRunner} and nothing else, so
     * there is no second implementation of the crawl in the production graph.
     */
    @FunctionalInterface
    interface CrawlExecution {

        /**
         * @throws RuntimeException whatever the browser, sign-in or crawl threw, never replaced
         * @throws Error propagated untouched
         */
        CrawlRunner.CrawlRunResult run(WebsiteConfig config, CrawlScope scope);
    }

    private final CrawlExecution crawl;

    /** The production coordinator: a real browser, a real sign-in, a real crawl. */
    CrawlerRunCoordinator() {
        this((config, scope) -> new CrawlRunner().run(config, scope));
    }

    CrawlerRunCoordinator(CrawlExecution crawl) {
        this.crawl = Objects.requireNonNull(crawl, "crawl must not be null");
    }

    /**
     * Runs the crawl for one request and folds it.
     *
     * @param paths this run's directories; the only place a destination or a configuration is
     *     taken from
     * @param crawlScope how much of the site this run visits. Decided by the CLI from the
     *     request and passed straight through: this class makes no page-budget decision of its
     *     own, and a run that only selected Game Launch reaches the engine asking for its start
     *     page rather than for a crawl.
     * @return the fold the summary factory turns into a {@code RunSummary}
     * @throws com.qanize.automation.config.RunConfigurationException when the generated
     *     configuration is not usable, so the CLI reports it exactly as it reports any other
     *     configuration problem
     * @throws RuntimeException whatever the crawl threw, unchanged
     */
    /** The product checks this configuration asked for, named rather than inferred. */
    private static Set<CheckId> selectedChecks(WebsiteConfig config) {
        Set<CheckId> selected = EnumSet.noneOf(CheckId.class);
        if (config.tests().smoke()) selected.add(CheckId.SMOKE);
        if (config.tests().navigation()) selected.add(CheckId.NAVIGATION);
        if (config.tests().links()) selected.add(CheckId.LINKS);
        if (config.tests().search()) selected.add(CheckId.SEARCH);
        if (config.tests().forms()) selected.add(CheckId.FORMS);
        if (config.tests().login()) selected.add(CheckId.LOGIN);
        if (config.gameLaunch().enabled()) selected.add(CheckId.GAME_LAUNCH);
        return selected;
    }

    CrawlRunSummary execute(AutomationRunPaths paths, CrawlScope crawlScope) {
        Objects.requireNonNull(paths, "paths must not be null");
        Objects.requireNonNull(crawlScope, "crawlScope must not be null");
        CrawlRunner.CrawlRunResult result;
        Set<CheckId> selected;
        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(paths)) {
            // Inside the scope on purpose: a configuration that cannot be read must still leave
            // the properties exactly as it found them.
            WebsiteConfig config = WebsiteConfigLoader.load(paths.propertiesConfigFile());
            // Asserted rather than assumed. An already-closed scope here would mean the crawl is
            // about to write its evidence and screenshots into whatever destinations the previous
            // run left behind, which is the one failure this class exists to prevent and the one
            // that would not show up until a later run.
            if (scope.isClosed()) {
                throw new IllegalStateException("Run artifact destinations were not installed");
            }
            result = crawl.run(config, crawlScope);
            // Read inside the scope, from the same configuration the crawl was handed. A run that
            // is stopped by its sign-in leaves no trace of what it was asked to do, so the fold is
            // told rather than left to guess -- guessing is what reported a blocked Game Launch as
            // a check nobody had selected.
            selected = selectedChecks(config);
        }
        return CrawlRunSummary.from(result, selected);
    }
}
