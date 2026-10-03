package com.qanize.automation.browser;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.qanize.automation.config.WebsiteConfig;

import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * The one place a browser is configured and started.
 *
 * <p>This logic used to live in {@code BaseSeleniumTest}, which is test scope. That was fine while
 * the only caller was a JUnit wrapper, but it put browser creation somewhere production code
 * cannot reach. Moving it here — unchanged — is what lets a future run engine start the same
 * browser the six checks have always started, instead of a second one configured slightly
 * differently. There is exactly one authoritative Chrome configuration, and it is
 * {@link #chromeArguments(WebsiteConfig)}.
 *
 * <p><strong>This was a pure move.</strong> No option was added, removed or reordered. The headless
 * property, its default, the argument spelling, the window-size argument and the explicit
 * {@code setSize} that follows driver creation are all exactly as they were.
 *
 * <p><strong>Which Chrome, and which driver.</strong> By default, nothing here names either: the
 * options carry no binary, no service is built, and Selenium Manager resolves a driver for
 * whatever Google Chrome the machine happens to have installed that day. That is convenient and it
 * is not reproducible — the browser under test changes without anyone deciding it should. Setting
 * both {@link #CHROME_BINARY_VARIABLE} and {@link #CHROMEDRIVER_VARIABLE} pins the pair instead:
 * the binary goes into the options, the executable goes into a {@link ChromeDriverService}, and
 * Selenium Manager is left with nothing to choose. Neither variable set keeps the old behaviour
 * exactly. One of the two set, or either path not a file, is a configuration error raised before
 * the browser is started — never a silent fall back to the system Chrome, because a run that
 * quietly tested a different browser than the one that was asked for is worse than a run that
 * refused to start.
 *
 * <p><strong>The paths stay here.</strong> They name someone's own disk, so they are read, checked
 * and handed to Selenium, and nothing else. No message this class produces contains either value —
 * a failure names the variable, never its contents — and no field on this class is reachable by
 * any evidence or reporting code.
 *
 * <p><strong>It owns nothing.</strong> The driver is returned to the caller, which owns quitting
 * it. There is no static field, no singleton, no cache and no {@code ThreadLocal}: two calls return
 * two independent browsers, and a caller that forgets to quit one leaks its own driver rather than
 * corrupting everyone else's.
 *
 * <p><strong>It hides no failure.</strong> A driver that cannot start throws out of
 * {@link #create(WebsiteConfig)} untouched — a missing Chrome or a mismatched driver is an
 * infrastructure fact the caller must see, not something to paper over with a null.
 *
 * <p><strong>It downloads nothing.</strong> Both variables point at files that already exist on
 * the machine. Nothing here fetches, unpacks or installs a browser.
 *
 * <p>No JUnit, no screenshot extension, no crawler: this class knows about Chrome and a
 * {@link WebsiteConfig}, and nothing else.
 */
public final class SeleniumDriverFactory {

    /** Set to {@code false} to watch the run in a visible browser. Defaults to headless. */
    public static final String HEADLESS_PROPERTY = "qanize.headless";

    /** The exact argument the checks have always used. Chrome's modern headless mode. */
    public static final String HEADLESS_ARGUMENT = "--headless=new";

    /** Chrome's own experimental option carrying profile preferences. */
    static final String PREFERENCES_OPTION = "prefs";

    /** Chromium's Password Manager service preference. False keeps the manager out of the way. */
    public static final String PASSWORD_MANAGER_PREFERENCE = "credentials_enable_service";

    /**
     * Chromium's boolean for checking submitted credentials against known leaks.
     *
     * <p>False because the check's result is a browser-owned modal, and a browser-owned modal is
     * not something a website analyzer can see, report or reason about.
     */
    public static final String LEAK_DETECTION_PREFERENCE =
        "profile.password_manager_leak_detection";

    /** Absolute path to the Chrome binary to test against. Set together with the driver, or not at all. */
    public static final String CHROME_BINARY_VARIABLE = "QANIZE_CHROME_BINARY";

    /** Absolute path to the matching ChromeDriver executable. Set together with the binary. */
    public static final String CHROMEDRIVER_VARIABLE = "QANIZE_CHROMEDRIVER_PATH";

    /**
     * A browser and its driver, both checked to exist, chosen deliberately rather than discovered.
     *
     * <p>Only ever constructed by {@link #pinnedBrowser}, so a value of this type is already
     * validated: both components are regular files that were present a moment ago.
     */
    public record PinnedBrowser(Path chrome, Path chromeDriver) { }

    private final BrowserLauncher launcher;
    private final UnaryOperator<String> environment;

    /** The production factory: a real Chrome, and the real environment. */
    public SeleniumDriverFactory() {
        this(
            (options, service) -> service == null
                ? new ChromeDriver(options)
                : new ChromeDriver(service, options),
            System::getenv);
    }

    /**
     * The seam a test uses to avoid launching a real Chrome. Not part of the public API.
     *
     * <p>It replaces only the driver construction. Option building, validation and service
     * building stay shared, so a test can never assert against a second, parallel configuration.
     */
    SeleniumDriverFactory(BrowserLauncher launcher) {
        this(launcher, System::getenv);
    }

    /** The same seam, with the environment supplied too, so no test has to mutate the real one. */
    SeleniumDriverFactory(BrowserLauncher launcher, UnaryOperator<String> environment) {
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * Starts one browser for the supplied configuration.
     *
     * @return a new driver the caller owns and must quit
     * @throws IllegalStateException when the pinning variables are half-set or name something that
     *     is not a file; raised before any browser is started
     * @throws RuntimeException whatever the driver threw; never swallowed, never wrapped
     */
    public WebDriver create(WebsiteConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        // First, so a misconfigured pair costs no browser at all.
        Optional<PinnedBrowser> pinned = pinnedBrowser(environment);
        ChromeOptions options = chromeOptions(config);
        ChromeDriverService service = null;
        if (pinned.isPresent()) {
            options.setBinary(pinned.get().chrome().toFile());
            service = chromeDriverService(pinned.get().chromeDriver());
        }
        WebDriver driver = launcher.launch(options, service);
        // Kept because it was there: the window-size argument sets the initial size, and this
        // corrects the viewport afterwards. Dropping either would be a behaviour change.
        driver.manage().window().setSize(
            new Dimension(config.viewportWidth(), config.viewportHeight()));
        return driver;
    }

    /**
     * The pinned pair, or empty when neither variable is set.
     *
     * <p>Half a pair is rejected rather than half-honoured: a supplied Chrome with a discovered
     * driver, or the reverse, is exactly the mismatch pinning exists to prevent.
     *
     * @throws IllegalStateException naming the offending variable, never its value
     */
    static Optional<PinnedBrowser> pinnedBrowser(UnaryOperator<String> environment) {
        String chrome = supplied(environment, CHROME_BINARY_VARIABLE);
        String chromeDriver = supplied(environment, CHROMEDRIVER_VARIABLE);
        if (chrome == null && chromeDriver == null) return Optional.empty();
        if (chrome == null) {
            throw misconfigured(CHROMEDRIVER_VARIABLE + " is set but " + CHROME_BINARY_VARIABLE
                + " is not. Set both to pin a browser, or neither to use the installed Chrome.");
        }
        if (chromeDriver == null) {
            throw misconfigured(CHROME_BINARY_VARIABLE + " is set but " + CHROMEDRIVER_VARIABLE
                + " is not. Set both to pin a browser, or neither to use the installed Chrome.");
        }
        return Optional.of(new PinnedBrowser(
            existingFile(chrome, CHROME_BINARY_VARIABLE),
            existingFile(chromeDriver, CHROMEDRIVER_VARIABLE)));
    }

    /** The service that runs the supplied ChromeDriver, and no other. */
    static ChromeDriverService chromeDriverService(Path chromeDriver) {
        return new ChromeDriverService.Builder()
            .usingDriverExecutable(chromeDriver.toFile())
            .build();
    }

    private static String supplied(UnaryOperator<String> environment, String variable) {
        String value = environment.apply(variable);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Resolves and checks one path, and says only which variable was wrong. */
    private static Path existingFile(String value, String variable) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException notAPath) {
            throw misconfigured(variable + " is not a usable file path.");
        }
        if (!Files.isRegularFile(path)) {
            throw misconfigured(variable + " does not point to a file that exists.");
        }
        return path;
    }

    /** One short sentence, and never the value of either variable. */
    private static IllegalStateException misconfigured(String problem) {
        return new IllegalStateException("Browser configuration problem: " + problem);
    }

    /**
     * The authoritative Chrome arguments, in the order they have always been applied.
     *
     * <p>Returned as a plain list so the configuration can be asserted exactly, without launching
     * Chrome and without reaching into {@link ChromeOptions} internals.
     */
    public static List<String> chromeArguments(WebsiteConfig config) {
        List<String> arguments = new ArrayList<>();
        if (headlessRequested()) {
            arguments.add(HEADLESS_ARGUMENT);
        }
        arguments.add("--window-size=" + config.viewportWidth() + "," + config.viewportHeight());
        return List.copyOf(arguments);
    }

    /**
     * The same arguments, as the options object Chrome is started with, plus the two preferences
     * that keep Chrome's own password UI out of the page.
     *
     * <p>A run signed in, and Chrome answered by putting its compromised-password warning over the
     * page. That warning is browser UI, not website content: the page's own hit-test still reported
     * the control beneath it as reachable, the control stayed connected and did not move, and the
     * pointer produced no document click at all — the analyzer had no way to see what had blocked
     * it, and the warning's variable timing is what made the failure look intermittent.
     *
     * <p>So the two preferences below are set for every session this project starts. They are the
     * narrowest thing that works: the Password Manager service, and the leak check whose result is
     * that modal. Safe Browsing is untouched, web security is untouched, and nothing here dismisses
     * a dialog or clicks anything — a warning that never appears needs no handling.
     */
    public static ChromeOptions chromeOptions(WebsiteConfig config) {
        ChromeOptions options = new ChromeOptions();
        // One argument per call, exactly as before: addArguments appends, so the resulting order
        // is identical to the two separate calls this replaced.
        chromeArguments(config).forEach(options::addArguments);
        // Merged, not replaced: a preference someone adds later survives this, and these two
        // survive that. Neither value is read from anywhere, and no credential passes through.
        Map<String, Object> preferences = new LinkedHashMap<>(existingPreferences(options));
        preferences.put(PASSWORD_MANAGER_PREFERENCE, false);
        preferences.put(LEAK_DETECTION_PREFERENCE, false);
        options.setExperimentalOption(PREFERENCES_OPTION, preferences);
        return options;
    }

    /** Whatever preferences the options already carry, read back through their own capability map. */
    private static Map<String, Object> existingPreferences(ChromeOptions options) {
        Object chrome = options.asMap().get(ChromeOptions.CAPABILITY);
        if (!(chrome instanceof Map<?, ?> capability)) return Map.of();
        Object preferences = capability.get(PREFERENCES_OPTION);
        if (!(preferences instanceof Map<?, ?> existing)) return Map.of();
        Map<String, Object> copied = new LinkedHashMap<>();
        existing.forEach((key, value) -> copied.put(String.valueOf(key), value));
        return copied;
    }

    /** Headless unless the property explicitly says otherwise. The default has not changed. */
    public static boolean headlessRequested() {
        return Boolean.parseBoolean(System.getProperty(HEADLESS_PROPERTY, "true"));
    }

    /**
     * Starting the browser, and nothing else.
     *
     * <p>Deliberately the narrowest possible seam: it receives fully built options and returns a
     * driver, so no test can substitute a different configuration by substituting a launcher.
     *
     * @param service the service running a pinned ChromeDriver, or {@code null} to start the
     *     driver exactly as this project always has
     */
    @FunctionalInterface
    interface BrowserLauncher {

        /** @throws RuntimeException when the browser could not be started */
        WebDriver launch(ChromeOptions options, ChromeDriverService service);
    }
}
