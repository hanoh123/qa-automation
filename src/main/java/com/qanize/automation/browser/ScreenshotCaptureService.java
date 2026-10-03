package com.qanize.automation.browser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

/**
 * Masks what it can, takes one screenshot of a browser somebody else owns, and puts the page back.
 *
 * <p><strong>Ownership.</strong> The driver arrives as an argument and leaves untouched: this
 * class never creates, replaces, closes or quits it, never navigates and never refreshes. It holds
 * no driver field, so there is nothing for a second run to inherit.
 *
 * <p><strong>Secrets are arguments, not state.</strong> The values to mask are passed per capture
 * and copied into a local list that is cleared before the method returns. Nothing about a capture
 * survives it. The original page values are recorded inside the page itself, under a key unique to
 * the capture, and are deliberately never returned to Java — pulling them across the boundary
 * would put them in a heap dump, a log line or an exception trace for no benefit.
 *
 * <p><strong>Failures carry no borrowed text.</strong> A browser exception can quote the page it
 * was looking at, so a message such as {@code "element not interactable: <input value='hunter2'>"}
 * is a credential leak the moment anything prints it. This class therefore never stores, returns,
 * re-throws, wraps or logs the exception it caught. What survives a failure is two pieces of
 * information chosen from closed, non-sensitive vocabularies: which {@link Stage} failed, decided
 * by <em>where</em> the throw happened rather than by reading any message, and the exception's own
 * class name. Everything else is dropped at the catch site.
 *
 * <p><strong>What redaction actually does, and does not do.</strong> It is DOM manipulation, not
 * image processing. Three things happen before the shutter: password, tel and username-autocomplete
 * inputs are emptied and blurred; elements marked sensitive by attribute or class have their text
 * replaced and blurred; and every text node containing one of the supplied values has that value
 * replaced with a marker. Because the pixels are rendered after those edits, a value covered by one
 * of those rules genuinely does not appear in the saved image.
 *
 * <p>Everything else does. This is not OCR and not full-page secret removal. A value is still
 * visible in the screenshot when it lives in an attribute that is painted but not matched
 * ({@code placeholder}, {@code title}, {@code alt}, {@code aria-label}), inside an iframe, inside a
 * shadow root, drawn on a canvas, set through CSS {@code content}, rendered as part of an image, or
 * shown only as a partial or reformatted version of the supplied value. Callers must not treat a
 * captured screenshot as certified free of sensitive data.
 *
 * <p><strong>Restoration.</strong> The masking is temporary and is undone in a {@code finally}
 * path, so a capture taken part-way through a check does not leave the page in a mangled state. One
 * edit cannot be undone faithfully: replacing an element's text destroys any child elements it
 * contained, and restoring puts back the text alone. That is inherited from the original rule and
 * only affects elements the page itself marked as sensitive.
 */
public final class ScreenshotCaptureService {

    /**
     * Where a capture went wrong, decided structurally.
     *
     * <p>Each value is set at exactly one catch site wrapping exactly one operation, so the
     * classification is a fact about which call threw and never an interpretation of what it said.
     */
    public enum Stage {

        /** Nothing failed. */
        NONE,

        /** The masking script did not run to completion; nothing was photographed. */
        MASK_FAILED,

        /** The browser refused to photograph itself, or there was no browser to ask. */
        CAPTURE_FAILED,

        /** The destination could not be resolved, or the image could not be written to it. */
        WRITE_FAILED,

        /** The image was written, but the page could not be put back the way it was. */
        RESTORE_FAILED
    }

    /** Used when the caller passed no driver at all. Our own token, not a borrowed message. */
    static final String NO_DRIVER = "MissingDriver";

    /** Used when the driver cannot photograph itself. Our own token, not a borrowed message. */
    static final String NOT_PHOTOGRAPHIC = "DriverCannotScreenshot";

