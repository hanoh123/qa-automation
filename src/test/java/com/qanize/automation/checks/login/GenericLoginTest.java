package com.qanize.automation.checks.login;

import com.qanize.automation.browser.GenericBaseSeleniumTest;
import com.qanize.automation.config.WebsiteConfig;
import com.qanize.automation.config.WebsiteConfigLoader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The JUnit face of the Login check.
 *
 * <p>The sign-in flow itself lives in {@link LoginModule}. What stays here is what only JUnit and
 * Surefire can provide, plus the two things that belong to the browser session rather than to the
 * flow: resolving the credentials from the process environment, and registering them for
 * screenshot redaction. Redaction is a property of this test's browser lifecycle — a module cannot
 * reach it — so the values are resolved here and handed to the module as arguments.
 *
 * <p>The credentials never enter a configuration object, an argument list on disk, or an artifact.
 */
@Tag("website-analysis")
public class GenericLoginTest extends GenericBaseSeleniumTest {

    @Test
    void configuredLoginCanCompleteWhenEnabled() {
        WebsiteConfig config = websiteConfig();
        assumeTrue(config.tests().login(), "Login test is disabled by tests.login=false");

        WebsiteLoginConfig login = config.login();
        assumeTrue(login.enabled(), "Login execution is disabled by login.enabled=false");

        String username = requiredSecret(login.usernameEnv(), "login.usernameEnv");
        String password = requiredSecret(login.passwordEnv(), "login.passwordEnv");
        protectFromScreenshots(username);
        protectFromScreenshots(password);

        // Contract 1.1 execution evidence. The observer is told what happened, never what was
        // typed: its typing callbacks carry a duration and nothing else.
        LoginExecutionEvidence execution = new LoginExecutionEvidence();

        LoginModule.Result result =
            new LoginModule(driver, WebsiteConfigLoader.timeout(config), execution)
                .run(login, username, password);

        assertTrue(result.passed(), result.message());
    }

    private static String requiredSecret(String envName, String configKey) {
        assertFalse(envName == null || envName.isBlank(), configKey + " must define an environment variable name");
        String value = System.getenv(envName);
        assertFalse(value == null || value.isBlank(), "Environment variable configured by " + configKey + " is missing: " + envName);
        return value;
    }
}
