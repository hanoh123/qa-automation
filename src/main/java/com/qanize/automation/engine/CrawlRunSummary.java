package com.qanize.automation.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.qanize.automation.checks.gamelaunch.GameLaunchModule;
import com.qanize.automation.model.CheckExecution;
import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.TestedItem;

/**
 * One crawl, folded back into the six checks the product has always reported.
 *
 * <p>A crawl produces results per <em>page</em>; the contract reports them per <em>check</em>. This
 * is the fold between the two, and it is the only place that decides what several pages'
 * disagreeing opinions add up to.
 *
 * <p><strong>It is not a reporting model.</strong> It builds no JSON, adds no field to
 * {@code summary.json} and invents no status vocabulary: the statuses are
 * {@link CheckExecution.Status}, which the contract already uses. Turning this into the contract's
 * {@code testExecution[]} is {@code RunSummaryFactory}'s job, which is also why this class knows
 * nothing about the reporting package — the dependency runs one way, reporting to engine, and a
 * cycle would make both harder to reason about.
 *
 * <h2>The aggregation rule</h2>
 *
 * <p>Per check, across every page the crawl attempted:
 *
 * <ul>
 *   <li>never selected anywhere → {@link CheckExecution.Status#NOT_TESTED}</li>
 *   <li>failed on at least one page → {@link CheckExecution.Status#FAILED}</li>
 *   <li>otherwise passed on at least one page → {@link CheckExecution.Status#PASSED}</li>
 *   <li>otherwise (only "nothing to check", or never ran because a page would not open)
 *       → {@link CheckExecution.Status#SKIPPED}, with a reason</li>
 * </ul>
 *
 * <p>Failure wins because one broken page is a finding even when nine are fine, and a run that
 * reported "passed" while hiding it would be the reporting defect this project keeps removing.
 * {@code NOT_RUN} can never become a pass: it contributes only to the skipped count, so a check
 * that never executed cannot reach {@code PASSED}, whose precondition is at least one real pass.
 *
 * <p>Login is folded in as a once-per-run check rather than once per page, because a session is
 * established once for the whole crawl.
 *
 * <p>Deterministic by construction: the six checks are emitted in {@link CheckId#productChecks()}
 * order regardless of how the pages arrived.
 */