    /**
     * Records what it changes on {@code window[key]} and changes nothing else.
     *
     * <p>Returns nothing on purpose: the state it saves contains the very values being hidden.
     */
    static final String REDACT_SCRIPT = """
        const secrets = arguments[0].filter(value => value);
        const key = arguments[1];
        const state = { inputs: [], elements: [], textNodes: [] };

        document.querySelectorAll(
          "input[type='tel'], input[type='password'], " +
          "input[autocomplete='username'], input[autocomplete='tel']"
        ).forEach(element => {
          state.inputs.push({
            element: element,
            value: element.value,
            attribute: element.getAttribute('value'),
            filter: element.style.filter
          });
          element.value = '';
          element.setAttribute('value', '');
          element.style.filter = 'blur(8px)';
        });

        document.querySelectorAll(
          "[data-sensitive='true'], [class*='username'], [autocomplete='username']"
        ).forEach(element => {
          state.elements.push({
            element: element,
            text: element.textContent,
            filter: element.style.filter
          });
          element.textContent = '[REDACTED]';
          element.style.filter = 'blur(8px)';
        });

        if (document.body && secrets.length > 0) {
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          const textNodes = [];
          while (walker.nextNode()) {
            textNodes.push(walker.currentNode);
          }
          textNodes.forEach(node => {
            let replaced = node.nodeValue;
            secrets.forEach(secret => {
              replaced = replaced.split(secret).join('[REDACTED]');
            });
            if (replaced !== node.nodeValue) {
              state.textNodes.push({ node: node, value: node.nodeValue });
              node.nodeValue = replaced;
            }
          });
        }

        window[key] = state;
        return null;
        """;

    /** Undoes exactly what the redaction recorded, then drops the record from the page. */
    static final String RESTORE_SCRIPT = """
        const key = arguments[0];
        const state = window[key];
        if (!state) {
          return null;
        }
        state.inputs.forEach(saved => {
          saved.element.value = saved.value;
          if (saved.attribute === null) {
            saved.element.removeAttribute('value');
          } else {
            saved.element.setAttribute('value', saved.attribute);
          }
          saved.element.style.filter = saved.filter;
        });
        state.elements.forEach(saved => {
          saved.element.textContent = saved.text;
          saved.element.style.filter = saved.filter;
        });
        state.textNodes.forEach(saved => {
          saved.node.nodeValue = saved.value;
        });
        delete window[key];
        return null;
        """;

