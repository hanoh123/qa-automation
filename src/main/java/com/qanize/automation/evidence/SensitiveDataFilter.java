package com.qanize.automation.evidence;

import java.util.Locale;
import java.util.Set;

/**
 * Keeps credentials and other secrets out of evidence, summaries, logs and the UI.
 *
 * <p>The engine records field <em>structure</em>, never field values. This class is the single
 * place that decides whether an attribute is safe to echo back, and it masks user identifiers so
 * a run can be explained without disclosing who ran it.
 */
public final class SensitiveDataFilter {

    private static final Set<String> SENSITIVE_TYPES = Set.of("password", "hidden");

    private static final Set<String> SENSITIVE_WORDS = Set.of(
        "password", "passwd", "pwd", "secret", "token", "auth", "apikey", "api-key", "api_key",
        "credential", "session", "cookie", "csrf", "otp", "pin", "cvv", "card", "iban",
        "ssn", "security", "private"
    );

    private SensitiveDataFilter() {
    }

    /** True when a control's value must never be read or reported. */
    public static boolean isSensitiveField(String type, String name, String id) {
        String fieldType = safe(type);
        if (SENSITIVE_TYPES.contains(fieldType)) return true;
        String haystack = (safe(name) + " " + safe(id)).trim();
        for (String word : SENSITIVE_WORDS) {
            if (haystack.contains(word)) return true;
        }
        return false;
    }

    /** Field names can themselves leak context, so sensitive ones are reported generically. */
    public static String safeFieldName(String type, String name, String id) {
        if (isSensitiveField(type, name, id)) return "[redacted]";
        if (name != null && !name.isBlank()) return name.trim();
        if (id != null && !id.isBlank()) return id.trim();
        return null;
    }

    /** Masks emails, phone numbers and plain identifiers for display. */
    public static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int at = trimmed.indexOf('@');
        if (at > 0) {
            String local = trimmed.substring(0, at);
            String domain = trimmed.substring(at);
            return mask(local) + domain;
        }
        return mask(trimmed);
    }

    private static String mask(String value) {
        if (value.length() <= 2) return "*".repeat(value.length());
        return value.charAt(0) + "*".repeat(Math.max(1, value.length() - 2)) + value.charAt(value.length() - 1);
    }

    /**
     * Strips anything credential-shaped out of an exception or browser message before it reaches
     * the user, and bounds the length so stack-like output cannot leak context.
     */
    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) return null;
        String cleaned = message.replaceAll("[\\r\\n]+", " ").trim();
        cleaned = cleaned.replaceAll("(?i)\\b(password|passwd|pwd|token|secret|api[_-]?key|authorization|cookie|session)\\b\\s*[:=]\\s*\\S+", "$1=[redacted]");
        cleaned = cleaned.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", "[redacted-email]");
        cleaned = cleaned.replaceAll("\\b(?:[A-Za-z0-9_-]{20,})\\b", "[redacted-token]");
        return cleaned.length() > 400 ? cleaned.substring(0, 400) + "…" : cleaned;
    }

    /**
     * Makes a URL safe to publish as evidence.
     *
     * <p>Unlike the address a user submits, a discovered link comes from the site's own markup and
     * can carry a session token, reset key or API key in its query string. Only the <em>values</em>
     * of credential-shaped parameters are replaced, and any {@code user:password@} authority is
     * removed, so the destination stays recognisable and the report stays truthful about which page
     * was tested.
     *
     * <p>Deliberately not {@link #sanitizeMessage(String)}: that method replaces any run of twenty
     * or more characters with {@code [redacted-token]}, which would destroy ordinary long paths and
     * make the evidence useless.
     *
     * <p>The fragment is sanitized on the same terms as the query. A browser address read after a
     * sign-in is the one place a credential most often arrives after the {@code #}: an OAuth
     * implicit-flow redirect lands on {@code …/#access_token=…}, and a fragment is also the part a
     * server never sees, so nothing upstream will have stripped it.
     */
    public static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String value = url.replaceAll("[\\r\\n]+", " ").trim();

        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) {
            int authorityStart = schemeEnd + 3;
            int authorityEnd = value.indexOf('/', authorityStart);
            if (authorityEnd < 0) authorityEnd = value.length();
            String authority = value.substring(authorityStart, authorityEnd);
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                value = value.substring(0, authorityStart)
                    + "[redacted]@" + authority.substring(at + 1)
                    + value.substring(authorityEnd);
            }
        }

        // Split the fragment off first: otherwise it would be swallowed into the last query
        // parameter's value and escape redaction entirely.
        String fragment = null;
        int fragmentStart = value.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = value.substring(fragmentStart + 1);
            value = value.substring(0, fragmentStart);
        }

        int queryStart = value.indexOf('?');
        if (queryStart >= 0) {
            value = value.substring(0, queryStart) + "?"
                + sanitizeParameters(value.substring(queryStart + 1));
        }
        return fragment == null ? value : value + "#" + sanitizeParameters(fragment);
    }

    /**
     * Redacts the values of credential-shaped parameters in one {@code a=1&b=2} section.
     *
     * <p>A part with no {@code =} — an ordinary page anchor, or a bare flag — is passed through
     * untouched, so a normal address is never altered.
     */
    private static String sanitizeParameters(String section) {
        StringBuilder safe = new StringBuilder();
        for (String pair : section.split("&", -1)) {
            if (safe.length() > 0) safe.append('&');
            int equals = pair.indexOf('=');
            if (equals < 0) { safe.append(pair); continue; }
            String name = pair.substring(0, equals);
            safe.append(name).append('=')
                .append(isSensitiveField(null, name, name) ? "[redacted]" : pair.substring(equals + 1));
        }
        return safe.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
