package com.qanize.automation.checks.shared;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@Tag("unit")
class SelectorUtilsTest {

    @Test
    void cssTrimsSelector() {
        By selector = SelectorUtils.css("  main a[href]  ");

        assertEquals("By.cssSelector: main a[href]", selector.toString());
    }

    @Test
    void cssRejectsBlankSelector() {
        assertThrows(IllegalArgumentException.class, () -> SelectorUtils.css("   "));
    }

    @Test
    void hasUsableSelectorRecognizesNullAndBlankValues() {
        assertFalse(SelectorUtils.hasUsableSelector(null));
        assertFalse(SelectorUtils.hasUsableSelector(" "));
        assertTrue(SelectorUtils.hasUsableSelector("button"));
    }
}
