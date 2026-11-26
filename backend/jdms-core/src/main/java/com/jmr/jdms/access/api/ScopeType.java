package com.jmr.jdms.access.api;

/** What an access rule applies to; rules on a scope apply to everything inside it (inheritance, spec §6). */
public enum ScopeType {
    TENANT,
    CABINET,
    DOCUMENT_TYPE,
    /** The folder and its sub-folders. */
    FOLDER,
    DOCUMENT
}
