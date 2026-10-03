package com.qanize.automation.reporting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class SurefireResultReader {

    public static final Path DEFAULT_REPORT_DIRECTORY = Path.of("target", "surefire-reports");

    public TestRunSummary readDefault() {
        return read(DEFAULT_REPORT_DIRECTORY);
    }

    public TestRunSummary read(Path reportDirectory) {
        List<Path> xmlFiles = xmlFiles(reportDirectory);
        if (xmlFiles.isEmpty()) {
            return new TestRunSummary(
                0,
                0,
                0,
                0,
                0,
                TestRunSummary.OverallStatus.NO_RESULTS,
                List.of()
            );
        }

        MutableSummary summary = new MutableSummary();
        for (Path xmlFile : xmlFiles) {
            readFile(xmlFile, summary);
        }

        TestRunSummary.OverallStatus status = summary.failed > 0 || summary.errors > 0
            ? TestRunSummary.OverallStatus.FAIL
            : TestRunSummary.OverallStatus.PASS;

        return new TestRunSummary(
            summary.total,
            summary.total - summary.failed - summary.errors - summary.skipped,
            summary.failed,
            summary.skipped,
            summary.errors,
            status,
            summary.failedTests,
            List.copyOf(summary.suites)
        );
    }

    private static List<Path> xmlFiles(Path reportDirectory) {
        if (reportDirectory == null || !Files.isDirectory(reportDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(reportDirectory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                .filter(path -> path.getFileName().toString().endsWith(".xml"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Surefire report directory: " + reportDirectory, failure);
        }
    }

    private static void readFile(Path xmlFile, MutableSummary summary) {
        Document document = parse(xmlFile);
        Element suite = document.getDocumentElement();
        int tests = intAttribute(suite, "tests");
        int failures = intAttribute(suite, "failures");
        int errors = intAttribute(suite, "errors");
        int skipped = intAttribute(suite, "skipped");
        summary.total += tests;
        summary.failed += failures;
        summary.errors += errors;
        summary.skipped += skipped;
        summary.suites.add(new TestRunSummary.SuiteResult(
            suiteClassName(suite),
            tests,
            failures,
            errors,
            skipped,
            firstSkipReason(suite)
        ));
        collectFailedTests(suite, summary.failedTests);
    }

    /** Prefers the individual test case's classname; the suite name is the fallback. */
    private static String suiteClassName(Element suite) {
        NodeList testCases = suite.getElementsByTagName("testcase");
        for (int index = 0; index < testCases.getLength(); index++) {
            if (testCases.item(index) instanceof Element testCase) {
                String className = testCase.getAttribute("classname");
                if (className != null && !className.isBlank()) {
                    return className;
                }
            }
        }
        String name = suite.getAttribute("name");
        return name == null ? "" : name;
    }

    /**
     * JUnit records an assumption failure as a skipped test. The readable reason is sometimes in
     * the {@code message} attribute and sometimes only inside the element's CDATA body, prefixed
     * by "Assumption failed:" and followed by a stack trace, so both forms are handled and the
     * trace is discarded.
     */
    private static String firstSkipReason(Element suite) {
        NodeList skippedNodes = suite.getElementsByTagName("skipped");
        for (int index = 0; index < skippedNodes.getLength(); index++) {
            if (skippedNodes.item(index) instanceof Element skipped) {
                String message = skipped.getAttribute("message");
                if (message != null && !message.isBlank()) {
                    return cleanSkipReason(message);
                }
                String body = skipped.getTextContent();
                if (body != null && !body.isBlank()) {
                    String reason = cleanSkipReason(body);
                    if (!reason.isBlank()) return reason;
                }
            }
        }
        return "";
    }

    /** Keeps the human sentence and drops the exception class and stack frames. */
    static String cleanSkipReason(String raw) {
        String value = raw.trim();
        int marker = value.indexOf("Assumption failed:");
        if (marker >= 0) {
            value = value.substring(marker + "Assumption failed:".length());
        } else {
            int colon = value.indexOf(": ");
            if (colon > 0 && value.substring(0, colon).contains("Exception")) {
                value = value.substring(colon + 2);
            }
        }
        int stackStart = value.indexOf("\n\tat ");
        if (stackStart >= 0) value = value.substring(0, stackStart);
        int literalStack = value.indexOf("\tat ");
        if (literalStack >= 0) value = value.substring(0, literalStack);
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static Document parse(Path xmlFile) {
        try (InputStream input = Files.newInputStream(xmlFile)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(input);
        } catch (IOException | ParserConfigurationException | SAXException failure) {
            throw new IllegalStateException("Could not parse Surefire XML report: " + xmlFile, failure);
        }
    }

    private static void collectFailedTests(Element suite, List<TestRunSummary.FailedTestDetail> failedTests) {
        NodeList testCases = suite.getElementsByTagName("testcase");
        for (int index = 0; index < testCases.getLength(); index++) {
            Element testCase = (Element) testCases.item(index);
            addProblemDetails(testCase, "failure", TestRunSummary.ProblemType.FAILURE, failedTests);
            addProblemDetails(testCase, "error", TestRunSummary.ProblemType.ERROR, failedTests);
        }
    }

    private static void addProblemDetails(
        Element testCase,
        String tagName,
        TestRunSummary.ProblemType type,
        List<TestRunSummary.FailedTestDetail> failedTests
    ) {
        NodeList problems = testCase.getElementsByTagName(tagName);
        for (int index = 0; index < problems.getLength(); index++) {
            Node node = problems.item(index);
            if (node instanceof Element problem) {
                failedTests.add(new TestRunSummary.FailedTestDetail(
                    testCase.getAttribute("classname"),
                    testCase.getAttribute("name"),
                    type,
                    problem.getAttribute("message")
                ));
            }
        }
    }

    private static int intAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private static final class MutableSummary {
        private int total;
        private int failed;
        private int skipped;
        private int errors;
        private final List<TestRunSummary.FailedTestDetail> failedTests = new ArrayList<>();
        private final List<TestRunSummary.SuiteResult> suites = new ArrayList<>();
    }
}
