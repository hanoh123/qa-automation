package com.qanize.automation.browser;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screenshot boundary, proven without a browser.
 *
 * <p>The driver is a {@link Proxy} rather than a hand-written class, so this test cannot fall
 * behind a Selenium interface it does not implement, and it records what the real service asked the
 * browser to do rather than re-implementing any of it. Masking and restoration are the production
 * scripts, compared by identity; what is proven here is that they are issued, in the right order,
 * with the right arguments, on every path. Whether those scripts do what they say inside a live DOM
 * is a browser question, and nothing below claims to answer it.
 *
 * <p>The leak tests make the browser throw an exception whose message quotes a password, a token, a
 * URL query secret and an email address at once, then walk every accessor and the entire cause and
 * suppressed-exception tree looking for any of them. A check that only inspected {@code toString()}
 * would pass while a reporter printed the secret from a suppressed exception, so none of them stop
 * there.
 */
@Tag("unit")
class ScreenshotCaptureServiceTest {

    private static final Path SERVICE_SOURCE =
        Path.of("src/main/java/com/qanize/automation/browser/ScreenshotCaptureService.java");
    private static final Path EXTENSION_SOURCE =
        Path.of("src/test/java/com/qanize/automation/browser/ScreenshotOnFailureExtension.java");
    private static final Path THIS_TEST_SOURCE =
        Path.of("src/test/java/com/qanize/automation/browser/ScreenshotCaptureServiceTest.java");

    private static final String PASSWORD = "hunter2-VERY-SECRET";
    private static final String TOKEN = "tok_live_9f2bAAbbCCddEE";
    private static final String URL_SECRET = "https://shop.example.test/account?session=SEC7ET-VALUE";
    private static final String EMAIL = "alice.tester@example.test";
    private static final List<String> SECRETS = List.of(PASSWORD, TOKEN, URL_SECRET, EMAIL);

    @TempDir
    Path workspace;

    private Path screenshotsDirectory;

