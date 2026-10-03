package com.qanize.automation.evidence;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.qanize.automation.model.TestedItem;

/**
 * Which page a piece of evidence belongs to.
 *
 * <p>The checks name what they inspect with fixed identities: page health always records
 * {@code smoke-page-1}, and Links numbers its requests {@code link-1}, {@code link-2} and so on.
 * Over one page that is unambiguous. Over ten crawled pages the tenth page's {@code link-1} is
 * indistinguishable from the first page's, and — because contract 1.1 requires step sequences to
 * start at 1 and run without gaps <em>within a test case</em> — two pages sharing one case id
 * produce an artifact that a reader rejects as duplicated and out of order.
 *
 * <p>This is the one mechanism that fixes that, and it is deliberately the only one: Navigation,
 * Forms and Search can adopt it later without inventing a second.
 *
 * <h2>Two scopes</h2>
 *
 * <p>{@link #legacy()} changes nothing. Every identity passes through untouched and no metadata is
 * added, so a single-page check records byte-for-byte what it always did. It is the default
 * everywhere, which is why no existing caller had to change.
 *
 * <p>{@link #forPage} qualifies every identity with a stable page id.
 *
 * <h2>The serialized identity format</h2>
 *
 * <p>A scoped identity is exactly:
 *
 * <pre>{@code   <pageId> "/" <moduleId>        e.g.  page-001/link-1  }</pre>
 *
 * <p>The page id is {@code "page-"} followed by the page number zero-padded to at least three
 * digits, so ids sort in visiting order as plain text and the prefix is a fixed shape a machine
 * can rely on. The separator is {@code /} and appears exactly once, at a known offset, because the
 * page id cannot itself contain one.
 *
 * <p><strong>Splitting that string is not how a reader is meant to find the page.</strong> It is a
 * uniqueness device. Ownership is published explicitly as metadata as well, so a consumer reads a
 * field instead of parsing an identity.
 *
 * <h2>The page metadata</h2>
 *
 * <p>Written into the existing {@code Map<String, String>} that contract 1.0 already carries and
 * already serializes flat as {@code metadata.<key>}. No field was added to the frozen contract and
 * no reader had to change; an old reader simply sees keys it does not recognise.
 *
 * <ul>
 *   <li>{@code page.id} — {@code page-001}</li>
 *   <li>{@code page.number} — {@code 1}</li>
 *   <li>{@code page.url} — the sanitized page address</li>
 *   <li>{@code page.depth} — {@code 0} for the start page</li>
 *   <li>{@code page.discoveredFrom} — the sanitized parent address, absent for the start page</li>
 * </ul>
 *
 * <p>Every key sits under the reserved {@code page.} prefix, which no check uses, and enrichment
 * only ever fills a key that is absent — so a value a module owns can never be replaced by one
 * from here.
 *
 * <h2>What this is not</h2>
 *
 * <p>Immutable, and passed explicitly from the caller that knows which page is being audited.
 * There is no global current page, no static mutable field and no {@code ThreadLocal}: a scope
 * that has to be handed over cannot silently apply to the wrong page, and two runs cannot share
 * one. Addresses are sanitized on the way in, so a scope cannot carry a session token.
 */
public final class EvidenceScope {

    /** Separates the page id from the check's own identity. Appears exactly once. */
    public static final String SEPARATOR = "/";

    /** The reserved metadata namespace. No check writes a key beginning with this. */
    public static final String META_PREFIX = "page.";

    public static final String META_ID = META_PREFIX + "id";
    public static final String META_NUMBER = META_PREFIX + "number";
    public static final String META_URL = META_PREFIX + "url";

    /**
     * Where the browser actually ended up, written only when it differs from {@link #META_URL}.
     *
     * <p>A queued address and the document that came back are two different facts. Keeping both
     * means a page's evidence can say "you asked for this, you got that" without either address
     * having to be overwritten -- which is what previously made a redirected run look as though the
     * requested page had been tested.
     */
    public static final String META_LANDED_URL = META_PREFIX + "landedUrl";

    public static final String META_DEPTH = META_PREFIX + "depth";
    public static final String META_DISCOVERED_FROM = META_PREFIX + "discoveredFrom";

    private static final EvidenceScope LEGACY = new EvidenceScope(null, 0, null, null, 0, null);

    private final String pageId;
    private final int pageNumber;
    private final String pageUrl;
    private final String landedUrl;
    private final int depth;
    private final String discoveredFrom;

    private EvidenceScope(
        String pageId, int pageNumber, String pageUrl, String landedUrl, int depth,
        String discoveredFrom
    ) {
        this.pageId = pageId;
        this.pageNumber = pageNumber;
        this.pageUrl = pageUrl;
        this.landedUrl = landedUrl;
        this.depth = depth;
        this.discoveredFrom = discoveredFrom;
    }

    /**
     * The scope every single-page check uses: identities and metadata exactly as before.
     *
     * <p>One shared immutable instance, because it holds nothing.
     */
    public static EvidenceScope legacy() {
        return LEGACY;
    }

    /**
     * The scope for one crawled page.
     *
     * @param pageNumber     1 for the start page, then the order pages were attempted in
     * @param pageUrl        the page address, raw or sanitized; it is sanitized here either way
     * @param depth          0 for the start page
     * @param discoveredFrom the parent address, or null for the start page
     * @throws IllegalArgumentException when the identity could not describe a real page
     */
    public static EvidenceScope forPage(
        int pageNumber, String pageUrl, int depth, String discoveredFrom
    ) {
        return forPage(pageNumber, pageUrl, null, depth, discoveredFrom);
    }

