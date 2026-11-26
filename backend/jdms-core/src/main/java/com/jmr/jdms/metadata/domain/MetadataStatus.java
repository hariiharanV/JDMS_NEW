package com.jmr.jdms.metadata.domain;

/** Cabinets and document types are deactivated, never deleted, once documents can refer to them. */
public enum MetadataStatus {
    ACTIVE,
    INACTIVE
}
