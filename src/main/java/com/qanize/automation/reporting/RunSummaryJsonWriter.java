package com.qanize.automation.reporting;

import com.qanize.automation.model.ArtifactMetadata;
import com.qanize.automation.model.Evidence;
import com.qanize.automation.model.Finding;
import com.qanize.automation.model.RunError;
import com.qanize.automation.model.TestedItem;

import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.runtime.AutomationRunPaths;
import com.hanoh.support.JsonSupport;

public final class RunSummaryJsonWriter {

    public Path write(AutomationRunPaths paths, RunSummary summary) {
        if (!paths.runId().equals(summary.runId())) {
            throw new IllegalArgumentException("Summary runId must match output paths");
        }
        return JsonSupport.writeAtomically(paths.summaryFile(), toJson(summary));
    }

    String toJson(RunSummary summary) {
        StringBuilder json = new StringBuilder("{\n");
        field(json, 2, "schemaVersion", summary.schemaVersion(), true);
        field(json, 2, "runId", summary.runId(), true);
        field(json, 2, "lifecycle", summary.lifecycle().name(), true);
        field(json, 2, "outcome", summary.outcome().name(), true);
        field(json, 2, "targetUrl", summary.targetUrl(), true);
        field(json, 2, "startedAt", summary.startedAt().toString(), true);
        field(json, 2, "completedAt", summary.completedAt().toString(), true);
        numberField(json, 2, "durationMs", summary.durationMs(), true);
        json.append("  \"totals\": {\n");
        numberField(json, 4, "total", summary.totals().total(), true);
        numberField(json, 4, "passed", summary.totals().passed(), true);
        numberField(json, 4, "failed", summary.totals().failed(), true);
        numberField(json, 4, "skipped", summary.totals().skipped(), true);
        numberField(json, 4, "errors", summary.totals().errors(), false);
        json.append("  },\n");
        findings(json, summary.findings());
        json.append(",\n");
        errors(json, summary.errors());
        json.append(",\n");
        evidence(json, summary.evidence());
        json.append(",\n");
        artifacts(json, summary.artifacts());
        json.append(",\n");
        testExecution(json, summary.testExecution());
        json.append(",\n");
        testedItems(json, summary.testedItems());
        json.append("\n}\n");
        return json.toString();
    }

