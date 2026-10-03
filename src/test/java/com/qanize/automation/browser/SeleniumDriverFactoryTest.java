package com.qanize.automation.browser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteTestSelectionConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one authoritative Chrome configuration, proved without launching Chrome.
 *
 * <p>This was a pure move out of {@link BaseSeleniumTest}, so what matters is that nothing changed:
 * the same property with the same default, the same two arguments in the same order, and the same
 * explicit window size afterwards. Those are asserted against the argument list directly, which is
 * why no browser is needed here and why the unit suite still runs on a machine with no Chrome.
 */
@Tag("unit")
class SeleniumDriverFactoryTest {

    private static final Path FACTORY_SOURCE =
        Path.of("src/main/java/com/qanize/automation/browser/SeleniumDriverFactory.java");
    private static final Path BASE_TEST_SOURCE =
        Path.of("src/test/java/com/qanize/automation/browser/BaseSeleniumTest.java");

    @AfterEach
    void clearHeadlessProperty() {
        System.clearProperty(SeleniumDriverFactory.HEADLESS_PROPERTY);
    }

    private static WebsiteConfig config(int width, int height) {
        return new WebsiteConfig(
            "1.0", "example", URI.create("https://example.com/"), List.of(), List.of(), "", "",
            null, null, null, width, height, 5, 25, 10, null,
            new WebsiteTestSelectionConfig(true, true, true, false, true, false));
    }

    // --- 3, 4, 5, 6. the configuration is unchanged --------------------------------------------

    @Test
    void headlessIsTheDefaultAndTheArgumentsKeepTheirOriginalOrder() {
        System.clearProperty(SeleniumDriverFactory.HEADLESS_PROPERTY);

        assertTrue(SeleniumDriverFactory.headlessRequested(), "headless unless told otherwise");
        assertEquals(
            List.of("--headless=new", "--window-size=1440,900"),
            SeleniumDriverFactory.chromeArguments(config(1440, 900)),
            "exactly the two arguments BaseSeleniumTest applied, in the order it applied them");
    }

    @Test
    void headlessCanBeTurnedOffAndOnlyRemovesTheHeadlessArgument() {
        System.setProperty(SeleniumDriverFactory.HEADLESS_PROPERTY, "false");

        assertFalse(SeleniumDriverFactory.headlessRequested());
        assertEquals(
            List.of("--window-size=1280,720"),
            SeleniumDriverFactory.chromeArguments(config(1280, 720)),
            "a visible browser still gets its window size");
    }

    @Test
    void anExplicitTrueIsStillHeadlessAndAnUnparseableValueFallsBackToVisible() {
        System.setProperty(SeleniumDriverFactory.HEADLESS_PROPERTY, "true");
        assertTrue(SeleniumDriverFactory.headlessRequested());

        // Boolean.parseBoolean treats anything that is not "true" as false. That was the previous
        // behaviour too, and it is pinned so a future "stricter" parse is a deliberate decision.
        System.setProperty(SeleniumDriverFactory.HEADLESS_PROPERTY, "yes");
        assertFalse(SeleniumDriverFactory.headlessRequested(),
            "only the literal \"true\" means headless, exactly as before");
    }

    @Test
    void theWindowSizeArgumentComesFromTheConfiguredViewport() {
        System.setProperty(SeleniumDriverFactory.HEADLESS_PROPERTY, "false");

        assertEquals(List.of("--window-size=375,812"),
            SeleniumDriverFactory.chromeArguments(config(375, 812)));
        assertEquals(List.of("--window-size=2560,1440"),
            SeleniumDriverFactory.chromeArguments(config(2560, 1440)));
    }

    @Test
    void theOptionsObjectReallyCarriesThoseArguments() {
        System.clearProperty(SeleniumDriverFactory.HEADLESS_PROPERTY);

        ChromeOptions options = SeleniumDriverFactory.chromeOptions(config(1440, 900));

        // Asserted through the public capability map rather than a private field, so this proves
        // the arguments reached Chrome's options and not merely a list beside them.
        String capabilities = String.valueOf(options.asMap());
        assertTrue(capabilities.contains("--headless=new"), capabilities);
        assertTrue(capabilities.contains("--window-size=1440,900"), capabilities);
    }

