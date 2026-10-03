package com.qanize.automation.evidence;


import com.qanize.automation.model.TestedItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class EvidenceFileReaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void readsMultipleRecordsInOrder() throws IOException {
        Path file = tempDirectory.resolve("evidence.jsonl");
        Files.writeString(file, String.join("\n",
            line("link-1", "LINKS", "LINK", "PASSED", "\"httpStatus\":200"),
            line("link-2", "LINKS", "LINK", "FAILED", "\"httpStatus\":404")
        ));

        EvidenceFileReader.Result result = new EvidenceFileReader().read(file);

        assertEquals(2, result.items().size());
        assertFalse(result.hasParseErrors());
        assertEquals("link-1", result.items().get(0).id());
        assertEquals(200, result.items().get(0).httpStatus());
        assertEquals(TestedItem.Status.FAILED, result.items().get(1).status());
        assertEquals(404, result.items().get(1).httpStatus());
    }

    @Test
    void malformedLineIsReportedAndNeverBecomesAPassedItem() throws IOException {
        Path file = tempDirectory.resolve("evidence.jsonl");
        Files.writeString(file, String.join("\n",
            line("ok-1", "SMOKE", "PAGE", "PASSED", null),
            "{\"id\":\"broken\",\"testType\":\"SMOKE\"",
            "not json at all",
            "{\"id\":\"nested\",\"testType\":\"SMOKE\",\"itemType\":\"PAGE\",\"status\":\"PASSED\",\"meta\":{\"a\":1}}"
        ));

        EvidenceFileReader.Result result = new EvidenceFileReader().read(file);

        assertEquals(1, result.items().size());
        assertEquals("ok-1", result.items().getFirst().id());
        assertEquals(3, result.parseErrors().size());
        assertTrue(result.items().stream().noneMatch(item -> "broken".equals(item.id())));
    }

    @Test
    void recordMissingRequiredFieldIsRejected() throws IOException {
        Path file = tempDirectory.resolve("evidence.jsonl");
        // No status: the reader cannot know whether this passed, so it must not guess.
        Files.writeString(file, "{\"id\":\"x\",\"testType\":\"SMOKE\",\"itemType\":\"PAGE\"}");

        EvidenceFileReader.Result result = new EvidenceFileReader().read(file);

        assertTrue(result.items().isEmpty());
        assertEquals(1, result.parseErrors().size());
        assertTrue(result.parseErrors().getFirst().contains("status"));
    }

    @Test
    void missingEvidenceFileYieldsNoItemsAndNoErrors() {
        EvidenceFileReader.Result result =
            new EvidenceFileReader().read(tempDirectory.resolve("absent.jsonl"));

        assertTrue(result.items().isEmpty());
        assertFalse(result.hasParseErrors());
    }

    @Test
    void writerOutputRoundTripsThroughTheReader() throws IOException {
        TestedItem original = TestedItem.builder("FORMS", TestedItem.ItemType.FIELD)
            .id("form-1-field-2")
            .name("email")
            .pageUrl("https://example.com/")
            .action("Read the control's structure. No value was entered or read.")
            .expected("The control exposes a readable type and state.")
            .actual("Inspected a text control.")
            .status(TestedItem.Status.PASSED)
            .durationMs(12L)
            .meta("fieldType", "text")
            .meta("required", "true")
            .build();

        Path file = tempDirectory.resolve("evidence.jsonl");
        Files.writeString(file, EvidenceRecorder.toJsonLine(original));

        List<TestedItem> items = new EvidenceFileReader().read(file).items();
        assertEquals(1, items.size());
        TestedItem parsed = items.getFirst();
        assertEquals(original.id(), parsed.id());
        assertEquals(original.name(), parsed.name());
        assertEquals(original.status(), parsed.status());
        assertEquals(12L, parsed.durationMs());
        assertEquals("text", parsed.metadata().get("fieldType"));
        assertEquals("true", parsed.metadata().get("required"));
        assertNull(parsed.httpStatus(), "httpStatus was never collected, so it must stay absent");
    }

    @Test
    void quotesAndSeparatorsInsideValuesSurviveTheRoundTrip() throws IOException {
        TestedItem original = TestedItem.builder("SEARCH", TestedItem.ItemType.SEARCH)
            .id("search-input")
            .name("Search field")
            .actual("No field matched \"#missing, really\" on the page")
            .status(TestedItem.Status.FAILED)
            .build();

        Path file = tempDirectory.resolve("evidence.jsonl");
        Files.writeString(file, EvidenceRecorder.toJsonLine(original));

        TestedItem parsed = new EvidenceFileReader().read(file).items().getFirst();
        assertEquals("No field matched \"#missing, really\" on the page", parsed.actualResult());
    }

    private static String line(String id, String testType, String itemType, String status, String extra) {
        return "{\"id\":\"" + id + "\",\"testType\":\"" + testType + "\",\"itemType\":\"" + itemType
            + "\",\"status\":\"" + status + "\"" + (extra == null ? "" : "," + extra) + "}";
    }
}
