package com.qanize.automation.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.qanize.automation.browser.ScreenshotCaptureService;
import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.model.CheckId;

import org.openqa.selenium.WebDriver;

/**
 * The one place a crawl asks for a screenshot.
 *
 * <p>A crawl has failures the six single-page wrappers never had: a sign-in the site refused, a
 * page that would not open, a module that returned a failing verdict, a module that threw. Each is
 * known at a moment when the browser is still showing the thing that went wrong, and a moment
 * later the next module has moved on. This exists so that moment can be used, once, by whoever is
 * standing in it — without the runner or the per-page bridge learning anything about how a
 * screenshot is taken, redacted or named.
 *
 * <p><strong>It is a collaborator, not a mechanism.</strong> Every picture is taken by the same
 * {@link ScreenshotCaptureService} the JUnit extension uses, with the same masking, the same
 * directory resolution and the same containment rules. There is no second redaction here and no
 * second way to write a file.
 *
 * <p><strong>It owns no browser.</strong> The driver arrives as an argument on every call, is
 * never stored, and is never created, navigated or quit. That is deliberate: a driver field would
 * be one run's browser outliving that run.
 *
 * <p><strong>Credentials live here for one run and no longer.</strong> The runner hands them over
 * once, when a sign-in is actually going to happen, so that both the sign-in screenshot and every
 * later page screenshot can mask them. They are held in a plain list owned by this instance —
 * not static, not thread-local, not a field on the capture service, and never copied into a label,
 * a result, a message or an exception — and {@link #clear()} empties it during the runner's
 * cleanup.
 *
 * <p><strong>Ownership is typed, not spelled.</strong> A caller names a page number and a
 * {@link CheckTarget}; it cannot name a URL, a selector or an exception message, because there is
 * no parameter for one. That typed pair is what travels into the run result and, from there, into
 * the summary — so no later stage ever has to take a file name apart to work out which page and
 * which check a picture belongs to. The file name exists only to find the picture again.
 *
 * <p><strong>Nothing here throws.</strong> A capture that fails produces a {@link ScreenshotRef}
 * that says so and carries a stage and an exception class name — never the browser's own
 * exception, which can quote the page it was looking at. Supporting evidence must never become the
 * failure a run reports.
 */
public final class CrawlScreenshotRecorder {

    /** A capture that belongs to the run rather than to any crawled page. */
    public static final int RUN_LEVEL = 0;

    /**
     * What a crawl can photograph, and which reported check — if any — owns the picture.
     *
     * <p>This adds no status vocabulary: every value that belongs to a reported check maps onto
     * the existing {@link CheckId}, which is the single source of truth for what a check is
     * called. The two values that map to nothing map to nothing <em>on purpose</em>:
     *
     * <ul>
     *   <li>{@link #PAGE_OPEN} — a page that would not open produces no finding of its own. The
     *       Navigation module reports that same failed open, but attaching the picture there would
     *       be a guess: on a page where Navigation also failed for its own reason, the guess would
     *       be wrong and unfalsifiable. So it stays a general run artifact.</li>
     *   <li>{@link #UNEXPECTED} — something escaped the runner. That is an analyzer error, which
     *       the contract reports in {@code errors[]} rather than as a website finding, so there is
     *       no finding to attach it to and inventing one would be a fabricated fact.</li>
     * </ul>
     *
     * <p>The label key is the word that appears in a file name. It is fixed here rather than
     * supplied, which is what makes a label incapable of carrying anything a caller was holding.
     */
    public enum CheckTarget {

        /** The sign-in the website refused. Established once per run, so it has no page. */
        LOGIN("login", CheckId.LOGIN),

        /** A page the browser could not open. No finding of its own; see the class notes. */
        PAGE_OPEN("open", null),

        /** The page-health check, reported as {@link CheckId#SMOKE}. */
        PAGE_HEALTH("page-health", CheckId.SMOKE),

        LINKS("links", CheckId.LINKS),

        FORMS("forms", CheckId.FORMS),

        NAVIGATION("navigation", CheckId.NAVIGATION),

        SEARCH("search", CheckId.SEARCH),

        /**
         * The registered target for {@link CheckId#GAME_LAUNCH}. Nothing captures against it yet;
         * it exists so the label vocabulary is fixed before the check is written. Declared last
         * among the real targets, ahead of the {@code UNEXPECTED} catch-all.
         */
        GAME_LAUNCH("game-launch", CheckId.GAME_LAUNCH),

        /** Something escaped the runner. An analyzer error, not a website finding. */
        UNEXPECTED("unexpected", null);

        private final String labelKey;
        private final CheckId checkId;

        CheckTarget(String labelKey, CheckId checkId) {
            this.labelKey = labelKey;
            this.checkId = checkId;
        }

        /** The word this target contributes to a file name. Fixed, never supplied by a caller. */
        public String labelKey() {
            return labelKey;
        }

        /** The reported check that owns pictures of this kind, or empty when none truthfully does. */
        public Optional<CheckId> checkId() {
            return Optional.ofNullable(checkId);
        }

        /** Whether this belongs to the run as a whole rather than to one crawled page. */
        public boolean runLevel() {
            return this == LOGIN || this == UNEXPECTED;
        }
    }

    private final CaptureOperation screenshots;
    private final List<String> sensitiveValues = new ArrayList<>();
    private final List<ScreenshotRef> captured = new ArrayList<>();

