package com.qanize.automation.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ProfileIsolationTest {

    @Test
    void pomAndSourceTagsKeepUnitAndGenericRunsSeparate() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<groups>unit</groups>"));
        assertTrue(pom.contains("<id>generic</id>"));
        assertTrue(pom.contains("<groups>website-analysis</groups>"));

        // Recursive: each check now owns a package under checks/, holding its customer entry class
        // beside its own unit tests. Counting every file here would therefore count helper tests as
        // customer checks, so the tag is what identifies a customer execution class.
        List<Path> checkSources = javaSources(Path.of("src/test/java/com/qanize/automation/checks"));
        assertTrue(checkSources.size() > CUSTOMER_ENTRY_CLASSES.size(),
            "The scan found no check-owned unit tests, so it is not reaching the check packages");

        List<Path> customerEntryClasses = checkSources.stream()
            .filter(ProfileIsolationTest::declaresWebsiteAnalysisTag)
            .toList();
        assertEquals(7, customerEntryClasses.size(),
            "Exactly seven customer website checks may carry the website-analysis tag: "
                + customerEntryClasses);

        // The exact six names, because CheckId classifies every run by simple class name. A rename
        // here silently unclassifies both new and historical runs.
        assertEquals(
            CUSTOMER_ENTRY_CLASSES,
            customerEntryClasses.stream()
                .map(path -> path.getFileName().toString().replace(".java", ""))
                .sorted()
                .toList());

        // Everything else under checks/ is a helper or unit test and must never be tagged as a
        // customer website check.
        for (Path source : checkSources) {
            if (customerEntryClasses.contains(source)) continue;
            assertTrue(!declaresWebsiteAnalysisTag(source),
                source + " is not a customer entry class but carries the website-analysis tag");
        }
    }

    /**
     * Every concrete test class must declare one of the two tags the build selects on.
     *
     * <p>Surefire is configured with {@code <groups>}, which is a JUnit tag filter rather than a
     * name filter. An untagged class therefore compiles, lands in {@code target/test-classes}, and
     * is then silently dropped before execution: the suite stays green while the tests never run.
     * Nothing else in the build catches that, because a test that does not run cannot fail.
     *
     * <p>A misspelled tag is the same defect wearing a disguise, so an unrecognised tag fails here
     * too rather than being treated as "tagged".
     */
    @Test
    void everyConcreteTestClassDeclaresATagTheBuildActuallySelects() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        for (String tag : RECOGNISED_TAGS) {
            assertTrue(pom.contains("<groups>" + tag + "</groups>"),
                "Tag \"" + tag + "\" is accepted here but no Maven profile selects it");
        }

        List<Path> scanned = javaSources(TEST_SOURCES).stream()
            .filter(path -> path.getFileName().toString().endsWith("Test.java"))
            .toList();
        // A scan that silently found nothing would pass every assertion below, which is the exact
        // failure mode this guard exists to prevent. It must at least find itself.
        assertTrue(scanned.stream().anyMatch(path -> path.endsWith("ProfileIsolationTest.java")),
            "The scan did not reach its own source file, so its result proves nothing");

        List<String> offenders = new ArrayList<>();
        List<String> exempt = new ArrayList<>();
        for (Path source : scanned) {
            String code = Files.readString(source);
            String className = source.getFileName().toString().replace(".java", "");
            int declaration = declarationIndex(code, className);
            assertTrue(declaration >= 0, "Could not find the type declaration in " + source);

            boolean executable = EXECUTABLE_TEST.matcher(code).find();
            boolean isAbstract = Pattern.compile("\\babstract\\s+class\\s+" + Pattern.quote(className) + "\\b")
                .matcher(code).find();
            if (!executable && isAbstract) {
                // A base class holds shared setup, never a test of its own, so the build has
                // nothing to select and a tag would be meaningless.
                exempt.add(className);
                continue;
            }
            if (!executable) continue;

            // Only tags written above the class declaration count; a tag on a method cannot make
            // the class discoverable.
            List<String> classLevel = classLevelTags(code.substring(0, declaration));
            if (classLevel.isEmpty()) {
                offenders.add(source + " declares no class-level @Tag, so Maven will never run it");
            } else {
                classLevel.stream()
                    .filter(tag -> !RECOGNISED_TAGS.contains(tag))
                    .forEach(tag -> offenders.add(source + " declares an unrecognised tag \"" + tag + "\""));
            }
        }

        assertEquals(List.of(), offenders,
            "These test classes compile but are excluded from every Maven run:\n  "
                + String.join("\n  ", offenders));
        assertEquals(List.of("BaseSeleniumTest", "GenericBaseSeleniumTest"), exempt.stream().sorted().toList(),
            "Only abstract base classes with no executable tests may go untagged");
    }

    private static final Path TEST_SOURCES = Path.of("src/test/java");

    /**
     * The six customer website checks, by exact simple class name, sorted.
     *
     * <p>These names are the wire contract with {@code CheckId}, which classifies a Surefire result
     * by simple class name. They are asserted here so a rename cannot pass review unnoticed.
     */
    private static final List<String> CUSTOMER_ENTRY_CLASSES = List.of(
        "GenericFormDetectionTest",
        "GenericGameLaunchTest",
        "GenericLinkValidationTest",
        "GenericLoginTest",
        "GenericNavigationTest",
        "GenericSearchTest",
        "GenericSmokeTest"
    );

    /** A class-level {@code @Tag("website-analysis")} is what makes a class a customer check. */
    private static boolean declaresWebsiteAnalysisTag(Path source) {
        try {
            return Files.readString(source).contains("@Tag(\"website-analysis\")");
        } catch (IOException unreadable) {
            throw new IllegalStateException("Could not read " + source, unreadable);
        }
    }

    /**
     * The two tags the Surefire configuration and its one profile actually select on.
     *
     * <p>A tag absent from this list fails the guard below, so a class tagged for a profile that
     * no longer exists cannot sit in the tree compiling and never running.
     */
    private static final List<String> RECOGNISED_TAGS = List.of("unit", "website-analysis");

    private static final Pattern EXECUTABLE_TEST =
        Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    private static final Pattern CLASS_LEVEL_TAG = Pattern.compile("@Tag\\(\\s*\"([^\"]*)\"\\s*\\)");

    private static int declarationIndex(String code, String className) {
        Matcher matcher = Pattern.compile("\\bclass\\s+" + Pattern.quote(className) + "\\b").matcher(code);
        return matcher.find() ? matcher.start() : -1;
    }

    private static List<String> classLevelTags(String header) {
        List<String> tags = new ArrayList<>();
        Matcher matcher = CLASS_LEVEL_TAG.matcher(header);
        while (matcher.find()) tags.add(matcher.group(1));
        return tags;
    }

    private static List<Path> javaSources(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }
}