    /**
     * The scope for one crawled page, when the browser may have landed somewhere else.
     *
     * @param landedUrl the address the browser actually reached, or null when it is the same as
     *                  {@code pageUrl}; an equal value is dropped rather than published twice
     */
    public static EvidenceScope forPage(
        int pageNumber, String pageUrl, String landedUrl, int depth, String discoveredFrom
    ) {
        if (pageNumber < 1) {
            throw new IllegalArgumentException(
                "pageNumber must be at least 1 because the start page is page 1, but was "
                    + pageNumber);
        }
        if (depth < 0) {
            throw new IllegalArgumentException(
                "depth must not be negative because the start page is depth 0, but was " + depth);
        }
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new IllegalArgumentException("pageUrl must not be blank");
        }
        // Sanitized on the way in, so nothing downstream can publish a token by accident.
        String requested = SensitiveDataFilter.sanitizeUrl(pageUrl);
        String landed = landedUrl == null || landedUrl.isBlank()
            ? null : SensitiveDataFilter.sanitizeUrl(landedUrl);
        return new EvidenceScope(
            pageId(pageNumber),
            pageNumber,
            requested,
            landed == null || landed.equals(requested) ? null : landed,
            depth,
            SensitiveDataFilter.sanitizeUrl(discoveredFrom));
    }

    /** {@code page-001}. Zero-padded to at least three digits so ids sort in visiting order. */
    public static String pageId(int pageNumber) {
        return String.format(Locale.ROOT, "page-%03d", pageNumber);
    }

    /** Whether this scope leaves every identity exactly as the check wrote it. */
    public boolean isLegacy() {
        return pageId == null;
    }

    /** {@code page-001}, or null under the legacy scope. */
    public String pageId() {
        return pageId;
    }

    /** 0 under the legacy scope. */
    public int pageNumber() {
        return pageNumber;
    }

    /** The sanitized requested page address, or null under the legacy scope. */
    public String pageUrl() {
        return pageUrl;
    }

    /**
     * The sanitized address the browser actually reached, when it differs from {@link #pageUrl()}.
     *
     * <p>Null when the browser stayed where it was sent, and null under the legacy scope.
     */
    public String landedUrl() {
        return landedUrl;
    }

    /** 0 under the legacy scope. */
    public int depth() {
        return depth;
    }

    /** The sanitized parent address, or null for the start page and under the legacy scope. */
    public String discoveredFrom() {
        return discoveredFrom;
    }

    /**
     * The contract 1.0 tested-item id, qualified by page.
     *
     * <p>{@code smoke-page-1} becomes {@code page-002/smoke-page-1}; under the legacy scope it
     * stays {@code smoke-page-1}.
     */
    public String itemId(String moduleItemId) {
        return qualify(moduleItemId);
    }

    /**
     * The contract 1.1 execution test-case id, qualified by page.
     *
     * <p>This is what gives each page its own step sequence: the recorder numbers steps per test
     * case, so two pages with different case ids each begin at 1 and stay continuous.
     */
    public String testCaseId(String moduleTestCaseId) {
        return qualify(moduleTestCaseId);
    }

    private String qualify(String moduleId) {
        if (pageId == null || moduleId == null || moduleId.isBlank()) {
            return moduleId;
        }
        return pageId + SEPARATOR + moduleId;
    }

    /** The page ownership fields, in a stable order. Empty under the legacy scope. */
    public Map<String, String> pageMetadata() {
        if (pageId == null) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(META_ID, pageId);
        metadata.put(META_NUMBER, Integer.toString(pageNumber));
        metadata.put(META_URL, pageUrl);
        if (landedUrl != null && !landedUrl.isBlank()) {
            metadata.put(META_LANDED_URL, landedUrl);
        }
        metadata.put(META_DEPTH, Integer.toString(depth));
        if (discoveredFrom != null && !discoveredFrom.isBlank()) {
            metadata.put(META_DISCOVERED_FROM, discoveredFrom);
        }
        return Map.copyOf(metadata);
    }

    /**
     * Adds the page ownership fields to one tested item, without ever replacing a value the check
     * itself set.
     *
     * <p>Call it last in a builder chain: enrichment fills only keys that are still absent, so
     * whatever the module wrote wins. Combined with the reserved {@code page.} namespace, a
     * collision needs a check to deliberately claim a reserved key — and even then the check's
     * value is the one that survives.
     *
     * @return the same builder, so it can close a chain
     */
    public TestedItem.Builder enrich(TestedItem.Builder item) {
        if (pageId == null) {
            return item;
        }
        pageMetadata().forEach(item::metaIfAbsent);
        return item;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EvidenceScope scope)) return false;
        return pageNumber == scope.pageNumber
            && depth == scope.depth
            && Objects.equals(pageId, scope.pageId)
            && Objects.equals(pageUrl, scope.pageUrl)
            && Objects.equals(landedUrl, scope.landedUrl)
            && Objects.equals(discoveredFrom, scope.discoveredFrom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, pageNumber, pageUrl, landedUrl, depth, discoveredFrom);
    }

    @Override
    public String toString() {
        return pageId == null
            ? "EvidenceScope[legacy]"
            : "EvidenceScope[" + pageId + ", url=" + pageUrl + ", depth=" + depth + "]";
    }
}
