package com.qanize.automation.checks.shared;

import java.time.Duration;

import org.openqa.selenium.WebDriver;


public final class CommonPage extends BasePage {

    public CommonPage(WebDriver driver, Duration timeout) {
        super(driver, timeout);
    }

    public boolean hasLoadedDocument() {
        String source = driver.getPageSource();
        return source != null && !source.isBlank();
    }
}
