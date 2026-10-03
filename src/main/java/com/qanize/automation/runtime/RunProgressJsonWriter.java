package com.qanize.automation.runtime;

import java.nio.file.Path;

import com.hanoh.support.JsonSupport;

public final class RunProgressJsonWriter {

    public Path write(AutomationRunPaths paths, RunProgress progress) {
        if (!paths.runId().equals(progress.runId())) {
            throw new IllegalArgumentException("Progress runId must match output paths");
        }
        return JsonSupport.writeAtomically(paths.progressFile(), toJson(progress));
    }

    String toJson(RunProgress progress) {
        return "{\n"
            + field("schemaVersion", progress.schemaVersion(), true)
            + field("runId", progress.runId(), true)
            + field("state", progress.state().name(), true)
            + field("phase", progress.phase(), true)
            + nullableField("currentPage", progress.currentPage(), true)
            + numberField("completedChecks", progress.completedChecks(), true)
            + numberField("totalChecks", progress.totalChecks(), true)
            + field("startedAt", progress.startedAt().toString(), true)
            + field("updatedAt", progress.updatedAt().toString(), true)
            + numberField("elapsedMs", progress.elapsedMs(), true)
            + "  \"cancellable\": " + progress.cancellable() + ",\n"
            + "  \"cancelRequested\": " + progress.cancelRequested() + ",\n"
            + field("message", progress.message(), false)
            + "}\n";
    }

    private static String field(String name, String value, boolean comma) {
        return "  " + JsonSupport.quote(name) + ": " + JsonSupport.quote(value)
            + (comma ? "," : "") + "\n";
    }

    private static String numberField(String name, long value, boolean comma) {
        return "  " + JsonSupport.quote(name) + ": " + value
            + (comma ? "," : "") + "\n";
    }

    private static String nullableField(String name, String value, boolean comma) {
        String encoded = value == null ? "null" : JsonSupport.quote(value);
        return "  " + JsonSupport.quote(name) + ": " + encoded
            + (comma ? "," : "") + "\n";
    }
}
