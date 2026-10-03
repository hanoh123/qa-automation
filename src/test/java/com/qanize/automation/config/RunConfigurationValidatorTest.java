package com.qanize.automation.config;


import com.qanize.automation.runtime.AutomationRunRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class RunConfigurationValidatorTest {

    @Test
    void missingLoginEnvironmentValueIsAConfigurationError() {
        AutomationRunRequest request = new AutomationRunRequest(
            "1.0",
            "https://example.com/",
            "desktop",
            1440,
            900,
            1,
            25,
            true,
            "https://example.com/login",
            "QANIZE_INTENTIONALLY_MISSING_USERNAME_71CF",
            "QANIZE_INTENTIONALLY_MISSING_PASSWORD_71CF",
            "#username",
            "#password",
            "button[type='submit']",
            "",
            "",
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            "",
            "",
            "",
            "",
            null,
            null,
            null,
            null,
            null,
            null
        );

        RunConfigurationException failure = assertThrows(
            RunConfigurationException.class,
            () -> RunConfigurationValidator.validate(request)
        );

        assertEquals("LOGIN_ENVIRONMENT_VARIABLE_MISSING", failure.code());
        assertEquals("RUN_CONFIGURATION", failure.category());
    }
}
