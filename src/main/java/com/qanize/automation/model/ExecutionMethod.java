package com.qanize.automation.model;

/**
 * How the analyzer performed an action.
 *
 * <p>Contract 1.1, section 7. This is the field that lets a reader tell a real browser interaction
 * apart from an HTTP probe or a DOM read, which is exactly the distinction a 1.0 report cannot
 * express: "the link passed" reads the same whether the browser opened it or a HEAD request
 * answered 200.
 */
public enum ExecutionMethod {

    /** A browser session was started. */
    BROWSER_START,

    /** A URL was opened in the browser. */
    NAVIGATE,

    /** An element was clicked. */
    CLICK,

    /** Text was entered into a field. */
    TYPE,

    /** The rendered DOM was read without interacting with it. */
    DOM_INSPECTION,

    /** A network request was made without a browser. */
    HTTP_REQUEST,

    /** Execution paused until a condition held or the timeout expired. */
    WAIT_FOR_CONDITION,

    /** An expectation was compared against an observation. */
    ASSERT,

    /** A screenshot was captured. */
    SCREENSHOT,

    /** The browser session was closed. */
    BROWSER_CLOSE
}
