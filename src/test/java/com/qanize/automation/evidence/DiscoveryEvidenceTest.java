package com.qanize.automation.evidence;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.checks.shared.DiscoveryDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the measured counts actually reach the run artifact, so a reader never has to infer a
 * fact such as the page-content timeout from the wording of a sentence.
 */
@Tag("unit")
class DiscoveryEvidenceTest {

    @TempDir
    Path runDirectory;

    @AfterEach
    void clearEvidenceFileProperty() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
    }

    private String recordAndRead(String testType, DiscoveryDiagnostics diagnostics) {
        Path evidence = runDirectory.resolve("evidence.jsonl");
        System.setProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY, evidence.toString());
        DiscoveryEvidence.recordNothingDiscovered(
            testType, testType.toLowerCase() + "-discovery", "Discovery",
            "https://example.com/page",
            "Opened the submitted page and looked for something to test.",
            "At least one item is available to test.",
            "Nothing was discovered.",
            diagnostics
        );
        try {
            List<String> lines = Files.readAllLines(evidence);
            assertEquals(1, lines.size(), "exactly one diagnostic record per skipped check");
            return lines.getFirst();
        } catch (java.io.IOException failure) {
            throw new AssertionError("evidence file was not written", failure);
        }
    }

    @Test
    void theTimeoutFlagIsPersistedExplicitlyRatherThanImplied() {
        String line = recordAndRead("FORMS", new DiscoveryDiagnostics.ForForms(4, 10, 0, 0, false));

        assertTrue(line.contains("\"metadata.discovery.contentWaitTimedOut\":\"false\""), line);
    }

    @Test
    void formsDiagnosticsReachTheArtifactWithoutLinkClassificationCounts() {
        String line = recordAndRead("FORMS", new DiscoveryDiagnostics.ForForms(4, 10, 0, 2, false));

        assertTrue(line.contains("\"metadata.discovery.anchors\":\"4\""), line);
        assertTrue(line.contains("\"metadata.discovery.buttons\":\"10\""), line);
        assertTrue(line.contains("\"metadata.discovery.forms\":\"0\""), line);
        assertTrue(line.contains("\"metadata.discovery.iframes\":\"2\""), line);
        assertFalse(line.contains("sameOriginAnchors"), line);
        assertFalse(line.contains("navigationAnchors"), line);
    }

    @Test
    void linkDiagnosticsReachTheArtifactWithTheirClassificationCounts() {
        String line = recordAndRead("LINKS", new DiscoveryDiagnostics.ForLinks(9, 4, 2, 6, 0, true));

        assertTrue(line.contains("\"metadata.discovery.sameOriginAnchors\":\"4\""), line);
        assertTrue(line.contains("\"metadata.discovery.navigationAnchors\":\"2\""), line);
        assertTrue(line.contains("\"metadata.discovery.contentWaitTimedOut\":\"true\""), line);
    }

    @Test
    void aSkippedCheckIsNeverRecordedAsPassed() {
        String line = recordAndRead("FORMS", new DiscoveryDiagnostics.ForForms(0, 0, 0, 0, true));

        assertTrue(line.contains("\"status\":\"SKIPPED\""), line);
        assertFalse(line.contains("\"status\":\"PASSED\""), line);
    }

    @Test
    void recordingIsANoOpWhenNoRunDirectoryIsConfigured() {
        System.clearProperty(EvidenceRecorder.EVIDENCE_FILE_PROPERTY);
        // A direct Maven invocation has no evidence file; this must not throw or fabricate output.
        DiscoveryEvidence.recordNothingDiscovered(
            "FORMS", "forms-discovery", "Form discovery", "https://example.com",
            "action", "expected", "reason",
            new DiscoveryDiagnostics.ForForms(0, 0, 0, 0, false)
        );
        assertFalse(Files.exists(runDirectory.resolve("evidence.jsonl")));
    }

    @Test
    void aRecordThatExplainsAnAbsenceSaysThatIsWhatItIs() {
        // Forms reported "No forms were discovered" in its reason and "Discovered: 1" in its counts.
        // The one being counted was this record. It is marked so the count can drop it without
        // anyone having to recognise an id.
        String line = recordAndRead("FORMS", new DiscoveryDiagnostics.ForForms(4, 10, 0, 0, false));

        assertTrue(line.contains("\"metadata.discoveryRecord\":\"true\""), line);
        assertTrue(line.contains("\"status\":\"SKIPPED\""),
            "still a skip: something was looked at and deliberately not tested");
    }
}
