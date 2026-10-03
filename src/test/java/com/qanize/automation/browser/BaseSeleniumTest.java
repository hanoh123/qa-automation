package com.qanize.automation.browser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.qanize.automation.config.WebsiteConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openqa.selenium.WebDriver;

import static org.junit.jupiter.api.Assertions.assertFalse;

public abstract class BaseSeleniumTest {

    protected WebDriver driver;

    private final List<String> sensitiveValues = new ArrayList<>();

    @RegisterExtension
    final ScreenshotOnFailureExtension screenshotOnFailure =
        new ScreenshotOnFailureExtension(
            () -> driver,
            () -> List.copyOf(sensitiveValues)
        );

    /**
     * One browser per test method, started by the shared production factory.
     *
     * <p>The Chrome configuration itself lives in {@link SeleniumDriverFactory} so that a run
     * engine and these checks cannot drift into two different browsers. Nothing about the
     * behaviour changed when it moved: same headless property and default, same arguments, same
     * explicit window size.
     *
     * <p>Quitting stays here, in {@link #stopBrowser()}, because the lifecycle belongs to whoever
     * asked for the browser.
     */
    @BeforeEach
    void startBrowser() {
        driver = new SeleniumDriverFactory().create(WebsiteConfigLoader.loadSelected());
    }

    @AfterEach
    void stopBrowser() {
        try {
            if (driver != null) {
                driver.quit();
            }
        } finally {
            driver = null;
            sensitiveValues.clear();
        }
    }

    protected void protectFromScreenshots(String value) {
        if (value != null && !value.isBlank()) {
            sensitiveValues.add(value);
        }
    }

    protected String requireEnvironmentSecret(String variableName) {
        String value = System.getenv(variableName);
        protectFromScreenshots(value);
        assertFalse(
            value == null || value.isBlank(),
            variableName + " environment variable is missing"
        );
        return value;
    }

    protected Path captureRedactedScreenshot(String fileName) {
        try {
            return ScreenshotOnFailureExtension.captureRedactedScreenshot(
                driver,
                List.copyOf(sensitiveValues),
                fileName
            );
        } catch (IOException screenshotFailure) {
            throw new UncheckedIOException(
                "Could not capture a redacted screenshot",
                screenshotFailure
            );
        }
    }
}
