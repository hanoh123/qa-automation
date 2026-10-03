package com.qanize.automation.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record AutomationRunPaths(
    String runId,
    Path runDirectory,
    Path runConfigFile,
    Path propertiesConfigFile,
    Path surefireReportsDirectory,
    Path screenshotsDirectory,
    Path progressFile,
    Path summaryFile,
    Path evidenceFile
) {

    public static final Path DEFAULT_RUNS_DIRECTORY = Path.of("target", "qanize-runs");
    public static final String RUN_CONFIG_FILE_NAME = "run-config.json";
    public static final String PROPERTIES_CONFIG_FILE_NAME = "generated-run.properties";
    public static final String SUREFIRE_REPORTS_DIRECTORY_NAME = "surefire-reports";
    public static final String SCREENSHOTS_DIRECTORY_NAME = "screenshots";
    public static final String PROGRESS_FILE_NAME = "progress.json";
    public static final String SUMMARY_FILE_NAME = "summary.json";
    /** JSON Lines evidence written by the Selenium layer during the run. */
    public static final String EVIDENCE_FILE_NAME = "evidence.jsonl";

    public static AutomationRunPaths forRunId(String runId) {
        String resolvedRunId = runId == null || runId.isBlank()
            ? generatedRunId()
            : sanitizeRunId(runId);
        Path runDirectory = DEFAULT_RUNS_DIRECTORY.resolve(resolvedRunId).normalize();
        return new AutomationRunPaths(
            resolvedRunId,
            runDirectory,
            runDirectory.resolve(RUN_CONFIG_FILE_NAME),
            runDirectory.resolve(PROPERTIES_CONFIG_FILE_NAME),
            runDirectory.resolve(SUREFIRE_REPORTS_DIRECTORY_NAME),
            runDirectory.resolve(SCREENSHOTS_DIRECTORY_NAME),
            runDirectory.resolve(PROGRESS_FILE_NAME),
            runDirectory.resolve(SUMMARY_FILE_NAME),
            runDirectory.resolve(EVIDENCE_FILE_NAME)
        );
    }

    /** The internal properties adapter retained for Selenium/JUnit execution. */
    public Path configFile() {
        return propertiesConfigFile;
    }

    public Path resolveArtifact(String relativeName) {
        requireSafeRelativeArtifactName(relativeName);
        Path root = runDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(Path.of(relativeName)).normalize();
        // Belt and braces. The syntactic rule above already rejects every shape that could escape,
        // but this is the property that actually matters, so it is asserted rather than assumed.
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Artifact path must stay inside the run directory");
        }
        return resolved;
    }

    /**
     * The one rule every artifact output name must satisfy: a safe relative path, beneath the run
     * directory, judged identically on every operating system.
     *
     * <p><strong>Why this is not {@code Path.isAbsolute()}.</strong> That method answers for the
     * host it happens to be running on, and the answer differs between them in both directions.
     * On Linux, {@code Path.of("C:/outside.json")} is <em>relative</em> — a directory literally
     * named {@code C:} containing {@code outside.json} — so it passed validation and then wrote a
     * file that is absolute the moment the same name reaches Windows. Symmetrically, on Windows
     * {@code Path.of("/tmp/outside.json").isAbsolute()} is {@code false}, because the path names no
     * drive, so a Unix absolute path passed validation there. A rule that changes its mind
     * depending on where it runs is not a security rule.
     *
     * <p>So the check is syntactic and host-independent. Both {@code /} and {@code \} are treated
     * as separators everywhere, and a name is rejected when it is:
     *
     * <ul>
     *   <li>rooted — begins with a separator, which covers Unix absolute paths
     *       ({@code /tmp/x}), Windows root-relative paths ({@code \x}) and UNC paths
     *       ({@code \\server\share}, {@code //server/share});</li>
     *   <li>drive-qualified — begins with a letter and a colon, which covers drive-rooted
     *       ({@code C:\x}, {@code C:/x}) and drive-relative ({@code C:x}) forms;</li>
     *   <li>traversing — contains a {@code ..} segment under either separator;</li>
     *   <li>absolute according to the host as well, as a final catch-all for any shape not
     *       enumerated above.</li>
     * </ul>
     *
     * <p>A plain filename and a nested relative path are the only things left, which is exactly
     * what an artifact name is meant to be.
     *
     * @throws IllegalArgumentException when the name could name a file outside the run directory
     */
    public static void requireSafeRelativeArtifactName(String relativeName) {
        if (relativeName == null || relativeName.isBlank()) {
            throw new IllegalArgumentException("Artifact name is required");
        }
        String unified = relativeName.replace('\\', '/');
        if (unified.startsWith("/")) {
            throw new IllegalArgumentException(
                "Artifact name must be relative, but it is rooted: " + relativeName);
        }
        if (unified.length() >= 2
            && Character.isLetter(unified.charAt(0))
            && unified.charAt(1) == ':') {
            throw new IllegalArgumentException(
                "Artifact name must be relative, but it names a drive: " + relativeName);
        }
        for (String segment : unified.split("/", -1)) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException(
                    "Artifact name must not traverse upwards: " + relativeName);
            }
        }
        if (Path.of(relativeName).isAbsolute()) {
            throw new IllegalArgumentException(
                "Artifact name must be relative: " + relativeName);
        }
    }

    public String relativeName(Path artifact) {
        Path root = runDirectory.toAbsolutePath().normalize();
        Path resolved = artifact.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Artifact path must stay inside the run directory");
        }
        return root.relativize(resolved).toString().replace('\\', '/');
    }

    private static String generatedRunId() {
        return "run-"
            + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replaceAll("[^0-9TZ]", "")
            + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    }

    static String sanitizeRunId(String runId) {
        String sanitized = runId.trim();
        if (!sanitized.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
            || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("runId contains unsupported characters");
        }
        return sanitized;
    }
}
