package com.qanize.automation.engine;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.qanize.automation.checks.gamelaunch.GameCardInspector;
import com.qanize.automation.checks.gamelaunch.GameLaunchModule;
import com.qanize.automation.checks.gamelaunch.LaunchNetworkProbe;
import com.qanize.automation.checks.gamelaunch.LaunchSurfaceDetector;
import com.qanize.automation.model.TestedItem;
import com.qanize.automation.model.CheckExecution;
import com.qanize.automation.model.CheckId;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several pages' disagreeing opinions, folded into the six checks the contract reports.
 *
 * <p>Everything here is a value fold: no browser, no crawl, no artifact. What is being proved is
 * the arithmetic of "one page failed and nine passed", which is exactly the place a reporting
 * layer is most tempted to round in the wrong direction.
 */
@Tag("unit")
class CrawlRunSummaryTest {

    private static final String PAGE = "https://example.com/";

    // --- fixtures ---------------------------------------------------------------------------------

    private static CrawlerPageProcessor.ModuleOutcome outcome(CrawlerPageProcessor.Status status) {
        return new CrawlerPageProcessor.ModuleOutcome(status, null);
    }

    /** One page with the five per-page checks in the fixed order health, links, forms, nav, search. */
    private static CrawlerPageProcessor.PageAuditResult page(
        int number, CrawlerPageProcessor.Status open, CrawlerPageProcessor.Status... checks
    ) {
        return new CrawlerPageProcessor.PageAuditResult(
            new PageAuditContext(number, PAGE + number, PAGE + number, 0, null),
            outcome(open),
            outcome(checks[0]), outcome(checks[1]), outcome(checks[2]),
            outcome(checks[3]), outcome(checks[4]),
            List.of());
    }

    private static CrawlerPageProcessor.PageAuditResult allPages(
        int number, CrawlerPageProcessor.Status everyCheck
    ) {
        return page(number, CrawlerPageProcessor.Status.PASSED,
            everyCheck, everyCheck, everyCheck, everyCheck, everyCheck);
    }

    private static Crawler.CrawlResult crawlOf(int attempted) {
        List<Crawler.PageResult> results = new ArrayList<>();
        for (int index = 1; index <= attempted; index++) {
            results.add(new Crawler.PageResult(PAGE + index, 0, null,
                Crawler.PageStatus.PROCESSED, null, null));
        }
        return new Crawler.CrawlResult(results,
            new Crawler.LimitReport(10, 3, false, false, 0));
    }

