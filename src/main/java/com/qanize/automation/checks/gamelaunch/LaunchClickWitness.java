package com.qanize.automation.checks.gamelaunch;

import java.util.Map;
import java.util.Objects;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Answers one question about the launch click: which node received it?
 *
 * <p>A run reported a launch control clicked once, the address unchanged for ten seconds and no
 * surface, and the first version of this class narrowed that to "the saved element saw nothing".
 * That was not enough. "No click happened anywhere", "a click happened somewhere else" and "the
 * saved node was gone by then" are three different defects with three different fixes, and the
 * first version could not tell them apart — it recorded only whether the saved element was hit,
 * so every other possibility collapsed into one answer.
 *
 * <p><strong>What it records now.</strong> Whether any click reached the document at all, whether
 * the browser called that click user-generated, whether the saved element or a descendant was its
 * target, whether a trusted click landed outside the saved element, whether the saved element was
 * still in the document before and immediately after the click, and the saved element's rectangle
 * at those same two moments. Eight facts, all of them booleans or integers.
 *
 * <p><strong>Immediately means immediately.</strong> The sample is taken the moment
 * {@code clickOnce} returns, before any polling begins. Connectivity and geometry read ten seconds
 * later describe a page that has had ten seconds to change, which would have made the very
 * question this class exists to answer unanswerable.
 *
 * <p><strong>It changes nothing.</strong> The listener is passive and capture-phase: it cannot
 * cancel, stop or reorder an event. It is attached before the one pointer click and detached when
 * the caller closes it. This class never dispatches a click, and the project's single
 * {@code Actions} call remains the only way anything is clicked.
 *
 * <p><strong>Nothing identifying is read.</strong> No text, no markup, no attribute, no selector,
 * no address, and never the identity of whatever else was clicked — knowing a click landed
 * elsewhere is the whole answer, and naming what it landed on would only create something to leak.
 */
public final class LaunchClickWitness {

    /**
     * A rectangle, as four integers.
     *
     * <p>Rounded on the way out of the browser, so a fractional layout cannot turn into a
     * distinguishing fingerprint, and absent when the element could not be measured.
     */
    public record Rect(boolean measured, int x, int y, int width, int height) {

        public static final Rect UNKNOWN = new Rect(false, 0, 0, 0, 0);

        /** The four numbers, or the word for "no measurement". Never anything else. */
        public String describe() {
            return measured ? x + "," + y + "," + width + "," + height : "unmeasured";
        }

        /** Whether the element occupied a different place or size at the two moments. */
        public boolean differsFrom(Rect other) {
            if (!measured || !other.measured) return false;
            return x != other.x || y != other.y || width != other.width || height != other.height;
        }
    }

    /**
     * What the listener and the two samples saw.
     *
     * @param available          whether the listener could be installed and read at all
     * @param documentClick      whether any click event reached the document
     * @param documentClickTrusted whether that event was reported as user-generated
     * @param targetReceived     whether its target was the saved element or a descendant
     * @param landedOutside      whether a trusted click occurred outside the saved element
     * @param targetConnectedBefore whether the saved element was in the document before the click
     * @param targetConnectedImmediatelyAfter the same question the moment the click returned
     */
    public record Observation(
        boolean available,
        boolean documentClick,
        boolean documentClickTrusted,
        boolean targetReceived,
        boolean landedOutside,
        boolean targetConnectedBefore,
        boolean targetConnectedImmediatelyAfter,
        Rect rectBefore,
        Rect rectImmediatelyAfter
    ) {

        public static final Observation UNAVAILABLE = new Observation(
            false, false, false, false, false, false, false, Rect.UNKNOWN, Rect.UNKNOWN);

        /** Kept for the callers that only ever asked whether the saved element was hit. */
        public boolean clickObserved() {
            return targetReceived;
        }

        /** Kept for the same reason: trust, as it applies to the click the saved element got. */
        public boolean trusted() {
            return targetReceived && documentClickTrusted;
        }

        public String observedWord() {
            return available ? Boolean.toString(targetReceived) : "unavailable";
        }

        public String trustedWord() {
            if (!available) return "unavailable";
            return targetReceived ? Boolean.toString(documentClickTrusted) : "not-applicable";
        }

        /** A three-valued word for a flag that is only meaningful once the listener ran. */
        public String word(boolean value) {
            return available ? Boolean.toString(value) : "unavailable";
        }

        /** Whether the element moved or resized between the two samples. */
        public boolean rectangleMoved() {
            return rectImmediatelyAfter.differsFrom(rectBefore);
        }
    }

    /** One installed listener. Always closed, whatever the launch did. */
    public interface Watch extends AutoCloseable {

        Watch NONE = new Watch() {
            @Override public Observation sampleNow() { return Observation.UNAVAILABLE; }
            @Override public Observation read() { return Observation.UNAVAILABLE; }
            @Override public void close() { }
        };

        /**
         * Takes the sample that describes the moment the click returned.
         *
         * <p>Called once, immediately after {@code clickOnce}, before anything waits for anything.
         * Every later reader gets this same sample back.
         */
        Observation sampleNow();

        /** The sample taken by {@link #sampleNow()}, or an unavailable reading if none was. */
        Observation read();

        @Override
        void close();
    }

    /** How a watch is obtained. A test supplies its own; production supplies the browser's. */
    @FunctionalInterface
    public interface Installer {

