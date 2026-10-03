package com.qanize.automation.reporting;

import com.qanize.automation.engine.CrawlRunSummary;
import com.qanize.automation.engine.CrawlRunner;
import com.qanize.automation.engine.CrawlScreenshotRecorder;
import com.qanize.automation.evidence.DiscoveryEvidence;
import com.qanize.automation.evidence.EvidenceFileReader;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;

import com.qanize.automation.model.ArtifactMetadata;
import com.qanize.automation.model.CheckId;
import com.qanize.automation.model.Evidence;
import com.qanize.automation.model.Finding;
import com.qanize.automation.model.RunError;
import com.qanize.automation.model.RunLifecycle;
import com.qanize.automation.model.RunOutcome;
import com.qanize.automation.model.TestedItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.qanize.automation.config.RunConfigurationException;
import com.qanize.automation.runtime.AutomationRunPaths;
import com.qanize.automation.runtime.AutomationRunRequest;

public final class RunSummaryFactory {

    public RunSummary configurationError(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        RunConfigurationException failure
    ) {
        Instant completedAt = Instant.now();
        return summary(
            paths, request, startedAt, completedAt, RunLifecycle.FAILED,
            RunOutcome.RUN_CONFIGURATION_ERROR,
            new RunSummary.Totals(0, 0, 0, 0, 0),
            List.of(),
            NO_FINDING_EVIDENCE,
            List.of(new RunError(
                failure.category(), failure.code(), failure.userMessage(),
                failure.suggestedAction(), completedAt
            )),
            notExecuted(request),
            List.of()
        );
    }

    public RunSummary infrastructureError(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        String code,
        String message
    ) {
        Instant completedAt = Instant.now();
        return summary(
            paths, request, startedAt, completedAt, RunLifecycle.FAILED,
            RunOutcome.INFRASTRUCTURE_ERROR,
            new RunSummary.Totals(0, 0, 0, 0, 0),
            List.of(),
            NO_FINDING_EVIDENCE,
            List.of(new RunError(
                "INFRASTRUCTURE",
                code,
                "The analyzer infrastructure could not complete the run.",
                "Verify the Java, Maven, browser, and driver installation, then retry.",
                completedAt
            )),
            notExecuted(request),
            List.of()
        );
    }

    /**
     * The run died, and the report says so without erasing what the run had already proved.
     *
     * <p>A run signed in, opened three games and wrote every one of those steps to disk, then hit
     * an exception on a fourth. The summary reported both selected checks as having produced no
     * execution record -- contradicted by the two artifact files sitting beside it. The evidence
     * is durable before this method runs, so it is read back through the reader this class already
     * uses and the checks that left records keep them. The run outcome stays
     * {@link RunOutcome#ANALYZER_INTERNAL_ERROR}: what failed is still reported as a failure.
     *
     * <p>A check with no durable item keeps the existing no-evidence representation, and evidence
     * that cannot be read at all falls back to exactly that for everything -- a report that cannot
     * be produced must never become a second failure on top of the first.
     */
    public RunSummary internalError(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        String message
    ) {
        Instant completedAt = Instant.now();
        List<TestedItem> durable = durableItems(paths);
        return summary(
            paths, request, startedAt, completedAt, RunLifecycle.FAILED,
            RunOutcome.ANALYZER_INTERNAL_ERROR,
            new RunSummary.Totals(1, 0, 0, 0, 1),
            List.of(),
            NO_FINDING_EVIDENCE,
            List.of(new RunError(
                "ANALYZER_INTERNAL",
                "ANALYZER_INTERNAL_ERROR",
                "The analyzer encountered an internal error.",
                "Review analyzer logs and retry the run.",
                completedAt,
                // The caller's fingerprint, already reduced to a class name and one frame of ours.
                // The three sentences above are unchanged: this is beside them, never instead.
                message
            )),
            withDurableEvidence(notExecuted(request), durable),
            durable
        );
    }

