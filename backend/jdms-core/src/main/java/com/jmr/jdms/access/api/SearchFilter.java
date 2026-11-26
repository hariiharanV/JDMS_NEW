package com.jmr.jdms.access.api;

import com.jmr.jdms.shared.Classification;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The set of documents a subject may act on, as a filter for queries (database now, OpenSearch in B-07). A document
 * matches when its classification is in {@code classifications}, no deny scope contains it, and an allow scope
 * contains it (with its branch condition) or it is in {@code grantedDocumentIds}.
 */
public record SearchFilter(Set<Classification> classifications, List<Scope> allowScopes, List<Scope> denyScopes,
        Set<UUID> grantedDocumentIds) {

    public SearchFilter {
        classifications = Set.copyOf(classifications);
        allowScopes = List.copyOf(allowScopes);
        denyScopes = List.copyOf(denyScopes);
        grantedDocumentIds = Set.copyOf(grantedDocumentIds);
    }

    public boolean matchesNothing() {
        return classifications.isEmpty() || (allowScopes.isEmpty() && grantedDocumentIds.isEmpty());
    }

    /** A scope with its branch condition ({@code allBranches} or the listed branch IDs). */
    public record Scope(ScopeType type, UUID id, boolean allBranches, Set<UUID> branchIds) {

        public Scope {
            branchIds = Set.copyOf(branchIds);
        }
    }
}
