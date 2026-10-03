package com.qanize.automation.config;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.qanize.automation.engine.CrawlPolicy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WebsiteConfigLoaderTest {

    @Test
    void searchDisabledDoesNotRequireSelectors() {
        WebsiteConfig config = load("""
            contract.version=1.0
            site.url=https://example.com/
            tests.search=false
            """);

        assertFalse(config.search().enabled());
        assertEquals(1440, config.viewportWidth());
        assertEquals(900, config.viewportHeight());
    }

    @Test
    void searchEnabledWithoutInputSelectorHasStructuredError() {
        RunConfigurationException failure = assertThrows(
            RunConfigurationException.class,
            () -> load("""
                contract.version=1.0
                site.url=https://example.com/
                tests.search=true
                search.query=qanize
                """)
        );

        assertEquals("RUN_CONFIGURATION", failure.category());
        assertEquals("SEARCH_INPUT_SELECTOR_REQUIRED", failure.code());
        assertEquals(
            "Search testing was enabled, but no search input selector was configured.",
            failure.userMessage()
        );
        assertEquals(
            "Configure the search-field selector or disable Search testing.",
            failure.suggestedAction()
        );
    }

    @Test
    void validEnabledSearchIsForwarded() {
        WebsiteConfig config = load("""
            contract.version=1.0
            site.url=https://example.com/
            tests.search=true
            search.inputSelector=input[type='search']
            search.submitSelector=button[type='submit']
            search.resultsSelector=#results
            search.query=qanize
            """);

        assertTrue(config.search().enabled());
        assertEquals("input[type='search']", config.search().inputSelector());
        assertEquals("button[type='submit']", config.search().submitSelector());
        assertEquals("#results", config.search().resultsSelector());
        assertEquals("qanize", config.search().query());
    }

    @Test
    void loginUsesEnvironmentVariableNamesAndRejectsCredentialText() {
        RunConfigurationException failure = assertThrows(
            RunConfigurationException.class,
            () -> load("""
                contract.version=1.0
                site.url=https://example.com/
                tests.login=true
                login.url=https://example.com/login
                login.usernameEnv=user@example.com
                login.passwordEnv=QANIZE_TEST_PASSWORD
                login.usernameSelector=#username
                login.passwordSelector=#password
                login.submitSelector=button[type='submit']
                """)
        );

        assertEquals("INVALID_ENVIRONMENT_VARIABLE_NAME", failure.code());
    }

    @Test
    void privateTargetAndLoginUrlsAreRejected() {
        RunConfigurationException targetFailure = assertThrows(
            RunConfigurationException.class,
            () -> load("site.url=http://127.0.0.1/")
        );
        assertEquals("PRIVATE_TARGET_NOT_ALLOWED", targetFailure.code());

        RunConfigurationException loginFailure = assertThrows(
            RunConfigurationException.class,
            () -> load("""
                site.url=https://example.com/
                tests.login=true
                login.url=http://localhost/login
                login.usernameEnv=QANIZE_TEST_USERNAME
                login.passwordEnv=QANIZE_TEST_PASSWORD
                login.usernameSelector=#username
                login.passwordSelector=#password
                login.submitSelector=button
                """)
        );
        assertEquals("PRIVATE_TARGET_NOT_ALLOWED", loginFailure.code());
    }

    // --- the crawl's page budget ------------------------------------------------------------------

    @Test
    void aConfiguredPageBudgetIsLoadedExactlyAsWritten() {
        for (int configured : List.of(1, 3, 10)) {
            WebsiteConfig config = load("""
                contract.version=1.0
                site.url=https://example.com/
                maxPages=%d
                """.formatted(configured));

            assertEquals(configured, config.maxPages(),
                "maxPages=" + configured + " must survive the load unchanged");
        }
    }

    @Test
    void anOmittedPageBudgetFallsBackToTheDocumentedDefault() {
        // This is the only layer where "the run said nothing" is still distinguishable from "the
        // run asked for exactly this many": below here it is an int like any other. The fallback
        // is the crawl's own published constant, so the two cannot drift apart.
        WebsiteConfig config = load("""
            contract.version=1.0
            site.url=https://example.com/
            """);

        assertEquals(CrawlPolicy.DEFAULT_MAX_PAGES, config.maxPages());
        assertEquals(10, config.maxPages(), "the documented default page budget");
    }

    @Test
    void theMaximumLinksSettingIsLoadedSeparatelyFromThePageBudget() {
        // Two adjacent keys with opposite meanings. Reading either into the other would produce a
        // run that looked configured and crawled the wrong amount.
        WebsiteConfig config = load("""
            contract.version=1.0
            site.url=https://example.com/
            maxPages=3
            maxLinks=25
            """);

        assertEquals(3, config.maxPages());
        assertEquals(25, config.maxLinks());
    }

    @Test
    void anUnusablePageBudgetIsRejectedRatherThanQuietlyDefaulted() {
        // Zero or negative would otherwise reach CrawlPolicy, which rejects it as a run is
        // starting rather than before one is accepted.
        for (String unusable : List.of("0", "-1", "three")) {
            assertThrows(RunConfigurationException.class, () -> load("""
                contract.version=1.0
                site.url=https://example.com/
                maxPages=%s
                """.formatted(unusable)), "maxPages=" + unusable + " must be rejected");
        }
    }

    private static WebsiteConfig load(String content) {
        try {
            return WebsiteConfigLoader.load(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "test config"
            );
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
