package com.jmr.jdms.access.api;

/** The authorization policy (D-06, spec §6). Every endpoint, download, search hit and job result goes through it. */
public interface AccessPolicy {

    /** Pure decision: no side effects. */
    Decision decide(Subject subject, Action action, ResourceRef resource);

    /** The documents the subject may act on, for queries; built per request from current rules. */
    SearchFilter searchFilter(Subject subject, Action action);

    /** Decides for the current request's subject and audits a denial ({@code ACCESS_DENIED}). */
    Decision check(Action action, ResourceRef resource);
}
