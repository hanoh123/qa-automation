package com.qanize.automation.checks.forms;

import java.util.stream.Stream;

import com.qanize.automation.browser.GenericBaseSeleniumTest;
import com.qanize.automation.checks.shared.CommonPage;
import com.qanize.automation.checks.shared.OpenedPage;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The JUnit face of the Forms check.
 *
 * <p>The Forms behaviour itself lives in {@link FormsModule}. What stays here is what only JUnit
 * and Surefire can provide: the class name {@code CheckId} classifies a run by, the tag the build
 * selects on, the assumption that skips an unselected check, and the conversion of the module's
 * verdict into a pass, a failure or a skip.
 *
 * <p>Opening the start page is still done here, deliberately. A module must receive a page that is
 * already open, so that a future runner opens each page once for every module rather than each
 * module opening it again.
 */
@Tag("website-analysis")
public class GenericFormDetectionTest extends GenericBaseSeleniumTest {

    @Test
    void discoveredFormsExposeInspectableStructure() {
        WebsiteConfig config = websiteConfig();
        assumeTrue(config.tests().forms(), "Test is disabled by tests.forms=false");
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        String requested = config.baseUrl().toString();
        // Contract 1.1 execution evidence, written as each action completes. One instance, shared
        // with the module below: step numbers run per test case, so the module's first step has to
        // continue from the start-page step recorded here rather than restart at 1.
        FormExecutionEvidence execution = new FormExecutionEvidence();

        long stepStart = System.nanoTime();
        // "No forms were discovered" is a statement about a specific page, and after a redirect
        // that page is not the one that was submitted.
        OpenedPage opened = OpenedPage.open(page, requested);
        execution.openedStartPage(opened.landed(), elapsedMs(stepStart));

        FormsModule.Result result =
            new FormsModule(page, driver, opened.landed(), execution).inspectDiscoveredForms();

        // A page with no form to inspect has always been reported as a skip rather than a pass or
        // a failure, and that difference reaches the customer's summary.
        assumeTrue(!result.skipped(), result.message());
        assertTrue(result.passed(), result.message());
    }

    /**
     * Fills in one explicitly configured field, and only when it is configured.
     *
     * <p>Returning an empty stream when the interaction is off is what makes "off" indistinguishable
     * from "never built": JUnit reports no test, so no container, no skip and no evidence appear
     * anywhere. The discovery check above is untouched either way.
     *
     * <p>The decision is made here, from configuration alone, before any browser work happens.
     */
    @TestFactory
    Stream<DynamicTest> configuredFieldAcceptsTheTestValue() {
        WebsiteConfig config = websiteConfig();
        WebsiteFormInteractionConfig form = config.formInteraction();
        if (!config.tests().forms() || form == null || !form.enabled()) {
            return Stream.empty();
        }
        // A stable display name. Never the selector, and never the configured value.
        return Stream.of(DynamicTest.dynamicTest(
            "configured field accepts the test value", () -> runConfiguredFieldCheck(config, form)));
    }

    /** Opens the start page, then hands the one configured field to the module. */
    private void runConfiguredFieldCheck(WebsiteConfig config, WebsiteFormInteractionConfig form) {
        CommonPage page = new CommonPage(driver, WebsiteConfigLoader.timeout(config));
        FormExecutionEvidence execution = new FormExecutionEvidence();
        OpenedPage opened = OpenedPage.open(page, config.baseUrl().toString());

        FormsModule.Result result =
            new FormsModule(page, driver, opened.landed(), execution).fillConfiguredField(form);

        // A sensitive control is refused rather than filled in, and a refusal is a skip.
        assumeTrue(!result.skipped(), result.message());
        assertTrue(result.passed(), result.message());
    }

    /** Wall-clock milliseconds around one real action. Never negative. */
    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
