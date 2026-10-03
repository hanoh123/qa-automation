package com.qanize.automation.model;

/**
 * Whose problem a failure is.
 *
 * <p>Contract 1.1, section 7. In contract 1.0 a website defect and an analyzer malfunction both
 * arrive as a failed check and the difference has to be inferred from prose. This enum makes the
 * distinction a field, so a reader is never asked to decide whether their site is broken.
 */
public enum ErrorCategory {

    /** The website behaved incorrectly. The only category that is the user's problem. */
    WEBSITE_FINDING,

    /** The run configuration was wrong, so the check could not be performed as described. */
    CONFIGURATION_ERROR,

    /** The browser, driver, Maven or network failed. The analyzer could not run. */
    INFRASTRUCTURE_ERROR,

    /** Nothing eligible existed to test. Not a defect, and not a failure to run. */
    NOT_APPLICABLE,

    /** The user did not select this check. */
    NOT_SELECTED
}
