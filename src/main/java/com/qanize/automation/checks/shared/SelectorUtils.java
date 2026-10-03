package com.qanize.automation.checks.shared;

import org.openqa.selenium.By;


public final class SelectorUtils {

    private SelectorUtils() {
    }

    public static By css(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("CSS selector must not be blank");
        }
        return By.cssSelector(selector.trim());
    }

    public static boolean hasUsableSelector(String selector) {
        return selector != null && !selector.isBlank();
    }
}
