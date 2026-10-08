package io.toolgate.api;

/**
 * What a tool does with data, for taint tracking.
 *
 * <p>{@link #PRIVATE_DATA} and {@link #UNTRUSTED_CONTENT} mark sources: once such a tool's output
 * reaches the model, the session carries the label. {@link #EXTERNAL_SINK} marks tools that send
 * data out. A session holding both source labels that calls a sink is the "lethal trifecta".
 */
public enum Label {
    /** The tool returns data that must not leave, such as customer records. */
    PRIVATE_DATA,
    /** The tool returns content someone outside could have written, such as a web page or an email. */
    UNTRUSTED_CONTENT,
    /** The tool sends data out of the application, such as an email or an HTTP request. */
    EXTERNAL_SINK
}
