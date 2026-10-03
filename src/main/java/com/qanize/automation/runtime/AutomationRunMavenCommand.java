package com.qanize.automation.runtime;

import java.util.List;
import java.util.Locale;

import com.qanize.automation.browser.ScreenshotDirectoryResolver;
import com.qanize.automation.evidence.EvidenceRecorder;

public final class AutomationRunMavenCommand {

    private AutomationRunMavenCommand() {
    }

    /**
     * The legacy forked-Maven command, unchanged on the wire.
     *
     * <p>The two run-artifact keys are now taken from the classes that read them rather than
     * spelled again here, so the forked run and an in-process {@link RunArtifactPropertyScope}
     * cannot disagree about a key. The emitted arguments are byte-for-byte what they were.
     */
    public static List<String> genericTestCommand(AutomationRunPaths paths) {
        return List.of(
            mavenExecutable(),
            "test",
            "-Pgeneric",
            "-DsiteConfig=" + paths.configFile(),
            "-Dsurefire.reportsDirectory=" + paths.surefireReportsDirectory(),
            define(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, paths.screenshotsDirectory()),
            define(EvidenceRecorder.EVIDENCE_FILE_PROPERTY, paths.evidenceFile())
        );
    }

    private static String define(String key, Object value) {
        return "-D" + key + "=" + value;
    }

    static String mavenExecutable() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win") ? "mvn.cmd" : "mvn";
    }
}
