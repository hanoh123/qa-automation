package com.qanize.automation.checks.gamelaunch;

import java.util.Objects;

/**
 * Everything a launch that did nothing can be asked, reduced to values safe to publish.
 *
 * <p>A run reported one click, an unchanged address for ten seconds and no surface, and the
 * artifacts could not say which of three things had happened. Every field here exists to separate
 * those three, and the {@link Diagnosis} at the end states which separation the numbers support.
 *
 * <p>The first version of this record could only say whether the saved element was hit. Three runs
 * later that was proven insufficient: "no click reached the page", "a click reached the page but
 * not this element" and "this element was gone by the time the click returned" all arrived here
 * as the same single answer. The witness now reports each of those separately, and the branches in
 * {@link #of} are the rules that turn them into one word.
 *
 * <p><strong>None of this decides anything.</strong> The verdict is already settled by the time
 * these values are assembled. They are observations about a decision that has been taken, not
 * inputs to it, and a diagnostic that could change a verdict would be a second code path nobody
 * asked for.
 *
 * <p><strong>Sanitized by construction.</strong> Two addresses already reduced to
 * {@code scheme://host/path} by {@link LaunchSurfaceDetector.SafeLocation}, integers, two
 * rectangles as four integers each, a duration and words from fixed vocabularies. No text from the
 * page, no markup, no selector, no identifier, no clicked-element identity and no message from the
 * browser reaches this record.
 */
