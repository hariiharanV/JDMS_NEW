package com.jmr.jdms.document.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sanitises what clients say about an uploaded file. Both values are untrusted and for display only: the file name
 * is never an identifier (CLAUDE.md rule 5) and the worker detects the real type (rule 6).
 */
public final class ClientFileInfo {

    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private ClientFileInfo() {
    }

    /** The last path segment, without control characters, at most 255 characters; null when nothing is left. */
    public static String fileName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        name = CONTROL.matcher(name).replaceAll("").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return null;
        }
        return name.codePointCount(0, name.length()) <= 255 ? name
                : name.substring(0, name.offsetByCodePoints(0, 255));
    }

    /** {@code type/subtype} without parameters, or {@value #DEFAULT_MIME_TYPE}. */
    public static String mimeType(String raw) {
        if (raw == null) {
            return DEFAULT_MIME_TYPE;
        }
        String type = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return MIME.matcher(type).matches() ? type : DEFAULT_MIME_TYPE;
    }
}