    @BeforeEach
    void pointScreenshotsAtTheWorkspace() {
        screenshotsDirectory = workspace.resolve("run-a").resolve("screenshots");
        System.setProperty(
            ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, screenshotsDirectory.toString());
        System.clearProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY);
    }

    @AfterEach
    void clearScreenshotProperties() {
        System.clearProperty(ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY);
        System.clearProperty(ScreenshotDirectoryResolver.LEGACY_SCREENSHOTS_DIR_PROPERTY);
    }

    // --- 1, 2. where the image lands ----------------------------------------------------------

    @Test
    void theScreenshotIsWrittenInsideTheConfiguredDirectory() {
        FakeBrowser browser = new FakeBrowser();

        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(fullBrowser(browser), "check-1.png", List.of());

        assertTrue(result.captured(), result.toString());
        Path screenshot = result.screenshot().orElseThrow();
        assertTrue(Files.isRegularFile(screenshot), screenshot.toString());
        assertEquals(normalized(screenshotsDirectory), normalized(screenshot.getParent()));
        assertEquals("check-1.png", screenshot.getFileName().toString());
        assertEquals(ScreenshotCaptureService.Stage.NONE, result.stage());
        assertTrue(result.maskingRestored());
    }

    @Test
    void theConfiguredScreenshotsDirectoryPropertyStaysAuthoritative() {
        ScreenshotCaptureService service = new ScreenshotCaptureService();

        Path first = service.capture(fullBrowser(new FakeBrowser()), "one.png", List.of())
            .screenshot().orElseThrow();

        Path relocated = workspace.resolve("run-b").resolve("screenshots");
        System.setProperty(
            ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY, relocated.toString());
        Path moved = service.capture(fullBrowser(new FakeBrowser()), "two.png", List.of())
            .screenshot().orElseThrow();

        assertEquals(normalized(screenshotsDirectory), normalized(first.getParent()));
        assertEquals(normalized(relocated), normalized(moved.getParent()));
        assertNotEquals(normalized(first.getParent()), normalized(moved.getParent()));
    }

    // --- 3, 4, 5. names ------------------------------------------------------------------------

    @Test
    void hostileLabelsBecomeSafeFileNamesThatCannotEscapeTheDirectory() {
        List<String> hostile = Arrays.asList(
            "..",
            ".",
            "",
            "   ",
            null,
            "../../etc/passwd",
            "..\\..\\windows\\system32\\evil.png",
            "/etc/evil.png",
            "C:\\Windows\\evil.png",
            "report.png?token=abc123&user=admin",
            "....//....//escape.png",
            "x".repeat(400) + ".png");

        for (String label : hostile) {
            ScreenshotCaptureService.Result result = new ScreenshotCaptureService()
                .capture(fullBrowser(new FakeBrowser()), label, List.of());

            assertTrue(result.captured(), "failed for label " + label + ": " + result);
            Path screenshot = result.screenshot().orElseThrow();
            String name = screenshot.getFileName().toString();

            assertEquals(normalized(screenshotsDirectory), normalized(screenshot.getParent()),
                "escaped the directory for label " + label);
            assertTrue(normalized(screenshot).startsWith(normalized(screenshotsDirectory)),
                "left the directory for label " + label);
            assertFalse(name.contains(".."), "dot-dot survived for label " + label + ": " + name);
            assertFalse(name.contains("/"), name);
            assertFalse(name.contains("\\"), name);
            assertFalse(name.contains(":"), name);
            assertFalse(name.isBlank(), "blank name for label " + label);
            assertTrue(name.length() <= ScreenshotDirectoryResolver.MAX_FILE_NAME_LENGTH, name);
        }
    }

    @Test
    void declaredSensitiveValuesNeverReachTheFileName() {
        FakeBrowser browser = new FakeBrowser();

        ScreenshotCaptureService.Result result = new ScreenshotCaptureService()
            .capture(fullBrowser(browser), "login-" + PASSWORD + "-failure.png", List.of(PASSWORD));

        Path screenshot = result.screenshot().orElseThrow();
        assertNoSecret("the file name", screenshot.getFileName().toString());
        assertNoSecret("the full path", screenshot.toString());
        assertNoSecret("the result", result.toString());
        assertEquals("ScreenshotCaptureResult[captured=true, stage=NONE]", result.toString());
        assertEquals("", result.safeMessage());
    }

    // --- the leak that this correction closes --------------------------------------------------

    @Test
    void theResultCannotCarryAThrowableAtAll() {
        Class<?> resultType = ScreenshotCaptureService.Result.class;

        assertFalse(resultType.isRecord(), "a record would expose a component accessor");
        for (Field field : resultType.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertFalse(Throwable.class.isAssignableFrom(field.getType()),
                field.getName() + " stores a throwable");
        }
        for (Method method : resultType.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertFalse(Throwable.class.isAssignableFrom(method.getReturnType()),
                method.getName() + " returns a throwable");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(Throwable.class.isAssignableFrom(parameter),
                    method.getName() + " accepts a throwable");
            }
        }
        for (Constructor<?> constructor : resultType.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(Throwable.class.isAssignableFrom(parameter),
                    "a constructor accepts a throwable");
            }
        }
    }

    @Test
    void noSecretReachesAnyPublicAccessorOrToStringOfTheResult() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();

        ScreenshotCaptureService.Result result = new ScreenshotCaptureService()
            .capture(fullBrowser(browser), "login-" + PASSWORD + ".png", SECRETS);

        assertFalse(result.captured());
        assertNoSecret("toString", result.toString());

        int inspected = 0;
        for (Method method : ScreenshotCaptureService.Result.class.getDeclaredMethods()) {
            if (method.isSynthetic()
                || !Modifier.isPublic(method.getModifiers())
                || method.getParameterCount() != 0) {
                continue;
            }
            inspected++;
            assertNoSecret(method.getName(), String.valueOf(method.invoke(result)));
        }
        assertTrue(inspected >= 6, "only " + inspected + " accessors were inspected");
    }

    @Test
    void theSuppressedExceptionOnAPrimaryFailureCarriesNoSecretAnywhereInItsTree() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();
        AssertionError primaryFailure = new AssertionError("the website was wrong");

        ScreenshotOnFailureExtension.captureForFailure(
            fullBrowser(browser), SECRETS, "MyTest-myMethod.png", primaryFailure);

        assertEquals(1, primaryFailure.getSuppressed().length);
        Throwable attached = primaryFailure.getSuppressed()[0];

        assertTrue(attached instanceof ScreenshotCaptureFailure, attached.getClass().getName());
        assertNoSecret("the suppressed exception tree", exceptionTree(primaryFailure));
        assertNoSecret("the suppressed exception alone", exceptionTree(attached));
    }

    @Test
    void theSafeExceptionIsCauselessAndCannotLaterBeGivenOne() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();
        AssertionError primaryFailure = new AssertionError("the website was wrong");

        ScreenshotOnFailureExtension.captureForFailure(
            fullBrowser(browser), SECRETS, "MyTest-myMethod.png", primaryFailure);

        Throwable attached = primaryFailure.getSuppressed()[0];
        assertNull(attached.getCause(), "the browser's exception must not be carried as a cause");
        assertEquals(0, attached.getSuppressed().length);
        assertThrows(IllegalStateException.class, () -> attached.initCause(leakyFailure()),
            "a cause must not be attachable afterwards");
    }

    @Test
    void thePrimaryFailureRemainsTheExactSameObjectAndIsUnchanged() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();
        AssertionError primaryFailure = new AssertionError("the website was wrong");
        AssertionError sameReference = primaryFailure;

        ScreenshotOnFailureExtension.captureForFailure(
            fullBrowser(browser), SECRETS, "MyTest-myMethod.png", primaryFailure);

        assertSame(sameReference, primaryFailure);
        assertEquals("the website was wrong", primaryFailure.getMessage());
        assertNull(primaryFailure.getCause());
    }

    @Test
    void theSafeFailureStillSaysThatScreenshotCaptureFailedAndWhere() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();
        AssertionError primaryFailure = new AssertionError("the website was wrong");

        ScreenshotOnFailureExtension.captureForFailure(
            fullBrowser(browser), SECRETS, "MyTest-myMethod.png", primaryFailure);

        Throwable attached = primaryFailure.getSuppressed()[0];
        assertTrue(attached.getMessage().contains("Screenshot capture failed"), attached.getMessage());
        assertTrue(attached.getMessage().contains("CAPTURE_FAILED"), attached.getMessage());
        assertTrue(attached.getMessage().contains("RuntimeException"), attached.getMessage());
        assertEquals(ScreenshotCaptureService.Stage.CAPTURE_FAILED,
            ((ScreenshotCaptureFailure) attached).stage());
    }

    @Test
    void theDeliberateCapturePathThrowsATreeWithNoSecret() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();

        ScreenshotCaptureFailure thrown = assertThrows(
            ScreenshotCaptureFailure.class,
            () -> ScreenshotOnFailureExtension.captureRedactedScreenshot(
                fullBrowser(browser), SECRETS, "login-" + PASSWORD + ".png"));

        assertNoSecret("the thrown exception tree", exceptionTree(thrown));
        assertNull(thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(ScreenshotCaptureService.Stage.CAPTURE_FAILED, thrown.stage());
        assertTrue(IOException.class.isInstance(thrown), "the declared signature must still hold");
    }

    @Test
    void exactlyOneCaptureAttemptOccursEvenWhenTheCaptureFails() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();

        new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", SECRETS);

        assertEquals(1, browser.captureAttempts);
    }

    // --- stage classification is structural, never textual -------------------------------------

    @Test
    void aMaskFailureIsClassifiedAsMaskFailedAndNothingIsPhotographed() {
        FakeBrowser browser = new FakeBrowser();
        browser.maskFailure = leakyFailure();

        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", SECRETS);

        assertFalse(result.captured());
        assertEquals(ScreenshotCaptureService.Stage.MASK_FAILED, result.stage());
        assertEquals("RuntimeException", result.failureType());
        assertEquals(0, browser.captureAttempts);
        // The partial mask is still undone.
        assertEquals(List.of("mask", "unmask"), browser.timeline);
        assertNoSecret("the mask failure result", result.safeMessage());
    }

    @Test
    void aCaptureFailureIsClassifiedAsCaptureFailed() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = leakyFailure();

        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", SECRETS);

        assertEquals(ScreenshotCaptureService.Stage.CAPTURE_FAILED, result.stage());
        assertEquals("RuntimeException", result.failureType());
    }

    @Test
    void aDriverThatCannotPhotographItselfIsReportedWithoutThrowing() {
        FakeBrowser browser = new FakeBrowser();

        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(plainDriver(browser), "check.png", List.of());

        assertFalse(result.captured());
        assertEquals(ScreenshotCaptureService.Stage.CAPTURE_FAILED, result.stage());
        assertEquals(ScreenshotCaptureService.NOT_PHOTOGRAPHIC, result.failureType());
        assertEquals(0, browser.captureAttempts);
    }

    @Test
    void aNullDriverIsReportedWithoutThrowing() {
        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(null, "check.png", List.of());

        assertFalse(result.captured());
        assertEquals(ScreenshotCaptureService.Stage.CAPTURE_FAILED, result.stage());
        assertEquals(ScreenshotCaptureService.NO_DRIVER, result.failureType());
    }

    // --- 6, 7. the driver belongs to the caller -----------------------------------------------

    @Test
    void theSuppliedDriverIsNeverCreatedNavigatedOrQuit() {
        FakeBrowser browser = new FakeBrowser();

        new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", List.of("s3cret"));

        assertEquals(List.of("mask", "capture", "unmask"), browser.timeline);
        assertFalse(browser.timeline.contains("driver:quit"), browser.timeline.toString());
        assertFalse(browser.timeline.contains("driver:close"), browser.timeline.toString());
        assertFalse(browser.timeline.contains("driver:get"), browser.timeline.toString());
        assertFalse(browser.timeline.contains("driver:navigate"), browser.timeline.toString());
    }

    @Test
    void exactlyOneCaptureAttemptIsMade() {
        FakeBrowser browser = new FakeBrowser();

        new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", List.of());

        assertEquals(1, browser.captureAttempts);
    }

    // --- 8, 9, 10. masking is temporary on every path ------------------------------------------

    @Test
    void maskingIsRestoredAfterASuccessfulCapture() {
        FakeBrowser browser = new FakeBrowser();

        new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", List.of("s3cret"));

        assertEquals(List.of("mask", "capture", "unmask"), browser.timeline);
        assertNotNull(maskStateKey(browser));
        assertEquals(maskStateKey(browser), restoreStateKey(browser),
            "the restore must undo the state the mask recorded");
    }

    @Test
    void maskingIsAlsoRestoredWhenTheCaptureFails() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = new RuntimeException("the shutter jammed");

        ScreenshotCaptureService.Result result = new ScreenshotCaptureService()
            .capture(fullBrowser(browser), "check.png", List.of("s3cret"));

        assertFalse(result.captured());
        assertEquals(List.of("mask", "capture", "unmask"), browser.timeline);
        assertNotNull(maskStateKey(browser));
        assertEquals(maskStateKey(browser), restoreStateKey(browser));
    }

    @Test
    void aRestoreFailureNeitherThrowsNorDiscardsAGoodScreenshot() {
        FakeBrowser browser = new FakeBrowser();
        browser.restoreFailure = leakyFailure();

        ScreenshotCaptureService.Result result = new ScreenshotCaptureService()
            .capture(fullBrowser(browser), "check.png", SECRETS);

        assertTrue(result.captured(), result.toString());
        assertEquals(List.of("mask", "capture", "unmask"), browser.timeline);
        assertEquals(ScreenshotCaptureService.Stage.RESTORE_FAILED, result.stage());
        assertFalse(result.maskingRestored());
        assertNoSecret("the restore failure result", result.safeMessage());
        assertNoSecret("the restore failure toString", result.toString());
    }

    @Test
    void aBrowserThatCannotRunScriptStillCapturesAndAsksForNoRestore() {
        FakeBrowser browser = new FakeBrowser();

        ScreenshotCaptureService.Result result = new ScreenshotCaptureService()
            .capture(photographicBrowser(browser), "check.png", List.of("s3cret"));

        assertTrue(result.captured(), result.toString());
        assertEquals(List.of("capture"), browser.timeline);
        assertTrue(result.maskingRestored());
    }

    // --- 11, 12, 13. the values to hide --------------------------------------------------------

    @Test
    void everySensitiveValueIsHandedToTheMask() {
        FakeBrowser browser = new FakeBrowser();

        new ScreenshotCaptureService().capture(
            fullBrowser(browser), "check.png", List.of("first-secret", "second-secret", "third"));

        assertEquals(List.of("first-secret", "second-secret", "third"), maskedSecrets(browser));
    }

    @Test
    void blankNullAndDuplicateSensitiveValuesAreDroppedSafely() {
        FakeBrowser browser = new FakeBrowser();
        List<String> messy = Arrays.asList("keep", null, "", "   ", "keep", "other", null);

        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", messy);

        assertTrue(result.captured(), result.toString());
        assertEquals(List.of("keep", "other"), maskedSecrets(browser));
    }

    @Test
    void aNullSensitiveValueListIsAcceptedAsNothingToHide() {
        FakeBrowser browser = new FakeBrowser();

        ScreenshotCaptureService.Result result =
            new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", null);

        assertTrue(result.captured(), result.toString());
        assertEquals(List.of(), maskedSecrets(browser));
    }

    @Test
    void theCallersOwnListIsNeverMutatedAndTheServicesCopyIsCleared() {
        FakeBrowser browser = new FakeBrowser();
        List<String> callerValues = new ArrayList<>(List.of("s3cret", "other"));

        new ScreenshotCaptureService().capture(fullBrowser(browser), "check.png", callerValues);

        assertEquals(List.of("s3cret", "other"), callerValues,
            "the caller's list is not the service's");
        // The browser was handed the service's own working copy; by the time capture returns, the
        // service has emptied it, so nothing about this capture outlives it.
        assertNotNull(browser.liveMaskSecrets);
        assertTrue(browser.liveMaskSecrets.isEmpty(),
            "the service must clear its working copy of the values");
    }

    @Test
    void twoSequentialCapturesShareNoSensitiveValuesOrState() {
        ScreenshotCaptureService service = new ScreenshotCaptureService();
        FakeBrowser first = new FakeBrowser();
        FakeBrowser second = new FakeBrowser();

        service.capture(fullBrowser(first), "first.png", List.of("first-secret"));
        service.capture(fullBrowser(second), "second.png", List.of("second-secret"));

        assertEquals(List.of("first-secret"), maskedSecrets(first));
        assertEquals(List.of("second-secret"), maskedSecrets(second));
        assertFalse(maskedSecrets(second).contains("first-secret"));
        assertNotEquals(maskStateKey(first), maskStateKey(second),
            "each capture must record its state under its own key");
    }

    // --- 14, 15. what the production service may be --------------------------------------------

    @Test
    void theServiceImportsNoJUnitCrawlerLoginOrRuntimeClass() throws IOException {
        String raw = Files.readString(SERVICE_SOURCE);
        String code = code(raw);
        List<String> imports = imports(raw);

        assertFalse(code.contains("org.junit"), "production code must not depend on JUnit");
        assertFalse(code.contains("opentest4j"), "production code must not depend on JUnit");
        for (String forbidden : List.of(
            "Crawler", "CrawlRunner", "CrawlPolicy", "CrawlerPageProcessor", "PageAuditContext",
            "LoginModule", "GenericLoginHelper", "LoginExecutionEvidence",
            "RunArtifactPropertyScope", "AutomationRunOrchestratorCli", "AutomationRunPaths")) {
            assertFalse(code.contains(forbidden), "the service must not reference " + forbidden);
        }

        // An allowlist, so a dependency nobody thought to ban still fails here.
        for (String imported : imports) {
            assertTrue(
                imported.startsWith("java.") || imported.startsWith("org.openqa.selenium."),
                "unexpected dependency: " + imported);
        }
    }

    @Test
    void theServiceHoldsNoDriverCredentialSingletonOrThreadLocal() throws IOException {
        for (Field field : ScreenshotCaptureService.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
                field.getName() + " must be a constant: the service keeps no state between calls");
            assertNotEquals(ThreadLocal.class, field.getType(), field.getName());
            assertFalse(WebDriver.class.isAssignableFrom(field.getType()), field.getName());
            assertFalse(List.class.isAssignableFrom(field.getType()),
                field.getName() + " must not retain a collection of values");
        }

        // The result carries a path, a stage and a type name — nothing that could be a credential
        // and nothing that could be an exception.
        for (Field field : ScreenshotCaptureService.Result.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertTrue(
                Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
                field.getName());
            assertTrue(
                field.getType() == Path.class
                    || field.getType() == String.class
                    || field.getType() == ScreenshotCaptureService.Stage.class,
                field.getName() + " has unexpected type " + field.getType());
        }

        String code = code(Files.readString(SERVICE_SOURCE));
        assertFalse(code.contains("ThreadLocal"), "no per-thread state");
        assertFalse(code.contains("new Chrome" + "Driver"), "the service never builds a browser");
        assertFalse(code.contains(".quit()"), "the service never quits the caller's browser");
        assertFalse(code.contains(".close()"), "the service never closes the caller's browser");
        assertFalse(code.contains(".navigate()"), "the service never navigates");
        assertFalse(code.contains("driver.get("), "the service never loads a page");
    }

    @Test
    void theServiceNeverLogsOrReRaisesTheExceptionItCaught() throws IOException {
        String code = code(Files.readString(SERVICE_SOURCE));

        assertFalse(code.contains("System.out"), "a caught browser exception must not be printed");
        assertFalse(code.contains("System.err"), "a caught browser exception must not be printed");
        assertFalse(code.contains("printStackTrace"), code);
        assertFalse(code.contains("getMessage()"),
            "a browser message must never be read, let alone kept");
        assertFalse(code.contains("getLocalizedMessage"), code);
        // The only thing taken from a caught throwable.
        assertTrue(code.contains("getClass().getSimpleName()"), code);
    }

    // --- 16, 17, 18, 19. the legacy JUnit path -------------------------------------------------

    @Test
    void theExtensionDelegatesToTheSharedServiceInsteadOfCapturingItself() throws IOException {
        String code = code(Files.readString(EXTENSION_SOURCE));

        assertTrue(code.contains("new ScreenshotCaptureService()"), "it must delegate");
        assertFalse(code.contains("getScreenshotAs"), "no second shutter may remain here");
        assertFalse(code.contains("executeScript"), "no second redaction may remain here");
        assertFalse(code.contains("createTreeWalker"), "the masking script moved to the service");
        assertFalse(code.contains("OutputType"), "the capture moved to the service");
        assertFalse(code.contains("StandardCopyOption"), "the file write moved to the service");
        assertFalse(code.contains("getMessage()"), "no browser text may be copied here either");
        assertFalse(code.contains("addSuppressed(result"), "only a safe exception may be attached");
    }

    @Test
    void theExtensionStillCapturesOnlyForRealFailures() {
        assertTrue(ScreenshotOnFailureExtension.shouldCapture(new AssertionError("failed")));
        assertTrue(ScreenshotOnFailureExtension.shouldCapture(new RuntimeException("broke")));
        assertFalse(ScreenshotOnFailureExtension.shouldCapture(null),
            "a passing test must not be photographed");
        assertFalse(ScreenshotOnFailureExtension.shouldCapture(new TestAbortedException("skipped")),
            "an aborted test must not be photographed");
    }

    @Test
    void theExtensionStillNamesFailureScreenshotsTheSameWay() {
        String name = ScreenshotOnFailureExtension.failureScreenshotName("MyTest", "myMethod");

        assertTrue(name.matches("MyTest-myMethod-\\d{8}-\\d{6}-\\d{3}\\.png"), name);
    }

    @Test
    void aSuccessfulFailureScreenshotAddsNothingToThePrimaryFailure() {
        AssertionError primaryFailure = new AssertionError("the website was wrong");

        ScreenshotOnFailureExtension.captureForFailure(
            fullBrowser(new FakeBrowser()), List.of(), "MyTest-myMethod.png", primaryFailure);

        assertEquals(0, primaryFailure.getSuppressed().length);
    }

    @Test
    void aDriverThatCannotPhotographItselfIsNotReportedOnTheFailure() {
        AssertionError primaryFailure = new AssertionError("the website was wrong");

        ScreenshotOnFailureExtension.captureForFailure(
            plainDriver(new FakeBrowser()), List.of(), "MyTest-myMethod.png", primaryFailure);

        assertEquals(0, primaryFailure.getSuppressed().length);
    }

    @Test
    void theDeliberateMidTestCaptureStillReportsItsProblemsUpwards() {
        FakeBrowser browser = new FakeBrowser();
        browser.captureFailure = new IllegalStateException("the shutter jammed");

        assertThrows(
            ScreenshotCaptureFailure.class,
            () -> ScreenshotOnFailureExtension.captureRedactedScreenshot(
                fullBrowser(browser), List.of(), "check.png"));

        assertThrows(
            ScreenshotCaptureFailure.class,
            () -> ScreenshotOnFailureExtension.captureRedactedScreenshot(
                plainDriver(new FakeBrowser()), List.of(), "check.png"));
    }

    @Test
    void theDeliberateMidTestCaptureReturnsThePathOnSuccess() throws IOException {
        Path screenshot = ScreenshotOnFailureExtension.captureRedactedScreenshot(
            fullBrowser(new FakeBrowser()), List.of("s3cret"), "deliberate.png");

        assertTrue(Files.isRegularFile(screenshot), screenshot.toString());
        assertEquals(normalized(screenshotsDirectory), normalized(screenshot.getParent()));
    }

    @Test
    void protectFromScreenshotsStillCollectsValuesAndIgnoresEmptyOnes() throws Exception {
        BaseSeleniumTest testInstance = new BaseSeleniumTest() {
        };

        testInstance.protectFromScreenshots("first-secret");
        testInstance.protectFromScreenshots(null);
        testInstance.protectFromScreenshots("");
        testInstance.protectFromScreenshots("   ");
        testInstance.protectFromScreenshots("second-secret");

        Field field = BaseSeleniumTest.class.getDeclaredField("sensitiveValues");
        field.setAccessible(true);
        assertEquals(List.of("first-secret", "second-secret"), field.get(testInstance));
    }

    // --- 20. no browser required ---------------------------------------------------------------

    @Test
    void theUnitSuiteNeedsNoChrome() throws IOException {
        String raw = Files.readString(THIS_TEST_SOURCE);
        String code = code(raw);

        // Split literals: this assertion must not match itself.
        assertFalse(code.contains("Chrome" + "Driver"), "no real browser may be started here");
        assertFalse(code.contains("Chrome" + "Options"), "no browser may be configured here");
        assertFalse(code.contains("Selenium" + "DriverFactory"), "no driver may be built here");
        assertTrue(imports(raw).stream().noneMatch(name -> name.contains(".chrome.")),
            "nothing from Chrome may be imported here");
        assertTrue(code.contains("Proxy.newProxyInstance"),
            "the driver is a recording stand-in, not a browser");

        Tag tag = ScreenshotCaptureServiceTest.class.getAnnotation(Tag.class);
        assertNotNull(tag);
        assertEquals("unit", tag.value());
    }

    // --- the stand-in browser -------------------------------------------------------------------

    /**
     * Records what the service asked the browser to do.
     *
     * <p>A {@link Proxy} rather than a written class, so it satisfies whatever {@code WebDriver}
     * happens to declare without this test tracking it, and so it cannot grow an implementation of
     * its own. Script arguments are snapshotted on arrival because the service clears its working
     * copy before returning — which is itself worth asserting, so the live reference is kept too.
     */
    private static final class FakeBrowser implements InvocationHandler {

        private static final byte[] PNG = "pretend-this-is-a-png".getBytes(StandardCharsets.UTF_8);

        final List<String> timeline = new ArrayList<>();
        final List<Object[]> scriptCalls = new ArrayList<>();
        List<?> liveMaskSecrets;
        int captureAttempts;
        RuntimeException maskFailure;
        RuntimeException captureFailure;
        RuntimeException restoreFailure;

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if (name.equals("getScreenshotAs")) {
                captureAttempts++;
                timeline.add("capture");
                if (captureFailure != null) {
                    throw captureFailure;
                }
                return ((OutputType<?>) arguments[0]).convertFromPngBytes(PNG);
            }
            if (name.equals("executeScript")) {
                return recordScript((String) arguments[0], (Object[]) arguments[1]);
            }
            if (name.equals("toString")) {
                return "FakeBrowser";
            }
            if (name.equals("equals")) {
                return proxy == arguments[0];
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            timeline.add("driver:" + name);
            return null;
        }

        private Object recordScript(String script, Object[] scriptArguments) {
            Object[] snapshot = new Object[scriptArguments.length];
            for (int index = 0; index < scriptArguments.length; index++) {
                Object value = scriptArguments[index];
                snapshot[index] = value instanceof List<?> list ? List.copyOf(list) : value;
            }
            scriptCalls.add(new Object[] { script, snapshot });

            if (ScreenshotCaptureService.REDACT_SCRIPT.equals(script)) {
                timeline.add("mask");
                if (scriptArguments[0] instanceof List<?> live) {
                    liveMaskSecrets = live;
                }
                if (maskFailure != null) {
                    throw maskFailure;
                }
            } else if (ScreenshotCaptureService.RESTORE_SCRIPT.equals(script)) {
                timeline.add("unmask");
                if (restoreFailure != null) {
                    throw restoreFailure;
                }
            } else {
                timeline.add("script:unknown");
            }
            return null;
        }
    }

    /** A browser exception of the kind that quotes the page it was looking at. */
    private static RuntimeException leakyFailure() {
        return new RuntimeException(
            "element not interactable: <input value='" + PASSWORD
                + "' data-token='" + TOKEN
                + "' data-user='" + EMAIL
                + "'> while on " + URL_SECRET);
    }

    private static void assertNoSecret(String label, String text) {
        for (String secret : SECRETS) {
            assertFalse(text.contains(secret), label + " leaked a sensitive value");
        }
        assertFalse(text.contains("SEC7ET-VALUE"), label + " leaked a URL query secret");
        assertFalse(text.contains("not interactable"),
            label + " carried text borrowed from the browser");
    }

    /** Every message, class name and frame reachable from a throwable, however nested. */
    private static String exceptionTree(Throwable root) {
        StringBuilder rendered = new StringBuilder();
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (current == null || !seen.add(current)) {
                continue;
            }
            rendered.append(current.getClass().getName()).append('\n')
                .append(String.valueOf(current.getMessage())).append('\n')
                .append(current).append('\n');
            for (StackTraceElement frame : current.getStackTrace()) {
                rendered.append(frame).append('\n');
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            pending.addAll(Arrays.asList(current.getSuppressed()));
        }
        return rendered.toString();
    }

    private static WebDriver plainDriver(FakeBrowser browser) {
        return proxyFor(browser, WebDriver.class);
    }

    private static WebDriver photographicBrowser(FakeBrowser browser) {
        return proxyFor(browser, WebDriver.class, TakesScreenshot.class);
    }

    private static WebDriver fullBrowser(FakeBrowser browser) {
        return proxyFor(browser, WebDriver.class, TakesScreenshot.class, JavascriptExecutor.class);
    }

    private static WebDriver proxyFor(FakeBrowser handler, Class<?>... interfaces) {
        return (WebDriver) Proxy.newProxyInstance(
            ScreenshotCaptureServiceTest.class.getClassLoader(), interfaces, handler);
    }

    @SuppressWarnings("unchecked")
    private static List<String> maskedSecrets(FakeBrowser browser) {
        Object[] arguments = scriptArguments(browser, ScreenshotCaptureService.REDACT_SCRIPT);
        return arguments == null ? List.of() : (List<String>) arguments[0];
    }

    private static String maskStateKey(FakeBrowser browser) {
        Object[] arguments = scriptArguments(browser, ScreenshotCaptureService.REDACT_SCRIPT);
        return arguments == null ? null : (String) arguments[1];
    }

    private static String restoreStateKey(FakeBrowser browser) {
        Object[] arguments = scriptArguments(browser, ScreenshotCaptureService.RESTORE_SCRIPT);
        return arguments == null ? null : (String) arguments[0];
    }

    private static Object[] scriptArguments(FakeBrowser browser, String script) {
        for (Object[] call : browser.scriptCalls) {
            if (script.equals(call[0])) {
                return (Object[]) call[1];
            }
        }
        return null;
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** Source with comments removed, so documentation is never mistaken for a dependency. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<String> imports(String source) {
        return source.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("import "))
            .map(line -> line.substring("import ".length()).replace("static ", "").replace(";", ""))
            .toList();
    }
}
