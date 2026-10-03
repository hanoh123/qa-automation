package com.qanize.automation.reporting;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SurefireSummaryCli {

    private SurefireSummaryCli() {
    }

    public static void main(String[] args) {
        Map<String, String> arguments = parseArgs(args);
        Path reportDirectory = reportDirectory(arguments, args);
        TestRunSummary summary = new SurefireResultReader().read(reportDirectory);

        System.out.println("Total: " + summary.total());
        System.out.println("Passed: " + summary.passed());
        System.out.println("Failed: " + summary.failed());
        System.out.println("Skipped: " + summary.skipped());
        System.out.println("Errors: " + summary.errors());
        System.out.println("Status: " + summary.overallStatus());

        for (TestRunSummary.FailedTestDetail failedTest : summary.failedTests()) {
            System.out.println(
                "Failed test: "
                    + failedTest.className()
                    + "."
                    + failedTest.testName()
                    + " - "
                    + failedTest.message()
            );
        }
    }

    private static Path reportDirectory(Map<String, String> arguments, String[] rawArgs) {
        String reportsDir = arguments.get("reportsDir");
        if (reportsDir != null && !reportsDir.isBlank()) {
            return Path.of(reportsDir);
        }
        if (rawArgs.length == 1 && !rawArgs[0].startsWith("--")) {
            return Path.of(rawArgs[0]);
        }
        return SurefireResultReader.DEFAULT_REPORT_DIRECTORY;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg == null || arg.isBlank() || !arg.startsWith("--")) {
                continue;
            }
            int separator = arg.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException("Missing '=' for argument: " + arg + ". Use --name=value");
            }
            values.put(arg.substring(2, separator).trim(), arg.substring(separator + 1).trim());
        }
        return values;
    }
}
