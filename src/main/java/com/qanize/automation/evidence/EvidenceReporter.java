package com.qanize.automation.evidence;

import java.net.URI;

import com.qanize.automation.config.WebsiteConfig;

public final class EvidenceReporter {

    private EvidenceReporter() {
    }

    public static void logTestStart(String testName, WebsiteConfig config) {
        System.out.println("QA Evidence | test=" + testName
            + " | site=" + config.siteName()
            + " | baseUrl=" + config.baseUrl());
    }

    public static void logPageChecked(String testName, URI url, String title) {
        System.out.println("QA Evidence | test=" + testName
            + " | checkedUrl=" + url
            + " | title=" + safe(title));
    }

    public static void logInfo(String testName, String message) {
        System.out.println("QA Evidence | test=" + testName + " | info=" + safe(message));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "[blank]" : value.replaceAll("[\r\n]+", " ");
    }
}
