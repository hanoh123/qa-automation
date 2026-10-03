package com.qanize.automation.checks.gamelaunch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch diagnostics: what they may say, and what they may never carry.
 *
 * <p>These exist because a launch that did nothing produced three equally good explanations and no
 * way to choose between them. The first version narrowed that to "the saved element saw nothing",
 * which left "no click happened at all", "a click happened somewhere else" and "the saved node was
 * gone by then" still collapsed into one answer. Everything here is about separating those
 * honestly — and about the harder half of the job, which is doing it on a live operator's site
 * without the diagnostic itself becoming the leak.
 */
@Tag("unit")
class LaunchDiagnosticsTest {

    private static final Path WITNESS = Path.of(
        "src/main/java/com/qanize/automation/checks/gamelaunch/LaunchClickWitness.java");

    private static final String KEY = "__qanizeLaunchWitness";

    // --- what the listener reports ----------------------------------------------------------------

    @Test
    void aClickOnADescendantIsObservedByTheConfiguredControl() {
        // The shape that made this necessary. The configured control wraps the element a pointer
        // actually lands on, so an observer that only accepted the exact target would report "no
        // click" for a click that plainly arrived.
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        FakeElement inner = page.childOf(anchor, "inner");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.dispatchClick(inner, true);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertTrue(seen.available());
        assertTrue(seen.targetReceived(), "a click inside the control is a click on the control");
        assertTrue(seen.documentClick(), "and it is also a click on the document");
        assertFalse(seen.landedOutside(), "nothing landed outside it");
        assertTrue(seen.trusted());
        assertEquals("true", seen.observedWord());
        assertEquals("true", seen.trustedWord());
    }