    private static void testedItems(StringBuilder json, List<TestedItem> items) {
        json.append("  \"testedItems\": [");
        if (items.isEmpty()) { json.append(']'); return; }
        json.append('\n');
        for (int index = 0; index < items.size(); index++) {
            TestedItem item = items.get(index);
            json.append("    {\n");
            List<String> lines = new java.util.ArrayList<>();
            addText(lines, "id", item.id());
            addText(lines, "testType", item.testType());
            addText(lines, "itemType", item.itemType());
            addText(lines, "name", item.name());
            addText(lines, "pageUrl", item.pageUrl());
            addText(lines, "target", item.target());
            addText(lines, "action", item.action());
            addText(lines, "expectedResult", item.expectedResult());
            addText(lines, "actualResult", item.actualResult());
            addText(lines, "status", item.status() == null ? null : item.status().name());
            addText(lines, "message", item.message());
            addText(lines, "startedAt", item.startedAt());
            addText(lines, "completedAt", item.completedAt());
            if (item.durationMs() != null) lines.add("      \"durationMs\": " + item.durationMs());
            addText(lines, "selector", item.selector());
            if (item.httpStatus() != null) lines.add("      \"httpStatus\": " + item.httpStatus());
            addText(lines, "severity", item.severity());
            StringBuilder meta = new StringBuilder("      \"metadata\": {");
            boolean firstMeta = true;
            for (java.util.Map.Entry<String, String> entry : item.metadata().entrySet()) {
                if (!firstMeta) meta.append(", ");
                firstMeta = false;
                meta.append(JsonSupport.quote(entry.getKey())).append(": ").append(JsonSupport.quote(entry.getValue()));
            }
            lines.add(meta.append("}").toString());
            json.append(String.join(",\n", lines)).append('\n');
            json.append("    }");
            if (index < items.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void addText(List<String> lines, String key, String value) {
        if (value == null || value.isBlank()) return;
        lines.add("      " + JsonSupport.quote(key) + ": " + JsonSupport.quote(value));
    }

    private static void testExecution(StringBuilder json, List<TestExecution> executions) {
        json.append("  \"testExecution\": [");
        if (executions.isEmpty()) {
            json.append(']');
            return;
        }
        json.append('\n');
        for (int index = 0; index < executions.size(); index++) {
            TestExecution execution = executions.get(index);
            json.append("    {\n");
            field(json, 6, "testType", execution.testType(), true);
            json.append(" ".repeat(6)).append("\"selected\": ").append(execution.selected()).append(",\n");
            json.append(" ".repeat(6)).append("\"executed\": ").append(execution.executed()).append(",\n");
            field(json, 6, "status", execution.status().name(), true);
            numberField(json, 6, "checkCount", execution.checkCount(), true);
            numberField(json, 6, "passedCount", execution.passedCount(), true);
            numberField(json, 6, "failedCount", execution.failedCount(), true);
            numberField(json, 6, "warningCount", execution.warningCount(), true);
            numberField(json, 6, "skippedCount", execution.skippedCount(), true);
            field(json, 6, "skippedReason", execution.skippedReason(), true);
            numberField(json, 6, "itemsDiscovered", execution.itemsDiscovered(), true);
            numberField(json, 6, "itemsTested", execution.itemsTested(), true);
            numberField(json, 6, "itemsPassed", execution.itemsPassed(), true);
            numberField(json, 6, "itemsFailed", execution.itemsFailed(), true);
            numberField(json, 6, "itemsSkipped", execution.itemsSkipped(), true);
            numberField(json, 6, "itemsDetailed", execution.itemsDetailed(), true);
            numberField(json, 6, "itemsOmitted", execution.itemsOmitted(), false);
            json.append("    }");
            if (index < executions.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void findings(StringBuilder json, List<Finding> findings) {
        json.append("  \"findings\": [");
        if (findings.isEmpty()) {
            json.append(']');
            return;
        }
        json.append('\n');
        for (int index = 0; index < findings.size(); index++) {
            Finding finding = findings.get(index);
            json.append("    {\n");
            field(json, 6, "id", finding.id(), true);
            field(json, 6, "runId", finding.runId(), true);
            field(json, 6, "category", finding.category(), true);
            field(json, 6, "severity", finding.severity(), true);
            field(json, 6, "title", finding.title(), true);
            field(json, 6, "description", finding.description(), true);
            field(json, 6, "pageUrl", finding.pageUrl(), true);
            field(json, 6, "expected", finding.expected(), true);
            field(json, 6, "actual", finding.actual(), true);
            field(json, 6, "whyItFailed", finding.whyItFailed(), true);
            stringArray(json, 6, "reproductionSteps", finding.reproductionSteps(), true);
            stringArray(json, 6, "evidence", finding.evidence(), true);
            field(json, 6, "recommendation", finding.recommendation(), true);
            field(json, 6, "timestamp", finding.timestamp().toString(), false);
            json.append("    }");
            if (index < findings.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void errors(StringBuilder json, List<RunError> errors) {
        json.append("  \"errors\": [");
        if (errors.isEmpty()) {
            json.append(']');
            return;
        }
        json.append('\n');
        for (int index = 0; index < errors.size(); index++) {
            RunError error = errors.get(index);
            json.append("    {\n");
            field(json, 6, "category", error.category(), true);
            field(json, 6, "code", error.code(), true);
            field(json, 6, "userMessage", error.userMessage(), true);
            field(json, 6, "suggestedAction", error.suggestedAction(), true);
            // Written only when something filled it in, so a summary from before this field
            // existed and a summary with nothing to say are byte-identical.
            if (error.hasDiagnostic()) {
                field(json, 6, "diagnostic", error.diagnostic(), true);
            }
            field(json, 6, "timestamp", error.timestamp().toString(), false);
            json.append("    }");
            if (index < errors.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void evidence(StringBuilder json, List<Evidence> evidence) {
        json.append("  \"evidence\": [");
        if (evidence.isEmpty()) {
            json.append(']');
            return;
        }
        json.append('\n');
        for (int index = 0; index < evidence.size(); index++) {
            Evidence item = evidence.get(index);
            json.append("    {\n");
            field(json, 6, "id", item.id(), true);
            field(json, 6, "type", item.type(), true);
            field(json, 6, "label", item.label(), true);
            field(json, 6, "description", item.description(), true);
            field(json, 6, "artifactId", item.artifactId(), true);
            field(json, 6, "pageUrl", item.pageUrl(), false);
            json.append("    }");
            if (index < evidence.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void artifacts(StringBuilder json, List<ArtifactMetadata> artifacts) {
        json.append("  \"artifacts\": [");
        if (artifacts.isEmpty()) {
            json.append(']');
            return;
        }
        json.append('\n');
        for (int index = 0; index < artifacts.size(); index++) {
            ArtifactMetadata artifact = artifacts.get(index);
            json.append("    {\n");
            field(json, 6, "id", artifact.id(), true);
            field(json, 6, "type", artifact.type(), true);
            field(json, 6, "label", artifact.label(), true);
            field(json, 6, "mediaType", artifact.mediaType(), true);
            field(json, 6, "relativePath", artifact.relativePath(), false);
            json.append("    }");
            if (index < artifacts.size() - 1) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
    }

    private static void stringArray(
        StringBuilder json,
        int spaces,
        String name,
        List<String> values,
        boolean comma
    ) {
        json.append(" ".repeat(spaces)).append(JsonSupport.quote(name)).append(": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(", ");
            json.append(JsonSupport.quote(values.get(index)));
        }
        json.append(']');
        json.append(comma ? ",\n" : "\n");
    }

    private static void field(
        StringBuilder json,
        int spaces,
        String name,
        String value,
        boolean comma
    ) {
        json.append(" ".repeat(spaces)).append(JsonSupport.quote(name)).append(": ")
            .append(JsonSupport.quote(value == null ? "" : value));
        json.append(comma ? ",\n" : "\n");
    }

    private static void numberField(
        StringBuilder json,
        int spaces,
        String name,
        long value,
        boolean comma
    ) {
        json.append(" ".repeat(spaces)).append(JsonSupport.quote(name)).append(": ")
            .append(value);
        json.append(comma ? ",\n" : "\n");
    }
}
