package com.qanize.automation.checks.smoke;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;

import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.HttpStatusClassification;
import com.qanize.automation.checks.shared.PageStatusProbe;
import com.qanize.automation.evidence.EvidenceRecorder;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.evidence.SensitiveDataFilter;
import com.qanize.automation.model.TestedItem;

/**
 * The health of one already-open page.
 *
 * <p>This is the Basic website check the UI names, and the check the contract calls {@code SMOKE}:
 * did a usable page come back? It reads the final address, the title and whether a document loaded,
 * and optionally compares the title and the page text with configured expectations.
 *
 * <p><strong>It inspects; it does not navigate.</strong> There is no page opening here, no driver
 * creation or teardown, no click, no form submission, and nothing that belongs to Links,
 * Navigation, Forms, Search or Login. That is deliberate and is what makes it reusable: today the
 * JUnit wrapper opens the page and calls this once, and later a crawler will open each page and
 * call the same module on every one of them.
 *
 * <p><strong>It owns no queue.</strong> Nothing here records visited pages or decides where to go
 * next.
 *
 * <p><strong>It never asserts.</strong> The outcome is returned so the same inspection can back a
 * JUnit wrapper and, later, a run engine.
 *
 * <p><strong>A missing title is a question, not a verdict.</strong> A page that came back without
 * one used to be failed as "the page did not load", which is how {@code /basic_auth} — a page
 * whose authentication was working exactly as designed — was reported as a defect on the
 * customer's site. The browser cannot tell the two apart: a 401 whose credential prompt was
 * dismissed renders an empty, titleless document that looks identical to a broken page. So when
 * there is no title, and only then, the module asks its {@link PageStatusProbe} what the server
 * actually answered. A protected or rate-limited resource is reported as a page that could not be
 * checked; anything else keeps the failure it always had, now able to name the status it saw.
 */
public final class PageHealthModule {

    /** Contract 1.0 item id for the page itself. Unchanged. */
    static final String PAGE_ITEM_ID = "smoke-page-1";

    private final PageState page;
    private final SmokeExecutionEvidence execution;
    private final EvidenceScope scope;
    /** Consulted only for a page that produced no title. {@link PageStatusProbe#NONE} by default. */
    private final PageStatusProbe statusProbe;

    /**
     * The production entry point.
     *
     * @param page      a page whose browser is already on the address being inspected
     * @param execution the recorder the caller already used, so per-test-case sequence numbers
     *                  continue rather than restarting
     */
    public PageHealthModule(CommonPage page, SmokeExecutionEvidence execution) {
        this(new CommonPageState(page), execution, EvidenceScope.legacy());
    }

    /**
     * The entry point for one crawled page.
     *
     * @param scope which page this inspection belongs to, so its evidence cannot collide with
     *              another page's
     */
    public PageHealthModule(
        CommonPage page, SmokeExecutionEvidence execution, EvidenceScope scope
    ) {
        this(new CommonPageState(page), execution, scope, PageStatusProbe.NONE);
    }

    /**
     * The entry point for one crawled page, with a way to learn what the server answered.
     *
     * @param statusProbe asked for a status only when the page produced no title, and never
     *                    otherwise; {@link PageStatusProbe#NONE} keeps the browser-only behaviour
     */
    public PageHealthModule(
        CommonPage page, SmokeExecutionEvidence execution, EvidenceScope scope,
        PageStatusProbe statusProbe
    ) {
        this(new CommonPageState(page), execution, scope, statusProbe);
    }

    /** The seam a test uses to supply its own page state. Not part of the public API. */
    PageHealthModule(PageState page, SmokeExecutionEvidence execution) {
        this(page, execution, EvidenceScope.legacy());
    }

    /** The seam a test uses to supply its own page state and scope. Not part of the public API. */
    PageHealthModule(PageState page, SmokeExecutionEvidence execution, EvidenceScope scope) {
        this(page, execution, scope, PageStatusProbe.NONE);
    }

    /** The seam a test uses to supply its own page state, scope and probe. Not public API. */
    PageHealthModule(
        PageState page, SmokeExecutionEvidence execution, EvidenceScope scope,
        PageStatusProbe statusProbe
    ) {
        this.page = page;
        this.execution = execution;
        this.scope = Objects.requireNonNull(
            scope, "scope must not be null; use the two-argument constructor for the legacy scope");
        this.statusProbe = statusProbe == null ? PageStatusProbe.NONE : statusProbe;
    }