        /** Watches nothing and reports nothing. The default everywhere the seam is not wired. */
        Installer NONE = target -> Watch.NONE;

        Watch watch(WebElement target);
    }

    private LaunchClickWitness() {
    }

    /**
     * The production installer, over the browser the run already has.
     *
     * <p>A browser that cannot run script, or that refuses, yields {@link Watch#NONE}: the
     * diagnostic is optional by construction and may never become something a launch depends on.
     */
    public static Installer on(WebDriver driver) {
        Objects.requireNonNull(driver, "driver must not be null");
        if (!(driver instanceof JavascriptExecutor script)) return Installer.NONE;
        return target -> {
            String key = "__qanizeLaunchWitness";
            try {
                Object before = script.executeScript(INSTALL, target, key);
                return new ScriptWatch(script, key, stateOf(before));
            } catch (RuntimeException refused) {
                return Watch.NONE;
            }
        };
    }

    private static final class ScriptWatch implements Watch {

        private final JavascriptExecutor script;
        private final String key;
        private final Map<?, ?> before;
        private Observation sampled;

        private ScriptWatch(JavascriptExecutor script, String key, Map<?, ?> before) {
            this.script = script;
            this.key = key;
            this.before = before;
        }

        @Override
        public Observation sampleNow() {
            if (sampled != null) return sampled;
            try {
                Map<?, ?> after = stateOf(script.executeScript(SAMPLE, key));
                sampled = (before == null || after == null)
                    ? Observation.UNAVAILABLE
                    : new Observation(true,
                        flag(after, "documentClick"),
                        flag(after, "documentClickTrusted"),
                        flag(after, "targetReceived"),
                        flag(after, "landedOutside"),
                        flag(before, "connected"),
                        flag(after, "connected"),
                        rect(before),
                        rect(after));
            } catch (RuntimeException refused) {
                sampled = Observation.UNAVAILABLE;
            }
            return sampled;
        }

        @Override
        public Observation read() {
            return sampled == null ? Observation.UNAVAILABLE : sampled;
        }

        @Override
        public void close() {
            try {
                script.executeScript(REMOVE, key);
            } catch (RuntimeException alreadyGone) {
                // A page that navigated away took the listener with it. Nothing to undo.
            }
        }
    }

    private static Map<?, ?> stateOf(Object answer) {
        return answer instanceof Map<?, ?> values ? values : null;
    }

    private static boolean flag(Map<?, ?> values, String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    private static Rect rect(Map<?, ?> values) {
        if (!Boolean.TRUE.equals(values.get("measured"))) return Rect.UNKNOWN;
        return new Rect(true,
            number(values, "x"), number(values, "y"),
            number(values, "width"), number(values, "height"));
    }

    private static int number(Map<?, ?> values, String name) {
        Object value = values.get(name);
        return value instanceof Number found ? found.intValue() : 0;
    }

    /**
     * Installs the listener and measures the element once, before the click.
     *
     * <p>Capture phase so the answer does not depend on the site letting the event bubble, and
     * passive so it is on the record that this listener cannot cancel anything. The four flags it
     * maintains are set independently: a click outside the saved element is recorded as such
     * rather than being discarded, which is the whole difference from the first version.
     */
    private static final String INSTALL = """
        const target = arguments[0];
        const key = arguments[1];
        const seen = {
          documentClick: false,
          documentClickTrusted: false,
          targetReceived: false,
          landedOutside: false
        };
        const listener = function (event) {
          const trusted = event.isTrusted === true;
          if (!seen.documentClick) {
            seen.documentClick = true;
            seen.documentClickTrusted = trusted;
          }
          const hit = event.target;
          const inside = hit === target || target.contains(hit);
          if (inside) {
            seen.targetReceived = true;
            if (trusted) { seen.documentClickTrusted = true; }
          } else if (trusted) {
            seen.landedOutside = true;
          }
        };
        document.addEventListener('click', listener, { capture: true, passive: true });
        window[key] = { seen: seen, listener: listener, target: target };
        const box = target.getBoundingClientRect();
        return {
          measured: true,
          x: Math.round(box.x), y: Math.round(box.y),
          width: Math.round(box.width), height: Math.round(box.height),
          connected: target.isConnected === true
        };
        """;

    /**
     * Returns the four flags, the connectivity and the four numbers. Nothing else.
     *
     * <p>The element is read back from the holder the install put it in, not passed in as a
     * WebElement argument. A node the page has since replaced would make Selenium refuse such an
     * argument as stale, and the refusal would hide the very fact this sample exists to record:
     * that the element the run held was no longer in the document when the click returned.
     */
    private static final String SAMPLE = """
        const held = window[arguments[0]];
        if (!held) { return null; }
        const target = held.target;
        const box = target.getBoundingClientRect();
        return {
          documentClick: held.seen.documentClick === true,
          documentClickTrusted: held.seen.documentClickTrusted === true,
          targetReceived: held.seen.targetReceived === true,
          landedOutside: held.seen.landedOutside === true,
          measured: true,
          x: Math.round(box.x), y: Math.round(box.y),
          width: Math.round(box.width), height: Math.round(box.height),
          connected: target.isConnected === true
        };
        """;

    /** Detaches the listener and drops the holder, so nothing of this run outlives it. */
    private static final String REMOVE = """
        const key = arguments[0];
        const held = window[key];
        if (!held) { return null; }
        document.removeEventListener('click', held.listener, true);
        delete window[key];
        return null;
        """;
}
