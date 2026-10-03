package com.qanize.automation.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ScreenshotDirectoryResolverTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearScreenshotProperties() {
        System.clearProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);
        System.clearProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY);
    }

    @Test
    void defaultScreenshotDirectoryStillWorks() {
        System.clearProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);
        System.clearProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY);

        assertEquals(Path.of("target", "screenshots"), ScreenshotDirectoryResolver.resolve());
    }

    @Test
    void screenshotDirectoryCanBeResolvedFromSystemProperty() {
        Path expected = tempDirectory.resolve("run-001/screenshots");
        System.setProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, expected.toString());

        assertEquals(expected, ScreenshotDirectoryResolver.resolve());
    }

    @Test
    void legacyTypoScreenshotDirectoryPropertyIsStillAccepted() {
        Path expected = tempDirectory.resolve("legacy-run/screenshots");
        System.setProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY, expected.toString());

        assertEquals(expected, ScreenshotDirectoryResolver.resolve());
    }

    @Test
    void officialScreenshotDirectoryPropertyWinsOverLegacyTypoProperty() {
        Path official = tempDirectory.resolve("official-run/screenshots");
        Path legacy = tempDirectory.resolve("legacy-run/screenshots");
        System.setProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, official.toString());
        System.setProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY, legacy.toString());

        assertEquals(official, ScreenshotDirectoryResolver.resolve());
    }

    @Test
    void perRunScreenshotDirectoryIsCreatedAutomatically() throws IOException {
        Path expected = tempDirectory.resolve("target/qanize-runs/run-001/screenshots");
        System.setProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, expected.toString());

        Path actual = ScreenshotDirectoryResolver.ensureDirectory();

        assertEquals(expected, actual);
        assertTrue(Files.isDirectory(expected));
    }

    @Test
    void generatedScreenshotPathsAreSafeAndPredictable() throws IOException {
        Path expectedDirectory = tempDirectory.resolve("screenshots");
        System.setProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, expectedDirectory.toString());

        Path screenshotPath = ScreenshotDirectoryResolver.screenshotPath("bad name/?secret.png");

        assertEquals(expectedDirectory.resolve("bad_name__secret.png"), screenshotPath);
        assertTrue(Files.isDirectory(expectedDirectory));
    }
}
