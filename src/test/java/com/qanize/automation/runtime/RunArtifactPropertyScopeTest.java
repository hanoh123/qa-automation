package com.qanize.automation.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qanize.automation.browser.ScreenshotDirectoryResolver;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.ExecutionEvidenceRecorder;
import com.qanize.automation.model.ExecutionMethod;
import com.qanize.automation.model.ExecutionStep;
import com.qanize.automation.model.Phase;
import com.qanize.automation.model.TestedItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property lifecycle: what a scope installs, what it restores, and that two runs in one JVM
 * cannot write into each other's artifacts.
 *
 * <p>The sink-isolation tests drive the real {@link EvidenceRecorder} and
 * {@link ExecutionEvidenceRecorder} and then read the files off disk. A mock would prove only that
 * the scope sets a string; the question worth answering is where the bytes actually land.
 */
@Tag("unit")
class RunArtifactPropertyScopeTest {

    private static final String EVIDENCE_KEY = EvidenceRecorder.EVIDENCE_FILE_PROPERTY;
    private static final String SCREENSHOTS_KEY = ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY;

    @TempDir
    Path runA;

    @TempDir
    Path runB;

    /** Whatever the surrounding JVM had, so this class cannot leak into the rest of the suite. */
    private final Map<String, String> ambient = new LinkedHashMap<>();

    @BeforeEach
    void captureAndClearAmbientProperties() {
        for (String key : RunArtifactPropertyScope.propertyKeys()) {
            ambient.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreAmbientProperties() {
        ambient.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    // --- what the scope installs --------------------------------------------------------------

    @Test
    void openingInstallsTheExactRunSpecificAbsolutePaths() {
        AutomationRunPaths paths = runPaths(runA, "run-a");

        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(paths)) {
            assertFalse(scope.isClosed());
            assertEquals(absolute(paths.evidenceFile()), System.getProperty(EVIDENCE_KEY));
            assertEquals(absolute(paths.screenshotsDirectory()), System.getProperty(SCREENSHOTS_KEY));
            assertTrue(Path.of(System.getProperty(EVIDENCE_KEY)).isAbsolute());
            assertTrue(Path.of(System.getProperty(SCREENSHOTS_KEY)).isAbsolute());
        }
    }

    @Test
    void theValuesAreVisibleBeforeTheFirstRecorderCall() throws IOException {
        AutomationRunPaths paths = runPaths(runA, "run-a");

        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(paths)) {
            assertFalse(scope.isClosed());
            // Read before anything has been recorded: the destination is already in place, so the
            // very first write cannot land anywhere else.
            assertEquals(absolute(paths.evidenceFile()), System.getProperty(EVIDENCE_KEY));
            assertTrue(evidenceLines(runA).isEmpty());

            EvidenceRecorder.record(testedItem("run-a-item"));
            assertEquals(1, evidenceLines(runA).size());
        }
    }

    @Test
    void theScreenshotsDirectoryIsInstalledWithoutCapturingAnything() {
        AutomationRunPaths paths = runPaths(runA, "run-a");

        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(paths)) {
            assertFalse(scope.isClosed());
            assertEquals(absolute(paths.screenshotsDirectory()), System.getProperty(SCREENSHOTS_KEY));
            assertEquals(
                paths.screenshotsDirectory().toAbsolutePath().normalize(),
                ScreenshotDirectoryResolver.resolve().toAbsolutePath().normalize());
            // This slice moves the destination and nothing else: opening a scope creates no
            // directory and captures no screenshot.
            assertFalse(Files.exists(paths.screenshotsDirectory()));
        }
    }

    // --- real sinks ---------------------------------------------------------------------------

    @Test
    void aRealEvidenceRecorderWriteLandsOnlyInTheOpenRun() throws IOException {
        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(runPaths(runA, "run-a"))) {
            assertFalse(scope.isClosed());
            EvidenceRecorder.record(testedItem("run-a-item"));
        }