    @Test
    void aClickThatWasNeverDeliveredStaysFalse() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertTrue(seen.available(), "the listener was installed and readable");
        assertFalse(seen.documentClick(), "nothing reached the page at all");
        assertFalse(seen.targetReceived(), "nothing reached the control");
        assertFalse(seen.landedOutside(), "and nothing reached anything else either");
        assertEquals("false", seen.observedWord());
        assertEquals("not-applicable", seen.trustedWord(),
            "trust is meaningless when nothing was observed");
    }

    @Test
    void aTrustedClickSomewhereElseIsRecordedAsLandingOutsideTheControl() {
        // The distinction the previous version could not make, and the reason for this slice: a
        // pointer that fired at the wrong place looks exactly like a pointer that never fired,
        // unless the listener is willing to say so.
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        FakeElement elsewhere = page.element("elsewhere");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.dispatchClick(elsewhere, true);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertTrue(seen.documentClick(), "the page received a click");
        assertTrue(seen.documentClickTrusted(), "and the browser called it real");
        assertFalse(seen.targetReceived(), "but the control did not get it");
        assertTrue(seen.landedOutside(), "it landed somewhere else, and that is the finding");
    }

    @Test
    void anOutsideClickTheBrowserDidNotTrustIsNotCountedAsARealMiss() {
        // A synthetic event fired by the site's own script is not evidence about where the
        // pointer went, so it may not be reported as one.
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        FakeElement elsewhere = page.element("elsewhere");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.dispatchClick(elsewhere, false);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertTrue(seen.documentClick());
        assertFalse(seen.documentClickTrusted());
        assertFalse(seen.landedOutside(), "only a trusted click outside counts as landing outside");
    }

    @Test
    void anUntrustedClickIsObservedAndSaidToBeUntrusted() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.dispatchClick(anchor, false);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertTrue(seen.targetReceived());
        assertFalse(seen.trusted());
        assertEquals("false", seen.trustedWord());
    }

    // --- connectivity and geometry, at the moment the click returned ------------------------------

    @Test
    void aControlThatLeftTheDocumentIsReportedAsGoneRatherThanAsConnected() {
        // The surviving explanation for the SauceDemo run: the node the resolver held was replaced
        // between resolution and click. Reporting it as connected would have hidden exactly that.
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        anchor.detach();

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertTrue(seen.targetConnectedBefore(), "it was in the document when the click began");
        assertFalse(seen.targetConnectedImmediatelyAfter(), "and gone the moment it returned");
        assertEquals("true", seen.word(seen.targetConnectedBefore()));
        assertEquals("false", seen.word(seen.targetConnectedImmediatelyAfter()));
    }

    @Test
    void aControlThatWasAlreadyGoneBeforeTheClickIsNotBlamedOnTheClick() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        anchor.detach();

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertFalse(seen.targetConnectedBefore());
        assertFalse(seen.targetConnectedImmediatelyAfter());
    }

    @Test
    void aControlThatMovedIsReportedWithBothRectangles() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        anchor.place(12, 34, 100, 40);

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        anchor.place(12, 900, 100, 40);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertEquals("12,34,100,40", seen.rectBefore().describe());
        assertEquals("12,900,100,40", seen.rectImmediatelyAfter().describe());
        assertTrue(seen.rectangleMoved(), "it did not occupy the same place at the two moments");
    }

    @Test
    void aControlThatStayedPutReportsNoMovement() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        anchor.place(12, 34, 100, 40);

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);

        LaunchClickWitness.Observation seen = watch.sampleNow();
        assertEquals(seen.rectBefore().describe(), seen.rectImmediatelyAfter().describe());
        assertFalse(seen.rectangleMoved());
    }

    @Test
    void anUnmeasuredRectangleIsNeverCalledAMove() {
        // Absent is not "different". A missing measurement may not be dressed up as a finding.
        LaunchClickWitness.Rect measured = new LaunchClickWitness.Rect(true, 1, 2, 3, 4);

        assertFalse(LaunchClickWitness.Rect.UNKNOWN.differsFrom(measured));
        assertFalse(measured.differsFrom(LaunchClickWitness.Rect.UNKNOWN));
        assertEquals("unmeasured", LaunchClickWitness.Rect.UNKNOWN.describe());
    }

    // --- when the sample is taken -----------------------------------------------------------------

    @Test
    void theSampleIsTakenOnceAndEveryLaterReaderGetsThatSameMoment() {
        // The listener stays installed until the caller closes it, so a second read would describe
        // a later page. The moment immediately after the click is the only one that answers the
        // question, so it is the only one anybody gets.
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.dispatchClick(anchor, true);
        LaunchClickWitness.Observation first = watch.sampleNow();

        anchor.detach();
        page.dispatchClick(anchor, true);

        assertSame(first, watch.sampleNow(), "a second sample is the first sample");
        assertSame(first, watch.read(), "and so is every read");
        assertTrue(first.targetConnectedImmediatelyAfter(),
            "the recorded moment is the one the click returned in, not a later one");
    }

    @Test
    void readingBeforeAnythingWasSampledReportsNothingRatherThanGuessing() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);

        assertEquals(LaunchClickWitness.Observation.UNAVAILABLE, watch.read(),
            "no sample taken means no answer, not a fabricated one");
    }

    @Test
    void theListenerIsRemovedAfterTheResult() {
        // It is installed on someone else's website. Leaving it attached would mean this run's
        // diagnostic outliving this run.
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        assertEquals(1, page.listeners, "installed");

        watch.close();

        assertEquals(0, page.listeners, "removed");
        assertFalse(page.held.containsKey(KEY), "and nothing is left behind");
        assertEquals(LaunchClickWitness.Observation.UNAVAILABLE, watch.sampleNow(),
            "a removed listener reports nothing rather than a stale answer");
    }

    @Test
    void theSampleSurvivesTheListenerBeingRemoved() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");

        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.dispatchClick(anchor, true);
        LaunchClickWitness.Observation taken = watch.sampleNow();
        watch.close();

        assertSame(taken, watch.read(), "the reading outlives the listener that produced it");
        assertTrue(taken.targetReceived());
    }

    @Test
    void aBrowserThatRefusesTheListenerIsSimplyUnavailable() {
        // The diagnostic is optional by construction. A browser that will not run it must not
        // change what the launch does or says about the website.
        FakePage refusing = new FakePage();
        refusing.refuse = true;

        LaunchClickWitness.Watch watch =
            LaunchClickWitness.on(refusing).watch(refusing.element("anchor"));

        assertEquals(LaunchClickWitness.Watch.NONE, watch);
        assertEquals("unavailable", watch.sampleNow().observedWord());
        assertEquals("unavailable", watch.sampleNow().trustedWord());
        assertEquals("unavailable", watch.read().word(true));
    }

    @Test
    void aBrowserThatRefusesTheSampleReportsNothingRatherThanHalfAnAnswer() {
        FakePage page = new FakePage();
        FakeElement anchor = page.element("anchor");
        LaunchClickWitness.Watch watch = LaunchClickWitness.on(page).watch(anchor);
        page.refuse = true;

        assertEquals(LaunchClickWitness.Observation.UNAVAILABLE, watch.sampleNow());
    }

    // --- the script itself ------------------------------------------------------------------------

    @Test
    void theInstalledScriptContainsTheContainmentRuleAndCapturePhase() throws IOException {
        // The behaviour above is modelled; this is the script that has to implement it on a real
        // page. Both halves are needed: one pins what the module relies on, the other pins that
        // the browser is actually asked for it.
        String source = Files.readString(WITNESS);

        assertTrue(source.contains("target.contains(hit)"),
            "a click on a descendant must count as a click on the control");
        assertTrue(source.contains("capture: true"), "capture phase, so bubbling cannot hide it");
        assertTrue(source.contains("passive: true"),
            "passive, so this listener cannot cancel the page's own handling");
        assertTrue(source.contains("removeEventListener"), "and it is taken away again");
        assertTrue(source.contains("event.isTrusted"), "trust is read from the event, not assumed");
        assertTrue(source.contains("seen.landedOutside = true"),
            "a real click somewhere else must be recorded, not discarded");
        assertTrue(source.contains("target.isConnected"),
            "connectivity is read from the element, not assumed");
        assertTrue(source.contains("getBoundingClientRect"), "and geometry is measured");
        assertTrue(source.contains("Math.round"),
            "rectangles leave the browser as integers, never as a fractional fingerprint");
    }

    @Test
    void theSampleReadsTheElementFromItsOwnHolderSoADetachedOneCanStillBeMeasured() throws IOException {
        // Passing the element back in as a WebElement argument would make Selenium refuse a node
        // the page had replaced -- and that refusal would hide the exact fact being looked for.
        String source = Files.readString(WITNESS);
        String sample = source.substring(source.indexOf("private static final String SAMPLE"));

        assertTrue(sample.contains("const target = held.target"),
            "the sample takes the element from the holder the install put it in");
        assertTrue(source.contains("script.executeScript(SAMPLE, key)"),
            "and the call passes no element argument for Selenium to reject as stale");
    }

    @Test
    void theScriptReadsNothingFromThePageBeyondFlagsAndNumbers() throws IOException {
        // The scripts only -- the prose around them is allowed to use ordinary English.
        String source = Files.readString(WITNESS);
        String scripts = source.substring(source.indexOf("private static final String INSTALL"));

        for (String forbidden : List.of(
            "textContent", "innerText", "innerHTML", "outerHTML", "getAttribute",
            ".value", "document.cookie", "document.title", "location", "localStorage",
            "tagName", "className", "nodeName", "dataset", "getAttributeNames")) {
            assertFalse(scripts.contains(forbidden),
                "the witness must not read " + forbidden + " from the page");
        }
    }

    // --- what the reading concludes ---------------------------------------------------------------

    @Test
    void aClickThatNeverArrivedReadsAsUndelivered() {
        LaunchDiagnostics reading = reading(observation(true, false, false), unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_NOT_DELIVERED, reading.diagnosis());
    }

    @Test
    void aControlThatLeftTheDocumentReadsAsDetached() {
        // Rule three. Checked before "landed outside", because a node that has been replaced
        // necessarily also looks like a click landing elsewhere -- the replacement is the cause.
        LaunchDiagnostics reading = reading(
            missed().connected(true, false).landedOutside(true).build(), unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_TARGET_DETACHED, reading.diagnosis());
        assertEquals("true", reading.targetConnectedBefore());
        assertEquals("false", reading.targetConnectedAfter());
    }

    @Test
    void aRealClickSomewhereElseReadsAsLandingOutsideTheControl() {
        LaunchDiagnostics reading = reading(
            missed().landedOutside(true).build(), unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_LANDED_OUTSIDE_TARGET, reading.diagnosis());
        assertEquals("true", reading.clickLandedOutside());
    }

    @Test
    void aControlThatMovedUnderThePointerReadsAsMoved() {
        LaunchDiagnostics reading = reading(
            missed().rects(0, 0, 100, 40, 0, 780, 100, 40).build(), unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_TARGET_MOVED, reading.diagnosis());
        assertEquals("0,0,100,40", reading.targetRectBefore());
        assertEquals("0,780,100,40", reading.targetRectAfter());
        assertEquals("true", reading.targetRectMoved());
    }

    @Test
    void anUntrustedClickOnTheControlStillReadsAsUntrustedRatherThanAsAMiss() {
        LaunchDiagnostics reading = reading(observation(true, true, false), unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_NOT_TRUSTED, reading.diagnosis());
    }

    @Test
    void aTrustedClickOnAPageThatDidNothingReadsAsThePageNotActing() {
        // The SauceDemo shape, and the one this whole slice exists to be able to state.
        LaunchDiagnostics reading = reading(observation(true, true, true), unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_DELIVERED_PAGE_DID_NOT_ACT,
            reading.diagnosis());
    }

    @Test
    void aControlThatGotTheClickIsNeverBlamedOnGeometryOrConnectivity() {
        // Delivery answered is the end of the question. A control that received the click and was
        // then removed by the page's own handler is a working launch, not a detached target.
        LaunchDiagnostics reading = reading(
            received().connected(true, false).rects(0, 0, 100, 40, 0, 780, 100, 40).build(),
            unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_DELIVERED_PAGE_DID_NOT_ACT,
            reading.diagnosis());
    }

    @Test
    void anAddressThatMovedWithoutASurfaceBlamesTheDetectionSide() {
        LaunchDiagnostics reading = reading(observation(true, true, true), moved());

        assertEquals(LaunchDiagnostics.Diagnosis.LOCATION_MOVED_WITHOUT_SURFACE,
            reading.diagnosis());
    }

    @Test
    void aSurfaceThatOpenedEndsTheQuestionWhateverTheGeometrySays() {
        LaunchDiagnostics reading = LaunchDiagnostics.of(
            missed().connected(true, false).build(), BrowserErrorProbe.Reading.UNAVAILABLE,
            snapshot(unchangedLocation(), 0), 1,
            detection(LaunchSurfaceDetector.SurfaceType.IFRAME, unchangedLocation(), 2), 40L);

        assertEquals(LaunchDiagnostics.Diagnosis.SURFACE_OPENED, reading.diagnosis());
    }

    @Test
    void anUninstallableListenerLeavesDeliveryUnknownRatherThanGuessed() {
        LaunchDiagnostics reading = reading(LaunchClickWitness.Observation.UNAVAILABLE, unchanged());

        assertEquals(LaunchDiagnostics.Diagnosis.CLICK_DELIVERY_UNKNOWN, reading.diagnosis());
        assertEquals("unavailable", reading.documentClick());
        assertEquals("unavailable", reading.targetConnectedBefore());
        assertEquals("unmeasured", reading.targetRectBefore());
    }

    // --- what reaches the artifact ----------------------------------------------------------------

    @Test
    void everyPublishedAddressIsQueryStripped() {
        LaunchSurfaceDetector.SafeLocation beforeLocation = LaunchSurfaceDetector.safeLocation(
            "https://shop.example.test/inventory.html?session=abc123#frag");
        LaunchSurfaceDetector.SafeLocation afterLocation = LaunchSurfaceDetector.safeLocation(
            "https://shop.example.test/item.html?id=4&token=deadbeef");

        LaunchDiagnostics reading = LaunchDiagnostics.of(
            observation(true, true, true), BrowserErrorProbe.Reading.UNAVAILABLE,
            snapshot(beforeLocation, 0), 0,
            detection(LaunchSurfaceDetector.SurfaceType.NONE, afterLocation, 20), 10_263L);

        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "FAILED", LaunchSurfaceDetector.SurfaceType.NONE,
            afterLocation.value(), afterLocation.queryOmitted(),
            "NOT_REPORTED", "no new surface appeared", "VERIFIED", true, reading);

        String everything = String.join(" ", published.values());
        for (String secret : List.of("abc123", "deadbeef", "token", "session", "?", "#frag")) {
            assertFalse(everything.contains(secret),
                "a query survived into the artifact: " + everything);
        }
        assertEquals("https://shop.example.test/inventory.html", published.get("locationBefore"));
        assertEquals("true", published.get("locationBeforeQueryOmitted"));
        assertEquals("true", published.get("locationQueryOmitted"));
    }

    @Test
    void noCredentialPageTextOrConsoleMessageCanReachTheArtifact() {
        // Every value below is one this class wrote itself. There is no parameter through which a
        // page's text, a person's credential or a browser's own phrasing could arrive.
        LaunchDiagnostics reading = reading(observation(true, true, true), unchanged());

        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "FAILED", LaunchSurfaceDetector.SurfaceType.NONE, null, false,
            "NOT_REPORTED", "no new surface appeared", "VERIFIED", true, reading);

        String everything = String.join(" ", published.values()) + " " + reading.sentence();
        for (String forbidden : List.of(
            "standard_user", "secret_sauce", "Sauce Labs Backpack", "Uncaught TypeError",
            "Bearer ", "=")) {
            assertFalse(everything.contains(forbidden), "found " + forbidden + " in " + everything);
        }
        // What it does carry: numbers and words this project chose.
        assertEquals("true", published.get("clickObserved"));
        assertEquals("true", published.get("clickTrusted"));
        assertEquals("20", published.get("surfaceAttempts"));
        assertEquals("CLICK_DELIVERED_PAGE_DID_NOT_ACT", published.get("launchDiagnosis"));
    }

    @Test
    void theNewObservationsReachTheArtifactAsWordsAndFourIntegers() {
        LaunchDiagnostics reading = reading(
            missed().connected(true, false).landedOutside(true)
                .rects(12, 34, 100, 40, 0, 0, 0, 0).build(),
            unchanged());

        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "FAILED", LaunchSurfaceDetector.SurfaceType.NONE, null, false,
            "NOT_REPORTED", "no new surface appeared", "VERIFIED", true, reading);

        assertEquals("true", published.get("documentClick"));
        assertEquals("true", published.get("clickLandedOutside"));
        assertEquals("true", published.get("targetConnectedBefore"));
        assertEquals("false", published.get("targetConnectedAfter"));
        assertEquals("12,34,100,40", published.get("targetRectBefore"));
        assertEquals("0,0,0,0", published.get("targetRectAfter"));
        assertEquals("true", published.get("targetRectMoved"));
        assertEquals("CLICK_TARGET_DETACHED", published.get("launchDiagnosis"));

        for (String key : List.of("targetRectBefore", "targetRectAfter")) {
            assertTrue(published.get(key).matches("-?\\d+,-?\\d+,-?\\d+,-?\\d+"),
                key + " must be four integers and nothing else: " + published.get(key));
        }
    }

    @Test
    void everyDiagnosticFieldOnTheAllowlistIsOneThisRecordProduces() {
        // The allowlist is the only door. A field that is published without being named here would
        // be a value nobody agreed to send.
        LaunchDiagnostics reading = reading(observation(true, true, true), unchanged());

        Map<String, String> published = GameLaunchExecutionEvidence.publishable(
            "k", "n", "p", "FAILED", LaunchSurfaceDetector.SurfaceType.NONE, null, false,
            "NOT_REPORTED", "no new surface appeared", "VERIFIED", true, reading);

        for (String key : published.keySet()) {
            assertTrue(GameLaunchExecutionEvidence.PUBLISHABLE_KEYS.contains(key),
                key + " reached the artifact without being on the allowlist");
        }
        for (String added : List.of(
            "documentClick", "clickLandedOutside", "targetConnectedBefore",
            "targetConnectedAfter", "targetRectBefore", "targetRectAfter", "targetRectMoved")) {
            assertTrue(GameLaunchExecutionEvidence.PUBLISHABLE_KEYS.contains(added),
                added + " is produced but not allowlisted");
        }
    }

    @Test
    void theSentenceNamesEveryObservationAndNothingElse() {
        LaunchDiagnostics reading = reading(
            missed().connected(true, false).landedOutside(true)
                .rects(12, 34, 100, 40, 0, 0, 0, 0).build(),
            unchanged());

        String sentence = reading.sentence();
        assertTrue(sentence.contains("CLICK_TARGET_DETACHED"));
        assertTrue(sentence.contains("12,34,100,40"));
        assertTrue(sentence.contains("0,0,0,0"));
        assertFalse(sentence.contains("anchor"), "no element identity is ever named");
        assertNotEquals("", sentence);
    }

    @Test
    void theBrowserErrorProbeKeepsOnlyAClassification() {
        // The message is read to choose a word and then dropped. Nothing about it is kept.
        BrowserErrorProbe.Reading script = new BrowserErrorProbe.Reading(
            true, true, BrowserErrorProbe.Kind.SCRIPT);

        LaunchDiagnostics reading = LaunchDiagnostics.of(
            observation(true, true, true), script, snapshot(unchangedLocation(), 0), 0,
            detection(LaunchSurfaceDetector.SurfaceType.NONE, unchangedLocation(), 4), 1L);

        assertEquals("true", reading.browserError());
        assertEquals("SCRIPT", reading.browserErrorKind());
        assertFalse(reading.sentence().contains("Uncaught"));
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** The three-value shorthand the older cases were written against. */
    private static LaunchClickWitness.Observation observation(
        boolean available, boolean observed, boolean trusted
    ) {
        if (!available) return LaunchClickWitness.Observation.UNAVAILABLE;
        return new Sighting()
            .documentClick(observed || trusted)
            .trusted(trusted)
            .received(observed)
            .build();
    }

    /** A click the control received. */
    private static Sighting received() {
        return new Sighting().documentClick(true).trusted(true).received(true);
    }

    /** A click the control did not receive. */
    private static Sighting missed() {
        return new Sighting().documentClick(true).trusted(true).received(false);
    }

    /** Builds one observation without nine positional booleans at every call site. */
    private static final class Sighting {

        private boolean documentClick;
        private boolean trusted;
        private boolean received;
        private boolean landedOutside;
        private boolean connectedBefore = true;
        private boolean connectedAfter = true;
        private LaunchClickWitness.Rect before = new LaunchClickWitness.Rect(true, 0, 0, 100, 40);
        private LaunchClickWitness.Rect after = new LaunchClickWitness.Rect(true, 0, 0, 100, 40);

        private Sighting documentClick(boolean value) { documentClick = value; return this; }
        private Sighting trusted(boolean value) { trusted = value; return this; }
        private Sighting received(boolean value) { received = value; return this; }
        private Sighting landedOutside(boolean value) { landedOutside = value; return this; }

        private Sighting connected(boolean beforeClick, boolean afterClick) {
            connectedBefore = beforeClick;
            connectedAfter = afterClick;
            return this;
        }

        private Sighting rects(int x1, int y1, int w1, int h1, int x2, int y2, int w2, int h2) {
            before = new LaunchClickWitness.Rect(true, x1, y1, w1, h1);
            after = new LaunchClickWitness.Rect(true, x2, y2, w2, h2);
            return this;
        }

        private LaunchClickWitness.Observation build() {
            return new LaunchClickWitness.Observation(true, documentClick, trusted, received,
                landedOutside, connectedBefore, connectedAfter, before, after);
        }
    }

    private static LaunchSurfaceDetector.SafeLocation unchangedLocation() {
        return LaunchSurfaceDetector.safeLocation("https://shop.example.test/inventory.html");
    }

    private static LaunchSurfaceDetector.Detection unchanged() {
        return detection(LaunchSurfaceDetector.SurfaceType.NONE, unchangedLocation(), 20);
    }

    private static LaunchSurfaceDetector.Detection moved() {
        return detection(LaunchSurfaceDetector.SurfaceType.NONE,
            LaunchSurfaceDetector.safeLocation("https://shop.example.test/item.html"), 20);
    }

    private static LaunchDiagnostics reading(
        LaunchClickWitness.Observation clicked, LaunchSurfaceDetector.Detection detection
    ) {
        return LaunchDiagnostics.of(clicked, BrowserErrorProbe.Reading.UNAVAILABLE,
            snapshot(unchangedLocation(), 0), 0, detection, 10_263L);
    }

    private static LaunchSurfaceDetector.Snapshot snapshot(
        LaunchSurfaceDetector.SafeLocation location, int surfaces
    ) {
        return new LaunchSurfaceDetector.Snapshot(
            Set.of("main"), "main", location, surfaces, false);
    }

    private static LaunchSurfaceDetector.Detection detection(
        LaunchSurfaceDetector.SurfaceType type,
        LaunchSurfaceDetector.SafeLocation location,
        int attempts
    ) {
        return new LaunchSurfaceDetector.Detection(
            type, java.util.Optional.empty(), location, attempts, "no new surface appeared");
    }

    /**
     * A page that runs the witness's scripts the way a browser would.
     *
     * <p>It models the contract the module depends on — install, containment, trust, connectivity,
     * geometry, removal — and nothing else. The script text that has to implement that contract on
     * a real site is pinned separately above.
     */
    private static final class FakePage implements WebDriver, JavascriptExecutor {

        private final Map<String, FakeElement> parents = new LinkedHashMap<>();
        private final Map<String, FakeElement> held = new LinkedHashMap<>();
        private final List<String> scripts = new ArrayList<>();
        private int listeners;
        private boolean refuse;
        private FakeElement watched;
        private boolean documentClick;
        private boolean documentClickTrusted;
        private boolean targetReceived;
        private boolean landedOutside;

        private FakeElement element(String id) {
            return new FakeElement(id);
        }

        private FakeElement childOf(FakeElement parent, String id) {
            FakeElement child = element(id);
            parents.put(id, parent);
            return child;
        }

        private boolean contains(FakeElement ancestor, FakeElement candidate) {
            FakeElement walk = candidate;
            while (walk != null) {
                if (walk.id.equals(ancestor.id)) return true;
                walk = parents.get(walk.id);
            }
            return false;
        }

        /** What a pointer landing on {@code hit} does to an installed listener. */
        private void dispatchClick(FakeElement hit, boolean isTrusted) {
            if (listeners == 0 || watched == null) return;
            if (!documentClick) {
                documentClick = true;
                documentClickTrusted = isTrusted;
            }
            if (contains(watched, hit)) {
                targetReceived = true;
                if (isTrusted) documentClickTrusted = true;
            } else if (isTrusted) {
                landedOutside = true;
            }
        }

        @Override
        public Object executeScript(String script, Object... args) {
            scripts.add(script);
            if (refuse) throw new IllegalStateException("script refused");
            if (script.contains("addEventListener")) {
                watched = (FakeElement) args[0];
                listeners++;
                held.put((String) args[1], watched);
                return watched.measure();
            }
            if (script.contains("removeEventListener")) {
                if (held.remove((String) args[0]) != null) listeners--;
                return null;
            }
            FakeElement target = held.get((String) args[0]);
            if (target == null) return null;
            Map<String, Object> sample = new LinkedHashMap<>(target.measure());
            sample.put("documentClick", documentClick);
            sample.put("documentClickTrusted", documentClickTrusted);
            sample.put("targetReceived", targetReceived);
            sample.put("landedOutside", landedOutside);
            return sample;
        }

        @Override public Object executeAsyncScript(String script, Object... args) {
            throw new UnsupportedOperationException("the witness runs no asynchronous script");
        }
        @Override public void get(String url) { throw new AssertionError("never navigates"); }
        @Override public String getCurrentUrl() { return "https://shop.example.test/inventory.html"; }
        @Override public String getTitle() { return ""; }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) { throw new UnsupportedOperationException(); }
        @Override public String getPageSource() { throw new AssertionError("never read"); }
        @Override public void close() { }
        @Override public void quit() { }
        @Override public Set<String> getWindowHandles() { return new LinkedHashSet<>(Set.of("main")); }
        @Override public String getWindowHandle() { return "main"; }
        @Override public TargetLocator switchTo() { throw new UnsupportedOperationException(); }
        @Override public Navigation navigate() { throw new AssertionError("never navigates"); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }
    }

    /** An element with an identity, a place and a presence. Never read as text. */
    private static final class FakeElement implements WebElement {

        private final String id;
        private int x;
        private int y;
        private int width = 100;
        private int height = 40;
        private boolean connected = true;

        private FakeElement(String id) {
            this.id = id;
        }

        private void place(int newX, int newY, int newWidth, int newHeight) {
            x = newX;
            y = newY;
            width = newWidth;
            height = newHeight;
        }

        /** What the page does to a node it replaces: the rectangle collapses with it. */
        private void detach() {
            connected = false;
            place(0, 0, 0, 0);
        }

        private Map<String, Object> measure() {
            Map<String, Object> box = new LinkedHashMap<>();
            box.put("measured", true);
            box.put("x", x);
            box.put("y", y);
            box.put("width", width);
            box.put("height", height);
            box.put("connected", connected);
            return box;
        }

        @Override public void click() { throw new AssertionError("the witness never clicks"); }
        @Override public void submit() { throw new AssertionError("never"); }
        @Override public void sendKeys(CharSequence... keys) { throw new AssertionError("never"); }
        @Override public void clear() { throw new AssertionError("never"); }
        @Override public String getTagName() { return "a"; }
        @Override public String getAttribute(String name) { return null; }
        @Override public boolean isSelected() { return false; }
        @Override public boolean isEnabled() { return true; }
        @Override public String getText() { throw new AssertionError("the witness reads no text"); }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public WebElement findElement(By by) { throw new UnsupportedOperationException(); }
        @Override public boolean isDisplayed() { return true; }
        @Override public org.openqa.selenium.Point getLocation() {
            return new org.openqa.selenium.Point(x, y);
        }
        @Override public org.openqa.selenium.Dimension getSize() {
            return new org.openqa.selenium.Dimension(width, height);
        }
        @Override public org.openqa.selenium.Rectangle getRect() {
            return new org.openqa.selenium.Rectangle(getLocation(), getSize());
        }
        @Override public String getCssValue(String propertyName) { return ""; }
        @Override public <X> X getScreenshotAs(org.openqa.selenium.OutputType<X> target) {
            throw new AssertionError("the witness takes no screenshot");
        }
    }
}
