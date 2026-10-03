package com.qanize.automation.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

import com.qanize.automation.model.TestedItem;
import com.hanoh.support.JsonSupport;

/**
 * Persists tested-item evidence as JSON Lines inside the current run directory.
 *
 * <p>The console is not the persistence mechanism: Maven runs with inherited IO, so anything
 * printed is lost. Each record is appended as one flat JSON object to the file named by the
 * {@code qanize.evidenceFile} system property, which the orchestrator points at a path unique to
 * the run. Records are flat (metadata keys are written as {@code metadata.<key>}) so the reader
 * needs no JSON dependency and can reject malformed input strictly.
 *
 * <p>If the property is absent — a direct Maven invocation, for example — recording is a no-op.
 * That yields no tested items rather than fabricated ones.
 */
public final class EvidenceRecorder {

    public static final String EVIDENCE_FILE_PROPERTY = "qanize.evidenceFile";

    private EvidenceRecorder() {
    }

    public static String nowIso() {
        return Instant.now().toString();
    }

    /** Appends one tested item. Never throws into a test: reporting must not fail the check. */
    public static void record(TestedItem item) {
        if (item == null) return;
        Path target = evidenceFile();
        if (target == null) return;
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            synchronized (EvidenceRecorder.class) {
                Files.writeString(
                    target,
                    toJsonLine(item) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
                );
            }
        } catch (IOException | RuntimeException failure) {
            // Reporting problems must never mark a website check as failed.
            System.out.println("QA Evidence | evidence-write-failed | " + safeLine(failure.toString()));
        }
    }

    static Path evidenceFile() {
        String configured = System.getProperty(EVIDENCE_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) return null;
        return Path.of(configured.trim());
    }

    /** One flat JSON object per line. Absent values are omitted, never defaulted. */
    public static String toJsonLine(TestedItem item) {
        StringBuilder json = new StringBuilder("{");
        boolean[] first = { true };
        text(json, first, "id", item.id());
        text(json, first, "testType", item.testType());
        text(json, first, "itemType", item.itemType());
        text(json, first, "name", item.name());
        text(json, first, "pageUrl", item.pageUrl());
        text(json, first, "target", item.target());
        text(json, first, "action", item.action());
        text(json, first, "expectedResult", item.expectedResult());
        text(json, first, "actualResult", item.actualResult());
        text(json, first, "status", item.status() == null ? null : item.status().name());
        text(json, first, "message", item.message());
        text(json, first, "startedAt", item.startedAt());
        text(json, first, "completedAt", item.completedAt());
        number(json, first, "durationMs", item.durationMs());
        text(json, first, "selector", item.selector());
        number(json, first, "httpStatus", item.httpStatus());
        text(json, first, "severity", item.severity());
        for (Map.Entry<String, String> entry : item.metadata().entrySet()) {
            text(json, first, "metadata." + entry.getKey(), entry.getValue());
        }
        return json.append("}").toString();
    }

    private static void text(StringBuilder json, boolean[] first, String key, String value) {
        if (value == null || value.isBlank()) return;
        separator(json, first);
        json.append(JsonSupport.quote(key)).append(":").append(JsonSupport.quote(safeLine(value)));
    }

    private static void number(StringBuilder json, boolean[] first, String key, Number value) {
        if (value == null) return;
        separator(json, first);
        json.append(JsonSupport.quote(key)).append(":").append(value);
    }

    private static void separator(StringBuilder json, boolean[] first) {
        if (first[0]) { first[0] = false; } else { json.append(","); }
    }

    /**
     * Records are one line each, so embedded newlines would corrupt the file.
     *
     * <p>Public because the per-check execution evidence classes now live in their own
     * check packages and still need this exact flattening. The implementation is unchanged.
     */
    public static String safeLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
