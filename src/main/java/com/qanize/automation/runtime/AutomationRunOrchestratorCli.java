package com.qanize.automation.runtime;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.qanize.automation.checks.gamelaunch.WebsiteGameLaunchConfig;
import com.qanize.automation.config.RunConfigurationException;
import com.qanize.automation.engine.CrawlRunSummary;
import com.qanize.automation.engine.CrawlScope;
import com.qanize.automation.model.RunOutcome;
import com.qanize.automation.reporting.ExecutionTimelineJsonWriter;
import com.qanize.automation.reporting.RunSummary;
import com.qanize.automation.reporting.RunSummaryFactory;
import com.qanize.automation.reporting.RunSummaryJsonWriter;
import com.qanize.automation.reporting.SurefireResultReader;
import com.qanize.automation.reporting.TestRunSummary;

public final class AutomationRunOrchestratorCli {

    /** Frames from this project may be named; frames from anyone else's code may not. */
    private static final String QANIZE_PACKAGE = "com.qanize.";

    private AutomationRunOrchestratorCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, new ProcessBuilderMavenRunner());
    }

    static int run(String[] args, PrintStream out, PrintStream err, MavenRunner mavenRunner) {
        return run(args, out, err, mavenRunner, new CrawlerRunCoordinator());
    }

    /**
     * The one entry point that knows about both execution paths.
     *
     * <p>Both collaborators are always supplied and exactly one of them is ever used, which is
     * what makes "no request runs both" something a test can observe rather than something the
     * reader has to trust: a test hands in a counting Maven runner and a counting coordinator and
     * reads both counts afterwards.
     */
    static int run(
        String[] args,
        PrintStream out,
        PrintStream err,
        MavenRunner mavenRunner,
        CrawlerRunCoordinator crawlerCoordinator
    ) {
        Instant startedAt = Instant.now();
        Map<String, String> arguments;
        AutomationRunPaths paths;
        AutomationRunRequest request;
        try {
            arguments = parseArgs(args);
            paths = AutomationRunPaths.forRunId(value(arguments, "runId"));
            request = requestFrom(arguments);
            Files.createDirectories(paths.runDirectory());
            Files.createDirectories(paths.surefireReportsDirectory());
            Files.createDirectories(paths.screenshotsDirectory());
        } catch (IllegalArgumentException | IOException failure) {
            err.println(safeMessage(failure));
            return 2;
        }

        RuntimeConfigGenerator configGenerator = new RuntimeConfigGenerator();
        RunProgressJsonWriter progressWriter = new RunProgressJsonWriter();
        RunSummaryJsonWriter summaryWriter = new RunSummaryJsonWriter();
        RunSummaryFactory summaryFactory = new RunSummaryFactory();
        int totalChecks = selectedCheckCount(request);

        try {
            progressWriter.write(paths, progress(
                paths,
                request,
                startedAt,
                RunProgress.State.CONFIGURING,
                "VALIDATING_CONFIGURATION",
                0,
                totalChecks,
                "Validating the Website Analyzer run configuration."
            ));
            configGenerator.writeRequestSnapshotIfAbsent(request, paths.runConfigFile());
            configGenerator.generate(request, paths.propertiesConfigFile());

            progressWriter.write(paths, progress(
                paths,
                request,
                startedAt,
                RunProgress.State.RUNNING,
                "RUNNING_CHECKS",
                0,
                totalChecks,
                "Running the selected website checks."
            ));

            // The fork in the road, and the only one. Everything above this line is shared by
            // both paths: the request is parsed, the run directory exists and the generated
            // configuration has been written. Everything below belongs to one path or the other,
            // and the crawler branch returns rather than falling through, so there is no
            // arrangement of arguments that reaches Maven after reaching the crawler.
            if (request.crawlerEnabled() || request.gameLaunch().enabled()) {
                // Game Launch needs a signed-in browser, and this is the only path that owns one.
                // A request that selected it without selecting the crawler therefore comes here
                // too — it is not a reason to fork Maven as well, which would open a second
                // browser — but it asks for its start page rather than for a crawl, so turning
                // the check on never turns a one-page run into a ten-page one.
                CrawlScope crawlScope = request.crawlerEnabled()
                    ? CrawlScope.CONFIGURED_BUDGET
                    : CrawlScope.START_PAGE_ONLY;
                return runCrawler(
                    out, paths, request, startedAt, crawlerCoordinator, crawlScope,
                    summaryFactory, summaryWriter, progressWriter, totalChecks);
            }

            int mavenExitCode;
            try {
                mavenExitCode = mavenRunner.run(AutomationRunMavenCommand.genericTestCommand(paths));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                RunSummary summary = summaryFactory.cancelled(paths, request, startedAt);
                summaryWriter.write(paths, summary);
                writeFinalProgress(progressWriter, paths, request, startedAt, summary);
                printSummary(out, summary);
                err.println("The website analysis was interrupted.");
                return 130;
            } catch (IOException failure) {
                RunSummary summary = summaryFactory.infrastructureError(
                    paths,
                    request,
                    startedAt,
                    "MAVEN_PROCESS_START_FAILED",
                    safeMessage(failure)
                );
                summaryWriter.write(paths, summary);
                writeFinalProgress(progressWriter, paths, request, startedAt, summary);
                printSummary(out, summary);
                err.println(summary.errors().getFirst().userMessage());
                return 3;
            }

            progressWriter.write(paths, progress(
                paths,
                request,
                startedAt,
                RunProgress.State.FINALIZING,
                "COLLECTING_RESULTS",
                0,
                totalChecks,
                "Collecting findings and evidence."
            ));
            TestRunSummary raw = new SurefireResultReader().read(paths.surefireReportsDirectory());
            RunSummary summary = summaryFactory.fromTestResults(
                paths,
                request,
                startedAt,
                raw,
                mavenExitCode
            );
            summaryWriter.write(paths, summary);
            // Additive projection of the contract 1.1 execution evidence, written only after the
            // contract 1.0 summary is already durable. The Maven child process has exited by now,
            // so execution-evidence.jsonl is complete. This call cannot throw, so a timeline
            // problem can never reach the RuntimeException handler below and rewrite the outcome.
            new ExecutionTimelineJsonWriter().writeIfPossible(paths);
            writeFinalProgress(progressWriter, paths, request, startedAt, summary);
            printSummary(out, summary);
            return completed(summary.outcome()) ? 0 : 3;
        } catch (RunConfigurationException failure) {
            configGenerator.writeRequestSnapshotIfAbsent(request, paths.runConfigFile());
            RunSummary summary = summaryFactory.configurationError(paths, request, startedAt, failure);
            summaryWriter.write(paths, summary);
            writeFinalProgress(progressWriter, paths, request, startedAt, summary);
            printSummary(out, summary);
            err.println(failure.userMessage());
            return 2;
        } catch (RuntimeException failure) {
            RunSummary summary = summaryFactory.internalError(
                paths,
                request,
                startedAt,
                safeFingerprint(failure)
            );
            summaryWriter.write(paths, summary);
            writeFinalProgress(progressWriter, paths, request, startedAt, summary);
            printSummary(out, summary);
            err.println(summary.errors().getFirst().userMessage());
            return 3;
        }
    }

    /**
     * The crawler path: one in-process crawl, then the same reporting the legacy path performs.
     *
     * <p>No Maven process is started and no Surefire report is read — there is none to read, which
     * is exactly why {@link RunSummaryFactory#fromCrawlResults} exists rather than a faked
     * {@code TestRunSummary}. Everything after the crawl is the legacy sequence unchanged:
     * collecting progress, the contract 1.0 summary, the additive timeline, terminal progress, the
     * printed summary, and the same exit-code rule.
     *
     * <p>Nothing is caught here. A configuration problem and a {@link RuntimeException} belong to
     * the handlers the legacy path already has, so both paths report a failure the same way and
     * neither can leave progress non-terminal.
     */
    private static int runCrawler(
        PrintStream out,
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        CrawlerRunCoordinator crawlerCoordinator,
        CrawlScope crawlScope,
        RunSummaryFactory summaryFactory,
        RunSummaryJsonWriter summaryWriter,
        RunProgressJsonWriter progressWriter,
        int totalChecks
    ) {
        try {
            CrawlRunSummary crawl = crawlerCoordinator.execute(paths, crawlScope);

            progressWriter.write(paths, progress(
                paths,
                request,
                startedAt,
                RunProgress.State.FINALIZING,
                "COLLECTING_RESULTS",
                0,
                totalChecks,
                "Collecting findings and evidence."
            ));
            RunSummary summary = summaryFactory.fromCrawlResults(paths, request, startedAt, crawl);
            summaryWriter.write(paths, summary);
            // Same additive projection, same guarantee: it cannot throw, so a timeline problem can
            // never reach a handler and rewrite the outcome the crawl actually produced.
            new ExecutionTimelineJsonWriter().writeIfPossible(paths);
            writeFinalProgress(progressWriter, paths, request, startedAt, summary);
            printSummary(out, summary);
            return completed(summary.outcome()) ? 0 : 3;
        } catch (Error fatal) {
            // The one Error boundary in this class, and it exists for one reason: a run that is
            // never finalised stays RUNNING forever, and whatever is watching this run has no way
            // to learn that it stopped. So the run is closed out and the Error is then rethrown
            // by identity -- it is not wrapped, not downgraded to an exit code, not given a cause
            // or a suppressed exception, and nothing about it is inspected beyond its type.
            //
            // Narrow on purpose. It wraps the crawler lifecycle and its reporting and nothing
            // else: the legacy branch returns before this method is entered, and no module, no
            // check and no part of the crawl algorithm gains a catch from it. RuntimeException is
            // deliberately not caught here -- it already has a handler that reports it, and
            // catching it twice would change what that handler reports.
            reportFatalCrawlerError(
                paths, request, startedAt, summaryFactory, summaryWriter, progressWriter, fatal);
            throw fatal;
        }
    }

    /**
     * Closes out a run that an {@link Error} ended, without ever displacing that {@link Error}.
     *
     * <p>Two independent attempts, in the order that matters: the summary first, because it is
     * the artifact the contract is about, then terminal progress, because a watcher that only
     * reads progress must still learn the run is over. The second attempt happens even when the
     * first failed, which is why they are not one try block.
     *
     * <p>Everything either attempt can raise is swallowed here -- including {@link Error}, since
     * an out-of-memory condition inside the reporting of an out-of-memory condition must not
     * become the thing the caller sees. {@code Throwable} is not caught: the two branches it adds
     * over {@code RuntimeException | Error} are checked exceptions that nothing on this path
     * declares, so catching it would only hide a compiler error.
     *
     * <p>Nothing from the {@link Error} is read except its type. Its message can quote anything
     * the browser, the page or the site configuration was holding, so it is not passed to the
     * summary, not printed, not logged, and not attached to anything: the reported failure is the
     * analyzer's own fixed wording, chosen by the type of the branch rather than by parsing a
     * message.
     */
    private static void reportFatalCrawlerError(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        RunSummaryFactory summaryFactory,
        RunSummaryJsonWriter summaryWriter,
        RunProgressJsonWriter progressWriter,
        Error fatal
    ) {
        try {
            summaryWriter.write(paths, summaryFactory.internalError(
                paths, request, startedAt, analyzerStoppedDescription(fatal)));
        } catch (RuntimeException | Error ignored) {
            // Intentionally empty. A reporting problem is not the failure this run had.
        }
        try {
            // Stated outright rather than derived from the summary, because the summary may not
            // exist: a terminal state that is only reachable when the previous attempt succeeded
            // is exactly the branch that is missing on the day it is needed. There are no counts
            // to report either -- a run that stopped completed no checks -- so the only thing
            // this says is that it is over, and how it ended.
            progressWriter.write(paths, progress(
                paths,
                request,
                startedAt,
                RunProgress.State.FAILED,
                "FINISHED",
                0,
                selectedCheckCount(request),
                finalMessage(RunOutcome.ANALYZER_INTERNAL_ERROR)
            ));
        } catch (RuntimeException | Error ignored) {
            // Intentionally empty, for the same reason.
        }
    }

    /**
     * What is said about an {@link Error}: its type, and nothing it was carrying.
     *
     * <p>{@link RunSummaryFactory#internalError} does not currently echo this text into the
     * summary at all -- the reported wording is fixed there. It is built safely anyway, so that
     * the day someone does start echoing it, there is nothing here to leak.
     */
    private static String analyzerStoppedDescription(Error fatal) {
        return "The analyzer stopped with " + fatal.getClass().getName() + ".";
    }

    private static RunProgress progress(
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        RunProgress.State state,
        String phase,
        int completed,
        int total,
        String message
    ) {
        return RunProgress.of(
            paths.runId(),
            state,
            phase,
            request.siteUrl(),
            completed,
            total,
            startedAt,
            message
        );
    }

    private static void writeFinalProgress(
        RunProgressJsonWriter writer,
        AutomationRunPaths paths,
        AutomationRunRequest request,
        Instant startedAt,
        RunSummary summary
    ) {
        RunProgress.State state = switch (summary.lifecycle()) {
            case COMPLETED -> RunProgress.State.COMPLETED;
            case FAILED -> RunProgress.State.FAILED;
            case CANCELLED -> RunProgress.State.CANCELLED;
        };
        int completed = summary.totals().passed()
            + summary.totals().failed()
            + summary.totals().errors();
        completed = Math.min(completed, selectedCheckCount(request));
        writer.write(paths, progress(
            paths,
            request,
            startedAt,
            state,
            "FINISHED",
            completed,
            selectedCheckCount(request),
            finalMessage(summary.outcome())
        ));
    }

    private static String finalMessage(RunOutcome outcome) {
        return switch (outcome) {
            case NO_FINDINGS -> "Analysis completed with no findings.";
            case WEBSITE_FINDINGS -> "Analysis completed with website findings.";
            case RUN_CONFIGURATION_ERROR -> "The run configuration must be corrected.";
            case ANALYZER_INTERNAL_ERROR -> "The analyzer encountered an internal error.";
            case INFRASTRUCTURE_ERROR -> "The local analysis infrastructure failed.";
            case CANCELLED -> "The analysis was cancelled.";
        };
    }

    private static boolean completed(RunOutcome outcome) {
        return outcome == RunOutcome.NO_FINDINGS || outcome == RunOutcome.WEBSITE_FINDINGS;
    }

    private static void printSummary(PrintStream out, RunSummary summary) {
        out.println("Run ID: " + summary.runId());
        out.println("Outcome: " + summary.outcome());
        out.println("Total: " + summary.totals().total());
        out.println("Passed: " + summary.totals().passed());
        out.println("Failed: " + summary.totals().failed());
        out.println("Skipped: " + summary.totals().skipped());
        out.println("Errors: " + summary.totals().errors());
        out.println("Summary artifact: " + AutomationRunPaths.SUMMARY_FILE_NAME);
    }

    private static int selectedCheckCount(AutomationRunRequest request) {
        int total = 0;
        if (request.smokeTest()) total++;
        if (request.navigationTest()) total++;
        if (request.linksTest()) total++;
        if (request.searchTest()) total++;
        if (request.formsTest()) total++;
        if (request.loginTest()) total++;
        if (request.gameLaunch().enabled()) total++;
        return total;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg == null || arg.isBlank()) continue;
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unsupported argument format. Use --name=value");
            }
            int separator = arg.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException("Missing '=' for an argument. Use --name=value");
            }
            String name = arg.substring(2, separator).trim();
            if (name.isBlank()) throw new IllegalArgumentException("Argument name must not be blank");
            values.put(name, arg.substring(separator + 1).trim());
        }
        return values;
    }

    private static AutomationRunRequest requestFrom(Map<String, String> values) {
        boolean loginEnabled = bool(values, "loginEnabled", false);
        boolean loginTest = bool(values, "login", loginEnabled);
        return new AutomationRunRequest(
            value(values, "schemaVersion", AutomationRunRequest.CONTRACT_VERSION),
            value(values, "siteUrl"),
            value(values, "viewportPreset", AutomationRunRequest.DEFAULT_VIEWPORT_PRESET),
            integer(values, "viewportWidth", AutomationRunRequest.DEFAULT_VIEWPORT_WIDTH),
            integer(values, "viewportHeight", AutomationRunRequest.DEFAULT_VIEWPORT_HEIGHT),
            integer(values, "maxPages", AutomationRunRequest.DEFAULT_MAX_PAGES),
            integer(values, "maxLinks", AutomationRunRequest.DEFAULT_MAX_LINKS),
            loginEnabled,
            value(values, "loginUrl"),
            value(values, "usernameEnv"),
            value(values, "passwordEnv"),
            value(values, "usernameSelector"),
            value(values, "passwordSelector"),
            value(values, "submitSelector"),
            value(values, "successUrlContains"),
            value(values, "successText"),
            bool(values, "smoke", true),
            bool(values, "navigation", true),
            bool(values, "links", true),
            bool(values, "search", false),
            bool(values, "forms", true),
            loginTest,
            bool(values, "searchEnabled", false),
            value(values, "searchInputSelector"),
            value(values, "searchSubmitSelector"),
            value(values, "searchResultsSelector"),
            value(values, "searchQuery", "test"),
            // Optional click-to-open menu. Absent means the menu check does not exist for
            // this run, which must be indistinguishable from the feature never existing.
            value(values, "menuTriggerSelector"),
            value(values, "menuPanelSelector"),
            value(values, "menuLabel"),
            // Optional safe form interaction. Absent means the Forms check stays discovery-only,
            // which must be indistinguishable from the feature never existing.
            value(values, "formSelector"),
            value(values, "formFieldSelector"),
            value(values, "formTestValue"),
            // Which execution path this run takes. Absent is legacy, which is what every request
            // written before this argument existed sends.
            strictBool(values, "crawler", false),
            // Configuration for a check that does not exist yet. Absent in every argument means
            // the disabled block, so a legacy command line describes exactly the run it always
            // described. `--gameLaunch` uses the strict boolean for the same reason `--crawler`
            // does: a value that is neither true nor false must not quietly become false and let
            // the run report a check the caller thought they had asked for.
            gameLaunchFrom(values),
            // Optional, blank on almost every site: the control that reveals the sign-in form.
            value(values, "loginTriggerSelector")
        );
    }

    private static WebsiteGameLaunchConfig gameLaunchFrom(Map<String, String> values) {
        return new WebsiteGameLaunchConfig(
            strictBool(values, "gameLaunch", false),
            integer(values, "maxGames", WebsiteGameLaunchConfig.DEFAULT_MAX_GAMES),
            value(values, "gameCardSelector"),
            value(values, "gameNameSelector"),
            value(values, "gameProviderSelector"),
            value(values, "gameLaunchTriggerSelector"),
            value(values, "gameSurfaceSelector"),
            value(values, "gameErrorSelector"),
            value(values, "gameCloseSelector"),
            value(values, "gameAuthenticatedIndicatorSelector"),
            value(values, "gameBlockingOverlayCloseSelector")
        );
    }

    private static String value(Map<String, String> values, String key) {
        return value(values, key, "");
    }

    private static String value(Map<String, String> values, String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    private static boolean bool(Map<String, String> values, String key, boolean defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    /**
     * A boolean that refuses anything that is not one, for the one argument that chooses a path.
     *
     * <p>{@link #bool} keeps {@link Boolean#parseBoolean} semantics, where anything unrecognised
     * is silently {@code false}. That is tolerable for a check toggle and not tolerable here:
     * {@code --crawler=yes} would quietly start Maven and produce a truthful-looking report of a
     * run the caller did not ask for. So an unrecognised value is rejected the way a malformed
     * number already is, through the argument handling that is already there.
     */
    private static boolean strictBool(Map<String, String> values, String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true")) return true;
        if (normalized.equals("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int integer(Map<String, String> values, String key, int defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
    }

    /**
     * The one thing about a fatal exception that may be written down.
     *
     * <p>A run that died left a summary saying only "internal error", which is true and useless:
     * the next person cannot tell a stale element from a refused command without reproducing it.
     * The class name and the first frame inside this project say which, and nothing else is taken.
     *
     * <p><strong>The message is never read.</strong> A browser exception quotes the page it was
     * looking at and a stack trace quotes whatever was in scope, so neither
     * {@link Throwable#getMessage()} nor any frame outside {@code com.qanize} appears here. What
     * is produced is a class name and, at most, one {@code Class.method(File:line)} from source
     * this project wrote.
     */
    static String safeFingerprint(Throwable failure) {
        if (failure == null) return "";
        String fingerprint = failure.getClass().getSimpleName();
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith(QANIZE_PACKAGE)) {
                String type = frame.getClassName();
                return fingerprint + " at " + type.substring(type.lastIndexOf('.') + 1)
                    + "." + frame.getMethodName() + "(" + frame.getLineNumber() + ")";
            }
        }
        return fingerprint;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message.replaceAll("[\\r\\n]+", " ").trim();
    }

    @FunctionalInterface
    interface MavenRunner {
        int run(List<String> command) throws IOException, InterruptedException;
    }

    private static final class ProcessBuilderMavenRunner implements MavenRunner {
        @Override
        public int run(List<String> command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        }
    }
}