    // --- 1, 2, 9. BaseSeleniumTest delegates and still owns the lifecycle ----------------------

    @Test
    void baseSeleniumTestDelegatesBrowserCreationAndKeepsNoSecondConfiguration() throws IOException {
        String source = Files.readString(BASE_TEST_SOURCE);

        assertTrue(source.contains("new SeleniumDriverFactory().create(WebsiteConfigLoader.loadSelected())"),
            "creation is delegated to the one factory");
        // There must be exactly one authoritative configuration, so no copy may remain here.
        assertFalse(source.contains("new ChromeDriver"), "no driver may be constructed here");
        assertFalse(source.contains("ChromeOptions"), "no options may be built here");
        assertFalse(source.contains("--headless"), "the headless argument moved to the factory");
        assertFalse(source.contains("qanize.headless"), "the property moved with it");
        assertFalse(source.contains("--window-size"), source);
    }

    @Test
    void baseSeleniumTestStillStartsOneBrowserPerTestAndQuitsIt() throws IOException {
        String source = Files.readString(BASE_TEST_SOURCE);

        assertTrue(source.contains("@BeforeEach"), "one browser per test method, as before");
        assertTrue(source.contains("void startBrowser()"), source);
        assertTrue(source.contains("@AfterEach"), source);
        assertTrue(source.contains("driver.quit()"),
            "quitting stays with whoever asked for the browser");
        assertEquals(1, source.split("driver\\.quit\\(\\)", -1).length - 1,
            "exactly one quit, in the teardown");
        assertTrue(source.contains("protected WebDriver driver;"),
            "subclasses still see the driver exactly as before");
    }

    @Test
    void baseSeleniumTestKeepsItsScreenshotAndSecretHelpers() throws IOException {
        String source = Files.readString(BASE_TEST_SOURCE);

        assertTrue(source.contains("@RegisterExtension"), source);
        assertTrue(source.contains("new ScreenshotOnFailureExtension("), source);
        assertTrue(source.contains("protected void protectFromScreenshots(String value)"), source);
        assertTrue(source.contains("protected String requireEnvironmentSecret(String variableName)"),
            source);
        assertTrue(source.contains("protected Path captureRedactedScreenshot(String fileName)"),
            source);
        assertTrue(source.contains("sensitiveValues.clear()"),
            "teardown still clears the redaction list");
    }

    // --- 7, 8. what the factory must not become -----------------------------------------------

    /**
     * The factory's dependencies, judged by what it actually imports and executes.
     *
     * <p>This was originally a substring search over the raw file, which made the class
     * documentation part of the assertion: a sentence explaining that the logic moved out of
     * {@code BaseSeleniumTest}, and another saying the class has no crawler, both read as
     * dependencies. Deleting that documentation to satisfy a string match would have thrown away
     * the explanation and kept the weak check. So the check is on comment-stripped code and on the
     * import list instead, which is what a dependency actually is.
     */
    @Test
    void theFactoryDependsOnNothingButChromeAndTheWebsiteConfig() throws IOException {
        String raw = Files.readString(FACTORY_SOURCE);
        String code = code(raw);
        List<String> imports = imports(raw);

        // Production code, so no test framework and nothing from the screenshot extension.
        assertFalse(code.contains("org.junit"), "production code must not depend on JUnit");
        assertTrue(imports.stream().noneMatch(name -> name.startsWith("org.junit")),
            "no JUnit import: " + imports);
        assertFalse(code.contains("ScreenshotOnFailure"),
            "the factory must not reach into the screenshot extension");

        // The documentation may name the class the logic came from; no executable line may use it.
        assertFalse(code.contains("BaseSeleniumTest"),
            "the factory must not depend on the test-scope base class");

        // No crawler dependency yet: neither an import from the engine package nor its types.
        assertTrue(imports.stream().noneMatch(name -> name.startsWith("com.qanize.automation.engine")),
            "no crawler import: " + imports);
        for (String crawlerType : List.of(
            "Crawler", "CrawlRunner", "CrawlPolicy", "CrawlerPageProcessor", "PageAuditContext")) {
            assertFalse(code.contains(crawlerType),
                "the factory must not reference " + crawlerType + " yet");
        }

        // An allowlist rather than a blocklist: any dependency at all that is not the JDK,
        // Selenium or the website configuration fails here, including one nobody thought to ban.
        for (String imported : imports) {
            assertTrue(
                imported.startsWith("java.")
                    || imported.startsWith("org.openqa.selenium.")
                    || imported.equals("com.qanize.automation.config.WebsiteConfig"),
                "unexpected dependency in the driver factory: " + imported);
        }
        assertTrue(imports.contains("org.openqa.selenium.chrome.ChromeOptions"),
            "the scan must actually be reading the imports: " + imports);
    }

