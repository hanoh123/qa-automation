package com.qanize.automation.checks.gamelaunch;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;

/**
 * What the browser reported about the network while a game was opening — when it will say.
 *
 * <p>A game surface can appear and still be broken: the frame is there, the provider returned 403,
 * and a check that only looked at the DOM would call that a pass. This asks the browser what it
 * saw, and is careful to be worth nothing when it cannot.
 *
 * <p><strong>Optional by design.</strong> CDP that is absent, unsupported, disconnected, or that
 * simply produced no relevant event, all end the same way: {@link Status#NOT_REPORTED}, which never
 * fails a game. A probe that turned its own unavailability into a website finding would be worse
 * than no probe, so every failure path here is swallowed and reported as "not reported".
 *
 * <p><strong>It observes numbers, not traffic.</strong> Only HTTP status codes and the fact that a
 * document or frame load failed. No URL, no header, no body, no cookie, no token, no storage, and
 * never a frame's {@code src}. There is no field on this class that could hold any of them.
 *
 * <p><strong>It creates nothing.</strong> The session belongs to the caller; this attaches to the
 * driver it is given and detaches afterwards. Listeners and counters are cleared between games, so
 * one game's failure can never be attributed to the next.
 */
public final class LaunchNetworkProbe {

    /** What the browser reported, reduced to one answer. */
    public enum Status {

        /** Responses were seen and none of them was a failure. */
        REPORTED_OK,

        /** At least one response came back at 400 or above. This fails the game. */
        HTTP_ERROR,

        /** The browser said a document or frame load failed outright. This fails the game. */
        LOADING_FAILED,

        /** Nothing usable was observed, for any reason. This never fails a game. */
        NOT_REPORTED
    }

    /**
     * The probe's answer for one launch.
     *
     * @param httpStatus the offending status code, present only for {@link Status#HTTP_ERROR}
     */
    public record Observation(Status status, Optional<Integer> httpStatus, String detail) {

        /** The answer whenever the browser said nothing usable, for any reason. */
        public static final Observation NOT_REPORTED =
            new Observation(Status.NOT_REPORTED, Optional.empty(), "the browser reported nothing");

        /** Whether this observation is a reason to fail the launched game. */
        public boolean failsTheGame() {
            return status == Status.HTTP_ERROR || status == Status.LOADING_FAILED;
        }

        /** The short, safe string the evidence publishes. Never a URL. */
        public String label() {
            return httpStatus.map(code -> status.name() + " " + code).orElse(status.name());
        }
    }

    /**
     * One attached observation, open across exactly one launch.
     *
     * <p>Closing it detaches every listener and drops every counter, which is what keeps games
     * independent. Implementations must never throw from {@link #close()}.
     */
    public interface Session extends AutoCloseable {

        /** What was seen between attaching and now. */
        Observation observe();

        @Override
        void close();
    }

    /** How a session is obtained. Production hands this the driver's own DevTools. */
    @FunctionalInterface
    public interface SessionFactory {
        Optional<Session> attach(WebDriver driver);
    }

    /** A probe that never reports anything, for a run that does not want one. */
    public static LaunchNetworkProbe disabled() {
        return new LaunchNetworkProbe(driver -> Optional.empty());
    }

    /** The production probe: CDP when the supplied driver has it, nothing otherwise. */
    public static LaunchNetworkProbe usingDevTools() {
        return new LaunchNetworkProbe(LaunchNetworkProbe::attachDevTools);
    }

    private final SessionFactory factory;

    public LaunchNetworkProbe(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
    }

    /**
     * Attaches for one launch, or returns a session that reports nothing.
     *
     * <p>Never null and never throws, so the caller has no unavailability to handle: it opens a
     * session, clicks, reads and closes, and an unavailable browser simply says NOT_REPORTED.
     */
    public Session begin(WebDriver driver) {
        if (driver == null) return NOTHING_REPORTED;
        try {
            return factory.attach(driver).orElse(NOTHING_REPORTED);
        } catch (WebDriverException | IllegalStateException
            | UnsupportedOperationException unavailable) {
            // The three shapes "this browser has no usable DevTools" actually arrives in, and
            // nothing else. WebDriverException covers DevToolsException, a session that is gone
            // and a command this endpoint refuses; Selenium answers a driver with no CDP at all
            // with IllegalStateException, and a remote driver with UnsupportedOperationException.
            //
            // No Error of any kind is caught, LinkageError included. This class sends CDP by
            // domain-name string and never touches a version-pinned CDP class, so a NoClassDefFoundError
            // or NoSuchMethodError arriving here would mean the build itself is wrong — a broken
            // classpath, not a browser that declines to talk. Reporting that as "the browser said
            // nothing" would hide a build defect behind a passing run, exactly as swallowing an
            // AssertionError, OutOfMemoryError, StackOverflowError or ThreadDeath would.
            return NOTHING_REPORTED;
        }
    }

    private static final Session NOTHING_REPORTED = new Session() {
        @Override public Observation observe() { return Observation.NOT_REPORTED; }
        @Override public void close() { }
    };