public record CrawlRunSummary(
    List<CheckAggregate> checks,
    LoginReport login,
    int pagesAttempted,
    int pagesFailedToOpen,
    int pagesTheProcessorCouldNotHandle,
    List<CrawlScreenshotRecorder.ScreenshotRef> screenshots,
    /** Why Game Launch stopped early, or blank. Never a website finding. */
    String gameLaunchSetupProblem
) {

    /** The pre-game-launch shape, so every existing caller compiles unchanged. */
    public CrawlRunSummary(
        List<CheckAggregate> checks,
        LoginReport login,
        int pagesAttempted,
        int pagesFailedToOpen,
        int pagesTheProcessorCouldNotHandle,
        List<CrawlScreenshotRecorder.ScreenshotRef> screenshots
    ) {
        this(checks, login, pagesAttempted, pagesFailedToOpen, pagesTheProcessorCouldNotHandle,
            screenshots, "");
    }

    /** The shape every existing caller uses: a fold that carries no pictures. */
    public CrawlRunSummary(
        List<CheckAggregate> checks,
        LoginReport login,
        int pagesAttempted,
        int pagesFailedToOpen,
        int pagesTheProcessorCouldNotHandle
    ) {
        this(checks, login, pagesAttempted, pagesFailedToOpen, pagesTheProcessorCouldNotHandle,
            List.of());
    }

    public CrawlRunSummary {
        checks = List.copyOf(checks);
        Objects.requireNonNull(login, "login must not be null");
        // Carried, not folded: the aggregation is per check, but a picture belongs to one page
        // and one check, and flattening that away is exactly what made the old association
        // impossible. Copied so the fold cannot be edited through the list it was handed.
        screenshots = List.copyOf(screenshots);
    }

    /**
     * What one check amounted to across the whole crawl.
     *
     * <p>{@code crashed} counts the pages where the module threw rather than returning a verdict.
     * Those pages are already inside {@code failed}; the separate count exists so the run-level
     * outcome can tell an analyzer fault from a website finding without reading a message.
     */
    public record CheckAggregate(
        CheckId checkId,
        boolean selected,
        boolean executed,
        CheckExecution.Status status,
        int considered,
        int passed,
        int failed,
        int skipped,
        int crashed,
        String skipReason
    ) {

        public CheckAggregate {
            Objects.requireNonNull(checkId, "checkId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (status == CheckExecution.Status.PASSED && passed <= 0) {
                throw new IllegalArgumentException(
                    "PASSED requires at least one real pass for " + checkId);
            }
            if (status == CheckExecution.Status.PASSED && failed != 0) {
                throw new IllegalArgumentException("PASSED requires no failure for " + checkId);
            }
            skipReason = skipReason == null ? "" : skipReason;
        }
    }

    /**
     * What happened about signing in, once for the run.
     *
     * <p>{@code cause} is present only when a sign-in prevented the crawl, and is typed rather
     * than inferred from {@code message}.
     */
    public record LoginReport(
        CrawlRunner.LoginOutcome outcome,
        CrawlRunner.LoginPreventedCause cause,
        String message
    ) {

        public LoginReport {
            Objects.requireNonNull(outcome, "outcome must not be null");
            message = message == null ? "" : message;
        }

        public boolean preventedTheCrawl() {
            return outcome == CrawlRunner.LoginOutcome.FAILED;
        }
    }

    /** Whether anything the crawl actually checked came back failed, for any reason. */
    public boolean hasFailure() {
        return pagesFailedToOpen > 0
            || pagesTheProcessorCouldNotHandle > 0
            || login.preventedTheCrawl()
            || checks.stream().anyMatch(check -> check.status() == CheckExecution.Status.FAILED);
    }

    /**
     * Whether any failure was the analyzer's fault rather than the website's.
     *
     * <p>Two shapes, both typed and neither inferred from a message: a module that threw where a
     * verdict was expected, and a page the processor could not handle at all — the latter having
     * left no page result behind, which is precisely why it has to be counted from the crawl
     * rather than from the per-page results.
     */
    /**
     * Whether Game Launch had to stop because the run could no longer be trusted.
     *
     * <p>Typed, and separate from {@link #hasAnalyzerFailure()}: a list that will not come back is
     * a run-configuration problem the operator can act on, not an analyzer crash and not a finding
     * about the website.
     */
    public String gameLaunchSetupProblem() {
        return gameLaunchSetupProblem == null ? "" : gameLaunchSetupProblem;
    }

    public boolean hasGameLaunchSetupProblem() {
        return !gameLaunchSetupProblem().isBlank();
    }

    public boolean hasAnalyzerFailure() {
        return pagesTheProcessorCouldNotHandle > 0
            || checks.stream().anyMatch(check -> check.crashed() > 0);
    }

    public CheckAggregate check(CheckId checkId) {
        return checks.stream()
            .filter(candidate -> candidate.checkId() == checkId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no aggregate for " + checkId));
    }

    // --- the fold ---------------------------------------------------------------------------------

    /**
     * Folds one run — crawled or prevented — into the six checks.
     *
     * @param selectedChecks the checks the run was asked to perform. A prevented run never gets
     *     far enough for any of them to leave a trace, so this cannot be inferred from the result
     *     and must be stated. Reporting a check the operator chose as one they did not choose is
     *     how a blocked Game Launch came back as "NOT SELECTED".
     */
    public static CrawlRunSummary from(
        CrawlRunner.CrawlRunResult result, Set<CheckId> selectedChecks
    ) {
        Objects.requireNonNull(result, "result must not be null");
        Set<CheckId> selected = Set.copyOf(
            Objects.requireNonNull(selectedChecks, "selectedChecks must not be null"));
        return switch (result) {
            case CrawlRunner.CrawlRunResult.LoginPrevented prevented ->
                fromPrevented(prevented, selected);
            case CrawlRunner.CrawlRunResult.Crawled crawled -> fromCrawled(crawled);
        };
    }

    private static CrawlRunSummary fromPrevented(
        CrawlRunner.CrawlRunResult.LoginPrevented prevented, Set<CheckId> selected
    ) {
        // No page was opened, so every page check is untested rather than skipped-by-choice.
        List<CheckAggregate> checks = new ArrayList<>();
        for (CheckId checkId : CheckId.productChecks()) {
            // Selected and blocked is not the same as never selected, and only the first of those
            // deserves a reason. A check nobody chose is reported exactly as it always was.
            boolean chosen = selected.contains(checkId);
            checks.add(switch (checkId) {
                case LOGIN -> loginAggregate(prevented.login());
                // The game list lives behind the sign-in, so a prevented sign-in means the check
                // never had anything to open. Untested, never skipped-by-choice.
                case GAME_LAUNCH -> new CheckAggregate(checkId, chosen, false,
                    CheckExecution.Status.NOT_TESTED, 0, 0, 0, 0, 0,
                    chosen
                        ? "No game was opened, because the sign-in did not complete."
                        : "Not selected for this run.");
                default -> new CheckAggregate(checkId, chosen, false,
                    CheckExecution.Status.NOT_TESTED, 0, 0, 0, 0, 0,
                    chosen
                        ? "No page was crawled, because the sign-in did not complete."
                        : "Not selected for this run.");
            });
        }
        return new CrawlRunSummary(
            checks,
            new LoginReport(prevented.login(), prevented.cause(), prevented.message()),
            0, 0, 0,
            prevented.screenshots(),
            "");
    }

    private static CrawlRunSummary fromCrawled(CrawlRunner.CrawlRunResult.Crawled crawled) {
        List<CrawlerPageProcessor.PageAuditResult> pages = crawled.pages();

        Map<CheckId, Tally> tallies = new EnumMap<>(CheckId.class);
        for (CheckId checkId : PAGE_CHECKS) {
            tallies.put(checkId, new Tally());
        }
        int failedToOpen = 0;
        for (CrawlerPageProcessor.PageAuditResult page : pages) {
            if (!page.opened()) {
                failedToOpen++;
            }
            tallies.get(CheckId.SMOKE).add(page.pageHealth());
            tallies.get(CheckId.LINKS).add(page.links());
            tallies.get(CheckId.FORMS).add(page.forms());
            tallies.get(CheckId.NAVIGATION).add(page.navigation());
            tallies.get(CheckId.SEARCH).add(page.search());
        }

        // A page the processor could not handle at all threw out of process(), so the Crawler
        // recorded it and the processor never produced a page result for it. It is therefore
        // invisible in pages() above, and counting it here is the only way it is seen at all.
        int unhandled = (int) crawled.crawl().attempted().stream()
            .filter(attempt -> attempt.status() == Crawler.PageStatus.FAILED)
            .count();

        List<CheckAggregate> checks = new ArrayList<>();
        for (CheckId checkId : CheckId.productChecks()) {
            checks.add(switch (checkId) {
                case LOGIN -> loginAggregate(crawled.login());
                case GAME_LAUNCH -> gameLaunchAggregate(crawled.gameLaunch());
                default -> tallies.get(checkId).toAggregate(checkId);
            });
        }
        return new CrawlRunSummary(
            checks,
            new LoginReport(crawled.login(), null, ""),
            pages.size(),
            failedToOpen,
            unhandled,
            crawled.screenshots(),
            crawled.gameLaunch() == null || crawled.gameLaunch().completed()
                ? ""
                : crawled.gameLaunch().setupProblem());
    }

    /** The five checks a crawl runs per page. Login is once per run and folded separately. */
    private static final List<CheckId> PAGE_CHECKS =
        List.of(CheckId.SMOKE, CheckId.LINKS, CheckId.FORMS, CheckId.NAVIGATION, CheckId.SEARCH);

    /**
     * Game Launch, folded once for the run rather than once per page.
     *
     * <p>Like Login, and for the same reason: a game is opened from the one list the run signed in
     * to, not from each page the crawler happened to visit. A run that did not select the check
     * reports it untested; a run whose list could not be discovered or restored reports every game
     * it had as skipped, and the surrounding configuration error is what tells the reader why.
     */
    private static CheckAggregate gameLaunchAggregate(GameLaunchModule.Result games) {
        if (games == null) {
            return new CheckAggregate(CheckId.GAME_LAUNCH, false, false,
                CheckExecution.Status.NOT_TESTED, 0, 0, 0, 0, 0, "");
        }
        int passed = count(games, TestedItem.Status.PASSED);
        int failed = count(games, TestedItem.Status.FAILED);
        int skipped = count(games, TestedItem.Status.SKIPPED);
        int considered = games.games().size();
        // Whether a launch control was actually clicked, not whether a verdict was reached. A game
        // whose click opened a surface and whose cleanup then failed is SKIPPED -- it was not
        // tested -- but the check plainly ran, and reporting "not executed" for a run that clicked
        // a control on a live website said something about this analyzer that was not true.
        boolean executed = games.games().stream()
            .anyMatch(GameLaunchModule.GameResult::launchClicked);
        CheckExecution.Status status;
        String reason = "";
        if (failed > 0) {
            status = CheckExecution.Status.FAILED;
        } else if (passed > 0) {
            status = CheckExecution.Status.PASSED;
        } else {
            status = CheckExecution.Status.SKIPPED;
            reason = games.completed()
                ? "No game on the list could be opened."
                : "The game list could not be used, so no game was opened.";
        }
        return new CheckAggregate(CheckId.GAME_LAUNCH, true, executed, status,
            considered, passed, failed, skipped, 0, reason);
    }

    private static int count(GameLaunchModule.Result games, TestedItem.Status status) {
        return (int) games.games().stream().filter(game -> game.status() == status).count();
    }

    /** The explanation lives in {@link LoginReport#message} and in the findings, not here. */
    private static CheckAggregate loginAggregate(CrawlRunner.LoginOutcome outcome) {
        return switch (outcome) {
            case NOT_SELECTED -> new CheckAggregate(CheckId.LOGIN, false, false,
                CheckExecution.Status.NOT_TESTED, 0, 0, 0, 0, 0, "Not selected for this run.");
            case DISABLED -> new CheckAggregate(CheckId.LOGIN, true, false,
                CheckExecution.Status.SKIPPED, 1, 0, 0, 1, 0,
                "Login execution is disabled by login.enabled=false.");
            case SUCCEEDED -> new CheckAggregate(CheckId.LOGIN, true, true,
                CheckExecution.Status.PASSED, 1, 1, 0, 0, 0, "");
            // No skip reason: this check did not skip, it failed. Putting the failure text into
            // skipReason would surface it as testExecution[].skippedReason, which reads as "here
            // is why we did not look" for something we did look at.
            // A sign-in that did not succeed is a result about the website, so crashed stays 0.
            // A sign-in that threw never reaches here: it escapes CrawlRunner as an exception.
            case FAILED -> new CheckAggregate(CheckId.LOGIN, true, true,
                CheckExecution.Status.FAILED, 1, 0, 1, 0, 0, "");
            // Never reaches a result: the sign-in has either happened or prevented the run.
            case REQUIRED -> throw new IllegalArgumentException(
                "REQUIRED is a decision state and cannot describe a finished run");
        };
    }

    /** Counts one check's per-page statuses, then decides what they add up to. */
    private static final class Tally {

        private int considered;
        private int passed;
        private int failed;
        private int skipped;
        private int crashed;
        private boolean everSelected;

        void add(CrawlerPageProcessor.ModuleOutcome outcome) {
            switch (outcome.status()) {
                case NOT_SELECTED -> { }
                case PASSED -> { everSelected = true; considered++; passed++; }
                case FAILED -> {
                    everSelected = true;
                    considered++;
                    failed++;
                    // Typed, from the outcome itself. A crash is still a failure; it is simply
                    // not the website's failure.
                    if (outcome.crashed()) crashed++;
                }
                // Ran and found nothing of its kind, or could not run because the page never
                // opened. Neither is a pass, and neither may ever become one.
                case NOTHING_TO_CHECK, NOT_RUN -> { everSelected = true; considered++; skipped++; }
            }
        }

        CheckAggregate toAggregate(CheckId checkId) {
            if (!everSelected) {
                return new CheckAggregate(checkId, false, false, CheckExecution.Status.NOT_TESTED,
                    0, 0, 0, 0, 0, "Not selected for this run.");
            }
            if (failed > 0) {
                return new CheckAggregate(checkId, true, true, CheckExecution.Status.FAILED,
                    considered, passed, failed, skipped, crashed, "");
            }
            if (passed > 0) {
                return new CheckAggregate(checkId, true, true, CheckExecution.Status.PASSED,
                    considered, passed, 0, skipped, 0, "");
            }
            return new CheckAggregate(checkId, true, false, CheckExecution.Status.SKIPPED,
                considered, 0, 0, skipped, 0,
                "No page offered anything for this check to inspect.");
        }
    }
}
