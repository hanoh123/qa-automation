package com.qanize.automation.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeConfigGeneratorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void canonicalJsonMatchesTheVersionOneWebContract() throws IOException {
        Path output = tempDirectory.resolve("run-config.json");
        new RuntimeConfigGenerator().writeCanonicalJson(searchRequest(), output);

        String json = Files.readString(output);
        assertTrue(json.contains("\"schemaVersion\": \"1.0\""));
        assertTrue(json.contains("\"target\": {"));
        assertTrue(json.contains("\"viewport\": {\"preset\": \"desktop\""));
        assertTrue(json.contains("\"checks\": {"));
        assertTrue(json.contains("\"search\": {"));
        assertTrue(json.contains("\"inputSelector\": \"input[type='search']\""));
        assertFalse(json.contains("\"tests\":"));
        assertFalse(json.contains("\"target\": {\n    \"url\": \"https://example.com/\",\n    \"viewport\""));
    }

    @Test
    void propertiesAdapterForwardsSearchAndViewportWithoutSecrets() {
        Properties properties = new RuntimeConfigGenerator().toProperties(searchRequest());

        assertEquals("input[type='search']", properties.getProperty("search.inputSelector"));
        assertEquals("button[type='submit']", properties.getProperty("search.submitSelector"));
        assertEquals("#results", properties.getProperty("search.resultsSelector"));
        assertEquals("qanize", properties.getProperty("search.query"));
        assertEquals("1440", properties.getProperty("viewport.width"));
        assertEquals("900", properties.getProperty("viewport.height"));
    }

    @Test
    void canonicalJsonPreservesBlankQueryWhenSearchIsDisabled() {
        String json = new RuntimeConfigGenerator().toJson(request(false, "", ""));

        assertTrue(json.contains("\"enabled\": false"));
        assertTrue(json.contains("\"query\": \"\""));
        assertFalse(json.contains("\"query\": \"test\""));
    }

    @Test
    void outputPathsRejectTraversalAndAbsoluteArtifacts() {
        assertThrows(IllegalArgumentException.class, () -> AutomationRunPaths.forRunId("../escape"));
        AutomationRunPaths paths = AutomationRunPaths.forRunId("run-safe_001");
        assertThrows(IllegalArgumentException.class, () -> paths.resolveArtifact("../outside.json"));
        // The literal string, not Path.of(...).toString(): routing it through the host's Path
        // rewrites it per-OS, which is precisely the coupling this rule must not have.
        assertThrows(IllegalArgumentException.class, () -> paths.resolveArtifact("C:/outside.json"));
    }

    // --- artifact names are judged identically on every operating system -----------------------

    /**
     * Every shape that could name a file outside the run directory on <em>some</em> supported OS.
     *
     * <p>The rule is deliberately not {@code Path.isAbsolute()}: that answers for the host it runs
     * on, and it answered wrongly in both directions. {@code C:/outside.json} is relative on Linux
     * — a folder called {@code C:} — and {@code /tmp/outside.json} is not absolute on Windows,
     * because it names no drive. Each of these must be rejected wherever the suite runs.
     */
    @Test
    void unsafeArtifactNamesAreRejectedOnEveryOperatingSystem() {
        AutomationRunPaths paths = AutomationRunPaths.forRunId("run-safe_001");

        for (String unsafe : java.util.List.of(
            "C:/outside.json",            // drive-rooted, forward slashes: relative on Linux
            "C:\\outside.json",           // drive-rooted, backslashes
            "c:/outside.json",            // lower-case drive letter
            "C:outside.json",             // drive-relative, no separator at all
            "\\\\server\\share\\out.json", // UNC
            "//server/share/out.json",     // UNC with forward slashes
            "/tmp/outside.json",          // Unix absolute: not absolute on Windows
            "\\windows\\system32\\x",     // Windows root-relative
            "../outside.json",            // traversal, forward slash
            "..\\outside.json",           // traversal, backslash: one flat segment on Linux
            "nested/../../outside.json",  // traversal after a safe-looking first segment
            "nested\\..\\..\\outside.json",
            "..")) {
            assertThrows(IllegalArgumentException.class, () -> paths.resolveArtifact(unsafe),
                "must be rejected on every OS: " + unsafe);
        }
    }

    @Test
    void safeRelativeArtifactNamesAreStillAcceptedAndStayInsideTheRunDirectory() {
        AutomationRunPaths paths = AutomationRunPaths.forRunId("run-safe_001");
        Path root = paths.runDirectory().toAbsolutePath().normalize();

        for (String safe : java.util.List.of(
            "summary.json",
            "execution-evidence.jsonl",
            "surefire-reports/TEST-Example.xml",
            "screenshots/page-001/shot.png",
            "./summary.json",
            "a.b.c-d_e.json")) {
            Path resolved = paths.resolveArtifact(safe);
            assertTrue(resolved.startsWith(root),
                safe + " resolved outside the run directory: " + resolved);
            assertTrue(resolved.isAbsolute(), "the resolved artifact path is absolute: " + resolved);
        }
    }

    @Test
    void aDriveLetterIsNotMistakenForAnOrdinaryFirstSegment() {
        AutomationRunPaths paths = AutomationRunPaths.forRunId("run-safe_001");

        // The bug in one line: before the fix, Linux resolved this to <run>/C:/outside.json and
        // accepted it, because Path.of("C:/outside.json") is a two-segment relative path there.
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
            () -> paths.resolveArtifact("C:/outside.json"));
        assertTrue(rejected.getMessage().contains("drive"), rejected.getMessage());

        // A name that merely starts with a letter is not drive-qualified and stays valid.
        assertTrue(paths.resolveArtifact("C-outside.json").getFileName().toString()
            .equals("C-outside.json"));
        assertTrue(paths.resolveArtifact("Cx/outside.json").toString().contains("outside.json"));
    }

    @Test
    void theSameRuleIsAvailableAsOneNamedProductionCheck() {
        // One rule, reusable by any other output path that needs it, rather than a condition
        // copied into each call site or re-implemented inside a test.
        assertThrows(IllegalArgumentException.class,
            () -> AutomationRunPaths.requireSafeRelativeArtifactName("C:/outside.json"));
        assertThrows(IllegalArgumentException.class,
            () -> AutomationRunPaths.requireSafeRelativeArtifactName("/tmp/outside.json"));
        assertThrows(IllegalArgumentException.class,
            () -> AutomationRunPaths.requireSafeRelativeArtifactName("  "));
        assertThrows(IllegalArgumentException.class,
            () -> AutomationRunPaths.requireSafeRelativeArtifactName(null));
        AutomationRunPaths.requireSafeRelativeArtifactName("surefire-reports/TEST-Example.xml");
    }

    @Test
    void progressJsonUsesTheVersionedParserShape() {
        RunProgress progress = RunProgress.of(
            "run-001",
            RunProgress.State.CONFIGURING,
            "VALIDATING_CONFIGURATION",
            null,
            0,
            6,
            Instant.now(),
            "Validating configuration."
        );

        String json = new RunProgressJsonWriter().toJson(progress);
        assertTrue(json.contains("\"schemaVersion\": \"1.0\""));
        assertTrue(json.contains("\"currentPage\": null"));
        assertTrue(json.contains("\"cancellable\": false"));
        assertTrue(json.contains("\"cancelRequested\": false"));
    }

    private static AutomationRunRequest searchRequest() {
        return request(true, "input[type='search']", "qanize");
    }

    private static AutomationRunRequest request(
        boolean searchEnabled,
        String searchInputSelector,
        String searchQuery
    ) {
        return new AutomationRunRequest(
            "1.0",
            "https://example.com/",
            "desktop",
            1440,
            900,
            5,
            25,
            false,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            true,
            true,
            true,
            searchEnabled,
            true,
            false,
            searchEnabled,
            searchInputSelector,
            "button[type='submit']",
            "#results",
            searchQuery,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