    /**
     * Collects status codes and load failures, and decides what they add up to.
     *
     * <p>Shared by the CDP session and by any test double, so the classification rule exists once.
     * Holds integers and one flag; there is nowhere here to put a URL.
     */
    public static final class Observations {

        private final Set<Integer> failingStatuses = new LinkedHashSet<>();
        private boolean sawAnyResponse;
        private boolean documentLoadFailed;

        /** One response the browser reported. Only the number is kept. */
        public void responseStatus(int status) {
            sawAnyResponse = true;
            if (status >= 400) failingStatuses.add(status);
        }

        /** A document or frame load the browser said failed outright. */
        public void loadingFailed() {
            documentLoadFailed = true;
        }

        /** Forgets everything, so the next game starts from nothing. */
        public void clear() {
            failingStatuses.clear();
            sawAnyResponse = false;
            documentLoadFailed = false;
        }

        /**
         * The rule, in one place.
         *
         * <p>An HTTP error outranks a load failure because it carries a number a person can act
         * on; a load failure outranks silence; silence is never a finding.
         */
        public Observation classify() {
            if (!failingStatuses.isEmpty()) {
                int worst = failingStatuses.iterator().next();
                return new Observation(Status.HTTP_ERROR, Optional.of(worst),
                    "the browser reported HTTP " + worst + " while the game was opening");
            }
            if (documentLoadFailed) {
                return new Observation(Status.LOADING_FAILED, Optional.empty(),
                    "the browser reported that a document or frame failed to load");
            }
            if (sawAnyResponse) {
                return new Observation(Status.REPORTED_OK, Optional.empty(),
                    "the browser reported no failing response");
            }
            return Observation.NOT_REPORTED;
        }
    }

    // --- the CDP session ---------------------------------------------------------------------------

    /**
     * Attaches to the driver's own DevTools, by raw domain name.
     *
     * <p>Raw {@code Network.*} commands and events rather than a version-pinned CDP package: the
     * pinned classes exist per Chrome version, and a browser one version ahead would make this
     * throw at class-loading time. A domain name that the browser does not understand fails the
     * command instead, which this already treats as "nothing reported".
     */
    private static Optional<Session> attachDevTools(WebDriver driver) {
        if (!(driver instanceof HasDevTools hasDevTools)) return Optional.empty();
        DevTools devTools = hasDevTools.getDevTools();
        devTools.createSessionIfThereIsNotOne();
        Observations observations = new Observations();
        devTools.send(new org.openqa.selenium.devtools.Command<>(
            "Network.enable", java.util.Map.of()));
        devTools.addListener(
            new org.openqa.selenium.devtools.Event<>("Network.responseReceived",
                input -> readStatus(input)),
            status -> { if (status != null) observations.responseStatus(status); });
        devTools.addListener(
            new org.openqa.selenium.devtools.Event<>("Network.loadingFailed",
                input -> readFailedResourceType(input)),
            type -> { if (isDocumentOrFrame(type)) observations.loadingFailed(); });
        return Optional.of(new DevToolsSession(devTools, observations));
    }

    /**
     * Reads {@code response.status} and nothing else out of the event.
     *
     * <p>Every other member — the URL, the headers, the MIME type, the security details — is
     * skipped rather than read into a field, so there is nothing to leak even briefly.
     */
    private static Integer readStatus(org.openqa.selenium.json.JsonInput input) {
        Integer status = null;
        input.beginObject();
        while (input.hasNext()) {
            if ("response".equals(input.nextName())) {
                input.beginObject();
                while (input.hasNext()) {
                    if ("status".equals(input.nextName())) {
                        status = input.read(Integer.class);
                    } else {
                        input.skipValue();
                    }
                }
                input.endObject();
            } else {
                input.skipValue();
            }
        }
        input.endObject();
        return status;
    }

    /** Reads only {@code type}, so a failing image is not mistaken for a failing game. */
    private static String readFailedResourceType(org.openqa.selenium.json.JsonInput input) {
        String type = null;
        input.beginObject();
        while (input.hasNext()) {
            if ("type".equals(input.nextName())) {
                type = input.read(String.class);
            } else {
                input.skipValue();
            }
        }
        input.endObject();
        return type;
    }

    static boolean isDocumentOrFrame(String resourceType) {
        return "Document".equalsIgnoreCase(resourceType) || "SubFrame".equalsIgnoreCase(resourceType);
    }

    /** One attached CDP session. Closing it detaches the listeners and drops the counters. */
    private record DevToolsSession(DevTools devTools, Observations observations) implements Session {

        @Override
        public Observation observe() {
            try {
                return observations.classify();
            } catch (RuntimeException unreadable) {
                return Observation.NOT_REPORTED;
            }
        }

        @Override
        public void close() {
            try {
                devTools.clearListeners();
                devTools.send(new org.openqa.selenium.devtools.Command<>(
                    "Network.disable", java.util.Map.of()));
            } catch (WebDriverException | IllegalStateException
                | UnsupportedOperationException alreadyGone) {
                // A disconnected browser has already forgotten everything this would clear. The
                // same three shapes as begin(), for the same reason, and every Error — LinkageError,
                // AssertionError and the fatal JVM ones alike — still travels on untouched.
            } finally {
                observations.clear();
            }
        }
    }
}
