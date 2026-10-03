package com.qanize.automation.checks.gamelaunch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking the browser what it saw, and being worth nothing when it will not say.
 *
 * <p>The probe's job is to catch the launch that looks right and is not: a frame that appeared
 * over a 403. Its other job — the harder one — is to be completely inert when CDP is missing,
 * refused or silent, because a probe that turned its own unavailability into a website finding
 * would fail real games on every browser that does not speak to it.
 *
 * <p>No browser here. The CDP-backed path is exercised through its one externally visible
 * property: a driver that does not implement {@code HasDevTools} attaches nothing. Everything
 * else is driven through the injected session factory, which is the seam production uses too.
 */
@Tag("unit")
class LaunchNetworkProbeTest {

    // --- the classification rule ---------------------------------------------------------------

    @Test
    void responsesWithNoFailureAreReportedAsFine() {
        LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
        seen.responseStatus(200);
        seen.responseStatus(304);

        LaunchNetworkProbe.Observation observed = seen.classify();

        assertEquals(LaunchNetworkProbe.Status.REPORTED_OK, observed.status());
        assertFalse(observed.failsTheGame());
    }

    @Test
    void aStatusAtOrAboveFourHundredFailsTheGameAndCarriesTheNumber() {
        for (int status : List.of(400, 403, 404, 500, 503)) {
            LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
            seen.responseStatus(200);
            seen.responseStatus(status);

            LaunchNetworkProbe.Observation observed = seen.classify();

            assertEquals(LaunchNetworkProbe.Status.HTTP_ERROR, observed.status(), "for " + status);
            assertTrue(observed.failsTheGame());
            assertEquals(status, observed.httpStatus().orElseThrow());
            assertEquals("HTTP_ERROR " + status, observed.label());
        }
    }

    @Test
    void aStatusJustBelowFourHundredIsNotAFailure() {
        LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
        seen.responseStatus(399);

        assertEquals(LaunchNetworkProbe.Status.REPORTED_OK, seen.classify().status());
    }

    @Test
    void aFailedDocumentOrFrameLoadFailsTheGame() {
        LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
        seen.responseStatus(200);
        seen.loadingFailed();

        LaunchNetworkProbe.Observation observed = seen.classify();

        assertEquals(LaunchNetworkProbe.Status.LOADING_FAILED, observed.status());
        assertTrue(observed.failsTheGame());
        assertTrue(observed.httpStatus().isEmpty());
        assertEquals("LOADING_FAILED", observed.label());
    }

    @Test
    void anHttpErrorOutranksALoadFailureBecauseItCarriesANumber() {
        LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
        seen.loadingFailed();
        seen.responseStatus(502);

        assertEquals(LaunchNetworkProbe.Status.HTTP_ERROR, seen.classify().status());
        assertEquals(502, seen.classify().httpStatus().orElseThrow());
    }

    @Test
    void onlyDocumentAndFrameLoadsCountAsALaunchFailure() {
        assertTrue(LaunchNetworkProbe.isDocumentOrFrame("Document"));
        assertTrue(LaunchNetworkProbe.isDocumentOrFrame("SubFrame"));
        // A failing analytics beacon or a missing thumbnail is not a broken game.
        assertFalse(LaunchNetworkProbe.isDocumentOrFrame("Image"));
        assertFalse(LaunchNetworkProbe.isDocumentOrFrame("Script"));
        assertFalse(LaunchNetworkProbe.isDocumentOrFrame("XHR"));
        assertFalse(LaunchNetworkProbe.isDocumentOrFrame(null));
    }

