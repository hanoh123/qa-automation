package com.qanize.automation.model;

/**
 * When, in the shape of a check, something happened.
 *
 * <p>Contract 1.1, section 7. The value set is fixed: a phase the analyzer cannot name is a phase
 * the report cannot explain, so new phases are a contract change rather than a code change.
 */
public enum Phase {

    /** Preparing to act: starting the browser, sizing the viewport, loading configuration. */
    SETUP,

    /** Doing something to the site: navigating, clicking, typing. */
    ACTION,

    /** Waiting for a condition the analyzer does not control. */
    WAIT,

    /** Looking for things to test, before any of them has been tested. */
    DISCOVERY,

    /** Comparing what was expected against what was observed. */
    ASSERTION,

    /** Capturing an artifact, such as a screenshot. */
    EVIDENCE,

    /** Releasing resources after the work is done. */
    CLEANUP
}