public record LaunchDiagnostics(
    String clickObserved,
    String clickTrusted,
    String documentClick,
    String clickLandedOutside,
    String targetConnectedBefore,
    String targetConnectedAfter,
    String targetRectBefore,
    String targetRectAfter,
    String targetRectMoved,
    String locationBefore,
    boolean locationBeforeQueryOmitted,
    int surfaceBefore,
    int surfaceAfter,
    int attempts,
    long elapsedMs,
    String browserError,
    String browserErrorKind,
    Diagnosis diagnosis
) {

    /** What the numbers support. One word, chosen here and never taken from a page. */
    public enum Diagnosis {

        /** A surface opened. Nothing to diagnose. */
        SURFACE_OPENED,

        /** No click reached the element the run resolved. The pointer did not deliver the event. */
        CLICK_NOT_DELIVERED,

        /** A click reached the element, the browser called it real, and the page did not act. */
        CLICK_DELIVERED_PAGE_DID_NOT_ACT,

        /** A click reached the element, and the browser did not call it user-generated. */
        CLICK_NOT_TRUSTED,

        /**
         * The saved element was in the document before the click and gone the moment it returned.
         * The page replaced the node under the pointer, so the click went to whatever took its
         * place.
         */
        CLICK_TARGET_DETACHED,

        /**
         * A real click reached the page somewhere other than the saved element. The pointer fired,
         * but not at the thing the run had resolved.
         */
        CLICK_LANDED_OUTSIDE_TARGET,

        /**
         * The saved element was still in the document but occupied a different rectangle the
         * moment the click returned. It moved out from under the pointer.
         */
        CLICK_TARGET_MOVED,

        /** The address moved but no surface was reported. The detection side is suspect. */
        LOCATION_MOVED_WITHOUT_SURFACE,

        /** The listener could not be installed or read, so delivery cannot be established. */
        CLICK_DELIVERY_UNKNOWN
    }

    /** The empty reading, for every path that performs no launch. */
    public static final LaunchDiagnostics NONE = new LaunchDiagnostics(
        "unavailable", "unavailable", "unavailable", "unavailable",
        "unavailable", "unavailable", "unmeasured", "unmeasured", "unavailable",
        null, false, 0, 0, 0, 0L,
        "unavailable", "unavailable", Diagnosis.CLICK_DELIVERY_UNKNOWN);

    public LaunchDiagnostics {
        Objects.requireNonNull(diagnosis, "diagnosis must not be null");
    }

    /**
     * Assembles the reading from what the launch already gathered.
     *
     * <p>The branches are the decision rules, in order of how specific an explanation they offer.
     * A surface that opened ends the question; a listener that never ran leaves it unanswerable.
     * After those two, the rules are:
     *
     * <ol>
     *   <li>The saved element received the click, and the browser did not call it user-generated:
     *       an untrusted event explains a silent handler on its own.</li>
     *   <li>The saved element received a trusted click: the delivery worked, so the remaining
     *       question is the address — moved without a surface is a defect on the detection side,
     *       unmoved means the page was handed a real click and did nothing with it.</li>
     *   <li>The saved element did not receive it and was gone from the document the moment the
     *       click returned: the node was replaced under the pointer. This is checked before the
     *       two below because a detached element necessarily also looks like a click landing
     *       elsewhere, and the replacement is the cause rather than the symptom.</li>
     *   <li>The saved element did not receive it, was still in the document, and a trusted click
     *       landed outside it: the pointer fired at something else.</li>
     *   <li>The saved element did not receive it, was still in the document, nothing trusted
     *       landed outside it, and its rectangle changed between the two samples: it moved out
     *       from under the pointer.</li>
     * </ol>
     *
     * <p>Anything left over is a click that reached neither the element nor, as far as the
     * document could tell, anywhere else — the original {@code CLICK_NOT_DELIVERED}.
     */
    public static LaunchDiagnostics of(
        LaunchClickWitness.Observation clicked,
        BrowserErrorProbe.Reading errors,
        LaunchSurfaceDetector.Snapshot before,
        int surfaceAfter,
        LaunchSurfaceDetector.Detection detection,
        long elapsedMs
    ) {
        boolean moved = !detection.location().sameAs(before.location());
        Diagnosis diagnosis;
        if (detection.surfaceOpened()) {
            diagnosis = Diagnosis.SURFACE_OPENED;
        } else if (!clicked.available()) {
            diagnosis = Diagnosis.CLICK_DELIVERY_UNKNOWN;
        } else if (clicked.targetReceived() && !clicked.documentClickTrusted()) {
            diagnosis = Diagnosis.CLICK_NOT_TRUSTED;
        } else if (clicked.targetReceived()) {
            diagnosis = moved
                ? Diagnosis.LOCATION_MOVED_WITHOUT_SURFACE
                : Diagnosis.CLICK_DELIVERED_PAGE_DID_NOT_ACT;
        } else if (clicked.targetConnectedBefore()
            && !clicked.targetConnectedImmediatelyAfter()) {
            diagnosis = Diagnosis.CLICK_TARGET_DETACHED;
        } else if (clicked.landedOutside()) {
            diagnosis = Diagnosis.CLICK_LANDED_OUTSIDE_TARGET;
        } else if (clicked.rectangleMoved()) {
            diagnosis = Diagnosis.CLICK_TARGET_MOVED;
        } else {
            diagnosis = Diagnosis.CLICK_NOT_DELIVERED;
        }
        return new LaunchDiagnostics(
            clicked.observedWord(),
            clicked.trustedWord(),
            clicked.word(clicked.documentClick()),
            clicked.word(clicked.landedOutside()),
            clicked.word(clicked.targetConnectedBefore()),
            clicked.word(clicked.targetConnectedImmediatelyAfter()),
            clicked.rectBefore().describe(),
            clicked.rectImmediatelyAfter().describe(),
            clicked.word(clicked.rectangleMoved()),
            before.location().value(),
            before.location().queryOmitted(),
            before.surfaceMatches(),
            surfaceAfter,
            detection.attempts(),
            elapsedMs,
            errors.occurredWord(),
            errors.kindWord(),
            diagnosis);
    }

    /** One sentence for the execution timeline. Numbers and fixed words only. */
    public String sentence() {
        return "Click reached the launch control: " + clickObserved
            + ". Browser reported it user-generated: " + clickTrusted
            + ". Any click reached the page: " + documentClick
            + ". A real click landed outside the control: " + clickLandedOutside
            + ". Control in the document before and immediately after: "
            + targetConnectedBefore + " and " + targetConnectedAfter
            + ". Its rectangle at those two moments: " + targetRectBefore
            + " and " + targetRectAfter + " (moved: " + targetRectMoved + ")"
            + ". Surface matches before and after: " + surfaceBefore + " and " + surfaceAfter
            + ". Polls: " + attempts + ". Severe browser error: " + browserError
            + " (" + browserErrorKind + "). Reading: " + diagnosis.name() + ".";
    }
}
