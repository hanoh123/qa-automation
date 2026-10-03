package com.qanize.automation.browser;

import java.io.IOException;

/**
 * Says that a screenshot could not be taken, and carries nothing else.
 *
 * <p>This exists because the obvious alternative is unsafe. A browser exception routinely quotes
 * the page it was looking at — {@code "element not interactable: <input value='hunter2'>"} — and
 * once such an object is attached as a cause or a suppressed exception, every JUnit reporter, every
 * Maven log and every CI artifact prints the whole tree. Hiding the message behind an accessor
 * changes nothing, because nobody reads it through the accessor.
 *
 * <p>So the original is never carried. This exception is built from
 * {@link ScreenshotCaptureService.Result#safeMessage()}, which is assembled from a fixed
 * {@link ScreenshotCaptureService.Stage} and an exception class name, and it is deliberately
 * <em>causeless</em>: the two-argument {@link IOException} constructor is called with a null cause,
 * which both leaves {@link #getCause()} null and makes a later {@code initCause} throw rather than
 * quietly attach one. Nothing in this package adds a suppressed exception to it either, so the
 * whole tree is one node deep and every character of it was written here.
 *
 * <p>It extends {@link IOException} so that the existing screenshot call signature and the
 * {@code UncheckedIOException} wrapping in {@code BaseSeleniumTest} keep working unchanged; what
 * they now wrap is this, rather than whatever the browser threw.
 */
public final class ScreenshotCaptureFailure extends IOException {

    private static final long serialVersionUID = 1L;

    private final ScreenshotCaptureService.Stage stage;

    /**
     * @param stage which step failed
     * @param safeMessage must already be free of anything the browser supplied; use
     *     {@link ScreenshotCaptureService.Result#safeMessage()} rather than composing one
     */
    public ScreenshotCaptureFailure(ScreenshotCaptureService.Stage stage, String safeMessage) {
        super(safeMessage, null);
        this.stage = stage;
    }

    public ScreenshotCaptureService.Stage stage() {
        return stage;
    }
}