    /**
     * What the run had already written, or nothing at all.
     *
     * <p>{@link EvidenceFileReader} is the reader this class already uses on both success paths;
     * there is deliberately no second parser here. Anything it cannot read -- a missing file, a
     * half-written line, a directory that is gone -- yields an empty list, because a reporting
     * path that throws while reporting a failure replaces one problem with a worse one.
     */
    private static List<TestedItem> durableItems(AutomationRunPaths paths) {
        try {
            return List.copyOf(new EvidenceFileReader().read(paths.evidenceFile()).items());
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * Upgrades the no-evidence rows for the checks that did leave evidence behind.
     *
     * <p>Only those: a check with no durable item is untouched, so "we could not" and "it never
     * ran" stay different answers. The status comes from the items themselves, by the same rule
     * every other fold in this class uses -- any failure fails the check, otherwise any pass
     * passes it, otherwise it was skipped.
     */
    private static List<TestExecution> withDurableEvidence(
        List<TestExecution> executions, List<TestedItem> durable
    ) {
        if (durable.isEmpty()) return executions;
        List<TestExecution> preserved = new ArrayList<>(executions.size());
        for (TestExecution execution : executions) {
            List<TestedItem> forType = durable.stream()
                .filter(item -> execution.testType().equals(item.testType()))
                .toList();
            if (!execution.selected() || forType.isEmpty()) {
                preserved.add(execution);
                continue;
            }
            int passed = (int) forType.stream().filter(i -> i.status() == TestedItem.Status.PASSED).count();
            int failed = (int) forType.stream().filter(i -> i.status() == TestedItem.Status.FAILED).count();
            TestExecution.Status status = failed > 0
                ? TestExecution.Status.FAILED
                : passed > 0 ? TestExecution.Status.PASSED : TestExecution.Status.SKIPPED;
            preserved.add(new TestExecution(
                execution.testType(), true, passed + failed > 0, status,
                forType.size(), passed, failed, 0, 0,
                passed + failed > 0
                    ? ""
                    : "The run ended before this check reached a verdict."));
        }
        return List.copyOf(preserved);
    }

    public RunSummary cancelled(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt
    ) {
        Instant completedAt = Instant.now();
        return summary(
            paths, request, startedAt, completedAt, RunLifecycle.CANCELLED,
            RunOutcome.CANCELLED,
            new RunSummary.Totals(0, 0, 0, 0, 0),
            List.of(),
            NO_FINDING_EVIDENCE,
            List.of(new RunError(
                "CANCELLED",
                "RUN_CANCELLED",
                "The website analysis was cancelled.",
                "Start a new run when you are ready.",
                completedAt
            )),
            notExecuted(request),
            List.of()
        );
    }

    public RunSummary fromTestResults(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        TestRunSummary raw,
        int mavenExitCode
    ) {
        Instant completedAt = Instant.now();
        EvidenceFileReader.Result evidence = new EvidenceFileReader().read(paths.evidenceFile());
        List<TestedItem> testedItems = evidence.items();
        List<Finding> findings = new ArrayList<>();
        List<String> evidenceKeys = new ArrayList<>();
        List<RunError> errors = new ArrayList<>();
        int findingIndex = 1;
        for (TestRunSummary.FailedTestDetail problem : raw.failedTests()) {
            if (problem.type() == TestRunSummary.ProblemType.FAILURE) {
                findings.add(toFinding(
                    paths.runId(), request, problem, findingIndex++, completedAt, testedItems));
                evidenceKeys.add(safe(problem.testName()));
            } else {
                errors.add(new RunError(
                    "ANALYZER_INTERNAL",
                    "ANALYZER_CHECK_ERROR",
                    "An analyzer check could not be completed.",
                    "Review browser evidence and analyzer logs, then retry the run.",
                    completedAt
                ));
            }
        }

        // Unreadable evidence is a reporting problem, never a website defect.
        for (String parseError : evidence.parseErrors()) {
            errors.add(new RunError(
                "ANALYZER_INTERNAL",
                "EVIDENCE_RECORD_UNREADABLE",
                "Some execution evidence could not be read, so the tested-item list may be incomplete.",
                "Rerun the analysis. If it repeats, inspect the run's evidence file.",
                completedAt
            ));
            System.out.println("QA Evidence | parse-error | " + parseError);
        }

        RunOutcome outcome;
        RunLifecycle lifecycle;
        if (!errors.isEmpty() || raw.errors() > 0) {
            outcome = RunOutcome.ANALYZER_INTERNAL_ERROR;
            lifecycle = RunLifecycle.FAILED;
        } else if (!findings.isEmpty() || raw.failed() > 0) {
            outcome = RunOutcome.WEBSITE_FINDINGS;
            lifecycle = RunLifecycle.COMPLETED;
        } else if (raw.total() == 0 || mavenExitCode != 0) {
            outcome = RunOutcome.INFRASTRUCTURE_ERROR;
            lifecycle = RunLifecycle.FAILED;
            errors.add(new RunError(
                "INFRASTRUCTURE",
                raw.total() == 0 ? "NO_TEST_RESULTS" : "MAVEN_EXECUTION_FAILED",
                raw.total() == 0
                    ? "The analyzer completed without producing test results."
                    : "Maven exited before the analysis completed successfully.",
                "Verify the local Java, Maven, browser, and driver setup, then retry.",
                completedAt
            ));
        } else {
            outcome = RunOutcome.NO_FINDINGS;
            lifecycle = RunLifecycle.COMPLETED;
        }

        return summary(
            paths, request, startedAt, completedAt, lifecycle, outcome,
            reportedTotals(request, raw),
            findings,
            // The legacy rule, untouched: match the check name inside the screenshot file name,
            // and fall back to every screenshot when there is exactly one finding.
            (linked, screenshots) -> attachFindingEvidence(linked, evidenceKeys, screenshots),
            errors,
            testExecution(request, raw),
            testedItems
        );
    }

    /**
     * The crawler-mode sibling of {@link #fromTestResults}.
     *
     * <p>A crawl runs no Surefire, so there is no XML to read and
     * {@code raw.total() == 0} would make {@code fromTestResults} call a perfectly good run an
     * {@code INFRASTRUCTURE_ERROR}. Rather than fake a {@code TestRunSummary} — which would be a
     * lie the rest of the pipeline then reasons about — crawler mode decides its own outcome from
     * what the crawl actually did, and then hands the result to exactly the same
     * {@link #summary} assembly the legacy path uses. That shared call is what makes the two
     * summaries key-for-key identical: there is only one place that builds a {@link RunSummary}.
     *
     * <p>Everything else is unchanged and still comes from the existing artifacts: the tested
     * items and their page-scoped ids from {@code evidence.jsonl} via {@link EvidenceFileReader},
     * and the execution timeline from {@code execution-evidence.jsonl}, written by the same
     * recorders the single-page checks use.
     *
     * <p>The legacy path is untouched. This method neither calls it nor shares a line with it
     * beyond the assembly both end in.
     */
    public RunSummary fromCrawlResults(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        CrawlRunSummary crawl
    ) {
        Instant completedAt = Instant.now();
        EvidenceFileReader.Result evidence = new EvidenceFileReader().read(paths.evidenceFile());
        List<TestedItem> testedItems = evidence.items();

        List<RunError> errors = new ArrayList<>();
        for (String parseError : evidence.parseErrors()) {
            errors.add(new RunError(
                "ANALYZER_INTERNAL",
                "EVIDENCE_RECORD_UNREADABLE",
                "Some execution evidence could not be read, so the tested-item list may be incomplete.",
                "Rerun the analysis. If it repeats, inspect the run's evidence file.",
                completedAt
            ));
            System.out.println("QA Evidence | parse-error | " + parseError);
        }

        // Findings come from the recorded items, exactly as the legacy path derives them from the
        // Surefire failures: a failed tested item is the crawl's equivalent of a failed test.
        List<Finding> findings = new ArrayList<>();
        // Who each finding belongs to, recorded as it is built rather than reconstructed later:
        // the page the item was recorded against and the check that recorded it. This is the
        // whole association, and neither half of it is ever read out of a file name.
        List<FindingOwner> owners = new ArrayList<>();
        int findingIndex = 1;
        for (TestedItem item : testedItems) {
            if (item.status() != TestedItem.Status.FAILED) continue;
            findings.add(toCrawlFinding(paths.runId(), request, item, findingIndex++, completedAt));
            owners.add(new FindingOwner(
                pageNumberOf(item), checkIdFromTestType(item.testType()).orElse(null)));
        }

        RunOutcome outcome;
        RunLifecycle lifecycle;
        if (!errors.isEmpty()) {
            outcome = RunOutcome.ANALYZER_INTERNAL_ERROR;
            lifecycle = RunLifecycle.FAILED;
        } else if (crawl.hasGameLaunchSetupProblem()) {
            // A game list that could not be restored is a run-configuration problem the operator
            // can fix, so it is reported as one — never as an analyzer crash, and never as a
            // finding about the website, which did nothing wrong.
            outcome = RunOutcome.RUN_CONFIGURATION_ERROR;
            lifecycle = RunLifecycle.FAILED;
            errors.add(new RunError(
                "RUN_CONFIGURATION",
                "GAME_LAUNCH_SETUP_PROBLEM",
                // The reason the run stopped, and nothing more. A sentence about games that were
                // not attempted used to be appended here unconditionally; with a budget of one
                // game, one game attempted and none remaining, it was simply false, and nothing
                // available at this point knows how many were left.
                SensitiveDataFilter.sanitizeMessage(crawl.gameLaunchSetupProblem()),
                "Confirm the configured game selectors and start address, then rerun.",
                completedAt
            ));
        } else if (crawl.hasAnalyzerFailure()) {
            // A module that threw, or a page the processor could not handle. Both are failures,
            // and both are ours. Reporting them as website findings would hand the customer a
            // defect report about their site for a defect in this analyzer. Typed, never
            // inferred from an exception class or a message.
            outcome = RunOutcome.ANALYZER_INTERNAL_ERROR;
            lifecycle = RunLifecycle.FAILED;
            errors.add(new RunError(
                "ANALYZER_INTERNAL",
                "ANALYZER_CHECK_ERROR",
                "An analyzer check could not be completed.",
                "Review browser evidence and analyzer logs, then retry the run.",
                completedAt
            ));
        } else if (crawl.login().cause()
            == CrawlRunner.LoginPreventedCause.CREDENTIALS_UNAVAILABLE) {
            // Nothing was ever asked of the website, so this is not a finding about it. Typed,
            // not inferred from the message.
            outcome = RunOutcome.RUN_CONFIGURATION_ERROR;
            lifecycle = RunLifecycle.FAILED;
            errors.add(new RunError(
                "RUN_CONFIGURATION",
                "LOGIN_CREDENTIALS_UNAVAILABLE",
                "Login is enabled but its credential environment variables are not set.",
                "Set the configured credential environment variables, or disable Login.",
                completedAt
            ));
        } else if (crawl.hasFailure() || !findings.isEmpty()) {
            outcome = RunOutcome.WEBSITE_FINDINGS;
            lifecycle = RunLifecycle.COMPLETED;
        } else {
            // The point of this method: a completed crawl with nothing wrong is a clean run, not
            // an infrastructure error just because no Surefire XML exists.
            outcome = RunOutcome.NO_FINDINGS;
            lifecycle = RunLifecycle.COMPLETED;
        }

        return summary(
            paths, request, startedAt, completedAt, lifecycle, outcome,
            crawlTotals(crawl),
            findings,
            (linked, screenshots) ->
                attachCrawlEvidence(linked, owners, crawl.screenshots(), screenshots),
            errors,
            crawlExecution(crawl),
            testedItems
        );
    }

    /** Totals in terms of the six checks, mirroring what the legacy path reports. */
    private static RunSummary.Totals crawlTotals(CrawlRunSummary crawl) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int total = 0;
        for (CrawlRunSummary.CheckAggregate check : crawl.checks()) {
            if (!check.selected()) continue;
            total++;
            switch (check.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                default -> skipped++;
            }
        }
        return new RunSummary.Totals(total, passed, failed, skipped, 0);
    }

    /**
     * The six checks, in {@link CheckId} order, folded from the crawl.
     *
     * <p>Uses the same {@link TestExecution} record and the same status names as the legacy path;
     * only the source of the numbers differs, because the execution differs.
     */
    private static List<TestExecution> crawlExecution(CrawlRunSummary crawl) {
        List<TestExecution> executions = new ArrayList<>();
        for (CheckId checkId : TEST_TYPES) {
            CrawlRunSummary.CheckAggregate check = crawl.check(checkId);
            executions.add(new TestExecution(
                checkId.name(),
                check.selected(),
                check.executed(),
                switch (check.status()) {
                    case PASSED -> TestExecution.Status.PASSED;
                    case FAILED -> TestExecution.Status.FAILED;
                    case WARNING -> TestExecution.Status.WARNING;
                    case SKIPPED -> TestExecution.Status.SKIPPED;
                    case NOT_TESTED -> TestExecution.Status.NOT_TESTED;
                },
                check.considered(),
                check.passed(),
                check.failed(),
                0,
                check.skipped(),
                check.skipReason()
            ));
        }
        return List.copyOf(executions);
    }

    /**
     * Reports totals in terms of the checks the user selected.
     *
     * <p>The generic Maven profile always starts all six check classes; the ones that were not
     * selected stop immediately through a JUnit assumption and are reported by Surefire as
     * skipped. Publishing those raw numbers would tell the user that six checks ran when they
     * selected one, so the user-facing totals are scoped to the selected checks instead.
     */
    private static RunSummary.Totals reportedTotals(AutomationRunRequest request, TestRunSummary raw) {
        int executed = raw.passed() + raw.failed() + raw.errors();
        int total = Math.max(selectedCheckCount(request), executed);
        return new RunSummary.Totals(
            total,
            raw.passed(),
            raw.failed(),
            Math.max(0, total - executed),
            raw.errors()
        );
    }

    /**
     * The six product check types, in the order the product presents them.
     *
     * <p>Derived from {@link CheckId#productChecks()} rather than repeated here, so the
     * presentation order and the classification registry cannot drift apart. Deliberately not
     * {@code values()}: an identity registered ahead of its behaviour must not add a row to
     * {@code summary.json} before the check that fills it exists.
     */
    private static final List<CheckId> TEST_TYPES = CheckId.productChecks();

    private static boolean isSelected(AutomationRunRequest request, CheckId checkId) {
        if (request == null) return false;
        return switch (checkId) {
            case SMOKE -> request.smokeTest();
            case NAVIGATION -> request.navigationTest();
            case LINKS -> request.linksTest();
            case FORMS -> request.formsTest();
            case SEARCH -> request.searchTest();
            case LOGIN -> request.loginTest();
            // Selected by the Game Launch block rather than by a flag of its own, because the
            // block is what carries the selectors the check cannot run without.
            case GAME_LAUNCH -> request.gameLaunch().enabled();
        };
    }

    /**
     * Builds the per-test execution record from evidence only.
     *
     * <p>{@code selected} comes from the validated run configuration and {@code executed} comes
     * from the Surefire result for that test class. A selected test with no executed check is
     * reported as skipped or not tested, never as passed.
     */
    private static List<TestExecution> testExecution(AutomationRunRequest request, TestRunSummary raw) {
        List<TestExecution> executions = new ArrayList<>();
        for (CheckId checkId : TEST_TYPES) {
            String testType = checkId.name();
            if (!isSelected(request, checkId)) {
                executions.add(TestExecution.notSelected(testType));
                continue;
            }
            // An unrecognised class matches nothing, so it can no longer be attributed to a check
            // it did not run. The selected check is then reported as having produced no evidence,
            // which is an existing and honest state, rather than borrowing another suite's result.
            TestRunSummary.SuiteResult suite = raw == null ? null : raw.suites().stream()
                .filter(candidate -> CheckId.fromClassName(candidate.className())
                    .filter(id -> id == checkId)
                    .isPresent())
                .findFirst()
                .orElse(null);
            executions.add(suite == null
                ? TestExecution.noEvidence(testType)
                : TestExecution.fromSuite(testType, suite));
        }
        return List.copyOf(executions);
    }

    /** Every check type is reported as not tested when the run never reached execution. */
    private static List<TestExecution> notExecuted(AutomationRunRequest request) {
        List<TestExecution> executions = new ArrayList<>();
        for (CheckId checkId : TEST_TYPES) {
            executions.add(isSelected(request, checkId)
                ? TestExecution.noEvidence(checkId.name())
                : TestExecution.notSelected(checkId.name()));
        }
        return List.copyOf(executions);
    }

    /** Item counts are derived from the recorded items so totals always reconcile. */
    /**
     * Caps how many item records are published per check type, on each page.
     *
     * <p>A page with hundreds of links would otherwise bury the useful detail. Failures are kept
     * first, then executed passes, then a representative sample of skipped items, so the reason a
     * run failed is never the thing that gets dropped.
     *
     * <p><strong>Per page, not per run.</strong> This budget was written for the single-page check,
     * where "per check type" and "per page" are the same sentence. On a crawl they are not, and the
     * difference deleted a page: a three-page run recorded 12, 13 and 13 passing links, the cap
     * ordered all 38 by status with no idea which page they came from, and the first twenty were
     * page 1's twelve and eight of page 2's. Page 3's Links check had run, passed and been written
     * to the evidence file — and then vanished from the report entirely, because the grouping that
     * builds a page's card has nothing to build one from when a check contributes no items. A
     * customer saw a selected check silently absent from a page with no reason given anywhere.
     *
     * <p>Each page now gets its own allowance, so a later page can never be crowded out by an
     * earlier one. A run with no page scoping — every single-page run — has exactly one bucket and
     * is therefore capped precisely as it was before.
     */
    static final int MAX_DETAILED_ITEMS_PER_TYPE = 20;

    static List<TestedItem> capDetails(List<TestedItem> items) {
        if (items.isEmpty()) return items;
        List<TestedItem> kept = new ArrayList<>();
        for (CheckId checkId : TEST_TYPES) {
            String testType = checkId.name();
            List<TestedItem> forType = items.stream()
                .filter(item -> testType.equals(item.testType()))
                .toList();
            if (forType.isEmpty()) continue;
            // Grouped in first-seen order, which is the order the crawler visited the pages in, so
            // the published detail stays in page order rather than being re-sorted by status.
            Map<String, List<TestedItem>> byPage = new LinkedHashMap<>();
            for (TestedItem item : forType) {
                byPage.computeIfAbsent(pageKeyOf(item), page -> new ArrayList<>()).add(item);
            }
            for (List<TestedItem> onOnePage : byPage.values()) {
                kept.addAll(capOnePage(onOnePage));
            }
        }
        return List.copyOf(kept);
    }

    /**
     * How much of what is left after the failures is held for skipped records.
     *
     * <p>One place in four. Small enough that a page full of failures still publishes almost
     * nothing but failures, and large enough that the reasons survive: a run of 27 links with 23
     * passes and 4 skips has room for every skip, which is the case this was written for.
     */
    private static final int SKIPPED_DETAIL_SHARE = 4;

    /**
     * One page's worth of one check, trimmed to the allowance.
     *
     * <p>Under the allowance nothing is reordered at all — the items are published exactly as they
     * were recorded. Only a page that genuinely overflows is sorted, and then failures come first
     * so that what is dropped is always the least informative thing present.
     *
     * <p><strong>Passes cannot evict every skip.</strong> The order alone used to decide this, and
     * on a page of 23 passing links and 4 skipped ones the passes filled all twenty places and
     * every skip was dropped — including the two destinations recorded as {@code AUTH_REQUIRED},
     * which were the only records in that check with anything to say. Twenty rows of "Responded
     * with HTTP 200." survived instead. This method's own documentation had promised "a
     * representative sample of skipped items" since it was written; only the implementation was
     * missing.
     *
     * <p>So failures are taken first, exactly as before and without limit. What remains is shared:
     * skipped records may claim up to {@link #SKIPPED_DETAIL_SHARE}, passes take the rest, and
     * whatever either leaves unused goes to the other — so no place is ever wasted and the
     * published total is unchanged. Nothing here touches the counts: {@code reconcile} works from
     * the complete list, and {@code itemsOmitted} is still discovered minus published.
     */
    private static List<TestedItem> capOnePage(List<TestedItem> onOnePage) {
        if (onOnePage.size() <= MAX_DETAILED_ITEMS_PER_TYPE) {
            return onOnePage;
        }
        List<TestedItem> failed =
            onOnePage.stream().filter(i -> i.status() == TestedItem.Status.FAILED).toList();
        List<TestedItem> passed =
            onOnePage.stream().filter(i -> i.status() == TestedItem.Status.PASSED).toList();
        List<TestedItem> skipped =
            onOnePage.stream().filter(i -> i.status() == TestedItem.Status.SKIPPED).toList();

        List<TestedItem> ordered = new ArrayList<>(
            failed.subList(0, Math.min(MAX_DETAILED_ITEMS_PER_TYPE, failed.size())));
        int remaining = MAX_DETAILED_ITEMS_PER_TYPE - ordered.size();
        if (remaining <= 0) return List.copyOf(ordered);

        // The skipped share, rounded up so one place is held even when very little is left, and
        // never more places than there are skipped records to fill them.
        int reservedForSkips = Math.min(
            skipped.size(), (remaining + SKIPPED_DETAIL_SHARE - 1) / SKIPPED_DETAIL_SHARE);
        List<TestedItem> keptPassed = passed.subList(
            0, Math.min(passed.size(), remaining - reservedForSkips));
        // Whatever the passes did not need goes back to the skips, and the reverse is handled by
        // reservedForSkips never exceeding how many there are.
        List<TestedItem> keptSkipped =
            representativeSkips(skipped, remaining - keptPassed.size());

        ordered.addAll(keptPassed);
        ordered.addAll(keptSkipped);
        return List.copyOf(ordered);
    }

    /**
     * Up to {@code budget} skipped records, chosen so every distinct reason is represented.
     *
     * <p>"A sample" and "a representative sample" are different promises. Taking the first few in
     * recorded order can return five external links and none of the one destination that required
     * authentication — which loses exactly the reason the reader needed. So the first record of
     * each distinct reason is taken first, in recorded order, and only then is the remaining
     * budget filled with the rest, also in recorded order.
     *
     * <p>The result is returned in recorded order regardless, so the published detail never looks
     * re-sorted.
     */
    private static List<TestedItem> representativeSkips(List<TestedItem> skipped, int budget) {
        if (budget <= 0) return List.of();
        if (skipped.size() <= budget) return skipped;

        Set<String> reasonsSeen = new LinkedHashSet<>();
        Set<Integer> chosen = new LinkedHashSet<>();
        for (int index = 0; index < skipped.size() && chosen.size() < budget; index++) {
            if (reasonsSeen.add(skipReasonKeyOf(skipped.get(index)))) chosen.add(index);
        }
        for (int index = 0; index < skipped.size() && chosen.size() < budget; index++) {
            chosen.add(index);
        }
        List<TestedItem> kept = new ArrayList<>(chosen.size());
        for (int index = 0; index < skipped.size(); index++) {
            if (chosen.contains(index)) kept.add(skipped.get(index));
        }
        return List.copyOf(kept);
    }

    /**
     * What makes two skipped records the same kind of skip.
     *
     * <p>The classification the check published, when it published one — an exclusion for a link
     * that was never requested, an outcome for one that was requested and answered, or a page's
     * access. Otherwise the recorded sentence, which is the only thing left that distinguishes
     * them. Never the status, which is the same word for every record here.
     */
    private static String skipReasonKeyOf(TestedItem item) {
        Map<String, String> metadata = item.metadata();
        if (metadata != null) {
            for (String key : List.of("exclusion", LINK_OUTCOME_META, "pageAccess")) {
                String value = metadata.get(key);
                if (value != null && !value.isBlank()) return key + "=" + value;
            }
        }
        String message = item.message() == null ? item.actualResult() : item.message();
        return message == null ? "" : message.trim();
    }

    /**
     * Which page an item belongs to, for the purpose of sharing out the allowance.
     *
     * <p>Read from the page metadata the crawler's evidence scope publishes, never parsed out of an
     * identifier: {@code page-002/link-1} contains a page number, and reading it from there would
     * work right up until something is renamed, at which point two pages would quietly share one
     * allowance. An item with no page metadata — everything a single-page run records, and the
     * run-level sign-in on a crawl — shares the one legacy bucket.
     */
    private static String pageKeyOf(TestedItem item) {
        Map<String, String> metadata = item.metadata();
        if (metadata == null) return "";
        String pageId = metadata.get(EvidenceScope.META_ID);
        return pageId == null ? "" : pageId;
    }

    private static List<TestExecution> reconcile(
        List<TestExecution> executions,
        List<TestedItem> testedItems,
        List<TestedItem> detailedItems
    ) {
        if (testedItems.isEmpty()) return executions;
        List<TestExecution> reconciled = new ArrayList<>(executions.size());
        for (TestExecution execution : executions) {
            List<TestedItem> forType = testedItems.stream()
                .filter(item -> execution.testType().equals(item.testType()))
                .toList();
            int passed = (int) forType.stream().filter(i -> i.status() == TestedItem.Status.PASSED).count();
            int failed = (int) forType.stream().filter(i -> i.status() == TestedItem.Status.FAILED).count();
            int skipped = (int) forType.stream().filter(i -> i.status() == TestedItem.Status.SKIPPED).count();
            int detailed = (int) detailedItems.stream()
                .filter(item -> execution.testType().equals(item.testType())).count();
            // A synthetic "nothing was discovered" record is an explanation, not a discovered
            // target. Counting it produced "No forms were discovered" beside "Discovered: 1" for
            // the same check. It is still a real evidence record and still a real safe skip, so it
            // keeps its place in `skipped`; only the discovered total drops it.
            int discovered = (int) forType.stream().filter(item -> !isDiscoveryRecord(item)).count();
            reconciled.add(execution.withItemCounts(
                discovered, passed + failed, passed, failed, skipped,
                detailed, Math.max(0, discovered - detailed)
            ));
        }
        return List.copyOf(reconciled);
    }

    /** Whether this record explains an absence rather than describing something discovered. */
    private static boolean isDiscoveryRecord(TestedItem item) {
        Map<String, String> metadata = item.metadata();
        return metadata != null
            && "true".equals(metadata.get(DiscoveryEvidence.SYNTHETIC_META_KEY));
    }

    private static int selectedCheckCount(AutomationRunRequest request) {
        if (request == null) return 0;
        int selected = 0;
        if (request.smokeTest()) selected++;
        if (request.navigationTest()) selected++;
        if (request.linksTest()) selected++;
        if (request.searchTest()) selected++;
        if (request.formsTest()) selected++;
        if (request.loginTest()) selected++;
        return selected;
    }

    private RunSummary summary(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        Instant completedAt,
        RunLifecycle lifecycle,
        RunOutcome outcome,
        RunSummary.Totals totals,
        List<Finding> findings,
        FindingEvidence findingEvidence,
        List<RunError> errors,
        List<TestExecution> testExecution,
        List<TestedItem> testedItems
    ) {
        // Totals are reconciled from the COMPLETE list; only the detail list is capped, so the
        // published counts and the category outcome never depend on how much detail is returned.
        List<TestedItem> detailedItems = capDetails(testedItems);
        // A screenshot is captured while a check is working on a page, and the file name carries
        // the check that captured it. Attributing every screenshot to the requested address said
        // that a dashboard had been photographed when the browser was on a public home page.
        EvidenceArtifacts collected =
            collectEvidence(paths, request, testedItems);
        List<Finding> findingsWithEvidence =
            findingEvidence.attachTo(findings, collected.artifacts());
        List<ArtifactMetadata> artifacts = new ArrayList<>(List.of(
            new ArtifactMetadata(
                "run-config", "CONFIG", "Run configuration", "application/json",
                AutomationRunPaths.RUN_CONFIG_FILE_NAME
            ),
            new ArtifactMetadata(
                "progress", "PROGRESS", "Run progress", "application/json",
                AutomationRunPaths.PROGRESS_FILE_NAME
            ),
            new ArtifactMetadata(
                "summary", "SUMMARY", "Run summary", "application/json",
                AutomationRunPaths.SUMMARY_FILE_NAME
            )
        ));
        artifacts.addAll(collected.artifacts());
        return new RunSummary(
            AutomationRunRequest.CONTRACT_VERSION,
            paths.runId(),
            lifecycle,
            outcome,
            targetUrl(request),
            startedAt,
            completedAt,
            Math.max(0, Duration.between(startedAt, completedAt).toMillis()),
            totals,
            findingsWithEvidence,
            errors,
            collected.evidence(),
            artifacts,
            reconcile(testExecution, testedItems, detailedItems),
            detailedItems
        );
    }

    /**
     * Links each finding to the screenshots captured for the same check.
     *
     * <p>{@code findingEvidenceKeys} holds the internal check name for the finding at the same
     * index. Screenshot file names embed that check name, so the key is matched against the file
     * name instead of being parsed back out of the user-facing title.
     */
    private static List<Finding> attachFindingEvidence(
        List<Finding> findings,
        List<String> findingEvidenceKeys,
        List<ArtifactMetadata> artifacts
    ) {
        List<ArtifactMetadata> screenshots = artifacts.stream()
            .filter(artifact -> "SCREENSHOT".equals(artifact.type()))
            .toList();
        if (findings.isEmpty() || screenshots.isEmpty()) {
            return findings;
        }

        List<Finding> linked = new ArrayList<>(findings.size());
        for (int index = 0; index < findings.size(); index++) {
            Finding finding = findings.get(index);
            String key = index < findingEvidenceKeys.size() ? findingEvidenceKeys.get(index) : "";
            List<String> references = key.isBlank()
                ? List.of()
                : screenshots.stream()
                    .filter(artifact -> artifact.relativePath().contains(key))
                    .map(ArtifactMetadata::id)
                    .toList();
            if (references.isEmpty() && findings.size() == 1) {
                references = screenshots.stream().map(ArtifactMetadata::id).toList();
            }
            linked.add(references.isEmpty() ? finding : withEvidence(finding, references));
        }
        return List.copyOf(linked);
    }

    /**
     * How one path turns screenshots into finding evidence.
     *
     * <p>Two rules that must not drift into each other. The legacy path matches a check name
     * inside a file name and, with a single finding, attaches everything; the crawler path matches
     * typed page-and-check ownership and attaches nothing it cannot prove. Passing the rule in
     * rather than a bag of keys is what keeps them separate: {@link #summary} has no idea which
     * one it is holding, so neither can leak into the other.
     */
    @FunctionalInterface
    private interface FindingEvidence {

        List<Finding> attachTo(List<Finding> findings, List<ArtifactMetadata> screenshots);
    }

    /** For the terminal summaries that report an error and carry no findings at all. */
    private static final FindingEvidence NO_FINDING_EVIDENCE = (findings, screenshots) -> findings;

    /** Which page and which check one crawl finding came from. Both typed, neither parsed. */
    private record FindingOwner(int pageNumber, CheckId checkId) {
    }

    /**
     * Links each crawl finding to the screenshot taken for that same page and check.
     *
     * <p>Every step is an equality on typed data. The finding's page comes from the
     * {@code page.number} metadata the evidence scope published, its check from the recorded
     * {@code testType}; the screenshot's page and check come from the {@link
     * CrawlScreenshotRecorder.ScreenshotRef} the capture produced. A file name is used once, at
     * the very end, and only to find the artifact that was already collected for that exact
     * file — never to work out who owns it.
     *
     * <p>There is deliberately no fallback. The legacy rule attaches every screenshot when a run
     * produced exactly one finding, which is harmless when a run is one page and one check and
     * actively false for a crawl: it would caption a picture of page 7's search box as the
     * evidence for page 2's broken link. A crawl finding with no screenshot of its own simply
     * keeps none, and the picture stays a general run artifact.
     */
    private static List<Finding> attachCrawlEvidence(
        List<Finding> findings,
        List<FindingOwner> owners,
        List<CrawlScreenshotRecorder.ScreenshotRef> references,
        List<ArtifactMetadata> artifacts
    ) {
        if (findings.isEmpty() || references.isEmpty() || artifacts.isEmpty()) {
            return findings;
        }
        Map<String, String> artifactIdByFileName = new LinkedHashMap<>();
        for (ArtifactMetadata artifact : artifacts) {
            if ("SCREENSHOT".equals(artifact.type())) {
                artifactIdByFileName.putIfAbsent(fileNameOf(artifact.relativePath()), artifact.id());
            }
        }

        List<Finding> linked = new ArrayList<>(findings.size());
        for (int index = 0; index < findings.size(); index++) {
            Finding finding = findings.get(index);
            FindingOwner owner = index < owners.size() ? owners.get(index) : null;
            List<String> attached = owner == null
                ? List.of()
                : references.stream()
                    .filter(reference -> owns(owner, reference))
                    .map(reference -> artifactIdByFileName.get(fileNameOf(reference.fileName())))
                    .filter(artifactId -> artifactId != null)
                    .distinct()
                    .toList();
            linked.add(attached.isEmpty() ? finding : withEvidence(finding, attached));
        }
        return List.copyOf(linked);
    }

    /**
     * Whether one screenshot belongs to one finding.
     *
     * <p>A capture that wrote no file owns nothing: there is no artifact to point at, and
     * pointing at another one would be a false association. A target with no reported check —
     * a page that would not open, or something that escaped the runner — owns nothing either,
     * by design.
     */
    private static boolean owns(FindingOwner owner, CrawlScreenshotRecorder.ScreenshotRef reference) {
        if (!reference.captured() || owner.checkId() == null) {
            return false;
        }
        CheckId target = reference.checkId().orElse(null);
        if (target != owner.checkId()) {
            return false;
        }
        // A sign-in is established once for the whole run, so it has no page of its own to match.
        return reference.runLevel() || reference.pageNumber() == owner.pageNumber();
    }

    /**
     * The page a crawl item was recorded against, from the metadata the evidence scope published.
     *
     * <p>Not parsed out of the item id: the id happens to begin with the page today, and a rule
     * that depends on that would break the moment the id format changed.
     */
    private static int pageNumberOf(TestedItem item) {
        String recorded = item.metadata().get(EvidenceScope.META_NUMBER);
        if (recorded == null || recorded.isBlank()) {
            return CrawlScreenshotRecorder.RUN_LEVEL;
        }
        try {
            return Integer.parseInt(recorded.trim());
        } catch (NumberFormatException notANumber) {
            return CrawlScreenshotRecorder.RUN_LEVEL;
        }
    }

    /**
     * The last segment of a path, under either separator.
     *
     * <p>Run-relative paths are published with forward slashes, but a name arriving from a
     * Windows filesystem may not be, and an exact lookup that silently missed would read as
     * "there was no screenshot" rather than as a bug.
     */
    private static String fileNameOf(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int lastSeparator = normalized.lastIndexOf('/');
        return lastSeparator < 0 ? normalized : normalized.substring(lastSeparator + 1);
    }

    private static Finding withEvidence(Finding finding, List<String> references) {
        return new Finding(
            finding.id(),
            finding.runId(),
            finding.category(),
            finding.severity(),
            finding.title(),
            finding.description(),
            finding.pageUrl(),
            finding.expected(),
            finding.actual(),
            finding.whyItFailed(),
            finding.reproductionSteps(),
            references,
            finding.recommendation(),
            finding.timestamp()
        );
    }

    /** Links a finding to the failed item of the same category when exactly one exists. */
    private static String relatedItemId(List<TestedItem> testedItems, String category) {
        List<TestedItem> failed = testedItems.stream()
            .filter(item -> category.equals(item.testType()) && item.status() == TestedItem.Status.FAILED)
            .toList();
        return failed.size() == 1 ? failed.getFirst().id() : null;
    }

    /**
     * The crawler-mode sibling of {@link #toFinding}: a failed tested item is a finding.
     *
     * <p>Deliberately a separate method rather than a shared refactor of the legacy one. The two
     * differ in how they resolve the check — a Surefire class name versus a recorded
     * {@code testType} — and in what they log when that fails. Keeping them apart means adding
     * crawler mode cannot change one byte of a legacy summary.
     */
    private static Finding toCrawlFinding(
        String runId,
        AutomationRunRequest request,
        TestedItem item,
        int index,
        Instant timestamp
    ) {
        String reported = item.message() == null || item.message().isBlank()
            ? item.actualResult()
            : item.message();
        String actual = safeMessage(reported, "The website check did not meet its expectation.");
        Optional<CheckId> checkId = checkIdFromTestType(item.testType());
        if (checkId.isEmpty()) {
            System.out.println("QA Reporting | unclassified-item-type | " + safe(item.testType()));
        }
        String source = item.pageUrl() == null || item.pageUrl().isBlank()
            ? targetUrl(request) : item.pageUrl();
        boolean aboutOneLink = checkId.filter(id -> id == CheckId.LINKS).isPresent()
            && TestedItem.ItemType.LINK.name().equals(item.itemType())
            && item.target() != null && !item.target().isBlank();
        return new Finding(
            "finding-%03d".formatted(index),
            runId,
            checkId.map(CheckId::name).orElse(UNCLASSIFIED_CATEGORY),
            "MEDIUM",
            // A link finding names its own destination. The shared per-check title is written for
            // a check, and a check that found three broken destinations produced three findings
            // reading "One or more links did not resolve successfully" — identical rows that a
            // reader cannot tell apart and that say nothing about which link to go and fix.
            aboutOneLink
                ? linkFindingTitle(item)
                : checkId.map(RunSummaryFactory::findingTitle).orElse(UNCLASSIFIED_TITLE),
            checkId.map(RunSummaryFactory::checkDescription).orElse(UNCLASSIFIED_DESCRIPTION),
            // The page the item was recorded against, so a multi-page crawl points at the right
            // page rather than at the submitted address for every finding.
            source,
            checkId.map(RunSummaryFactory::expectedBehavior).orElse(UNCLASSIFIED_EXPECTED),
            // The destination's status belongs to the destination. Printed alone under a finding
            // whose page is the source, "Responded with HTTP 401." reads as the source page having
            // answered 401 — which it did not; it served the link perfectly well.
            aboutOneLink ? linkFindingActual(item, source, actual) : actual,
            aboutOneLink ? linkFindingActual(item, source, actual) : actual,
            aboutOneLink
                ? linkReproduction(item, source)
                : List.of(
                    "Open " + source + " using the configured viewport.",
                    "Run the " + checkId.map(CheckId::label).orElse("reported")
                        + " check with the same run configuration.",
                    "Observe the reported mismatch."
                ),
            List.of(),
            checkId.map(RunSummaryFactory::recommendation).orElse(UNCLASSIFIED_RECOMMENDATION),
            timestamp
        );
    }

    /**
     * The title for a finding about one link destination.
     *
     * <p>Distinct per destination, which is what stops two different broken links producing two
     * rows a reader cannot tell apart. Still written for the check rather than assembled from an
     * internal identifier: no class name, no method name, no item id.
     */
    private static String linkFindingTitle(TestedItem item) {
        String destination = SensitiveDataFilter.sanitizeUrl(item.target());
        return item.httpStatus() == null
            ? "The link to " + destination + " could not be requested"
            : "The link to " + destination + " returned HTTP " + item.httpStatus();
    }

    /**
     * What happened, with the source, the destination and the status each named as itself.
     *
     * <p>Three facts the report had between it and could not state together: which page the link
     * was found on, which address was requested, and what that address answered.
     */
    private static String linkFindingActual(TestedItem item, String source, String recorded) {
        String destination = SensitiveDataFilter.sanitizeUrl(item.target());
        String status = item.httpStatus() == null
            ? "could not be requested"
            : "responded with HTTP " + item.httpStatus();
        String classification = item.metadata() == null
            ? null : item.metadata().get(LINK_OUTCOME_META);
        return "The link on " + source + " to " + destination + " " + status + "."
            + (classification == null || classification.isBlank()
                ? "" : " Classified as " + classification + ".")
            + (recorded == null || recorded.isBlank() ? "" : " " + recorded);
    }

    /** Reproduction that walks source, then destination, then the answer. */
    private static List<String> linkReproduction(TestedItem item, String source) {
        String destination = SensitiveDataFilter.sanitizeUrl(item.target());
        return List.of(
            "Open " + source + " using the configured viewport.",
            "Follow the link to " + destination + ".",
            item.httpStatus() == null
                ? "Observe that the destination cannot be requested."
                : "Observe the destination respond with HTTP " + item.httpStatus() + "."
        );
    }

    /** Where the Links check publishes how it classified a response. */
    private static final String LINK_OUTCOME_META = "linkOutcome";

    /**
     * Resolves a recorded {@code testType} back to its check. Empty when unrecognised.
     *
     * <p>Scoped to the product checks, so an identity registered ahead of its behaviour resolves
     * to nothing and takes the existing unclassified path rather than reaching copy written for a
     * check that does not run yet.
     */
    private static Optional<CheckId> checkIdFromTestType(String testType) {
        if (testType == null || testType.isBlank()) return Optional.empty();
        String trimmed = testType.trim();
        for (CheckId candidate : CheckId.productChecks()) {
            if (candidate.name().equals(trimmed)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * The page a check-level finding belongs to.
     *
     * <p>It used to be the requested address, unconditionally, which is how a finding about links
     * read from a public home page came to name a private dashboard. The truthful answer is the
     * page the check's OWN failed item was observed on -- evidence the check recorded itself, at the
     * moment it recorded it. Nothing is copied from another check, and nothing is derived from the
     * Basic check's final address: a retrospective copy of one page onto every result is the
     * mistake, not the fix. With no failed item to read, the requested address stands, because a
     * finding still has to name something and a guess would be worse.
     */
    private static String findingPage(
        Optional<CheckId> checkId, AutomationRunRequest request, List<TestedItem> testedItems
    ) {
        if (checkId.isPresent() && testedItems != null) {
            for (TestedItem item : testedItems) {
                if (!checkId.get().name().equals(item.testType())) continue;
                if (item.status() != TestedItem.Status.FAILED) continue;
                String page = item.pageUrl();
                if (page != null && !page.isBlank()) return page;
            }
        }
        return targetUrl(request);
    }

    private static Finding toFinding(
        String runId,
        AutomationRunRequest request,
        TestRunSummary.FailedTestDetail problem,
        int index,
        Instant timestamp,
        List<TestedItem> testedItems
    ) {
        String actual = safeMessage(problem.message(), "The website check did not meet its expectation.");
        Optional<CheckId> checkId = CheckId.fromClassName(problem.className());
        if (checkId.isEmpty()) {
            // The failure is real and must still be reported, but it cannot be attributed to a
            // product check. Naming it after one would be a fabricated fact, so it is reported as
            // unclassified and logged where an operator can see it.
            System.out.println("QA Reporting | unclassified-check-class | " + safe(problem.className()));
        }
        String checkCategory = checkId.map(CheckId::name).orElse(UNCLASSIFIED_CATEGORY);
        return new Finding(
            "finding-%03d".formatted(index),
            runId,
            checkCategory,
            "MEDIUM",
            checkId.map(RunSummaryFactory::findingTitle).orElse(UNCLASSIFIED_TITLE),
            checkId.map(RunSummaryFactory::checkDescription).orElse(UNCLASSIFIED_DESCRIPTION),
            findingPage(checkId, request, testedItems),
            checkId.map(RunSummaryFactory::expectedBehavior).orElse(UNCLASSIFIED_EXPECTED),
            actual,
            actual,
            List.of(
                // The reproduction starts from what a person would type, which is the request --
                // not the page the redirect landed on.
                "Open " + targetUrl(request) + " using the configured viewport.",
                "Run the " + checkId.map(CheckId::label).orElse("reported")
                    + " check with the same run configuration.",
                "Observe the reported mismatch."
            ),
            List.of(),
            checkId.map(RunSummaryFactory::recommendation).orElse(UNCLASSIFIED_RECOMMENDATION),
            timestamp
        );
    }

    /**
     * How a failure whose class is not a registered check is described.
     *
     * <p>{@code findings[].category} is a free-form string in the v1.0 contract and the product UI
     * falls back to displaying the raw value, so this needs no contract change. It deliberately is
     * not one of the six check names: the previous classifier answered Smoke here, which told the
     * user a smoke check had failed when nothing of the sort had been established.
     */
    static final String UNCLASSIFIED_CATEGORY = "UNCLASSIFIED";
    private static final String UNCLASSIFIED_TITLE = "A check failed that could not be identified";
    private static final String UNCLASSIFIED_DESCRIPTION =
        "A test failed during the run, but its class is not a registered website check, so the "
            + "analyzer cannot say which check the failure belongs to.";
    private static final String UNCLASSIFIED_EXPECTED =
        "Every failure reported by the analyzer should belong to a known website check.";
    private static final String UNCLASSIFIED_RECOMMENDATION =
        "Report this run: the analyzer executed a test it does not recognise, which is an "
            + "analyzer configuration problem rather than a website defect.";

    /** User-facing titles. Internal check method names must never reach the product UI. */
    private static String findingTitle(CheckId checkId) {
        return switch (checkId) {
            case SEARCH -> "Search did not behave as configured";
            case LOGIN -> "The configured login flow did not complete";
            case LINKS -> "One or more links did not resolve successfully";
            case FORMS -> "Form detection did not meet expectations";
            case NAVIGATION -> "A configured page did not load as expected";
            case SMOKE -> "The target page did not load as expected";
            case GAME_LAUNCH -> "A game did not open as expected";
        };
    }

    private static String checkDescription(CheckId checkId) {
        return "The " + checkId.label()
            + " check ran against the target website and the observed behavior did not match "
            + "the configured expectation.";
    }

    private static String expectedBehavior(CheckId checkId) {
        return switch (checkId) {
            case SEARCH -> "The configured search field should accept the query and return results.";
            case LOGIN -> "The configured login flow should sign in and reach the success condition.";
            case LINKS -> "Every checked same-origin link should return a successful HTTP response.";
            case FORMS -> "Forms on the page should be discoverable without being submitted.";
            case NAVIGATION -> "Every configured important page should load a usable document.";
            case SMOKE -> "The target page should load a usable document within the configured timeout.";
            case GAME_LAUNCH ->
                "Every game that is opened should become usable without an error.";
        };
    }

    private static String recommendation(CheckId checkId) {
        return switch (checkId) {
            case SEARCH ->
                "Confirm the search-field selector still matches the page, then fix the search "
                    + "behavior or update the run configuration and rerun the analysis.";
            case LOGIN ->
                "Confirm the login selectors and environment references are current, then fix the "
                    + "login flow and rerun the analysis.";
            case LINKS ->
                // Deliberately not "repair or remove the failing destinations". The analyzer
                // requested the address itself, without the session, headers or interaction a
                // person brings, and a status reported that way can be authentication, a redirect
                // chain or bot handling rather than a broken page. Reporting the status is factual;
                // calling it a defect is a conclusion this evidence cannot support.
                "Open the destination in a browser and verify whether the reported status is "
                    + "reproducible, then repair it if it is.";
            case GAME_LAUNCH ->
                "Open the reported game by hand, confirm what the player sees, then "
                    + "fix the launch or update the run configuration and rerun the analysis.";
            case SMOKE, FORMS, NAVIGATION ->
                "Review the affected page and correct the reported behavior, then rerun the analysis.";
        };
    }

    /**
     * The page a screenshot was taken on, read from the check its file name names.
     *
     * <p>Screenshot files are named after the JUnit class and method that captured them, so the
     * check is recoverable from the name. Within that check, the page is the one its own items
     * recorded -- a failed item first, since a screenshot is captured on a failure. A file whose
     * check cannot be identified, or a check with no items, keeps the requested address rather than
     * borrowing another check's page.
     *
     * <p>This is attribution from evidence, not a global replacement: two checks that worked on two
     * pages get two different answers, and a run with no item evidence gets none.
     */
    private static String screenshotPage(
        String fileName, AutomationRunRequest request, List<TestedItem> testedItems
    ) {
        // "GenericLinkValidationTest-internalLinks...-20260926-150343-514.png" -> the class name is
        // everything before the first hyphen. Not CheckId.fromClassName on the whole name: that
        // splits on the last dot and would read the file extension as the class.
        String name = fileName == null ? "" : fileName;
        int firstHyphen = name.indexOf('-');
        Optional<CheckId> owner =
            CheckId.fromClassName(firstHyphen < 0 ? name : name.substring(0, firstHyphen));
        if (owner.isEmpty() || testedItems == null) return targetUrl(request);
        String fallback = null;
        for (TestedItem item : testedItems) {
            if (!owner.get().name().equals(item.testType())) continue;
            String page = item.pageUrl();
            if (page == null || page.isBlank()) continue;
            if (item.status() == TestedItem.Status.FAILED) return page;
            if (fallback == null) fallback = page;
        }
        return fallback == null ? targetUrl(request) : fallback;
    }

    private static EvidenceArtifacts collectEvidence(
        AutomationRunPaths paths, AutomationRunRequest request, List<TestedItem> testedItems
    ) {
        if (!Files.isDirectory(paths.screenshotsDirectory())) {
            return new EvidenceArtifacts(List.of(), List.of());
        }
        List<Evidence> evidence = new ArrayList<>();
        List<ArtifactMetadata> artifacts = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(1);
        try (Stream<Path> files = Files.list(paths.screenshotsDirectory())) {
            files.filter(Files::isRegularFile).sorted().forEach(file -> {
                int number = index.getAndIncrement();
                String artifactId = "screenshot-%03d".formatted(number);
                String relativePath = paths.relativeName(file);
                artifacts.add(new ArtifactMetadata(
                    artifactId,
                    "SCREENSHOT",
                    "Browser screenshot " + number,
                    mediaType(file),
                    relativePath
                ));
                evidence.add(new Evidence(
                    "evidence-%03d".formatted(number),
                    "SCREENSHOT",
                    "Browser screenshot",
                    "A redacted browser screenshot captured during the run.",
                    artifactId,
                    screenshotPage(file.getFileName().toString(), request, testedItems)
                ));
            });
        } catch (IOException failure) {
            return new EvidenceArtifacts(List.of(), List.of());
        }
        return new EvidenceArtifacts(List.copyOf(evidence), List.copyOf(artifacts));
    }

    private static String mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private static String targetUrl(AutomationRunRequest request) {
        String value = request == null ? "" : safe(request.siteUrl());
        return value.isEmpty() ? "unavailable" : value;
    }

    private static String safeMessage(String value, String fallback) {
        return value == null || value.isBlank()
            ? fallback
            : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record EvidenceArtifacts(
        List<Evidence> evidence,
        List<ArtifactMetadata> artifacts
    ) {
    }
}
