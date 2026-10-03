package com.qanize.automation.evidence;


import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SensitiveDataFilterTest {

    @Test
    void passwordAndHiddenControlsAreSensitive() {
        assertTrue(SensitiveDataFilter.isSensitiveField("password", "pass", "pass"));
        assertTrue(SensitiveDataFilter.isSensitiveField("hidden", "csrf", null));
        assertTrue(SensitiveDataFilter.isSensitiveField("text", "api_key", null));
        assertTrue(SensitiveDataFilter.isSensitiveField("text", null, "session-token"));
        assertFalse(SensitiveDataFilter.isSensitiveField("text", "firstName", "firstName"));
    }

    @Test
    void sensitiveFieldNamesAreRedactedRatherThanEchoed() {
        assertEquals("[redacted]", SensitiveDataFilter.safeFieldName("password", "userPassword", null));
        assertEquals("firstName", SensitiveDataFilter.safeFieldName("text", "firstName", null));
    }

    @Test
    void identifiersAreMasked() {
        assertEquals("q********r@example.com", SensitiveDataFilter.maskIdentifier("qauserxyzr@example.com"));
        assertEquals("t****r", SensitiveDataFilter.maskIdentifier("tester"));
        assertEquals("**", SensitiveDataFilter.maskIdentifier("ab"));
    }

    @Test
    void messagesAreStrippedOfCredentialsAndEmails() {
        String cleaned = SensitiveDataFilter.sanitizeMessage(
            "Login failed password=hunter2 for user tester@example.com token=abcdefghijklmnopqrstuvwxyz"
        );

        assertFalse(cleaned.contains("hunter2"));
        assertFalse(cleaned.contains("tester@example.com"));
        assertFalse(cleaned.contains("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(cleaned.contains("[redacted]") || cleaned.contains("[redacted-email]"));
    }

    @Test
    void messagesAreSingleLineAndBounded() {
        String cleaned = SensitiveDataFilter.sanitizeMessage("line one\nline two\r\nline three");
        assertFalse(cleaned.contains("\n"));

        String longMessage = SensitiveDataFilter.sanitizeMessage("x".repeat(900));
        assertTrue(longMessage.length() <= 401);
    }

    // --- URLs discovered from a site's own markup ------------------------------------------

    @Test
    void credentialShapedQueryValuesAreRedactedButThePageStaysIdentifiable() {
        String safe = SensitiveDataFilter.sanitizeUrl(
            "https://example.com/reset?token=abc123&session_id=zzz&page=2&q=shoes");

        assertFalse(safe.contains("abc123"));
        assertFalse(safe.contains("zzz"));
        assertTrue(safe.contains("token=[redacted]"));
        assertTrue(safe.contains("session_id=[redacted]"));
        // Harmless parameters survive, otherwise the evidence stops identifying the page tested.
        assertTrue(safe.contains("page=2"));
        assertTrue(safe.contains("q=shoes"));
        assertTrue(safe.startsWith("https://example.com/reset?"));
    }

    @Test
    void embeddedUserInfoIsRemovedFromTheAuthority() {
        String safe = SensitiveDataFilter.sanitizeUrl("https://admin:hunter2@example.com/private?a=1");

        assertFalse(safe.contains("hunter2"));
        assertFalse(safe.contains("admin"));
        assertEquals("https://[redacted]@example.com/private?a=1", safe);
    }

    @Test
    void anOrdinaryUrlIsLeftExactlyAsItIs() {
        // The aggressive token rule in sanitizeMessage would destroy long path segments; a URL
        // must stay usable as evidence of which page was tested.
        String url = "https://example.com/docs/getting-started-with-the-analyzer/section-one";
        assertEquals(url, SensitiveDataFilter.sanitizeUrl(url));
        assertEquals("https://example.com/", SensitiveDataFilter.sanitizeUrl("  https://example.com/  "));
    }

    @Test
    void malformedAndEmptyUrlsAreHandledWithoutThrowing() {
        assertNull(SensitiveDataFilter.sanitizeUrl(null));
        assertNull(SensitiveDataFilter.sanitizeUrl("   "));
        assertEquals("/relative/path?token=[redacted]",
            SensitiveDataFilter.sanitizeUrl("/relative/path?token=secret"));
        assertEquals("not a url", SensitiveDataFilter.sanitizeUrl("not a url"));
        // A parameter with no value is preserved rather than dropped.
        assertEquals("https://example.com/?flag", SensitiveDataFilter.sanitizeUrl("https://example.com/?flag"));
    }

    @Test
    void urlsNeverContainNewlinesSoOneRecordStaysOneLine() {
        assertFalse(SensitiveDataFilter.sanitizeUrl("https://example.com/a\nb").contains("\n"));
    }

    // --- fragments: where a credential lands after a sign-in redirect ------------------------

    @Test
    void aCredentialShapedFragmentParameterIsRedacted() {
        // An OAuth implicit-flow redirect puts the credential after the '#', and a fragment never
        // reaches the server, so nothing upstream will have stripped it.
        String safe = SensitiveDataFilter.sanitizeUrl(
            "https://example.com/dashboard#access_token=synthetic-secret&token_type=bearer");

        assertFalse(safe.contains("synthetic-secret"));
        assertTrue(safe.contains("access_token=[redacted]"));
        assertTrue(safe.startsWith("https://example.com/dashboard#"));
    }

    @Test
    void aFragmentIsNotSwallowedIntoTheLastQueryParameter() {
        // Before the fragment was split off first, everything after '#' became part of the final
        // parameter's value and escaped redaction completely.
        String safe = SensitiveDataFilter.sanitizeUrl(
            "https://example.com/dashboard?tab=profile#session=synthetic-session");

        assertFalse(safe.contains("synthetic-session"));
        assertTrue(safe.contains("tab=profile"));
        assertTrue(safe.contains("session=[redacted]"));
    }

    @Test
    void anOrdinaryPageAnchorIsLeftExactlyAsItIs() {
        String url = "https://example.com/docs/guide#section-two";
        assertEquals(url, SensitiveDataFilter.sanitizeUrl(url));

        String withQuery = "https://example.com/docs?page=2#section-two";
        assertEquals(withQuery, SensitiveDataFilter.sanitizeUrl(withQuery));
    }

    @Test
    void aFragmentWithSafeParametersIsPreserved() {
        String url = "https://example.com/app#view=list&page=3";
        assertEquals(url, SensitiveDataFilter.sanitizeUrl(url));
    }

    @Test
    void anEmptyFragmentDoesNotBreakSanitization() {
        assertEquals("https://example.com/app#", SensitiveDataFilter.sanitizeUrl("https://example.com/app#"));
    }
}