    /**
     * Masks, photographs once, restores.
     *
     * @param driver a browser the caller owns; it is neither closed nor navigated
     * @param fileName the desired name, sanitized before use; it cannot leave the directory
     * @param sensitiveValues values to hide for this capture only; null, blank and duplicate
     *     entries are dropped, and the list is not retained
     * @return where the image landed, or which stage failed. Never throws, and never carries a
     *     throwable, a message or any other text borrowed from the browser.
     */
    public Result capture(WebDriver driver, String fileName, List<String> sensitiveValues) {
        if (driver == null) {
            return Result.failed(Stage.CAPTURE_FAILED, NO_DRIVER);
        }
        if (!(driver instanceof TakesScreenshot screenshotDriver)) {
            return Result.failed(Stage.CAPTURE_FAILED, NOT_PHOTOGRAPHIC);
        }

        List<String> secrets = distinctSecrets(sensitiveValues);
        JavascriptExecutor scriptRunner =
            driver instanceof JavascriptExecutor runner ? runner : null;
        // Generated before the script runs, so a mask that throws part-way can still be undone.
        String stateKey = "__qanizeRedaction_" + UUID.randomUUID().toString().replace("-", "");

        Stage stage = Stage.NONE;
        String failureType = "";
        Path written = null;
        String restoreFailureType = null;

        try {
            if (scriptRunner != null) {
                try {
                    scriptRunner.executeScript(REDACT_SCRIPT, secrets, stateKey);
                } catch (RuntimeException maskFailure) {
                    stage = Stage.MASK_FAILED;
                    failureType = typeOf(maskFailure);
                }
            }

            Path target = null;
            if (stage == Stage.NONE) {
                try {
                    target = ScreenshotDirectoryResolver.screenshotPath(
                        withoutSecrets(fileName, secrets));
                } catch (IOException | RuntimeException destinationFailure) {
                    stage = Stage.WRITE_FAILED;
                    failureType = typeOf(destinationFailure);
                }
            }

            File photographed = null;
            if (stage == Stage.NONE) {
                try {
                    photographed = screenshotDriver.getScreenshotAs(OutputType.FILE);
                } catch (RuntimeException captureFailure) {
                    stage = Stage.CAPTURE_FAILED;
                    failureType = typeOf(captureFailure);
                }
            }

            if (stage == Stage.NONE) {
                try {
                    Files.copy(
                        photographed.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                    written = target.toAbsolutePath();
                } catch (IOException | RuntimeException writeFailure) {
                    stage = Stage.WRITE_FAILED;
                    failureType = typeOf(writeFailure);
                }
            }
        } finally {
            restoreFailureType = unmask(scriptRunner, stateKey);
            secrets.clear();
        }

        if (stage == Stage.NONE && restoreFailureType != null) {
            // Only reported when nothing earlier failed: a jammed shutter is the more useful
            // answer than the tidying that followed it.
            stage = Stage.RESTORE_FAILED;
            failureType = restoreFailureType;
        }
        return written == null
            ? Result.failed(stage, failureType)
            : Result.captured(written, stage, failureType);
    }

    /**
     * Undoing runs in a {@code finally} path, so a failure here must not become the failure the
     * caller sees, nor discard a screenshot that was taken successfully. It is therefore reported
     * as a stage rather than raised, and — like every other failure here — only its class name
     * survives.
     *
     * @return the failing exception's class name, or null when there was nothing to undo or the
     *     undo succeeded
     */
    private static String unmask(JavascriptExecutor scriptRunner, String stateKey) {
        if (scriptRunner == null) {
            return null;
        }
        try {
            scriptRunner.executeScript(RESTORE_SCRIPT, stateKey);
            return null;
        } catch (RuntimeException restoreFailure) {
            return typeOf(restoreFailure);
        }
    }

    /**
     * The only thing kept from a caught exception.
     *
     * <p>A class name is written by whoever wrote the library, never by the page under test, so it
     * cannot carry a value the browser was displaying.
     */
    private static String typeOf(Throwable failure) {
        return failure.getClass().getSimpleName();
    }

    /**
     * Removes any supplied sensitive value from the file name before it is sanitized.
     *
     * <p>A file name is written to disk and read back in logs and reports, so a caller that builds
     * one out of something it also asked to be hidden should not get it back. This can only remove
     * values the caller declared — a secret the service was never told about is still a secret it
     * cannot recognise, and a short declared value will be cut out of an otherwise ordinary name.
     */
    private static String withoutSecrets(String fileName, List<String> secrets) {
        String cleaned = fileName == null ? "" : fileName;
        for (String secret : secrets) {
            cleaned = cleaned.replace(secret, "");
        }
        return cleaned;
    }

    /** Null, blank and repeated values are noise; the page cannot be masked with them. */
    private static List<String> distinctSecrets(List<String> sensitiveValues) {
        if (sensitiveValues == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : sensitiveValues) {
            if (value != null && !value.isBlank()) {
                distinct.add(value);
            }
        }
        return new ArrayList<>(distinct);
    }

    /**
     * What happened, in terms that are safe everywhere.
     *
     * <p>There is deliberately no throwable here, and no accessor that could return one. A caught
     * exception is reduced to a {@link Stage} and a class name at the catch site and the object
     * itself is dropped, so no caller — however careless — can attach it to something that gets
     * printed. The path is available to a caller that needs to record the artifact, but never
     * appears in {@link #toString()}.
     */
    public static final class Result {

        private final Path screenshot;
        private final Stage stage;
        private final String failureType;

        private Result(Path screenshot, Stage stage, String failureType) {
            this.screenshot = screenshot;
            this.stage = stage;
            this.failureType = failureType;
        }

        private static Result captured(Path screenshot, Stage stage, String failureType) {
            return new Result(screenshot, stage, failureType);
        }

        private static Result failed(Stage stage, String failureType) {
            return new Result(null, stage, failureType);
        }

        public boolean captured() {
            return screenshot != null;
        }

        public Optional<Path> screenshot() {
            return Optional.ofNullable(screenshot);
        }

        /** Which step went wrong, or {@link Stage#NONE}. */
        public Stage stage() {
            return stage;
        }

        /** The failing exception's class name, or one of this class's own tokens. May be empty. */
        public String failureType() {
            return failureType;
        }

        /** False only when the image was taken but the page could not be put back. */
        public boolean maskingRestored() {
            return stage != Stage.RESTORE_FAILED;
        }

        /**
         * A sentence built entirely from the two closed vocabularies above.
         *
         * <p>This is what the JUnit layer puts in an exception, so it must say plainly that a
         * screenshot failed while containing nothing the browser supplied.
         */
        public String safeMessage() {
            if (stage == Stage.NONE) {
                return "";
            }
            String detail = "(stage=" + stage
                + ", type=" + (failureType.isEmpty() ? "unknown" : failureType) + ")";
            return stage == Stage.RESTORE_FAILED
                ? "Screenshot captured, but page masking was not restored " + detail
                : "Screenshot capture failed " + detail;
        }

        @Override
        public String toString() {
            return "ScreenshotCaptureResult[captured=" + captured() + ", stage=" + stage + "]";
        }
    }
}
