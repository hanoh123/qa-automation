package com.qanize.automation.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ScreenshotDirectoryResolver {

    public static final String SCREENSHOTS_DIR_PROPERTY = "qanize.screenshotsDir";
    public static final String LEGACY_SCREENSHOTS_DIR_PROPERTY = "qanitize.screenshotsDir";
    public static final Path DEFAULT_SCREENSHOT_DIRECTORY = Path.of("target", "screenshots");

    /** Used when a caller supplies a name that sanitizes down to nothing usable. */
    static final String DEFAULT_SCREENSHOT_NAME = "screenshot.png";

    /** Leaves room for the run directory ahead of it under a conservative path limit. */
    static final int MAX_FILE_NAME_LENGTH = 150;

    private ScreenshotDirectoryResolver() {
    }

    public static Path resolve() {
        String configuredDirectory = System.getProperty(SCREENSHOTS_DIR_PROPERTY);
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            configuredDirectory = System.getProperty(LEGACY_SCREENSHOTS_DIR_PROPERTY);
        }
        return resolve(configuredDirectory);
    }

    static Path resolve(String configuredDirectory) {
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return DEFAULT_SCREENSHOT_DIRECTORY;
        }
        return Path.of(configuredDirectory.trim());
    }

    public static Path ensureDirectory() throws IOException {
        Path directory = resolve();
        Files.createDirectories(directory);
        return directory;
    }

    /**
     * A path for one screenshot, guaranteed to name a file inside the screenshots directory.
     *
     * <p>The containment check is asserted rather than assumed: {@link #safeFileName(String)}
     * already removes every shape that could escape, but this is the property that matters.
     *
     * @throws IOException when the directory cannot be created, or — impossibly, if the sanitizer
     *     is correct — when the resulting path would sit outside it
     */
    public static Path screenshotPath(String fileName) throws IOException {
        Path directory = ensureDirectory();
        Path screenshot = directory.resolve(safeFileName(fileName));
        if (!screenshot.toAbsolutePath().normalize()
            .startsWith(directory.toAbsolutePath().normalize())) {
            throw new IOException("Screenshot name must stay inside the screenshots directory");
        }
        return screenshot;
    }

    /**
     * Turns any label into a plain file name that cannot leave the screenshots directory.
     *
     * <p>{@link #safeName(String)} already replaces every separator, so a name cannot carry a
     * directory with it. What it does not stop is a name made only of dots: a dot is a legal
     * file-name character, so {@code ".."} survives it unchanged and then resolves to the parent
     * directory — the one remaining way out. Dot runs are therefore collapsed here, and a name
     * that sanitizes away to nothing falls back to a fixed one rather than resolving to the
     * directory itself.
     *
     * <p>Length is capped so that a long class-and-method name cannot push the whole path past a
     * filesystem limit; the {@code .png} suffix is kept when one is being truncated away.
     */
    static String safeFileName(String fileName) {
        String safe = safeName(fileName == null ? "" : fileName.trim());
        safe = safe.replace("..", "_");
        if (safe.isBlank() || safe.equals(".")) {
            safe = DEFAULT_SCREENSHOT_NAME;
        }
        if (safe.length() > MAX_FILE_NAME_LENGTH) {
            safe = safe.endsWith(".png")
                ? safe.substring(0, MAX_FILE_NAME_LENGTH - ".png".length()) + ".png"
                : safe.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return safe;
    }

    static String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
