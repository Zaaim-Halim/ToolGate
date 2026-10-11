package io.toolgate.api;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Objects;

/**
 * Where a session's taint came from, for a reviewer: "the session read untrusted content from
 * {@code fetchSupplierPage}". A review screen shows it next to the call, so a request worded to
 * persuade the reviewer is seen for what fed it.
 *
 * @param label  the source label
 * @param tool   the tool whose result brought it in
 * @param callId that call
 */
public record Provenance(Label label, ToolId tool, String callId) {

    /** Requires every component. */
    public Provenance {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(tool, "tool");
        requireNonBlank(callId, "callId");
    }
}
