package com.qanize.automation.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SurefireResultReaderTest {

    @TempDir
    Path reportDirectory;

    @TempDir
    Path oldReportDirectory;

    @Test
    void readsAllPassedResults() throws IOException {
        writeReport(reportDirectory, "TEST-passed.xml", """
            <testsuite name="passed" tests="2" failures="0" errors="0" skipped="0">
              <testcase classname="ExampleTest" name="passesOne"/>
              <testcase classname="ExampleTest" name="passesTwo"/>
            </testsuite>
            """);

        TestRunSummary summary = new SurefireResultReader().read(reportDirectory);

        assertEquals(2, summary.total());
        assertEquals(2, summary.passed());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.errors());
        assertEquals(0, summary.skipped());
        assertEquals(TestRunSummary.OverallStatus.PASS, summary.overallStatus());
        assertTrue(summary.failedTests().isEmpty());
    }

    @Test
    void readsFailuresWithDetails() throws IOException {
        writeReport(reportDirectory, "TEST-failed.xml", """
            <testsuite name="failed" tests="2" failures="1" errors="0" skipped="0">
              <testcase classname="ExampleTest" name="passes"/>
              <testcase classname="ExampleTest" name="fails">
                <failure message="expected true but was false">stack trace</failure>
              </testcase>
            </testsuite>
            """);

        TestRunSummary summary = new SurefireResultReader().read(reportDirectory);

        assertEquals(2, summary.total());
        assertEquals(1, summary.passed());
        assertEquals(1, summary.failed());
        assertEquals(0, summary.errors());
        assertEquals(TestRunSummary.OverallStatus.FAIL, summary.overallStatus());
        assertEquals(1, summary.failedTests().size());
        assertEquals("ExampleTest", summary.failedTests().getFirst().className());
        assertEquals("fails", summary.failedTests().getFirst().testName());
        assertEquals("expected true but was false", summary.failedTests().getFirst().message());
    }

    @Test
    void readsErrorsWithDetails() throws IOException {
        writeReport(reportDirectory, "TEST-error.xml", """
            <testsuite name="error" tests="1" failures="0" errors="1" skipped="0">
              <testcase classname="ExampleTest" name="errors">
                <error message="browser did not start">stack trace</error>
              </testcase>
            </testsuite>
            """);

        TestRunSummary summary = new SurefireResultReader().read(reportDirectory);

        assertEquals(1, summary.total());
        assertEquals(0, summary.passed());
        assertEquals(0, summary.failed());
        assertEquals(1, summary.errors());
        assertEquals(TestRunSummary.OverallStatus.FAIL, summary.overallStatus());
        assertEquals("browser did not start", summary.failedTests().getFirst().message());
    }

    @Test
    void readsSkippedResults() throws IOException {
        writeReport(reportDirectory, "TEST-skipped.xml", """
            <testsuite name="skipped" tests="3" failures="0" errors="0" skipped="1">
              <testcase classname="ExampleTest" name="passesOne"/>
              <testcase classname="ExampleTest" name="passesTwo"/>
              <testcase classname="ExampleTest" name="skipped"><skipped/></testcase>
            </testsuite>
            """);

        TestRunSummary summary = new SurefireResultReader().read(reportDirectory);

        assertEquals(3, summary.total());
        assertEquals(2, summary.passed());
        assertEquals(1, summary.skipped());
        assertEquals(TestRunSummary.OverallStatus.PASS, summary.overallStatus());
    }

    @Test
    void returnsNoResultsWhenNoXmlFilesExist() {
        TestRunSummary summary = new SurefireResultReader().read(reportDirectory.resolve("empty-isolated-run"));

        assertEquals(0, summary.total());
        assertEquals(0, summary.passed());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.errors());
        assertEquals(0, summary.skipped());
        assertEquals(TestRunSummary.OverallStatus.NO_RESULTS, summary.overallStatus());
    }

    @Test
    void aggregatesMultipleXmlFiles() throws IOException {
        writeReport(reportDirectory, "TEST-one.xml", """
            <testsuite name="one" tests="2" failures="0" errors="0" skipped="1">
              <testcase classname="OneTest" name="passes"/>
              <testcase classname="OneTest" name="skipped"><skipped/></testcase>
            </testsuite>
            """);
        writeReport(reportDirectory, "TEST-two.xml", """
            <testsuite name="two" tests="2" failures="1" errors="0" skipped="0">
              <testcase classname="TwoTest" name="passes"/>
              <testcase classname="TwoTest" name="fails">
                <failure message="boom">stack trace</failure>
              </testcase>
            </testsuite>
            """);

        TestRunSummary summary = new SurefireResultReader().read(reportDirectory);

        assertEquals(4, summary.total());
        assertEquals(2, summary.passed());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertEquals(0, summary.errors());
        assertEquals(TestRunSummary.OverallStatus.FAIL, summary.overallStatus());
        assertEquals("TwoTest", summary.failedTests().getFirst().className());
    }

    @Test
    void oldXmlFilesDoNotAffectIsolatedRunSummary() throws IOException {
        Path isolatedReports = reportDirectory.resolve("target/qanize-runs/run-001/surefire-reports");
        Files.createDirectories(isolatedReports);
        writeReport(oldReportDirectory, "TEST-old-profile.xml", """
            <testsuite name="old" tests="1" failures="1" errors="0" skipped="0">
              <testcase classname="OldProfileTest" name="fails"><failure message="old failure"/></testcase>
            </testsuite>
            """);
        writeReport(isolatedReports, "TEST-current.xml", """
            <testsuite name="current" tests="1" failures="0" errors="0" skipped="0">
              <testcase classname="CurrentGenericTest" name="passes"/>
            </testsuite>
            """);

        TestRunSummary summary = new SurefireResultReader().read(isolatedReports);

        assertEquals(1, summary.total());
        assertEquals(1, summary.passed());
        assertEquals(0, summary.failed());
        assertEquals(TestRunSummary.OverallStatus.PASS, summary.overallStatus());
    }

    @Test
    void summaryCliSupportsReportsDirArgument() throws IOException {
        writeReport(reportDirectory, "TEST-current.xml", """
            <testsuite name="current" tests="1" failures="0" errors="0" skipped="0">
              <testcase classname="CurrentGenericTest" name="passes"/>
            </testsuite>
            """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            SurefireSummaryCli.main(new String[] { "--reportsDir=" + reportDirectory });
        } finally {
            System.setOut(originalOut);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Total: 1"));
        assertTrue(text.contains("Status: PASS"));
    }

    private static void writeReport(Path directory, String fileName, String xml) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), xml);
    }


    @Test
    void assumptionReasonIsRecoveredFromTheCdataBodyWithoutTheStackTrace() {
        String raw = "org.opentest4j.TestAbortedException: Assumption failed: "
            + "No eligible same-origin links were discovered on the submitted page.\n"
            + "\tat org.junit.jupiter.api.Assumptions.throwAssumptionFailed(Assumptions.java:340)";

        String reason = SurefireResultReader.cleanSkipReason(raw);

        assertEquals("No eligible same-origin links were discovered on the submitted page.", reason);
    }

    @Test
    void plainAssumptionMessageIsLeftIntact() {
        assertEquals(
            "Test is disabled by tests.links=false",
            SurefireResultReader.cleanSkipReason("Test is disabled by tests.links=false")
        );
    }
}