    @Test
    void nothingObservedIsNeverAFinding() {
        LaunchNetworkProbe.Observation observed =
            new LaunchNetworkProbe.Observations().classify();

        assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, observed.status());
        assertFalse(observed.failsTheGame());
        assertEquals("NOT_REPORTED", observed.label());
    }

    // --- graceful degradation ---------------------------------------------------------------------

    @Test
    void aDriverWithoutDevToolsAttachesNothingAndReportsNothing() {
        // The production factory, against a driver that does not speak CDP at all.
        try (LaunchNetworkProbe.Session session =
                 LaunchNetworkProbe.usingDevTools().begin(new PlainDriver())) {
            assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, session.observe().status());
        }
    }

    @Test
    void aFactoryThatThrowsIsTreatedAsUnavailableRatherThanAsAFailure() {
        LaunchNetworkProbe probe = new LaunchNetworkProbe(driver -> {
            throw new IllegalStateException("CDP refused the connection");
        });

        try (LaunchNetworkProbe.Session session = probe.begin(new PlainDriver())) {
            assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, session.observe().status());
        }
    }

    @Test
    void aCdpClassThatFailsToLinkIsABuildProblemAndIsNotSwallowed() {
        // This class sends CDP by domain-name string and imports no version-pinned CDP class, so
        // a LinkageError here means the classpath is wrong, not that the browser declined. Turning
        // it into NOT_REPORTED would let a broken build report healthy games.
        for (LinkageError broken : List.of(
            new NoClassDefFoundError("org/openqa/selenium/devtools/Command"),
            new NoSuchMethodError("DevTools.createSessionIfThereIsNotOne()"))) {

            LaunchNetworkProbe probe = new LaunchNetworkProbe(driver -> { throw broken; });

            LinkageError raised =
                assertThrows(LinkageError.class, () -> probe.begin(new PlainDriver()));
            assertSame(broken, raised);
        }
    }

    @Test
    void everyShapeOfAnUnavailableDevToolsDegradesToNotReported() {
        // The four things "this browser has no usable DevTools" actually arrives as. Each one is
        // the browser declining, which is not a statement about the game.
        List<RuntimeException> declined = List.of(
            new WebDriverException("devtools endpoint refused"),
            new org.openqa.selenium.NoSuchSessionException("the session is gone"),
            new IllegalStateException("this driver has no CDP"),
            new UnsupportedOperationException("remote end does not implement CDP"));

        for (RuntimeException refusal : declined) {
            LaunchNetworkProbe probe = new LaunchNetworkProbe(driver -> { throw refusal; });

            try (LaunchNetworkProbe.Session session = probe.begin(new PlainDriver())) {
                assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, session.observe().status(),
                    "for " + refusal.getClass().getSimpleName());
                assertFalse(session.observe().failsTheGame());
            }
        }
    }

    @Test
    void anAssertionErrorIsNotConvertedIntoNotReported() {
        // A failing assertion inside a test double, or inside the probe itself, must reach the
        // test that wrote it. Swallowing it would turn a broken probe into a silently green run,
        // which is the one failure this class must never produce.
        LaunchNetworkProbe probe = new LaunchNetworkProbe(driver -> {
            throw new AssertionError("the probe was handed the wrong driver");
        });

        AssertionError raised =
            assertThrows(AssertionError.class, () -> probe.begin(new PlainDriver()));
        assertEquals("the probe was handed the wrong driver", raised.getMessage());
    }

    @Test
    void aFatalJvmErrorTravelsOnUntouched() {
        // A JVM that is out of memory or out of stack is not a browser without DevTools. Reporting
        // NOT_REPORTED here would claim a healthy observation on a JVM that cannot make one.
        for (Error fatal : List.of(
            new OutOfMemoryError("heap"), new StackOverflowError(), new InternalError("jvm"))) {
            LaunchNetworkProbe probe = new LaunchNetworkProbe(driver -> { throw fatal; });

            Error raised = assertThrows(Error.class, () -> probe.begin(new PlainDriver()));
            assertSame(fatal, raised);
        }
    }

    @Test
    void aDisabledProbeNeverAttachesAtAll() {
        Counting counting = new Counting();
        try (LaunchNetworkProbe.Session session =
                 LaunchNetworkProbe.disabled().begin(new PlainDriver())) {
            assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, session.observe().status());
        }
        assertEquals(0, counting.attachments);
    }

    @Test
    void aNullDriverIsAnswerableWithoutAttaching() {
        try (LaunchNetworkProbe.Session session =
                 LaunchNetworkProbe.usingDevTools().begin(null)) {
            assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, session.observe().status());
        }
    }

    // --- one game at a time -----------------------------------------------------------------------

    @Test
    void closingASessionClearsEverythingItObserved() {
        LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
        seen.responseStatus(500);
        seen.loadingFailed();

        seen.clear();

        assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, seen.classify().status());
    }

    @Test
    void everyGameGetsItsOwnSessionAndEachOneIsClosed() {
        Counting counting = new Counting();
        LaunchNetworkProbe probe = new LaunchNetworkProbe(counting);

        for (int game = 0; game < 3; game++) {
            try (LaunchNetworkProbe.Session session = probe.begin(new PlainDriver())) {
                session.observe();
            }
        }

        assertEquals(3, counting.attachments);
        assertEquals(3, counting.closes, "a session is never left attached between games");
    }

    @Test
    void oneGamesFailureCannotBeAttributedToTheNext() {
        Counting counting = new Counting();
        LaunchNetworkProbe probe = new LaunchNetworkProbe(counting);

        LaunchNetworkProbe.Observation first;
        try (LaunchNetworkProbe.Session session = probe.begin(new PlainDriver())) {
            counting.current.responseStatus(503);
            first = session.observe();
        }
        LaunchNetworkProbe.Observation second;
        try (LaunchNetworkProbe.Session session = probe.begin(new PlainDriver())) {
            second = session.observe();
        }

        assertEquals(LaunchNetworkProbe.Status.HTTP_ERROR, first.status());
        assertEquals(LaunchNetworkProbe.Status.NOT_REPORTED, second.status(),
            "the second game starts from nothing");
    }

    @Test
    void theProbeKeepsNothingBetweenRuns() {
        // Nothing on the probe itself can hold an observation: the state lives in the session.
        for (var field : LaunchNetworkProbe.class.getDeclaredFields()) {
            assertFalse(LaunchNetworkProbe.Observations.class.isAssignableFrom(field.getType()),
                field.getName() + " must not hold observations across games");
            assertFalse(LaunchNetworkProbe.Observation.class.isAssignableFrom(field.getType()),
                field.getName() + " must not hold an observation across games");
        }
    }

    @Test
    void nothingTheProbePublishesCanCarryAnAddressOrAHeader() {
        LaunchNetworkProbe.Observations seen = new LaunchNetworkProbe.Observations();
        seen.responseStatus(404);
        LaunchNetworkProbe.Observation observed = seen.classify();

        // Only a status name and a number. There is no component that could hold anything else.
        assertEquals(3, LaunchNetworkProbe.Observation.class.getRecordComponents().length);
        assertFalse(observed.detail().contains("http://"));
        assertFalse(observed.detail().contains("https://"));
        assertFalse(observed.label().contains("/"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** A session factory that counts attachments and closes, and exposes what it collected. */
    private static final class Counting implements LaunchNetworkProbe.SessionFactory {

        int attachments;
        int closes;
        LaunchNetworkProbe.Observations current = new LaunchNetworkProbe.Observations();

        @Override
        public Optional<LaunchNetworkProbe.Session> attach(WebDriver driver) {
            attachments++;
            current = new LaunchNetworkProbe.Observations();
            LaunchNetworkProbe.Observations observations = current;
            return Optional.of(new LaunchNetworkProbe.Session() {
                @Override public LaunchNetworkProbe.Observation observe() {
                    return observations.classify();
                }
                @Override public void close() { closes++; observations.clear(); }
            });
        }
    }

    /** A driver with no DevTools and nothing else worth having. */
    private static final class PlainDriver implements WebDriver {
        @Override public void get(String url) { throw new AssertionError("the probe never navigates"); }
        @Override public String getCurrentUrl() { return "about:blank"; }
        @Override public String getTitle() { return ""; }
        @Override public List<WebElement> findElements(By by) { return new ArrayList<>(); }
        @Override public WebElement findElement(By by) {
            throw new org.openqa.selenium.NotFoundException("none");
        }
        @Override public String getPageSource() { return ""; }
        @Override public void close() { throw new AssertionError("the probe never closes a window"); }
        @Override public void quit() { throw new AssertionError("the probe never quits the browser"); }
        @Override public java.util.Set<String> getWindowHandles() { return java.util.Set.of("one"); }
        @Override public String getWindowHandle() { return "one"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new AssertionError("the probe never navigates"); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }

    @Test
    void theProductionFactoryIsTheOneTheModuleUsesByDefault() {
        // Guards the wiring: a module built for production must observe, not be silently disabled.
        assertSame(LaunchNetworkProbe.class, LaunchNetworkProbe.usingDevTools().getClass());
    }
}