    private static CrawlRunSummary crawled(
        CrawlRunner.LoginOutcome login, List<CrawlerPageProcessor.PageAuditResult> pages
    ) {
        return foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
            login, crawlOf(pages.size()), pages));
    }

    // --- 15, 18. shape and determinism ------------------------------------------------------------

    @Test
    void allSevenChecksAppearExactlyOnceInCheckIdOrder() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
            List.of(allPages(1, CrawlerPageProcessor.Status.PASSED)));

        assertEquals(List.of(CheckId.SMOKE, CheckId.NAVIGATION, CheckId.LINKS,
                CheckId.FORMS, CheckId.SEARCH, CheckId.LOGIN, CheckId.GAME_LAUNCH),
            summary.checks().stream().map(CrawlRunSummary.CheckAggregate::checkId).toList(),
            "the seven checks, in the order the product declares them, each exactly once");
        assertEquals(7, summary.checks().size());
    }

    @Test
    void theFoldDoesNotDependOnHowThePageListWasBuilt() {
        List<CrawlerPageProcessor.PageAuditResult> pages = List.of(
            allPages(1, CrawlerPageProcessor.Status.PASSED),
            allPages(2, CrawlerPageProcessor.Status.FAILED));

        CrawlRunSummary fromImmutable = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, pages);
        CrawlRunSummary fromLinkedList =
            crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, new LinkedList<>(pages));
        CrawlRunSummary fromArrayList =
            crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, new ArrayList<>(pages));

        assertEquals(fromImmutable.checks(), fromLinkedList.checks());
        assertEquals(fromImmutable.checks(), fromArrayList.checks());
    }

    // --- 3, 4, 5, 6, 7, 8. the aggregation rule ---------------------------------------------------

    @Test
    void anyFailedModuleMakesThatCheckFail() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            page(1, CrawlerPageProcessor.Status.PASSED,
                CrawlerPageProcessor.Status.PASSED, CrawlerPageProcessor.Status.FAILED,
                CrawlerPageProcessor.Status.PASSED, CrawlerPageProcessor.Status.PASSED,
                CrawlerPageProcessor.Status.PASSED)));

        assertEquals(CheckExecution.Status.FAILED, summary.check(CheckId.LINKS).status());
        assertEquals(CheckExecution.Status.PASSED, summary.check(CheckId.SMOKE).status(),
            "the other checks are unaffected");
        assertTrue(summary.hasFailure());
    }

    @Test
    void oneFailingPageWinsOverPassesOnEveryOtherPage() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            allPages(1, CrawlerPageProcessor.Status.PASSED),
            allPages(2, CrawlerPageProcessor.Status.FAILED),
            allPages(3, CrawlerPageProcessor.Status.PASSED)));

        CrawlRunSummary.CheckAggregate links = summary.check(CheckId.LINKS);
        assertEquals(CheckExecution.Status.FAILED, links.status(),
            "nine good pages do not excuse the broken one");
        assertEquals(2, links.passed());
        assertEquals(1, links.failed());
        assertEquals(3, links.considered());
    }

    @Test
    void aPassOnOnePageAndNothingToCheckOnAnotherAggregatesToPassed() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            allPages(1, CrawlerPageProcessor.Status.PASSED),
            allPages(2, CrawlerPageProcessor.Status.NOTHING_TO_CHECK)));

        CrawlRunSummary.CheckAggregate forms = summary.check(CheckId.FORMS);
        assertEquals(CheckExecution.Status.PASSED, forms.status());
        assertEquals(1, forms.passed());
        assertEquals(1, forms.skipped());
        assertEquals(0, forms.failed());
        assertFalse(summary.hasFailure());
    }

    @Test
    void onlyNothingToCheckAggregatesToSkippedWithAReason() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            allPages(1, CrawlerPageProcessor.Status.NOTHING_TO_CHECK),
            allPages(2, CrawlerPageProcessor.Status.NOTHING_TO_CHECK)));

        CrawlRunSummary.CheckAggregate forms = summary.check(CheckId.FORMS);
        assertEquals(CheckExecution.Status.SKIPPED, forms.status());
        assertEquals(0, forms.passed());
        assertFalse(forms.executed(), "nothing was actually verified");
        assertFalse(forms.skipReason().isBlank(), "a skip that cannot say why is a silent failure");
    }

    @Test
    void anUnselectedCheckStaysNotTested() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            allPages(1, CrawlerPageProcessor.Status.NOT_SELECTED)));

        CrawlRunSummary.CheckAggregate search = summary.check(CheckId.SEARCH);
        assertEquals(CheckExecution.Status.NOT_TESTED, search.status());
        assertFalse(search.selected());
        assertEquals(0, search.considered(), "an unselected check considered nothing");
    }

    @Test
    void notRunNeverBecomesPassed() {
        // Every page failed to open, so every check is NOT_RUN on every page.
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            page(1, CrawlerPageProcessor.Status.FAILED,
                CrawlerPageProcessor.Status.NOT_RUN, CrawlerPageProcessor.Status.NOT_RUN,
                CrawlerPageProcessor.Status.NOT_RUN, CrawlerPageProcessor.Status.NOT_RUN,
                CrawlerPageProcessor.Status.NOT_RUN)));

        for (CheckId checkId : List.of(CheckId.SMOKE, CheckId.LINKS, CheckId.FORMS,
                                       CheckId.NAVIGATION, CheckId.SEARCH)) {
            CrawlRunSummary.CheckAggregate check = summary.check(checkId);
            assertEquals(CheckExecution.Status.SKIPPED, check.status(),
                checkId + " never ran and must not read as a pass");
            assertEquals(0, check.passed(), checkId.toString());
            assertFalse(check.executed(), checkId.toString());
        }
    }

    @Test
    void theAggregateTypeItselfRefusesAFabricatedPass() {
        assertThrows(IllegalArgumentException.class, () -> new CrawlRunSummary.CheckAggregate(
            CheckId.LINKS, true, true, CheckExecution.Status.PASSED, 1, 0, 0, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new CrawlRunSummary.CheckAggregate(
            CheckId.LINKS, true, true, CheckExecution.Status.PASSED, 2, 1, 1, 0, 0, ""));
    }

    // --- the false-success gap: a crash is a failure, but not the website's --------------------

    /** A module that threw where a verdict was expected, isolated so crawling continued. */
    private static CrawlerPageProcessor.ModuleOutcome crashed(String message) {
        return new CrawlerPageProcessor.ModuleOutcome(
            CrawlerPageProcessor.Status.FAILED, message,
            CrawlerPageProcessor.FailureOrigin.ANALYZER_CRASH);
    }

    /** A module that ran and returned a failing verdict about the site. */
    private static CrawlerPageProcessor.ModuleOutcome websiteFailure(String message) {
        return new CrawlerPageProcessor.ModuleOutcome(
            CrawlerPageProcessor.Status.FAILED, message,
            CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT);
    }

    private static CrawlerPageProcessor.PageAuditResult pageWithLinks(
        int number, CrawlerPageProcessor.ModuleOutcome links
    ) {
        return new CrawlerPageProcessor.PageAuditResult(
            new PageAuditContext(number, PAGE + number, PAGE + number, 0, null),
            outcome(CrawlerPageProcessor.Status.PASSED),
            outcome(CrawlerPageProcessor.Status.PASSED),
            links,
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            outcome(CrawlerPageProcessor.Status.NOT_SELECTED),
            List.of());
    }

    @Test
    void aCrashedModuleIsAFailureThatIsAttributedToTheAnalyzerNotTheWebsite() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
            List.of(pageWithLinks(1, crashed("IllegalStateException: stale element"))));

        assertEquals(CheckExecution.Status.FAILED, summary.check(CheckId.LINKS).status());
        assertEquals(1, summary.check(CheckId.LINKS).crashed());
        assertTrue(summary.hasFailure(), "a crash is still a failure");
        assertTrue(summary.hasAnalyzerFailure(), "and it is ours, not the website's");
    }

    @Test
    void anOrdinaryWebsiteFailureIsNeverAttributedToTheAnalyzer() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
            List.of(pageWithLinks(1, websiteFailure("Internal links failed: 404"))));

        assertEquals(CheckExecution.Status.FAILED, summary.check(CheckId.LINKS).status());
        assertEquals(0, summary.check(CheckId.LINKS).crashed());
        assertTrue(summary.hasFailure());
        assertFalse(summary.hasAnalyzerFailure(),
            "the site returned a bad answer; the analyzer worked perfectly");
    }

    @Test
    void classificationDoesNotDependOnTheWordingOfTheMessage() {
        // Same status, same origin, wildly different text. The classification must not move.
        for (String wording : List.of(
            "IllegalStateException: stale element reference",
            "totally different wording",
            "Internal links failed: https://example.com/x returned HTTP 404",
            "")) {
            CrawlRunSummary crash = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
                List.of(pageWithLinks(1, crashed(wording))));
            CrawlRunSummary website = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
                List.of(pageWithLinks(1, websiteFailure(wording))));

            assertTrue(crash.hasAnalyzerFailure(), "crash misread as a website finding: " + wording);
            assertFalse(website.hasAnalyzerFailure(),
                "website finding misread as a crash: " + wording);
        }
    }

    @Test
    void aPageTheProcessorCouldNotHandleAtAllIsStillSeen() {
        // This is the path that actually vanished: process() threw, so the Crawler recorded a
        // failed page and the processor never produced a page result for it at all.
        Crawler.CrawlResult crawl = new Crawler.CrawlResult(
            List.of(new Crawler.PageResult(PAGE + 1, 0, null,
                Crawler.PageStatus.FAILED, "IllegalStateException: boom", null)),
            new Crawler.LimitReport(10, 3, false, false, 0));

        CrawlRunSummary summary = foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.NOT_SELECTED, crawl, List.of()));

        assertEquals(0, summary.pagesAttempted(), "no page result was ever produced for it");
        assertEquals(1, summary.pagesTheProcessorCouldNotHandle());
        assertTrue(summary.hasFailure(), "it must not disappear into a clean run");
        assertTrue(summary.hasAnalyzerFailure(), "and it is an analyzer fault, not a finding");
    }

    @Test
    void everyFailedCheckAggregateMakesTheRunUnsuccessfulWhateverTheOrigin() {
        for (CrawlerPageProcessor.ModuleOutcome failure :
            List.of(crashed("thrown"), websiteFailure("returned"))) {
            CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
                List.of(pageWithLinks(1, failure)));
            assertTrue(summary.hasFailure(),
                "a FAILED aggregate must never leave the run looking clean: " + failure);
        }
    }

    @Test
    void anOutcomeCannotClaimAnOriginItDoesNotHave() {
        // The type refuses the two dishonest shapes outright.
        assertThrows(IllegalArgumentException.class, () -> new CrawlerPageProcessor.ModuleOutcome(
            CrawlerPageProcessor.Status.FAILED, "x", CrawlerPageProcessor.FailureOrigin.NONE));
        assertThrows(IllegalArgumentException.class, () -> new CrawlerPageProcessor.ModuleOutcome(
            CrawlerPageProcessor.Status.PASSED, null,
            CrawlerPageProcessor.FailureOrigin.ANALYZER_CRASH));

        // And the two-argument shape every existing caller uses still means a website result.
        assertEquals(CrawlerPageProcessor.FailureOrigin.WEBSITE_RESULT,
            new CrawlerPageProcessor.ModuleOutcome(
                CrawlerPageProcessor.Status.FAILED, "x").failureOrigin());
        assertEquals(CrawlerPageProcessor.FailureOrigin.NONE,
            new CrawlerPageProcessor.ModuleOutcome(
                CrawlerPageProcessor.Status.NOT_RUN, null).failureOrigin());
    }

    @Test
    void anAnalyzerFailureIsAlwaysAlsoAFailure() {
        // The precedence rule as an invariant of the value rather than of one if/else chain: as
        // long as this holds, no reordering of the outcome branches in RunSummaryFactory can let
        // a crash reach NO_FINDINGS, because NO_FINDINGS requires hasFailure() to be false.
        List<CrawlRunSummary> everyAnalyzerFault = List.of(
            crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
                List.of(pageWithLinks(1, crashed("thrown")))),
            foldOfARunThatSelectedEverything(new CrawlRunner.CrawlRunResult.Crawled(
                CrawlRunner.LoginOutcome.NOT_SELECTED,
                new Crawler.CrawlResult(
                    List.of(new Crawler.PageResult(PAGE + 1, 0, null,
                        Crawler.PageStatus.FAILED, "boom", null)),
                    new Crawler.LimitReport(10, 3, false, false, 0)),
                List.of())));

        for (CrawlRunSummary summary : everyAnalyzerFault) {
            assertTrue(summary.hasAnalyzerFailure(), summary.toString());
            assertTrue(summary.hasFailure(),
                "an analyzer fault that did not also count as a failure could reach NO_FINDINGS");
        }
    }

    @Test
    void aCleanCrawlHasNeitherKindOfFailure() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.SUCCEEDED, List.of(
            allPages(1, CrawlerPageProcessor.Status.PASSED),
            allPages(2, CrawlerPageProcessor.Status.NOTHING_TO_CHECK)));

        assertFalse(summary.hasFailure());
        assertFalse(summary.hasAnalyzerFailure());
        assertEquals(0, summary.pagesTheProcessorCouldNotHandle());
    }

    // --- 9. a page that would not open ------------------------------------------------------------

    @Test
    void aPageThatWouldNotOpenIsCountedAndMakesTheRunUnsuccessful() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.NOT_SELECTED, List.of(
            allPages(1, CrawlerPageProcessor.Status.PASSED),
            page(2, CrawlerPageProcessor.Status.FAILED,
                CrawlerPageProcessor.Status.NOT_RUN, CrawlerPageProcessor.Status.NOT_RUN,
                CrawlerPageProcessor.Status.NOT_RUN, CrawlerPageProcessor.Status.NOT_RUN,
                CrawlerPageProcessor.Status.NOT_RUN)));

        assertEquals(2, summary.pagesAttempted());
        assertEquals(1, summary.pagesFailedToOpen());
        assertTrue(summary.hasFailure(), "a page the crawler could not open is a real finding");
    }

    // --- 10, 11, 12. Login is once per run --------------------------------------------------------

    @Test
    void loginIsFoldedOncePerRunRegardlessOfHowManyPagesWereCrawled() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.SUCCEEDED, List.of(
            allPages(1, CrawlerPageProcessor.Status.PASSED),
            allPages(2, CrawlerPageProcessor.Status.PASSED),
            allPages(3, CrawlerPageProcessor.Status.PASSED)));

        CrawlRunSummary.CheckAggregate login = summary.check(CheckId.LOGIN);
        assertEquals(CheckExecution.Status.PASSED, login.status());
        assertEquals(1, login.considered(), "one sign-in for the whole run, not one per page");
        assertEquals(1, login.passed());
        assertEquals(3, summary.pagesAttempted());
    }

    @Test
    void loginNotSelectedAndLoginDisabledStayDistinct() {
        CrawlRunSummary.CheckAggregate notSelected =
            crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
                List.of(allPages(1, CrawlerPageProcessor.Status.PASSED))).check(CheckId.LOGIN);
        CrawlRunSummary.CheckAggregate disabled =
            crawled(CrawlRunner.LoginOutcome.DISABLED,
                List.of(allPages(1, CrawlerPageProcessor.Status.PASSED))).check(CheckId.LOGIN);

        assertEquals(CheckExecution.Status.NOT_TESTED, notSelected.status());
        assertFalse(notSelected.selected(), "nobody asked for it");

        assertEquals(CheckExecution.Status.SKIPPED, disabled.status());
        assertTrue(disabled.selected(), "it was asked for, but its execution is switched off");
        assertFalse(disabled.skipReason().isBlank());
    }

    @Test
    void theTwoWaysASignInCanPreventARunAreDistinguishedByTypeNotByText() {
        CrawlRunSummary missingCredentials = foldOfARunThatSelectedEverything(
            CrawlRunner.CrawlRunResult.credentialsUnavailable("variables not set: A, B"));
        CrawlRunSummary refusedByTheSite = foldOfARunThatSelectedEverything(
            CrawlRunner.CrawlRunResult.signInFailed("the website did not accept the credentials"));

        assertEquals(CrawlRunner.LoginPreventedCause.CREDENTIALS_UNAVAILABLE,
            missingCredentials.login().cause());
        assertEquals(CrawlRunner.LoginPreventedCause.SIGN_IN_FAILED,
            refusedByTheSite.login().cause());

        // Both are FAILED and both prevented the crawl; only the typed cause separates them, which
        // is what lets reporting choose between "fix your configuration" and "your site refused".
        assertEquals(CheckExecution.Status.FAILED,
            missingCredentials.check(CheckId.LOGIN).status());
        assertEquals(CheckExecution.Status.FAILED,
            refusedByTheSite.check(CheckId.LOGIN).status());
        assertTrue(missingCredentials.login().preventedTheCrawl());
        assertTrue(refusedByTheSite.login().preventedTheCrawl());
    }

    @Test
    void aPreventedRunCrawledNothingAndSaysSoForEveryPageCheck() {
        CrawlRunSummary summary = foldOfARunThatSelectedEverything(
            CrawlRunner.CrawlRunResult.signInFailed("refused"));

        assertEquals(0, summary.pagesAttempted());
        assertEquals(0, summary.pagesFailedToOpen());
        for (CheckId checkId : List.of(CheckId.SMOKE, CheckId.LINKS, CheckId.FORMS,
                                       CheckId.NAVIGATION, CheckId.SEARCH)) {
            assertEquals(CheckExecution.Status.NOT_TESTED, summary.check(checkId).status(),
                checkId + " cannot have been tested when no page was opened");
        }
        assertTrue(summary.hasFailure());
    }

    @Test
    void aSelectedCheckBlockedByTheSignInStaysSelectedAndUnexecuted() {
        // The defect this pins. A run that selected Game Launch and never got past its sign-in
        // reported the check as one nobody had asked for, while the reason beside it said the
        // opposite. "We could not" and "you did not want it" are different answers.
        CrawlRunSummary summary = CrawlRunSummary.from(
            CrawlRunner.CrawlRunResult.signInFailed("refused"),
            Set.of(CheckId.LOGIN, CheckId.GAME_LAUNCH));

        CrawlRunSummary.CheckAggregate games = summary.check(CheckId.GAME_LAUNCH);
        assertTrue(games.selected(), "it was asked for; the sign-in is what stopped it");
        assertFalse(games.executed(), "and it never ran");
        assertEquals(CheckExecution.Status.NOT_TESTED, games.status());
        assertEquals("No game was opened, because the sign-in did not complete.",
            games.skipReason());
    }

    @Test
    void aGameThatWasClickedAndThenLostItsListCountsAsExecuted() {
        // The run this pins clicked a launch control on a live website, opened an iframe, and then
        // could not restore the catalogue. The game is SKIPPED -- it was not tested -- but saying
        // the check did not execute described this analyzer, not the run.
        CrawlRunSummary summary = CrawlRunSummary.from(
            crawledWith(games(gameThatWasClicked(TestedItem.Status.SKIPPED))),
            java.util.Set.of(CheckId.LOGIN, CheckId.GAME_LAUNCH));

        CrawlRunSummary.CheckAggregate launch = summary.check(CheckId.GAME_LAUNCH);
        assertTrue(launch.selected(), "it was asked for");
        assertTrue(launch.executed(), "and a launch control was clicked");
        assertEquals(CheckExecution.Status.SKIPPED, launch.status(), "the verdict is unchanged");
        assertEquals(1, launch.skipped(), "and so are the counts");
        assertEquals(0, launch.passed());
        assertEquals(0, launch.failed());
    }

    @Test
    void aGameLaunchThatNeverClickedAnythingIsStillUnexecuted() {
        // The other half. A card that was skipped before any click must not become an execution.
        CrawlRunSummary summary = CrawlRunSummary.from(
            crawledWith(games(gameThatWasNeverClicked())),
            java.util.Set.of(CheckId.LOGIN, CheckId.GAME_LAUNCH));

        CrawlRunSummary.CheckAggregate launch = summary.check(CheckId.GAME_LAUNCH);
        assertTrue(launch.selected());
        assertFalse(launch.executed(), "nothing was clicked, so nothing executed");
        assertEquals(CheckExecution.Status.SKIPPED, launch.status());
    }

    /** One game the run clicked, with the verdict the caller chooses. */
    private static GameLaunchModule.GameResult gameThatWasClicked(TestedItem.Status status) {
        return new GameLaunchModule.GameResult(
            new GameCardInspector.GameCandidate("aviator|spribe", "Aviator", "Spribe", 0, true, true),
            status, LaunchSurfaceDetector.SurfaceType.IFRAME, "the list was not restored",
            true, GameLaunchModule.SessionState.VERIFIED, false,
            LaunchNetworkProbe.Observation.NOT_REPORTED, java.util.Map.of());
    }

    /** One game that was left alone before any click. */
    private static GameLaunchModule.GameResult gameThatWasNeverClicked() {
        return new GameLaunchModule.GameResult(
            new GameCardInspector.GameCandidate("aviator|spribe", "Aviator", "Spribe", 0, false, false),
            TestedItem.Status.SKIPPED, null, "the card was gone before it could be opened",
            false, GameLaunchModule.SessionState.NOT_VERIFIED, true,
            LaunchNetworkProbe.Observation.NOT_REPORTED, java.util.Map.of());
    }

    /** A crawl that reached the games, with the launch result the caller supplies. */
    private static CrawlRunner.CrawlRunResult crawledWith(GameLaunchModule.Result games) {
        return new CrawlRunner.CrawlRunResult.Crawled(
            CrawlRunner.LoginOutcome.SUCCEEDED, crawlOf(1),
            List.of(allPages(1, CrawlerPageProcessor.Status.PASSED)), List.of(), games);
    }

    private static GameLaunchModule.Result games(GameLaunchModule.GameResult... results) {
        return new GameLaunchModule.Result(
            GameLaunchModule.RunStatus.RUN_SETUP_PROBLEM,
            "The game list could not be restored after a launch.", java.util.List.of(results));
    }

    @Test
    void aCheckNobodySelectedIsStillReportedAsUnselected() {
        // The other half. The fix must not turn every untested check into a selected one, or the
        // distinction it exists to make would be gone in the opposite direction.
        CrawlRunSummary summary = CrawlRunSummary.from(
            CrawlRunner.CrawlRunResult.signInFailed("refused"), Set.of(CheckId.LOGIN));

        CrawlRunSummary.CheckAggregate games = summary.check(CheckId.GAME_LAUNCH);
        assertFalse(games.selected(), "nobody asked for it");
        assertFalse(games.executed());
        assertEquals("Not selected for this run.", games.skipReason());
    }

    @Test
    void everyPageCheckFollowsTheSameSelectedRuleAsGameLaunch() {
        CrawlRunSummary summary = CrawlRunSummary.from(
            CrawlRunner.CrawlRunResult.signInFailed("refused"),
            Set.of(CheckId.LOGIN, CheckId.LINKS));

        assertTrue(summary.check(CheckId.LINKS).selected(), "Links was chosen and then blocked");
        assertEquals("No page was crawled, because the sign-in did not complete.",
            summary.check(CheckId.LINKS).skipReason());
        for (CheckId unchosen : List.of(CheckId.SMOKE, CheckId.FORMS,
                                        CheckId.NAVIGATION, CheckId.SEARCH)) {
            assertFalse(summary.check(unchosen).selected(), unchosen + " was never asked for");
            assertEquals("Not selected for this run.", summary.check(unchosen).skipReason());
        }
    }

    @Test
    void theFoldRefusesToGuessWhichChecksWereSelected() {
        // Stating it is the point. A null here would be the fold inventing the fact again.
        assertThrows(NullPointerException.class, () -> CrawlRunSummary.from(
            CrawlRunner.CrawlRunResult.signInFailed("refused"), null));
    }

    @Test
    void aCrawlWithNoPagesIsStillAWellFormedFoldRatherThanACrash() {
        CrawlRunSummary summary = crawled(CrawlRunner.LoginOutcome.SUCCEEDED, List.of());

        assertEquals(0, summary.pagesAttempted());
        assertEquals(7, summary.checks().size());
        for (CheckId checkId : List.of(CheckId.SMOKE, CheckId.LINKS, CheckId.FORMS,
                                       CheckId.NAVIGATION, CheckId.SEARCH)) {
            assertEquals(CheckExecution.Status.NOT_TESTED, summary.check(checkId).status(),
                checkId + " saw no page at all");
        }
        assertEquals(CheckExecution.Status.PASSED, summary.check(CheckId.LOGIN).status(),
            "the sign-in still happened, and it is a once-per-run check");
    }

    // --- 19. nothing sensitive --------------------------------------------------------------------

    @Test
    void noCredentialValueSurvivesIntoTheAggregateOrItsToString() {
        String password = "synthetic-password-9f2b";
        CrawlRunSummary summary = foldOfARunThatSelectedEverything(
            CrawlRunner.CrawlRunResult.credentialsUnavailable(
                "variables not set: QANIZE_USER, QANIZE_PASSWORD"));

        String rendered = summary.toString() + " " + summary.login().message()
            + " " + summary.check(CheckId.LOGIN).skipReason();
        assertFalse(rendered.contains(password), rendered);
        // The variable names are useful and safe; the values never travel here at all.
        assertTrue(summary.login().message().contains("QANIZE_PASSWORD"));
    }

    @Test
    void theFoldIntroducesNoStatusVocabularyOfItsOwn() {
        // The statuses are the contract's own. A crawler-specific enum here would be the start of
        // a second reporting model, which is the thing this slice must not create.
        for (CrawlRunSummary.CheckAggregate check : crawled(CrawlRunner.LoginOutcome.NOT_SELECTED,
            List.of(allPages(1, CrawlerPageProcessor.Status.PASSED))).checks()) {
            assertTrue(List.of(CheckExecution.Status.values()).contains(check.status()),
                check.status() + " is not a contract status");
        }
    }

    /**
     * The fold, for a run that asked for every product check.
     *
     * <p>Which checks were selected is now stated rather than inferred, because a run stopped by
     * its sign-in leaves no trace of what it was asked to do. These cases are about the fold's
     * other behaviour, so they say "everything" and let the selection-specific tests carry that
     * question on their own.
     */
    private static CrawlRunSummary foldOfARunThatSelectedEverything(
        CrawlRunner.CrawlRunResult result
    ) {
        return CrawlRunSummary.from(result, Set.copyOf(CheckId.productChecks()));
    }

}
