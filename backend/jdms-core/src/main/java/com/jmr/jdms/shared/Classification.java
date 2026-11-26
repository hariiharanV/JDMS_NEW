package com.jmr.jdms.shared;

/**
 * Classification levels for documents and user clearance, lowest first (decision O-08 default). A user may see a
 * document only when their clearance is at least the document's classification.
 */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED;

    public boolean covers(Classification other) {
        return compareTo(other) >= 0;
    }
}