    /**
     * Inspects the open page.
     *
     * @param requested         the address the caller asked for, used as the item's identity
     * @param navigationFailure the caller's own navigation failure, already recorded, or null when
     *                          the page opened. A page that never opened is still reported as a
     *                          failed page rather than as an absence of evidence.
     * @param expectedTitle     optional configured title fragment, or null
     * @param expectedText      optional configured page text, or null
     */
    public Result inspect(
        String requested, String navigationFailure, String expectedTitle, String expectedText
    ) {
        Instant startedAt = Instant.now();
        String startedIso = startedAt.toString();

        String finalUrl = null;
        String title = null;
        boolean loaded = false;
        String failure = navigationFailure;
        if (failure == null) {
            long stepStart = System.nanoTime();
            try {
                finalUrl = page.currentUrl();
                execution.readAddress(requested, finalUrl, elapsedMs(stepStart));

                stepStart = System.nanoTime();
                title = page.title();
                execution.readTitle(requested, title, elapsedMs(stepStart));

                stepStart = System.nanoTime();
                loaded = page.hasLoadedDocument();
                execution.readContent(requested, loaded, elapsedMs(stepStart));
            } catch (RuntimeException browserFailure) {
                failure = browserFailure.getClass().getSimpleName() + ": " + browserFailure.getMessage();
                execution.browserFailed(
                    requested, SensitiveDataFilter.sanitizeMessage(failure), elapsedMs(stepStart));
            }
        }

        boolean ok = failure == null
            && finalUrl != null && !finalUrl.isBlank()
            && title != null && !title.isBlank()
            && loaded;
        boolean redirected = finalUrl != null && !requested.equals(finalUrl);

        // The one question the browser cannot answer, asked only where it decides the verdict: a
        // document came back and it has no title. Everything else — a page with a title, a browser
        // that never got a document at all — is settled already and is not probed.
        Integer status = null;
        HttpStatusClassification classification = HttpStatusClassification.NO_RESPONSE;
        boolean titleless = failure == null && loaded && (title == null || title.isBlank());
        if (titleless) {
            String address = finalUrl == null || finalUrl.isBlank() ? requested : finalUrl;
            OptionalInt probed = statusProbe.statusOf(address);
            if (probed.isPresent()) {
                status = probed.getAsInt();
                classification = HttpStatusClassification.of(status);
                execution.readProtectedStatus(requested, status, classification.name());
            }
        }
        // A protected or rate-limited resource is not a page that failed to load. It is a page
        // this run was not able to look at, which is a skip with a reason — the same distinction
        // every other check already draws between "the website is wrong" and "we could not check".
        boolean unverified = classification.unverified();

        TestedItem.Status itemStatus = ok
            ? TestedItem.Status.PASSED
            : unverified ? TestedItem.Status.SKIPPED : TestedItem.Status.FAILED;

        TestedItem.Builder item = TestedItem.builder("SMOKE", TestedItem.ItemType.PAGE)
            .id(scope.itemId(PAGE_ITEM_ID))
            .name("Submitted page")
            .pageUrl(requested)
            .target(requested)
            .action("Opened the submitted website and waited for the page-ready condition.")
            .expected("The page returns a document with a non-blank address and title.")
            .startedAt(startedIso)
            .completedAt(Instant.now().toString())
            .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
            .status(itemStatus)
            .meta("redirected", redirected ? "true" : "false");
        if (finalUrl != null && !finalUrl.isBlank()) item.meta("finalUrl", finalUrl);
        if (title != null && !title.isBlank()) item.meta("pageTitle", title);
        if (status != null) {
            item.httpStatus(status);
            // The classification, published under its own key so a report never re-derives it.
            item.meta("pageAccess", classification.name());
        }
        String actualSummary = actualSummary(
            failure, title, redirected, finalUrl, titleless, status, classification);
        item.actual(actualSummary);
        if (failure != null) {
            item.message(failure);
        } else if (unverified) {
            item.message(actualSummary);
        }
        // Last in the chain: enrichment only fills keys the check left absent.
        EvidenceRecorder.record(scope.enrich(item).build());

        // Recorded before the verdict is returned, so it survives whatever the caller does next.
        // Told what the page turned out to be, so a protected page is not recorded as a failure.
        execution.assertedUsablePage(
            requested,
            ok ? HttpStatusClassification.SUCCESS
               : unverified ? classification : HttpStatusClassification.BROKEN,
            actualSummary);

        // Fail-fast, in the order the check has always reported.
        if (finalUrl == null || finalUrl.isBlank()) {
            return new Result(Outcome.FAILED, "Current URL should not be blank after opening baseUrl");
        }
        if (title == null || title.isBlank()) {
            return unverified
                ? new Result(Outcome.SKIPPED, actualSummary)
                : new Result(Outcome.FAILED, titleFailure(status, classification));
        }
        if (!loaded) {
            return new Result(Outcome.FAILED, "Page source/body should not be blank");
        }

        if (expectedTitle != null) {
            // Re-read rather than reuse: the configured comparison has always asked the page again.
            String actualTitle = page.title();
            boolean matched = actualTitle.contains(expectedTitle);
            TestedItem.Builder titleItem = TestedItem.builder("SMOKE", TestedItem.ItemType.PAGE)
                .id(scope.itemId("smoke-expected-title"))
                .name("Expected page title")
                .pageUrl(requested)
                .action("Compared the page title with the configured expected text.")
                .expected("Title contains \"" + expectedTitle + "\".")
                .actual("Title is \"" + actualTitle + "\".")
                .status(matched ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
                .completedAt(EvidenceRecorder.nowIso());
            EvidenceRecorder.record(scope.enrich(titleItem).build());
            execution.assertedExpectedTitle(requested, expectedTitle, actualTitle, matched);
            if (!matched) {
                return new Result(Outcome.FAILED,
                    "Page title did not contain expected text: " + expectedTitle);
            }
        }

        if (expectedText != null) {
            long searchStart = System.nanoTime();
            boolean present = page.isTextPresent(expectedText);
            execution.searchedForExpectedText(
                requested, expectedText, present, elapsedMs(searchStart));
            TestedItem.Builder textItem = TestedItem.builder("SMOKE", TestedItem.ItemType.PAGE)
                .id(scope.itemId("smoke-expected-text"))
                .name("Expected page text")
                .pageUrl(requested)
                .action("Searched the loaded page for the configured expected text.")
                .expected("Page contains \"" + expectedText + "\".")
                .actual(present ? "The text was found." : "The text was not found.")
                .status(present ? TestedItem.Status.PASSED : TestedItem.Status.FAILED)
                .completedAt(EvidenceRecorder.nowIso());
            EvidenceRecorder.record(scope.enrich(textItem).build());
            if (!present) {
                return new Result(Outcome.FAILED,
                    "Page did not contain expected visible/source text: " + expectedText);
            }
        }

        return new Result(Outcome.PASSED, null);
    }

    /**
     * What was observed, said in the words the observation supports.
     *
     * <p>"The browser could not load the page" belongs to a page that never arrived. A page that
     * arrived without a title is described as exactly that, with the status when one is known —
     * which is what stops a report reading "did not load as expected" directly above "Loaded."
     */
    private static String actualSummary(
        String failure,
        String title,
        boolean redirected,
        String finalUrl,
        boolean titleless,
        Integer status,
        HttpStatusClassification classification
    ) {
        if (failure != null) return "The browser could not load the page.";
        String tail = redirected ? ", after redirecting to " + finalUrl : "";
        if (titleless && classification.unverified()) {
            return classification.reason(status);
        }
        if (titleless) {
            return "Loaded a document with no page title"
                + (status == null ? "" : ", and the address answered HTTP " + status)
                + tail + ".";
        }
        return "Loaded" + (title != null && !title.isBlank() ? " with title \"" + title + "\"" : "")
            + tail + ".";
    }

    /** The failure message for a titleless page, naming the status when one was established. */
    private static String titleFailure(Integer status, HttpStatusClassification classification) {
        if (status == null) return "Page title should not be blank";
        return "Page title should not be blank; the address answered HTTP " + status
            + (classification == HttpStatusClassification.BROKEN ? " and the destination is broken" : "");
    }

    /** Wall-clock milliseconds around one real browser call. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    // --- what the caller gets back ------------------------------------------------------------

    /**
     * How one page inspection ended.
     *
     * <p>{@link #SKIPPED} is the third answer the check needed and did not have: the page was
     * reached, the server answered, and the run still could not look at it — an authentication
     * challenge being the case that forced the distinction. Neither a pass, because nothing was
     * verified, nor a failure, because the website behaved correctly.
     */
    public enum Outcome { PASSED, FAILED, SKIPPED }

    /** The outcome of one page inspection, and the message the caller reports with it. */
    public record Result(Outcome outcome, String message) {

        public boolean passed() {
            return outcome == Outcome.PASSED;
        }

        /** Whether the page could not be checked, rather than having failed a check. */
        public boolean skipped() {
            return outcome == Outcome.SKIPPED;
        }
    }

    // --- the page reads this check needs ------------------------------------------------------

    /**
     * The four reads a page-health inspection performs. Deliberately not a general abstraction: it
     * exists so this one module can be driven without a browser, and it exposes nothing that could
     * navigate, click or submit.
     */
    interface PageState {

        String currentUrl();

        String title();

        boolean hasLoadedDocument();

        boolean isTextPresent(String expectedText);
    }

    /** The production reads, over the page the caller already opened. */
    private record CommonPageState(CommonPage page) implements PageState {

        @Override
        public String currentUrl() {
            return page.getCurrentUrl();
        }

        @Override
        public String title() {
            return page.getTitle();
        }

        @Override
        public boolean hasLoadedDocument() {
            return page.hasLoadedDocument();
        }

        @Override
        public boolean isTextPresent(String expectedText) {
            return page.isTextPresent(expectedText);
        }
    }
}