    /** The imported type names, so a dependency is judged by the import rather than by prose. */
    private static List<String> imports(String source) {
        return source.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("import ") && line.endsWith(";"))
            .map(line -> line.substring("import ".length(), line.length() - 1)
                .replace("static ", "")
                .trim())
            .toList();
    }

    @Test
    void theFactoryHoldsNoBrowserOfItsOwn() throws IOException {
        String source = code(Files.readString(FACTORY_SOURCE));

        assertFalse(source.contains("ThreadLocal"), "no ambient per-thread browser");
        assertFalse(source.contains("static WebDriver"), "no globally shared driver");
        assertFalse(source.contains("private WebDriver"), "the factory stores no driver at all");
        assertFalse(source.contains("getInstance"), "no singleton");
        assertFalse(source.contains(".quit()"),
            "the factory never quits what it hands out; the caller owns that");

        // One construction site, reachable only through the production constructor. It is a
        // lambda with two arms rather than a method reference, because a pinned run has to hand
        // the driver its service; both arms live in that one lambda and nowhere else.
        assertEquals(2, source.split("new ChromeDriver\\(", -1).length - 1,
            "exactly two starts, and both inside the single production launcher");
        assertEquals(1, source.split("new ChromeDriver\\(options\\)", -1).length - 1,
            "one of them is the unchanged default start");
        assertEquals(1, source.split("new ChromeDriver\\(service, options\\)", -1).length - 1,
            "and the other is the pinned start, through the supplied service");
        assertEquals(1, source.split("interface BrowserLauncher", -1).length - 1,
            "and there is still only one seam through which either can happen");
    }

    @Test
    void twoCallsProduceTwoIndependentBrowsers() {
        RecordingLauncher launcher = new RecordingLauncher();
        SeleniumDriverFactory factory = new SeleniumDriverFactory(launcher);

        assertThrows(IllegalStateException.class, () -> factory.create(config(1440, 900)));
        assertThrows(IllegalStateException.class, () -> factory.create(config(1440, 900)));

        assertEquals(2, launcher.launches, "each call starts its own browser, with no caching");
        assertEquals(2, launcher.seenArguments.size());
        assertEquals(launcher.seenArguments.get(0), launcher.seenArguments.get(1),
            "and both get the identical configuration");
    }

    // --- driver-creation failures are never swallowed ------------------------------------------

    @Test
    void aBrowserThatCannotStartPropagatesUntouched() {
        IllegalStateException driverFailure =
            new IllegalStateException("driver executable must exist");
        SeleniumDriverFactory factory = new SeleniumDriverFactory((options, service) -> {
            throw driverFailure;
        });

        IllegalStateException escaped = assertThrows(IllegalStateException.class,
            () -> factory.create(config(1440, 900)));
        assertTrue(escaped == driverFailure,
            "the original exception, neither swallowed nor wrapped: " + escaped);
    }

    @Test
    void theFactoryRefusesAMissingConfigurationOrLauncher() {
        assertThrows(NullPointerException.class, () -> new SeleniumDriverFactory(null));
        assertThrows(NullPointerException.class,
            () -> new SeleniumDriverFactory().create(null));
    }

    // --- 10. the six legacy wrappers are untouched --------------------------------------------

    @Test
    void theSixLegacyWrappersStillStartTheirBrowserTheSameWay() throws IOException {
        for (String wrapper : List.of(
            "src/test/java/com/qanize/automation/checks/smoke/GenericSmokeTest.java",
            "src/test/java/com/qanize/automation/checks/links/GenericLinkValidationTest.java",
            "src/test/java/com/qanize/automation/checks/navigation/GenericNavigationTest.java",
            "src/test/java/com/qanize/automation/checks/forms/GenericFormDetectionTest.java",
            "src/test/java/com/qanize/automation/checks/search/GenericSearchTest.java",
            "src/test/java/com/qanize/automation/checks/login/GenericLoginTest.java")) {
            String source = Files.readString(Path.of(wrapper));

            // They never knew how the browser was built and still do not: they inherit `driver`.
            assertTrue(source.contains("extends GenericBaseSeleniumTest"), wrapper);
            assertTrue(source.contains("@Tag(\"website-analysis\")"), wrapper);
            assertFalse(source.contains("SeleniumDriverFactory"),
                wrapper + " must not learn about the factory");
            assertFalse(source.contains("ChromeDriver"), wrapper);
            assertFalse(source.contains("ChromeOptions"), wrapper);
        }
    }

    @Test
    void theFactoryIsProductionCodeThatAFutureRunEngineCanReach() throws IOException {
        assertTrue(Files.isRegularFile(FACTORY_SOURCE),
            "it must live in src/main so non-test callers can use it");
        assertNotNull(SeleniumDriverFactory.class.getPackage());
        assertEquals("com.qanize.automation.browser",
            SeleniumDriverFactory.class.getPackage().getName());
    }

    /** Source with comments removed, so a guard cannot match documentation. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** Records what it was asked to launch, then refuses: no Chrome is ever started here. */
    private static final class RecordingLauncher implements SeleniumDriverFactory.BrowserLauncher {

        private int launches;
        private final java.util.List<String> seenArguments = new java.util.ArrayList<>();
        /** The service of the last launch, or null when the default path was taken. */
        private org.openqa.selenium.chrome.ChromeDriverService seenService;
        private ChromeOptions seenOptions;

        @Override
        public org.openqa.selenium.WebDriver launch(
            ChromeOptions options, org.openqa.selenium.chrome.ChromeDriverService service
        ) {
            launches++;
            seenOptions = options;
            seenService = service;
            seenArguments.add(String.valueOf(options.asMap()));
            throw new IllegalStateException("no browser is started in the unit suite");
        }
    }

    // --- Chrome's own password UI stays out of the page -----------------------------------------

    /** The preferences map Chrome would be started with, read back out of the options. */
    private static java.util.Map<?, ?> preferencesIn(ChromeOptions options) {
        Object chrome = options.asMap().get(ChromeOptions.CAPABILITY);
        if (!(chrome instanceof java.util.Map<?, ?> capability)) return java.util.Map.of();
        Object preferences = capability.get("prefs");
        return preferences instanceof java.util.Map<?, ?> found ? found : java.util.Map.of();
    }

    /** The arguments Chrome would be started with, read back out of the options. */
    @SuppressWarnings("unchecked")
    private static List<String> argumentsIn(ChromeOptions options) {
        Object chrome = options.asMap().get(ChromeOptions.CAPABILITY);
        if (!(chrome instanceof java.util.Map<?, ?> capability)) return List.of();
        Object arguments = capability.get("args");
        return arguments instanceof List<?> found ? (List<String>) found : List.of();
    }

    @Test
    void thePasswordManagerServiceIsTurnedOffForEverySessionThisProjectStarts() {
        // Chrome answered a successful sign-in by covering the page with its own warning. That
        // warning is browser UI, so the page's hit-test still called the control beneath it
        // reachable and the click produced no document event at all.
        assertEquals(Boolean.FALSE,
            preferencesIn(SeleniumDriverFactory.chromeOptions(config(1440, 900)))
                .get(SeleniumDriverFactory.PASSWORD_MANAGER_PREFERENCE));
        assertEquals("credentials_enable_service",
            SeleniumDriverFactory.PASSWORD_MANAGER_PREFERENCE,
            "Chromium's own name for the Password Manager service preference");
    }

    @Test
    void theLeakCheckWhoseResultIsThatModalIsTurnedOffToo() {
        assertEquals(Boolean.FALSE,
            preferencesIn(SeleniumDriverFactory.chromeOptions(config(1440, 900)))
                .get(SeleniumDriverFactory.LEAK_DETECTION_PREFERENCE));
        assertEquals("profile.password_manager_leak_detection",
            SeleniumDriverFactory.LEAK_DETECTION_PREFERENCE,
            "Chromium's own name for the submitted-credential leak check");
    }

    @Test
    void theArgumentsAreExactlyWhatTheyWereBeforeThePreferencesExisted() {
        System.clearProperty(SeleniumDriverFactory.HEADLESS_PROPERTY);

        assertEquals(List.of("--headless=new", "--window-size=1440,900"),
            argumentsIn(SeleniumDriverFactory.chromeOptions(config(1440, 900))),
            "the headless argument and the window size are untouched, in their original order");
        assertEquals(List.of("--headless=new", "--window-size=1440,900"),
            SeleniumDriverFactory.chromeArguments(config(1440, 900)));
    }

    @Test
    void nothingBroadWasDisabledToAchieveThis() {
        // The narrowest fix that works. Safe Browsing stays on, web security stays on, and no
        // argument was added at all -- two profile preferences and nothing else.
        ChromeOptions options = SeleniumDriverFactory.chromeOptions(config(1440, 900));

        for (String forbidden : List.of(
            "--disable-web-security", "--safebrowsing-disable-download-protection",
            "--safebrowsing-disable-auto-update", "--disable-features", "--allow-running-insecure-content",
            "--disable-popup-blocking", "--no-sandbox")) {
            assertFalse(String.valueOf(argumentsIn(options)).contains(forbidden),
                "no broad disabling argument may be introduced: " + forbidden);
        }
        java.util.Map<?, ?> preferences = preferencesIn(options);
        assertEquals(2, preferences.size(), "exactly the two agreed preferences: " + preferences);
        for (Object key : preferences.keySet()) {
            assertFalse(String.valueOf(key).contains("safebrowsing"),
                "Safe Browsing is never touched: " + key);
        }
    }

    @Test
    void anAlreadyPresentPreferenceSurvivesAndSoDoTheTwo() throws IOException {
        // Merged rather than replaced, so a preference someone adds later is not silently lost.
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("prefs",
            new java.util.LinkedHashMap<>(java.util.Map.of("intl.accept_languages", "en-GB")));

        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        preferencesIn(options).forEach((key, value) -> merged.put(String.valueOf(key), value));
        merged.put(SeleniumDriverFactory.PASSWORD_MANAGER_PREFERENCE, false);
        merged.put(SeleniumDriverFactory.LEAK_DETECTION_PREFERENCE, false);
        options.setExperimentalOption("prefs", merged);

        assertEquals("en-GB", preferencesIn(options).get("intl.accept_languages"));
        assertEquals(Boolean.FALSE,
            preferencesIn(options).get(SeleniumDriverFactory.PASSWORD_MANAGER_PREFERENCE));
        assertTrue(code(Files.readString(FACTORY_SOURCE)).contains("existingPreferences(options)"),
            "and production merges rather than overwrites");
    }

    @Test
    void nothingHereDismissesADialogOrClicksAnything() throws IOException {
        // A warning that never appears needs no handling, and handling one would be a second way
        // for this project to interact with a page.
        String source = code(Files.readString(FACTORY_SOURCE));

        for (String forbidden : List.of(
            "switchTo()", "alert", "Alert", "accept()", "dismiss()", ".click(", "Actions",
            "sleep", "Thread.", "retry")) {
            assertFalse(source.contains(forbidden),
                "the factory must never interact with a page or a dialog: found " + forbidden);
        }
    }

    // --- which Chrome, and which driver ---------------------------------------------------------

    /** An environment that answers only what a test put in it. The real one is never touched. */
    private static java.util.function.UnaryOperator<String> environment(String... pairs) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) values.put(pairs[index], pairs[index + 1]);
        return values::get;
    }

    /** The binary Chrome was told to run, read back out of the options. */
    private static String binaryIn(ChromeOptions options) {
        Object capability = options.asMap().get("goog:chromeOptions");
        if (!(capability instanceof java.util.Map<?, ?> chrome)) return null;
        Object binary = chrome.get("binary");
        return binary == null ? null : String.valueOf(binary);
    }

    @Test
    void bothVariablesPinTheBinaryAndTheDriverThatWereSupplied(@TempDir Path pinned)
        throws IOException {
        // The whole point: the run tests the browser it was handed, and the driver that matches it,
        // rather than whichever Chrome the machine updated itself to overnight.
        Path chrome = Files.createFile(pinned.resolve("chrome"));
        Path chromeDriver = Files.createFile(pinned.resolve("chromedriver"));
        RecordingLauncher launcher = new RecordingLauncher();

        assertThrows(IllegalStateException.class, () -> new SeleniumDriverFactory(
            launcher,
            environment(
                SeleniumDriverFactory.CHROME_BINARY_VARIABLE, chrome.toString(),
                SeleniumDriverFactory.CHROMEDRIVER_VARIABLE, chromeDriver.toString()))
            .create(config(1440, 900)));

        assertEquals(1, launcher.launches, "one browser start was attempted, and no more");
        assertEquals(chrome.toString(), binaryIn(launcher.seenOptions),
            "the supplied Chrome is the binary the options name");
        assertNotNull(launcher.seenService, "a service was built rather than left to be discovered");
        assertEquals(chromeDriver.toString(), launcher.seenService.getExecutable(),
            "and it runs the supplied ChromeDriver");
        assertTrue(launcher.seenArguments.getFirst().contains("--window-size=1440,900"),
            "pinning changes nothing about the arguments");
    }

    @Test
    void neitherVariablePreservesTheExistingBrowserStartExactly() {
        // The default path must stay the default path: no binary, no service, nothing named.
        RecordingLauncher launcher = new RecordingLauncher();

        assertThrows(IllegalStateException.class, () ->
            new SeleniumDriverFactory(launcher, environment()).create(config(1440, 900)));

        assertEquals(1, launcher.launches);
        assertNull(binaryIn(launcher.seenOptions), "no binary is named when nothing was pinned");
        assertNull(launcher.seenService, "and no service is built, exactly as before");
        assertEquals(java.util.Optional.empty(),
            SeleniumDriverFactory.pinnedBrowser(environment()));
    }

    @Test
    void anEmptyValueCountsAsUnsetRatherThanAsAPath() {
        assertEquals(java.util.Optional.empty(), SeleniumDriverFactory.pinnedBrowser(environment(
            SeleniumDriverFactory.CHROME_BINARY_VARIABLE, "   ",
            SeleniumDriverFactory.CHROMEDRIVER_VARIABLE, "")));
    }

    @Test
    void onlyTheBinaryFailsBeforeAnyBrowserIsStarted(@TempDir Path pinned) throws IOException {
        Path chrome = Files.createFile(pinned.resolve("chrome"));
        RecordingLauncher launcher = new RecordingLauncher();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
            new SeleniumDriverFactory(launcher, environment(
                SeleniumDriverFactory.CHROME_BINARY_VARIABLE, chrome.toString()))
                .create(config(1440, 900)));

        assertEquals(0, launcher.launches, "half a pair never starts a browser");
        assertTrue(refused.getMessage().contains(SeleniumDriverFactory.CHROMEDRIVER_VARIABLE),
            "the message names the variable that is missing: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(chrome.toString()),
            "and never repeats a path from someone's disk");
    }

    @Test
    void onlyTheDriverFailsBeforeAnyBrowserIsStarted(@TempDir Path pinned) throws IOException {
        Path chromeDriver = Files.createFile(pinned.resolve("chromedriver"));
        RecordingLauncher launcher = new RecordingLauncher();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
            new SeleniumDriverFactory(launcher, environment(
                SeleniumDriverFactory.CHROMEDRIVER_VARIABLE, chromeDriver.toString()))
                .create(config(1440, 900)));

        assertEquals(0, launcher.launches);
        assertTrue(refused.getMessage().contains(SeleniumDriverFactory.CHROME_BINARY_VARIABLE),
            "the message names the variable that is missing: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(chromeDriver.toString()));
    }

    @Test
    void aBinaryThatIsNotThereFailsRatherThanFallingBackToTheInstalledChrome(@TempDir Path pinned)
        throws IOException {
        Path chromeDriver = Files.createFile(pinned.resolve("chromedriver"));
        RecordingLauncher launcher = new RecordingLauncher();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
            new SeleniumDriverFactory(launcher, environment(
                SeleniumDriverFactory.CHROME_BINARY_VARIABLE, pinned.resolve("absent").toString(),
                SeleniumDriverFactory.CHROMEDRIVER_VARIABLE, chromeDriver.toString()))
                .create(config(1440, 900)));

        assertEquals(0, launcher.launches, "a browser that was asked for and is absent starts nothing");
        assertTrue(refused.getMessage().contains(SeleniumDriverFactory.CHROME_BINARY_VARIABLE));
    }

    @Test
    void aDriverThatIsNotThereFailsRatherThanLettingSeleniumManagerChoose(@TempDir Path pinned)
        throws IOException {
        Path chrome = Files.createFile(pinned.resolve("chrome"));
        RecordingLauncher launcher = new RecordingLauncher();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
            new SeleniumDriverFactory(launcher, environment(
                SeleniumDriverFactory.CHROME_BINARY_VARIABLE, chrome.toString(),
                SeleniumDriverFactory.CHROMEDRIVER_VARIABLE, pinned.resolve("absent").toString()))
                .create(config(1440, 900)));

        assertEquals(0, launcher.launches);
        assertTrue(refused.getMessage().contains(SeleniumDriverFactory.CHROMEDRIVER_VARIABLE));
    }

    @Test
    void aDirectoryIsNotAFileAndIsRefusedAsOne(@TempDir Path pinned) throws IOException {
        Path folder = Files.createDirectory(pinned.resolve("chrome-for-testing"));
        Path chromeDriver = Files.createFile(pinned.resolve("chromedriver"));

        assertThrows(IllegalStateException.class, () -> SeleniumDriverFactory.pinnedBrowser(
            environment(
                SeleniumDriverFactory.CHROME_BINARY_VARIABLE, folder.toString(),
                SeleniumDriverFactory.CHROMEDRIVER_VARIABLE, chromeDriver.toString())));
    }

    @Test
    void theServiceRunsTheSuppliedExecutableAndNoOther(@TempDir Path pinned) throws IOException {
        Path chromeDriver = Files.createFile(pinned.resolve("chromedriver"));

        assertEquals(chromeDriver.toString(),
            SeleniumDriverFactory.chromeDriverService(chromeDriver).getExecutable());
    }

    @Test
    void noVersionIsPinnedInProductionCodeAndNoBrowserIsEverDownloaded() throws IOException {
        // The pair is chosen outside this project, by whoever installed it. A version written into
        // the source would go stale silently, and a download would make the run its own installer.
        String source = code(Files.readString(FACTORY_SOURCE));

        assertFalse(source.matches("(?s).*\\b\\d+\\.\\d+\\.\\d+\\.\\d+\\b.*"),
            "no Chrome version may be hardcoded in production code");
        for (String forbidden : List.of("http://", "https://", "URL", "HttpClient", "download")) {
            assertFalse(source.contains(forbidden),
                "the factory must never fetch a browser: found " + forbidden);
        }
        assertTrue(source.contains("usingDriverExecutable"),
            "the service is built from the supplied executable");
        assertTrue(source.contains("setBinary"), "and the options name the supplied binary");
    }
}
