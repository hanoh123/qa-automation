package com.qanize.automation.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import com.qanize.automation.runtime.AutomationRunRequest;
import com.qanize.automation.runtime.RuntimeConfigGenerator;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.qanize.automation.checks.navigation.WebsiteMenuConfig;


/**
 * The configured click-to-open menu: its configuration, its transport, and the boundary that stops
 * it from clicking anything nobody asked for.
 *
 * <p>The disabled state carries most of the weight here. A menu that is not configured has to be
 * indistinguishable from a menu feature that was never built — not merely quiet.
 */
@Tag("unit")
class MenuInteractionTest {

    private static final Path CHECK =
        Path.of("src/test/java/com/qanize/automation/checks/navigation/GenericNavigationTest.java");
    private static final Path SHARED_CLICK =
        Path.of("src/main/java/com/qanize/automation/checks/shared/SafePointerClick.java");
    /** Where the menu interaction now lives; CHECK keeps only the JUnit face. */
    private static final Path MODULE =
        Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationModule.java");

    // ---------------------------------------------------------------- configuration

    @Test
    void bothSelectorsBlankDisablesTheMenuWithoutAnError() {
        WebsiteMenuConfig menu = new WebsiteMenuConfig("  ", "", null);
        assertFalse(menu.enabled(), "a blank configuration is off, not broken");
        assertFalse(WebsiteMenuConfig.disabled().enabled());
    }

    @Test
    void aFullyConfiguredMenuIsEnabledAndTrimmed() {
        WebsiteMenuConfig menu = new WebsiteMenuConfig("  #button ", " #panel ", "  Main menu ");
        assertTrue(menu.enabled());
        assertEquals("#button", menu.triggerSelector());
        assertEquals("#panel", menu.panelSelector());
        assertEquals("Main menu", menu.displayName());
    }

    @Test
    void anUnnamedMenuGetsAFriendlyNameRatherThanASelector() {
        WebsiteMenuConfig menu = new WebsiteMenuConfig("#button", "#panel", null);
        assertEquals("Configured menu", menu.displayName());
        assertFalse(menu.displayName().contains("#"), "a selector is a locator, never a name");
    }

