package com.qanize.automation.checks.login;

import java.util.List;
import java.util.Locale;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;


/**
 * Finds the sign-in controls on a page so ordinary users do not have to supply CSS selectors.
 *
 * <p>Detection is deliberately conservative. Only visible, enabled elements are considered, a role
 * is resolved only when there is a single best candidate, and controls that would register an
 * account, sign out, reset a password or start a third-party flow are excluded outright. When a
 * role cannot be resolved unambiguously the detector reports that instead of guessing, and the
 * caller must not interact with the page at all.
 *
 * <p>The logic is kept separate from login execution so the candidate filtering and ranking can be
 * unit tested against a page without performing a login.
 */
public final class LoginElementDetector {

    /** Which control the detector was looking for. */
    public enum Role {
        USERNAME("username or phone field"),
        PASSWORD("password field"),
        SUBMIT("sign-in button");

        private final String label;

        Role(String label) { this.label = label; }

        public String label() { return label; }
    }

    /** Why a role could not be resolved. */
    public enum Failure { NOT_FOUND, AMBIGUOUS }

    public record Resolution(Role role, WebElement element, Failure failure, int candidateCount) {
        public boolean resolved() { return element != null; }

        /** A customer-facing sentence that never contains a credential. */
        public String explanation() {
            if (resolved()) return null;
            return failure == Failure.AMBIGUOUS
                ? "QANIZE found " + candidateCount + " possible " + role.label()
                  + "s and could not tell which one to use."
                : "QANIZE could not find the " + role.label() + " on the login page.";
        }

        public String suggestedAction() {
            return resolved() ? null : "Open Advanced settings and provide the selector manually.";
        }
    }

    /** Words that mark a control we must never type into or click. */
    private static final List<String> UNSAFE_WORDS = List.of(
        "register", "signup", "sign up", "createaccount", "create account", "join",
        "forgot", "reset", "recover", "logout", "log out", "signout", "sign out",
        "delete", "remove", "cancel", "google", "facebook", "apple", "github",
        "microsoft", "twitter", "linkedin", "sso", "oauth"
    );

    /** Attribute hints that suggest an identifier field. */
    private static final List<String> USERNAME_HINTS = List.of(
        "username", "user", "email", "phone", "mobile", "login", "account", "identifier", "msisdn"
    );

    private final WebDriver driver;

    public LoginElementDetector(WebDriver driver) {
        this.driver = driver;
    }

    public Resolution detectPassword() {
        List<WebElement> candidates = usable(driver.findElements(By.cssSelector("input[type='password']")));
        if (candidates.isEmpty()) return new Resolution(Role.PASSWORD, null, Failure.NOT_FOUND, 0);
        if (candidates.size() == 1) return new Resolution(Role.PASSWORD, candidates.getFirst(), null, 1);

        // Several password inputs usually means a change-password or registration form.
        List<WebElement> current = candidates.stream()
            .filter(element -> "current-password".equalsIgnoreCase(attribute(element, "autocomplete")))
            .toList();
        if (current.size() == 1) return new Resolution(Role.PASSWORD, current.getFirst(), null, candidates.size());
        return new Resolution(Role.PASSWORD, null, Failure.AMBIGUOUS, candidates.size());
    }