    public CrawlScreenshotRecorder() {
        this(new ScreenshotCaptureService()::capture);
    }

    /** The seam a test uses to observe exactly what was asked of the capture service. */
    CrawlScreenshotRecorder(CaptureOperation screenshots) {
        this.screenshots = screenshots;
    }

    /**
     * The one call this collaborator makes into the capture service.
     *
     * <p>Narrow enough that a test can watch the driver, the file name and the values handed over
     * on every capture, without any of it becoming a second way to take a picture: the production
     * implementation is a method reference to the real service and nothing else.
     */
    @FunctionalInterface
    interface CaptureOperation {

        ScreenshotCaptureService.Result capture(
            WebDriver driver, String fileName, List<String> sensitiveValues);
    }

    /**
     * Forgets everything the previous run left behind.
     *
     * <p>Called at the start of a run. Without it a recorder reused across two runs would hand the
     * second run's result the first run's pictures, which is exactly the kind of quiet cross-run
     * bleed this whole design exists to prevent.
     */
    public void startRun() {
        sensitiveValues.clear();
        captured.clear();
    }

    /**
     * Adds one value to mask on every screenshot this run takes.
     *
     * <p>Blank, null and repeated values are dropped: none of them can hide anything.
     */
    public void protect(String value) {
        if (value != null && !value.isBlank() && !sensitiveValues.contains(value)) {
            sensitiveValues.add(value);
        }
    }

    /**
     * Takes one screenshot, now, of the browser the caller is holding.
     *
     * @param driver the caller's browser; borrowed, never created, navigated or quit
     * @param pageNumber the crawled page this belongs to, or {@link #RUN_LEVEL}
     * @param target which check the picture belongs to; typed, so it cannot carry anything else
     * @return what happened, always recorded, never throwing
     */
    public ScreenshotRef capture(WebDriver driver, int pageNumber, CheckTarget target) {
        CheckTarget owner = target == null ? CheckTarget.UNEXPECTED : target;
        String label = labelFor(pageNumber, owner);
        ScreenshotCaptureService.Result result =
            screenshots.capture(driver, label + ".png", sensitiveValues);

        ScreenshotRef reference = new ScreenshotRef(
            owner.runLevel() ? RUN_LEVEL : pageNumber,
            owner,
            fileNameOf(result),
            result.stage(),
            result.failureType());
        captured.add(reference);
        return reference;
    }

    /**
     * Every capture this run attempted, in the order it attempted them.
     *
     * <p>This is what the run result copies, and it is how the summary associates a picture with
     * the page and check it belongs to without taking a file name apart.
     */
    public List<ScreenshotRef> captured() {
        return List.copyOf(captured);
    }

    /**
     * Drops the values this run was masking.
     *
     * <p>Called from the runner's cleanup. The record of what was captured is kept: it names no
     * secret, and the caller still has to read it after the run has ended.
     */
    public void clear() {
        sensitiveValues.clear();
    }

    /**
     * {@code login-failed}, {@code crawler-unexpected-failure}, {@code page-002-links-failed}.
     *
     * <p>Page ids come from {@link EvidenceScope#pageId(int)}, so a screenshot and the tested-item
     * ids recorded for the same page agree about which page that was. The name is for finding the
     * file again; nothing downstream reads ownership out of it.
     */
    static String labelFor(int pageNumber, CheckTarget target) {
        CheckTarget owner = target == null ? CheckTarget.UNEXPECTED : target;
        if (!owner.runLevel() && pageNumber > RUN_LEVEL) {
            return EvidenceScope.pageId(pageNumber) + "-" + owner.labelKey() + "-failed";
        }
        return owner == CheckTarget.UNEXPECTED
            ? "crawler-unexpected-failure"
            : owner.labelKey() + "-failed";
    }

    private static String fileNameOf(ScreenshotCaptureService.Result result) {
        return result.screenshot().map(Path::getFileName).map(Path::toString).orElse(null);
    }

    /**
     * One capture attempt, and who it was for.
     *
     * <p>Every component is safe to print: a page number, a fixed enum, a file name this class
     * generated from that enum, and a capture stage with an exception <em>class</em> name. No URL,
     * credential, selector or browser message can reach it, so the default {@code toString()} is
     * safe and is deliberately left alone.
     *
     * <p>The file name rather than the full path: the picture lives in the run's screenshots
     * directory, which the summary already reads, so the leaf is what identifies it and an
     * absolute machine path in a result would be noise at best.
     *
     * @param pageNumber the crawled page, or {@link #RUN_LEVEL} for a run-level capture
     * @param target which check this belongs to
     * @param fileName the written file, or null when nothing was written
     * @param stage where a failed capture failed
     * @param failureType the failing exception's class name, never its message
     */
    public record ScreenshotRef(
        int pageNumber,
        CheckTarget target,
        String fileName,
        ScreenshotCaptureService.Stage stage,
        String failureType
    ) {

        public ScreenshotRef {
            target = target == null ? CheckTarget.UNEXPECTED : target;
            failureType = failureType == null ? "" : failureType;
        }

        /** Whether a file was actually written. A failed attempt associates with nothing. */
        public boolean captured() {
            return fileName != null && !fileName.isBlank();
        }

        /** Whether this belongs to the run rather than to one crawled page. */
        public boolean runLevel() {
            return target.runLevel();
        }

        /** The reported check that owns this picture, or empty when none truthfully does. */
        public Optional<CheckId> checkId() {
            return target.checkId();
        }
    }
}
