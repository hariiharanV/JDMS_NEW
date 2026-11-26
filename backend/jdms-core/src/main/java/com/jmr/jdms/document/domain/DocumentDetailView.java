package com.jmr.jdms.document.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.jmr.jdms.access.api.Action;
import java.util.List;

/**
 * A document opened by a caller: the document plus the document actions the caller may perform now, so the UI hides
 * the others (B-08). A hint only; every action is authorised again when requested.
 */
public record DocumentDetailView(@JsonUnwrapped DocumentView document, List<Action> allowedActions) {

    /** Actions reported per document; tenant-wide ones come from {@code /me/permissions}. */
    static final List<Action> DOCUMENT_ACTIONS = List.of(Action.VIEW_CONTENT, Action.DOWNLOAD, Action.EDIT_METADATA,
            Action.CHECK_OUT, Action.DELETE, Action.RESTORE, Action.VIEW_AUDIT);
}
