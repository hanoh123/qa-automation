package com.qanize.automation.checks.login;

import java.net.URI;

import com.qanize.automation.config.RunConfigurationException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The simplified login flow sends no selectors at all, so validation must accept that and leave
 * element resolution to detection. Login URL and credential references stay required: relaxing
 * the selectors must not weaken anything else.
 */
@Tag("unit")
class LoginSelectorRequirementTest {

    private static WebsiteLoginConfig withSelectors(String username, String password, String submit) {
        return new WebsiteLoginConfig(
            true,
            URI.create("https://example.com/login"),
            "QANIZE_TEST_USERNAME",
            "QANIZE_TEST_PASSWORD",
            username,
            password,
            submit,
            "",
            ""
        );
    }

    @Test
    void eachSelectorMayBeOmittedIndividually() {
        assertDoesNotThrow(() -> withSelectors("", "#password", "button[type='submit']").validateReadyForLogin());
        assertDoesNotThrow(() -> withSelectors("#username", "", "button[type='submit']").validateReadyForLogin());
        assertDoesNotThrow(() -> withSelectors("#username", "#password", "").validateReadyForLogin());
    }

    @Test
    void theSimplifiedFlowWithNoSelectorsAtAllIsAccepted() {
        assertDoesNotThrow(() -> withSelectors("", "", "").validateReadyForLogin());
    }

    @Test
    void suppliedSelectorsRemainAvailableToTheLoginHelper() {
        WebsiteLoginConfig config = withSelectors("#username", "#password", "button[type='submit']");
        assertEquals("#username", config.optionalUsernameSelector().orElseThrow());
        assertEquals("#password", config.optionalPasswordSelector().orElseThrow());
        assertEquals("button[type='submit']", config.optionalSubmitSelector().orElseThrow());
        assertTrue(withSelectors("", "", "").optionalUsernameSelector().isEmpty());
    }

    @Test
    void loginUrlAndCredentialReferencesRemainRequired() {
        WebsiteLoginConfig noUrl = new WebsiteLoginConfig(
            true, null, "QANIZE_TEST_USERNAME", "QANIZE_TEST_PASSWORD",
            "#username", "#password", "button[type='submit']", "", ""
        );
        assertEquals(
            "LOGIN_URL_REQUIRED",
            assertThrows(RunConfigurationException.class, noUrl::validateReadyForLogin).code()
        );

        WebsiteLoginConfig noCredentialReference = new WebsiteLoginConfig(
            true, URI.create("https://example.com/login"), "", "QANIZE_TEST_PASSWORD",
            "#username", "#password", "button[type='submit']", "", ""
        );
        assertEquals(
            "LOGIN_USERNAME_ENV_REQUIRED",
            assertThrows(RunConfigurationException.class, noCredentialReference::validateReadyForLogin).code()
        );
    }
}
