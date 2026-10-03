package com.qanize.automation.browser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.opentest4j.TestAbortedException;

/**
 * Takes a screenshot when a test fails, and otherwise stays out of the way.
 *
 * <p>The masking, the shutter and the file naming now live in the production
 * {@link ScreenshotCaptureService}, so a crawl run and a JUnit run cannot drift into two different
 * ideas of what a redacted screenshot is. What remains here is the part that is genuinely about
 * JUnit: when to bother, what to call the file, and how to report a problem without stealing the
 * test's own failure.
 *
 * <p>The trigger is unchanged — a real execution failure, and not an aborted (skipped) test — and
 * so is the file name, which is still
 * {@code <TestClass>-<testMethod>-<yyyyMMdd-HHmmss-SSS>.png}.
 */
final class ScreenshotOnFailureExtension implements AfterTestExecutionCallback {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern(
        "yyyyMMdd-HHmmss-SSS"
    );

    private final Supplier<WebDriver> driverSupplier;
    private final Supplier<List<String>> sensitiveValuesSupplier;

    ScreenshotOnFailureExtension(
        Supplier<WebDriver> driverSupplier,
        Supplier<List<String>> sensitiveValuesSupplier
    ) {
        this.driverSupplier = driverSupplier;
        this.sensitiveValuesSupplier = sensitiveValuesSupplier;
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Throwable executionFailure = context.getExecutionException().orElse(null);
        if (!shouldCapture(executionFailure)) {
            return;
        }
        try {
            captureForFailure(
                driverSupplier.get(),
                sensitiveValuesSupplier.get(),
                failureScreenshotName(
                    context.getRequiredTestClass().getSimpleName(),
                    context.getRequiredTestMethod().getName()),
                executionFailure);
        } catch (RuntimeException unexpected) {
            executionFailure.addSuppressed(unexpected);
        }
    }

    /** A genuine failure, but not a skip. Unchanged from before the service was extracted. */
    static boolean shouldCapture(Throwable executionFailure) {
        return executionFailure != null && !(executionFailure instanceof TestAbortedException);
    }

    /** The historical naming contract, kept exactly. */
    static String failureScreenshotName(String testClassName, String testMethodName) {
        return ScreenshotDirectoryResolver.safeName(testClassName)
            + "-"
            + ScreenshotDirectoryResolver.safeName(testMethodName)
            + "-"
            + TIMESTAMP.format(LocalDateTime.now())
            + ".png";
    }

    /**
     * Attempts one capture and notes any problem beside the failure already being reported.
     *
     * <p>The test's own failure stays the primary object, untouched. What is attached to it is a
     * {@link ScreenshotCaptureFailure} built here from a stage and a class name — never the
     * exception the browser threw, because that object can quote the page and everything that
     * prints a failure prints its suppressed exceptions too.
     *
     * <p>A driver that cannot photograph itself is not reported at all: that is a fact about how
     * the run was configured rather than something that went wrong during it.
     */
    static void captureForFailure(
        WebDriver driver,
        List<String> sensitiveValues,
        String fileName,
        Throwable executionFailure
    ) {
        if (!(driver instanceof TakesScreenshot)) {
            return;
        }
        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(driver, fileName, sensitiveValues);
        if (!result.captured()) {
            executionFailure.addSuppressed(
                new ScreenshotCaptureFailure(result.stage(), result.safeMessage()));
        }
    }

    /**
     * The deliberate, mid-test capture used by {@code BaseSeleniumTest}.
     *
     * <p>Unlike the failure path this one reports upwards, because a caller that asked for a
     * screenshot wants to know it did not get one. It still throws an {@link IOException}, so the
     * call signature and the caller's existing handling are unchanged; what it throws is the
     * causeless {@link ScreenshotCaptureFailure} rather than whatever the browser produced.
     */
    static Path captureRedactedScreenshot(
        WebDriver driver,
        List<String> sensitiveValues,
        String fileName
    ) throws IOException {
        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(driver, fileName, sensitiveValues);
        if (result.captured()) {
            return result.screenshot().orElseThrow();
        }
        throw new ScreenshotCaptureFailure(result.stage(), result.safeMessage());
    }
}