    @Test
    void aTriggerWithoutAPanelIsRejected() {
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(configWith("#button", "", ""), "test"));
        assertEquals("MENU_SELECTORS_INCOMPLETE", failure.code());
    }

    @Test
    void aPanelWithoutATriggerIsRejected() {
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(configWith("", "#panel", ""), "test"));
        assertEquals("MENU_SELECTORS_INCOMPLETE", failure.code());
    }

    @Test
    void anOverlongSelectorIsRejectedWithoutEchoingIt() {
        String enormous = "a".repeat(WebsiteMenuConfig.MAX_SELECTOR_LENGTH + 1);
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(configWith(enormous, "#panel", ""), "test"));
        assertEquals("MENU_TRIGGER_SELECTOR_TOO_LONG", failure.code());
        assertFalse(failure.userMessage().contains(enormous), "the rejected value is not echoed");
        assertFalse(failure.suggestedAction().contains(enormous));
    }

    @Test
    void anOverlongPanelSelectorAndLabelAreRejected() {
        assertEquals("MENU_PANEL_SELECTOR_TOO_LONG", assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(
                configWith("#button", "b".repeat(513), ""), "test")).code());
        assertEquals("MENU_LABEL_TOO_LONG", assertThrows(RunConfigurationException.class,
            () -> WebsiteConfigLoader.load(
                configWith("#button", "#panel", "c".repeat(121)), "test")).code());
    }

    @Test
    void aSelectorExactlyAtTheLimitIsAccepted() throws IOException {
        WebsiteConfig config = WebsiteConfigLoader.load(
            configWith("a".repeat(WebsiteMenuConfig.MAX_SELECTOR_LENGTH), "#panel", ""), "test");
        assertTrue(config.menu().enabled());
    }

    @Test
    void theRequestValidatorRejectsAHalfConfiguredMenuBeforeABrowserStarts() {
        RunConfigurationException failure = assertThrows(RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(requestWith("#button", "", "")));
        assertEquals("MENU_SELECTORS_INCOMPLETE", failure.code());
    }

    @Test
    void theRequestValidatorAcceptsBothBlankAndBothPresent() {
        RunConfigurationValidator.validate(requestWith("", "", ""));
        RunConfigurationValidator.validate(requestWith("#button", "#panel", "Main menu"));
    }

    // ---------------------------------------------------------------- transport

    @Test
    void allThreeFieldsTravelThroughThePropertiesFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("generated-run.properties");
        new RuntimeConfigGenerator().generate(requestWith("#button", "#panel", "Main menu"), file);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        assertEquals("#button", properties.getProperty("menu.triggerSelector"));
        assertEquals("#panel", properties.getProperty("menu.panelSelector"));
        assertEquals("Main menu", properties.getProperty("menu.label"));
    }

    @Test
    void theMenuKeysArePresentAndBlankWhenNoMenuIsConfigured(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("generated-run.properties");
        new RuntimeConfigGenerator().generate(requestWith("", "", ""), file);
        String written = Files.readString(file);
        // The file shape must not vary between runs, or a reader has to guess why a key vanished.
        assertTrue(written.contains("menu.triggerSelector"));
        assertTrue(written.contains("menu.panelSelector"));
        assertTrue(written.contains("menu.label"));
    }

    @Test
    void theRunConfigSnapshotCarriesTheMenu(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("run-config.json");
        new RuntimeConfigGenerator()
            .writeRequestSnapshotIfAbsent(requestWith("#button", "#panel", "Main menu"), file);
        String json = Files.readString(file);
        assertTrue(json.contains("\"menu\""), json);
        assertTrue(json.contains("\"triggerSelector\": \"#button\""), json);
        assertTrue(json.contains("\"panelSelector\": \"#panel\""), json);
        assertTrue(json.contains("\"label\": \"Main menu\""), json);
    }

    @Test
    void aConfiguredMenuSurvivesTheWholeLoaderPath() throws IOException {
        WebsiteConfig config = WebsiteConfigLoader.load(
            configWith("#button", "#panel", "Main menu"), "test");
        assertTrue(config.menu().enabled());
        assertEquals("Main menu", config.menu().displayName());
    }

    // ---------------------------------------------------------------- disabled means absent

    @Test
    void theFactoryReturnsNothingWhenNoMenuIsConfigured() throws IOException {
        String source = Files.readString(CHECK);
        // Proven against Surefire 3.5.6 before this was written: an empty factory contributes
        // nothing to the suite's tests or skipped attributes, so NAVIGATION's counts cannot move.
        assertTrue(source.contains("return Stream.empty();"),
            "a disabled menu must yield no dynamic test at all");
        assertFalse(code(source).contains("assumeTrue(false, \"menu"),
            "an assumption failure would add a skipped test and change the counts");
    }

    @Test
    void theDisabledPathIsDecidedBeforeAnyBrowserWorkHappens() throws IOException {
        // Scoped to the factory itself, brace-matched. The link check in the same class opens the
        // start page legitimately, so a file-wide ordering check would be about the wrong method.
        String factory = methodBody(
            code(Files.readString(CHECK)), "Stream<DynamicTest> configuredMenuOpensItsPanel");
        assertTrue(factory.contains("return Stream.empty();"), factory);
        // The decision is made from configuration alone: nothing in the factory touches a browser,
        // records evidence, or builds the module, so none of it can happen before the guard.
        for (String browserWork : List.of(
            "page.", "driver", "execution.", "new NavigationModule(", "new CommonPage(")) {
            assertFalse(factory.contains(browserWork),
                "the factory must decide before any browser work: " + browserWork);
        }
        assertFalse(code(Files.readString(MODULE)).contains("Stream.empty()"),
            "the disabled decision belongs to the factory, not the module");
    }

    @Test
    void theExistingLinkCheckIsUntouchedByTheMenuFeature() throws Exception {
        // The URL-based navigation behaviour and its identifiers must not shift. They now live in
        // the module, so they are read there; reading CHECK would find nothing and prove nothing.
        String source = Files.readString(MODULE);
        assertTrue(source.contains(
            "\"nav a[href], header a[href], [role='navigation'] a[href], .navbar a[href], .menu a[href]\""));
        assertTrue(source.contains("String testCaseId = \"nav-\" + tested;"));
        assertTrue(source.contains("\"nav-skipped-\" + index"));
        assertTrue(source.contains("navigation-discovery") || source.contains("DISCOVERY_TEST_CASE_ID"));
        // Counted by annotation, not by text: "@Test" is a prefix of "@TestFactory", so a
        // substring count reported the new factory as a second ordinary test. Reflection cannot
        // confuse the two, and it also pins both method names.
        Class<?> check = Class.forName("com.qanize.automation.checks.navigation.GenericNavigationTest");
        assertEquals(
            List.of("discoveredNavigationLinksReachUsablePages"),
            annotatedMethods(check, Test.class),
            "the link check remains the single ordinary test");
        assertEquals(
            List.of("configuredMenuOpensItsPanel"),
            annotatedMethods(check, TestFactory.class),
            "the menu is one factory, and a factory is never an ordinary test");
    }

    @Test
    void theMenuCaseUsesTheApprovedIdentifierUnderNavigation() throws IOException {
        String recorder = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationExecutionEvidence.java"));
        assertTrue(recorder.contains("MENU_TEST_CASE_ID = \"nav-menu-1\""), recorder);
        assertTrue(recorder.contains("private static final String CHECK_ID = \"NAVIGATION\""),
            "the menu case belongs to NAVIGATION, not a seventh check");
    }

    // ---------------------------------------------------------------- interaction boundary

    @Test
    void exactlyOneClickCanEverBeDispatched() throws IOException {
        String source = code(Files.readString(MODULE));
        assertEquals(1, source.split("safeClick\\.clickOnce\\(", -1).length - 1,
            "one delegated click, and only one");
        assertEquals(0, source.split("\\.click\\(\\)", -1).length - 1,
            "the check must never click an element directly");
    }

    @Test
    void nothingElseCanSubmitToggleOrNavigate() throws IOException {
        // Both halves: a forbidden construct must not appear in the wrapper either.
        String source = code(Files.readString(MODULE)) + "\n" + code(Files.readString(CHECK));
        for (String forbidden : List.of(
            "Keys.ENTER", "Keys.RETURN", ".submit()", "executeScript", "Thread.sleep",
            "arguments[0].click()", "while (", "do {")) {
            assertFalse(source.contains(forbidden), "must not contain " + forbidden);
        }
    }

    @Test
    void noFallbackSelectorIsEverSubstituted() throws IOException {
        // Brace-matched to the menu method alone. Reading to the end of the file would sweep in
        // the page adapter, whose anchor discovery legitimately mentions a[href].
        String menuCode = methodBody(
            code(Files.readString(MODULE)), "public Result openConfiguredMenu");
        assertTrue(menuCode.contains("menu.triggerSelector()"), menuCode);
        assertTrue(menuCode.contains("menu.panelSelector()"), menuCode);
        // The link check's own fallback must not have leaked into the menu path.
        assertFalse(menuCode.contains("a[href]"), "the menu path must not fall back to any selector");
        assertFalse(menuCode.contains("button, [role='button']"),
            "the menu path must not look for arbitrary buttons");
    }

    @Test
    void thereIsExactlyOneActionsClickImplementationInTheProject() throws IOException {
        String shared = code(Files.readString(SHARED_CLICK));
        assertEquals(1, shared.split("new Actions\\(", -1).length - 1,
            "the single pointer-click implementation lives in the shared helper");
        assertTrue(shared.contains("moveToElement(element).click().perform();"), shared);
    }

    @Test
    void theSharedHelperIsUsedRatherThanBasePageClickIfVisible() throws IOException {
        String source = code(Files.readString(MODULE));
        assertTrue(source.contains("new SafePointerClick(driver)"), source);
        // clickIfVisible belongs to Search; borrowing it would change a check this slice must not.
        assertFalse(source.contains("clickIfVisible"),
            "the menu check must not use the Search click path");
    }

    // ---------------------------------------------------------------- what is persisted

    @Test
    void noSelectorIsEverPersistedInTheResult() throws IOException {
        String verdict = methodBody(code(Files.readString(MODULE)), "private Result menuVerdict");
        assertTrue(verdict.contains(".name(menu.displayName())"), verdict);
        assertTrue(verdict.contains(".target(menu.displayName())"), verdict);
        assertFalse(verdict.contains("triggerSelector()"), "a selector must not reach the artifact");
        assertFalse(verdict.contains("panelSelector()"), "a selector must not reach the artifact");
    }

    @Test
    void theMetadataIsFourSafeBooleansAndNothingElse() throws IOException {
        // Scoped to the one method that builds the nav-menu-1 item. A whole-file count also saw
        // the pre-existing nav-{n} and nav-skipped-{n} metadata, which belongs to other items and
        // is not this invariant. The rule is: every emitted menu item carries these four keys and
        // nothing else.
        String verdict = methodBody(code(Files.readString(MODULE)), "private Result menuVerdict");
        List<String> required = List.of("triggerFound", "panelVisibleBefore", "clicked", "panelVisibleAfter");
        for (String key : required) {
            assertEquals(1, occurrences(verdict, ".meta(\"" + key + "\", Boolean.toString("),
                key + " must appear exactly once, as a plain boolean");
        }
        assertEquals(required.size(), occurrences(verdict, ".meta("),
            "a fifth metadata key on the menu item is not allowed");
        // Only one item is built here, so the four keys above are that item's complete metadata.
        assertEquals(1, occurrences(verdict, "EvidenceRecorder.record("),
            "the menu path emits exactly one contract 1.0 item");
        for (String unsafe : List.of("triggerSelector", "panelSelector", "getPageSource", "getAttribute")) {
            assertFalse(verdict.contains(unsafe), unsafe + " must never become metadata");
        }
    }

    @Test
    void theVerdictNeverClaimsMoreThanTheCheckProved() throws IOException {
        String recorder = Files.readString(
            Path.of("src/main/java/com/qanize/automation/checks/navigation/NavigationExecutionEvidence.java"));
        int menuStart = recorder.indexOf("assertedMenuOpened");
        String verdict = recorder.substring(menuStart, Math.min(recorder.length(), menuStart + 1400));
        for (String overclaim : List.of("destination loaded", "navigation succeeded", "menu item works")) {
            assertFalse(verdict.contains(overclaim), "must not claim: " + overclaim);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Method names carrying one annotation, sorted. Exact, so a prefix cannot match. */
    private static List<String> annotatedMethods(Class<?> type, Class<? extends Annotation> annotation) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(annotation))
            .map(Method::getName)
            .sorted()
            .toList();
    }

    /** One method's body, brace-matched, so a guard cannot drift into a neighbouring method. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method not found: " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') depth++;
            else if (character == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new IllegalStateException("unbalanced method: " + signature);
    }

    private static int occurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** Executable code only: prose explaining why something is absent must not satisfy a guard. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** A minimal runtime properties document, read through the loader's real entry point. */
    private static InputStream configWith(String trigger, String panel, String label) {
        String document = String.join("\n",
            "site.url=https://example.com/",
            "menu.triggerSelector=" + trigger,
            "menu.panelSelector=" + panel,
            "menu.label=" + label) + "\n";
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    private static AutomationRunRequest requestWith(String trigger, String panel, String label) {
        AutomationRunRequest base = AutomationRunRequest.simple("https://example.com/");
        return new AutomationRunRequest(
            base.schemaVersion(), base.siteUrl(), base.viewportPreset(),
            base.viewportWidth(), base.viewportHeight(), base.maxPages(), base.maxLinks(),
            base.loginEnabled(), base.loginUrl(), base.loginUsernameEnv(), base.loginPasswordEnv(),
            base.loginUsernameSelector(), base.loginPasswordSelector(), base.loginSubmitSelector(),
            base.loginSuccessUrlContains(), base.loginSuccessText(),
            base.smokeTest(), base.navigationTest(), base.linksTest(), base.searchTest(),
            base.formsTest(), base.loginTest(), base.searchEnabled(),
            base.searchInputSelector(), base.searchSubmitSelector(), base.searchResultsSelector(),
            base.searchQuery(), trigger, panel, label,
            null,
            null,
            null
        );
    }
}
