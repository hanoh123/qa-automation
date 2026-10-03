package com.qanize.automation.checks.shared;

import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;


public final class WaitUtils {

    private WaitUtils() {
    }

    public static WebElement visible(WebDriver driver, By locator, Duration timeout) {
        return wait(driver, timeout).until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public static WebElement clickable(WebDriver driver, By locator, Duration timeout) {
        return wait(driver, timeout).until(ExpectedConditions.elementToBeClickable(locator));
    }

    public static boolean titleIsNotEmpty(WebDriver driver, Duration timeout) {
        return wait(driver, timeout).until(activeDriver -> {
            String title = activeDriver.getTitle();
            return title != null && !title.isBlank();
        });
    }

    public static WebDriverWait wait(WebDriver driver, Duration timeout) {
        return new WebDriverWait(driver, timeout);
    }
}