        List<String> written = evidenceLines(runA);
        assertEquals(1, written.size());
        assertTrue(written.getFirst().contains("\"id\":\"run-a-item\""), written.getFirst());
        assertTrue(evidenceLines(runB).isEmpty());
    }

    @Test
    void aRealExecutionEvidenceRecorderWriteLandsOnlyInTheOpenRun() throws IOException {
        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(runPaths(runA, "run-a"))) {
            assertFalse(scope.isClosed());
            ExecutionEvidenceRecorder.record("SMOKE", "run-a-case", step());
        }

        List<String> written = stepLines(runA);
        assertEquals(1, written.size());
        assertTrue(written.getFirst().contains("\"testCaseId\":\"run-a-case\""), written.getFirst());
        // The step file has no property of its own; it followed the evidence file, so run B's
        // directory must be untouched too.
        assertTrue(stepLines(runB).isEmpty());
    }

    @Test
    void twoSequentialScopesCannotMixTheirEvidenceDestinations() throws IOException {
        try (RunArtifactPropertyScope first = RunArtifactPropertyScope.open(runPaths(runA, "run-a"))) {
            assertFalse(first.isClosed());
            EvidenceRecorder.record(testedItem("run-a-item"));
            ExecutionEvidenceRecorder.record("SMOKE", "run-a-case", step());
        }
        try (RunArtifactPropertyScope second = RunArtifactPropertyScope.open(runPaths(runB, "run-b"))) {
            assertFalse(second.isClosed());
            EvidenceRecorder.record(testedItem("run-b-item"));
            ExecutionEvidenceRecorder.record("SMOKE", "run-b-case", step());
        }

        assertEquals(List.of("run-a-item"), recordedIds(evidenceLines(runA)));
        assertEquals(List.of("run-b-item"), recordedIds(evidenceLines(runB)));
        assertEquals(1, stepLines(runA).size());
        assertEquals(1, stepLines(runB).size());
        assertTrue(stepLines(runA).getFirst().contains("\"testCaseId\":\"run-a-case\""));
        assertTrue(stepLines(runB).getFirst().contains("\"testCaseId\":\"run-b-case\""));
    }

    @Test
    void aSecondRunDoesNotAppendToTheFirstRunsArtifacts() throws IOException {
        try (RunArtifactPropertyScope first = RunArtifactPropertyScope.open(runPaths(runA, "run-a"))) {
            assertFalse(first.isClosed());
            EvidenceRecorder.record(testedItem("run-a-item"));
        }
        int afterFirstRun = evidenceLines(runA).size();

        try (RunArtifactPropertyScope second = RunArtifactPropertyScope.open(runPaths(runB, "run-b"))) {
            assertFalse(second.isClosed());
            EvidenceRecorder.record(testedItem("run-b-item"));
            EvidenceRecorder.record(testedItem("run-b-second-item"));
        }

        assertEquals(1, afterFirstRun);
        assertEquals(1, evidenceLines(runA).size(), "run A grew while run B was recording");
        assertEquals(2, evidenceLines(runB).size());
    }

    // --- restoration --------------------------------------------------------------------------

    @Test
    void closingRestoresAnAbsentPropertyToAbsent() {
        assertNull(System.getProperty(EVIDENCE_KEY));
        assertNull(System.getProperty(SCREENSHOTS_KEY));

        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(runPaths(runA, "run-a"))) {
            assertNotNull(System.getProperty(EVIDENCE_KEY));
            assertNotNull(System.getProperty(SCREENSHOTS_KEY));
            assertFalse(scope.isClosed());
        }

        assertNull(System.getProperty(EVIDENCE_KEY));
        assertNull(System.getProperty(SCREENSHOTS_KEY));
    }

    @Test
    void closingRestoresAPreExistingValueByteForByte() {
        // Deliberately awkward: surrounding spaces would not survive any implementation that
        // trimmed, re-parsed or re-derived the value instead of handing back what it captured.
        String previousEvidence = "  /pre/existing/evidence.jsonl  ";
        String previousScreenshots = "relative/pre-existing/screenshots";
        System.setProperty(EVIDENCE_KEY, previousEvidence);
        System.setProperty(SCREENSHOTS_KEY, previousScreenshots);

        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(runPaths(runA, "run-a"))) {
            assertFalse(scope.isClosed());
            assertEquals(absolute(runA.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME)),
                System.getProperty(EVIDENCE_KEY));
        }

        assertEquals(previousEvidence, System.getProperty(EVIDENCE_KEY));
        assertEquals(previousScreenshots, System.getProperty(SCREENSHOTS_KEY));
    }

    @Test
    void repeatedCloseIsSafeAndDoesNotCorruptRestoredValues() {
        System.setProperty(EVIDENCE_KEY, "before-the-scope");
        RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(runPaths(runA, "run-a"));

        scope.close();
        assertTrue(scope.isClosed());
        assertEquals("before-the-scope", System.getProperty(EVIDENCE_KEY));

        // Somebody else now owns the property. A second close must not reach back in and
        // overwrite their value with this scope's stale memory of the world.
        System.setProperty(EVIDENCE_KEY, "owned-by-someone-else");
        scope.close();
        scope.close();

        assertEquals("owned-by-someone-else", System.getProperty(EVIDENCE_KEY));
        assertTrue(scope.isClosed());
    }

    @Test
    void aNestedScopeRestoresTheOuterValuesWhenItCloses() throws IOException {
        AutomationRunPaths outerPaths = runPaths(runA, "run-a");
        AutomationRunPaths innerPaths = runPaths(runB, "run-b");

        try (RunArtifactPropertyScope outer = RunArtifactPropertyScope.open(outerPaths)) {
            assertFalse(outer.isClosed());
            assertEquals(absolute(outerPaths.evidenceFile()), System.getProperty(EVIDENCE_KEY));

            try (RunArtifactPropertyScope inner = RunArtifactPropertyScope.open(innerPaths)) {
                assertFalse(inner.isClosed());
                assertEquals(absolute(innerPaths.evidenceFile()), System.getProperty(EVIDENCE_KEY));
                assertEquals(absolute(innerPaths.screenshotsDirectory()),
                    System.getProperty(SCREENSHOTS_KEY));
                EvidenceRecorder.record(testedItem("run-b-item"));
            }

            assertEquals(absolute(outerPaths.evidenceFile()), System.getProperty(EVIDENCE_KEY));
            assertEquals(absolute(outerPaths.screenshotsDirectory()),
                System.getProperty(SCREENSHOTS_KEY));
            EvidenceRecorder.record(testedItem("run-a-item"));
        }

        assertEquals(List.of("run-a-item"), recordedIds(evidenceLines(runA)));
        assertEquals(List.of("run-b-item"), recordedIds(evidenceLines(runB)));
        assertNull(System.getProperty(EVIDENCE_KEY));
        assertNull(System.getProperty(SCREENSHOTS_KEY));
    }

    // --- refusing to act ----------------------------------------------------------------------

    @Test
    void nullInputChangesNoProperty() {
        System.setProperty(EVIDENCE_KEY, "untouched");
        System.setProperty(SCREENSHOTS_KEY, "also-untouched");

        assertThrows(IllegalArgumentException.class, () -> RunArtifactPropertyScope.open(null));

        assertEquals("untouched", System.getProperty(EVIDENCE_KEY));
        assertEquals("also-untouched", System.getProperty(SCREENSHOTS_KEY));
    }

    @Test
    void aLaterInvalidDestinationPreventsTheFirstPropertyFromBeingWritten() {
        System.setProperty(EVIDENCE_KEY, "untouched");
        System.setProperty(SCREENSHOTS_KEY, "also-untouched");
        // The evidence file is installed first and is perfectly valid; the screenshots directory,
        // validated in the same pass, is not. Nothing may be written.
        AutomationRunPaths missingScreenshots = new AutomationRunPaths(
            "run-a",
            runA,
            runA.resolve(AutomationRunPaths.RUN_CONFIG_FILE_NAME),
            runA.resolve(AutomationRunPaths.PROPERTIES_CONFIG_FILE_NAME),
            runA.resolve(AutomationRunPaths.SUREFIRE_REPORTS_DIRECTORY_NAME),
            null,
            runA.resolve(AutomationRunPaths.PROGRESS_FILE_NAME),
            runA.resolve(AutomationRunPaths.SUMMARY_FILE_NAME),
            runA.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> RunArtifactPropertyScope.open(missingScreenshots));

        assertTrue(failure.getMessage().contains(SCREENSHOTS_KEY), failure.getMessage());
        assertEquals("untouched", System.getProperty(EVIDENCE_KEY));
        assertEquals("also-untouched", System.getProperty(SCREENSHOTS_KEY));
    }

    @Test
    void anEmptyDestinationChangesNoPropertyAndIsReportedWithoutTheValue() {
        System.setProperty(EVIDENCE_KEY, "untouched");
        AutomationRunPaths emptyEvidence = new AutomationRunPaths(
            "run-a",
            runA,
            runA.resolve(AutomationRunPaths.RUN_CONFIG_FILE_NAME),
            runA.resolve(AutomationRunPaths.PROPERTIES_CONFIG_FILE_NAME),
            runA.resolve(AutomationRunPaths.SUREFIRE_REPORTS_DIRECTORY_NAME),
            runA.resolve(AutomationRunPaths.SCREENSHOTS_DIRECTORY_NAME),
            runA.resolve(AutomationRunPaths.PROGRESS_FILE_NAME),
            runA.resolve(AutomationRunPaths.SUMMARY_FILE_NAME),
            Path.of(""));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> RunArtifactPropertyScope.open(emptyEvidence));

        assertTrue(failure.getMessage().contains(EVIDENCE_KEY), failure.getMessage());
        assertEquals("untouched", System.getProperty(EVIDENCE_KEY));
    }

    @Test
    void aPartialInstallationFailureRollsBackTheEarlierMutation() {
        System.setProperty(EVIDENCE_KEY, "before-the-scope");
        System.clearProperty(SCREENSHOTS_KEY);

        // A store whose second write refuses. The first write has already happened by then, which
        // is exactly the state the rollback exists for.
        RunArtifactPropertyScope.PropertyAccess refusesSecondWrite =
            new RunArtifactPropertyScope.PropertyAccess() {

                @Override
                public String get(String key) {
                    return System.getProperty(key);
                }

                @Override
                public void set(String key, String value) {
                    if (SCREENSHOTS_KEY.equals(key)) {
                        throw new IllegalStateException("the property store refused this write");
                    }
                    System.setProperty(key, value);
                }

                @Override
                public void clear(String key) {
                    System.clearProperty(key);
                }
            };

        AutomationRunPaths paths = runPaths(runA, "run-a");
        assertThrows(
            IllegalStateException.class,
            () -> new RunArtifactPropertyScope(paths, refusesSecondWrite));

        assertEquals("before-the-scope", System.getProperty(EVIDENCE_KEY));
        assertNull(System.getProperty(SCREENSHOTS_KEY));
    }

    // --- discretion and shape -----------------------------------------------------------------

    @Test
    void noDestinationOrSecretIsReachableThroughToString() {
        Path secretish = runA.resolve("tenant-token-abc123");
        AutomationRunPaths paths = runPaths(secretish, "run-a");

        try (RunArtifactPropertyScope scope = RunArtifactPropertyScope.open(paths)) {
            String description = scope.toString();
            assertFalse(description.contains("tenant-token-abc123"), description);
            assertFalse(description.contains(runA.toString()), description);
            assertFalse(description.contains(AutomationRunPaths.EVIDENCE_FILE_NAME), description);
            assertTrue(description.startsWith("RunArtifactPropertyScope["), description);
        }
    }

    @Test
    void theScopeIntroducesNoThreadLocalOrAlternateArtifactMechanism() throws IOException {
        String code = withoutComments(Files.readString(Path.of(
            "src/main/java/com/qanize/automation/runtime/RunArtifactPropertyScope.java")));

        assertFalse(code.contains("ThreadLocal"), "the scope must not hold per-thread run state");
        assertFalse(code.contains("Thread"), "the scope must not reason about threads at all");
        assertFalse(code.contains("Files."), "the scope must not write artifacts of its own");
        assertFalse(code.contains("import java.io"), "the scope performs no IO");
        // The keys are borrowed from their consumers, never respelled.
        assertFalse(code.contains("\"qanize."), "property keys must not be duplicated as literals");
        assertTrue(code.contains("EvidenceRecorder.EVIDENCE_FILE_PROPERTY"));
        assertTrue(code.contains("ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY"));
    }

    @Test
    void theScopeOwnsExactlyTheAuditedArtifactProperties() {
        assertEquals(List.of(EVIDENCE_KEY, SCREENSHOTS_KEY), RunArtifactPropertyScope.propertyKeys());
    }

    @Test
    void theLegacyMavenCommandStillCarriesTheSameDefinitions() {
        AutomationRunPaths paths = runPaths(runA, "run-a");

        List<String> command = AutomationRunMavenCommand.genericTestCommand(paths);

        // Spelled out literally on purpose: this is the guard that sharing the key constants did
        // not change one byte of what the forked Maven process receives.
        assertEquals(7, command.size(), command.toString());
        assertEquals("test", command.get(1));
        assertEquals("-Pgeneric", command.get(2));
        assertEquals("-DsiteConfig=" + paths.configFile(), command.get(3));
        assertEquals("-Dsurefire.reportsDirectory=" + paths.surefireReportsDirectory(), command.get(4));
        assertEquals("-Dqanize.screenshotsDir=" + paths.screenshotsDirectory(), command.get(5));
        assertEquals("-Dqanize.evidenceFile=" + paths.evidenceFile(), command.get(6));
        // The legacy path passes destinations as arguments, so it needs no scope open.
        assertNull(System.getProperty(EVIDENCE_KEY));
    }

    @Test
    void thisTestClassIsSelectedByTheDefaultUnitProfile() throws IOException {
        Tag tag = RunArtifactPropertyScopeTest.class.getAnnotation(Tag.class);

        assertNotNull(tag, "the class must be tagged to run in the default profile");
        assertEquals("unit", tag.value());
        assertTrue(Files.readString(Path.of("pom.xml")).contains("<groups>unit</groups>"));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static AutomationRunPaths runPaths(Path runDirectory, String runId) {
        return new AutomationRunPaths(
            runId,
            runDirectory,
            runDirectory.resolve(AutomationRunPaths.RUN_CONFIG_FILE_NAME),
            runDirectory.resolve(AutomationRunPaths.PROPERTIES_CONFIG_FILE_NAME),
            runDirectory.resolve(AutomationRunPaths.SUREFIRE_REPORTS_DIRECTORY_NAME),
            runDirectory.resolve(AutomationRunPaths.SCREENSHOTS_DIRECTORY_NAME),
            runDirectory.resolve(AutomationRunPaths.PROGRESS_FILE_NAME),
            runDirectory.resolve(AutomationRunPaths.SUMMARY_FILE_NAME),
            runDirectory.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME));
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static List<String> evidenceLines(Path runDirectory) throws IOException {
        return linesOf(runDirectory.resolve(AutomationRunPaths.EVIDENCE_FILE_NAME));
    }

    private static List<String> stepLines(Path runDirectory) throws IOException {
        return linesOf(runDirectory.resolve(ExecutionEvidenceRecorder.EXECUTION_EVIDENCE_FILE_NAME));
    }

    private static List<String> linesOf(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    }

    private static List<String> recordedIds(List<String> lines) {
        return lines.stream().map(RunArtifactPropertyScopeTest::idOf).toList();
    }

    private static String idOf(String line) {
        int start = line.indexOf("\"id\":\"");
        if (start < 0) return "";
        int from = start + 6;
        int end = line.indexOf('"', from);
        return end < 0 ? "" : line.substring(from, end);
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static TestedItem testedItem(String id) {
        return TestedItem.builder("SMOKE", TestedItem.ItemType.PAGE)
            .id(id)
            .name("Home page")
            .status(TestedItem.Status.PASSED)
            .build();
    }

    private static ExecutionStep step() {
        return new ExecutionStep(
            1,
            Phase.ACTION,
            ExecutionMethod.DOM_INSPECTION,
            "Read the page title.",
            "page title",
            "",
            "a title is present",
            "a title is present",
            ExecutionStep.Status.PASSED,
            7,
            "");
    }
}
