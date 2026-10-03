package com.qanize.automation.checks.gamelaunch;

import java.util.Locale;
import java.util.Objects;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;

/**
 * Whether the browser reported a severe error while a game was being launched — and nothing more.
 *
 * <p>A launch that quietly does nothing is often a script that threw before the site's own handler
 * ran. Knowing that one occurred changes the diagnosis; knowing what it said does not, and a
 * console line on a live site can contain an address, a token or a person's data. So the message
 * is read, classified into one of a handful of fixed words, and dropped.
 *
 * <p><strong>Nothing read here is ever kept.</strong> No message, no address, no stack, no source
 * file, no timestamp. The only values that leave this class are a boolean and one word chosen from
 * {@link Kind}, all of which are written here rather than taken from the browser.
 *
 * <p><strong>Optional by construction.</strong> A browser that does not serve its log, or refuses,
 * yields {@link Reading#UNAVAILABLE}. No launch decision may ever depend on this.
 */
public final class BrowserErrorProbe {

    /** The broad shape of a severe entry. Fixed vocabulary, never text from the browser. */
    public enum Kind {
        /** No severe entry appeared. */
        NONE,
        /** A script threw or failed to parse. */
        SCRIPT,
        /** A request was refused, blocked or failed to load. */
        NETWORK,
        /** The browser refused something on security grounds. */
        SECURITY,
        /** Severe, and none of the above. */
        OTHER
    }

    /**
     * @param available whether the browser served its log at all
     * @param occurred  whether at least one new severe entry appeared during the launch
     */
    public record Reading(boolean available, boolean occurred, Kind kind) {

        public static final Reading UNAVAILABLE = new Reading(false, false, Kind.NONE);

        public String occurredWord() {
            return available ? Boolean.toString(occurred) : "unavailable";
        }

        public String kindWord() {
            return available ? kind.name() : "unavailable";
        }
    }

    /** A probe that answers nothing. The default wherever the seam is not wired. */
    public static final BrowserErrorProbe NONE = new BrowserErrorProbe(null);

    private final WebDriver driver;

    private BrowserErrorProbe(WebDriver driver) {
        this.driver = driver;
    }

    public static BrowserErrorProbe on(WebDriver driver) {
        return new BrowserErrorProbe(Objects.requireNonNull(driver, "driver must not be null"));
    }

    /**
     * Drains whatever severe entries the browser has to offer.
     *
     * <p>The log is drained once before the launch and once after, so "new" means new. Chrome
     * clears the buffer on each read, which is what makes the two-read comparison honest.
     */
    public Reading read() {
        if (driver == null) return Reading.UNAVAILABLE;
        try {
            LogEntries entries = driver.manage().logs().get(LogType.BROWSER);
            if (entries == null) return Reading.UNAVAILABLE;
            Kind worst = Kind.NONE;
            for (LogEntry entry : entries) {
                if (entry.getLevel() == null
                    || !"SEVERE".equals(entry.getLevel().getName())) {
                    continue;
                }
                Kind kind = classify(entry.getMessage());
                if (worst == Kind.NONE || kind == Kind.SCRIPT) worst = kind;
            }
            return new Reading(true, worst != Kind.NONE, worst);
        } catch (RuntimeException notServed) {
            return Reading.UNAVAILABLE;
        }
    }

    /**
     * One fixed word, chosen from the message and then forgetting it.
     *
     * <p>The parameter is never stored, returned, logged or compared to anything a caller supplied.
     */
    private static Kind classify(String message) {
        if (message == null) return Kind.OTHER;
        String text = message.toLowerCase(Locale.ROOT);
        if (text.contains("uncaught") || text.contains("syntaxerror")
            || text.contains("typeerror") || text.contains("referenceerror")) {
            return Kind.SCRIPT;
        }
        if (text.contains("failed to load") || text.contains("net::")
            || text.contains("err_") || text.contains("status of 4")
            || text.contains("status of 5")) {
            return Kind.NETWORK;
        }
        if (text.contains("content security policy") || text.contains("blocked by")
            || text.contains("mixed content") || text.contains("cross-origin")) {
            return Kind.SECURITY;
        }
        return Kind.OTHER;
    }
}