    public Resolution detectUsername() {
        List<WebElement> candidates = usable(driver.findElements(
            By.cssSelector("input[type='text'], input[type='email'], input[type='tel'], input:not([type])")
        )).stream().filter(element -> !looksUnsafe(element)).toList();

        if (candidates.isEmpty()) return new Resolution(Role.USERNAME, null, Failure.NOT_FOUND, 0);

        // 1. an explicit autocomplete contract is the strongest signal
        List<WebElement> byAutocomplete = candidates.stream()
            .filter(element -> {
                String value = attribute(element, "autocomplete").toLowerCase(Locale.ROOT);
                return value.equals("username") || value.equals("email") || value.equals("tel");
            })
            .toList();
        if (byAutocomplete.size() == 1) return new Resolution(Role.USERNAME, byAutocomplete.getFirst(), null, candidates.size());

        // 2. a dedicated email or telephone input type
        List<WebElement> byType = candidates.stream()
            .filter(element -> {
                String type = attribute(element, "type").toLowerCase(Locale.ROOT);
                return type.equals("email") || type.equals("tel");
            })
            .toList();
        if (byType.size() == 1) return new Resolution(Role.USERNAME, byType.getFirst(), null, candidates.size());

        // 3. a recognisable name, id or label
        List<WebElement> byHint = candidates.stream().filter(LoginElementDetector::hasUsernameHint).toList();
        if (byHint.size() == 1) return new Resolution(Role.USERNAME, byHint.getFirst(), null, candidates.size());

        // 4. a single plausible text field on the page
        if (candidates.size() == 1) return new Resolution(Role.USERNAME, candidates.getFirst(), null, 1);

        return new Resolution(Role.USERNAME, null, Failure.AMBIGUOUS, candidates.size());
    }

    public Resolution detectSubmit() {
        List<WebElement> candidates = usable(driver.findElements(
            By.cssSelector("button, input[type='submit'], input[type='button']")
        )).stream().filter(element -> !looksUnsafe(element)).toList();

        if (candidates.isEmpty()) return new Resolution(Role.SUBMIT, null, Failure.NOT_FOUND, 0);

        List<WebElement> submits = candidates.stream()
            .filter(element -> "submit".equalsIgnoreCase(attribute(element, "type")))
            .toList();
        if (submits.size() == 1) return new Resolution(Role.SUBMIT, submits.getFirst(), null, candidates.size());

        List<WebElement> byText = candidates.stream().filter(LoginElementDetector::hasLoginText).toList();
        if (byText.size() == 1) return new Resolution(Role.SUBMIT, byText.getFirst(), null, candidates.size());

        if (candidates.size() == 1) return new Resolution(Role.SUBMIT, candidates.getFirst(), null, 1);
        return new Resolution(Role.SUBMIT, null, Failure.AMBIGUOUS, candidates.size());
    }

    /** Only elements a user could actually interact with are ever considered. */
    private static List<WebElement> usable(List<WebElement> elements) {
        return elements.stream().filter(element -> {
            try {
                return element.isDisplayed() && element.isEnabled();
            } catch (RuntimeException detached) {
                return false;
            }
        }).toList();
    }

    static boolean containsUnsafeWord(String text) {
        String compact = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return UNSAFE_WORDS.stream().anyMatch(compact::contains);
    }

    private static boolean looksUnsafe(WebElement element) {
        return containsUnsafeWord(describe(element));
    }

    private static boolean hasUsernameHint(WebElement element) {
        String description = describe(element).toLowerCase(Locale.ROOT);
        return USERNAME_HINTS.stream().anyMatch(description::contains);
    }

    private static boolean hasLoginText(WebElement element) {
        String description = describe(element).toLowerCase(Locale.ROOT);
        return description.contains("log in") || description.contains("login")
            || description.contains("sign in") || description.contains("signin")
            || description.contains("continue") || description.contains("submit");
    }

    /**
     * Builds the text used for matching. Deliberately excludes any element value so a credential
     * already typed into a field can never influence matching or reach a message.
     */
    private static String describe(WebElement element) {
        return String.join(" ",
            attribute(element, "name"),
            attribute(element, "id"),
            attribute(element, "autocomplete"),
            attribute(element, "placeholder"),
            attribute(element, "aria-label"),
            attribute(element, "data-testid"),
            safeText(element)
        );
    }

    private static String attribute(WebElement element, String name) {
        try {
            String value = element.getAttribute(name);
            return value == null ? "" : value;
        } catch (RuntimeException detached) {
            return "";
        }
    }

    private static String safeText(WebElement element) {
        try {
            String text = element.getText();
            return text == null ? "" : text;
        } catch (RuntimeException detached) {
            return "";
        }
    }
}
