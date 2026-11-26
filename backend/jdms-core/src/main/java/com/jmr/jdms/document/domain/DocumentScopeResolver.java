package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.ScopeResolver;
import com.jmr.jdms.access.api.ScopeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Validates and labels document scopes of access rules. */
@Component
class DocumentScopeResolver implements ScopeResolver {

    private final DocumentRepository documents;

    DocumentScopeResolver(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public boolean supports(ScopeType type) {
        return type == ScopeType.DOCUMENT;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> describe(ScopeType type, UUID id) {
        return documents.findById(id).map(document -> "Document " + document.getTitle());
    }
}
