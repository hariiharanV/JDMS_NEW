/** The audit actions the core records, for the Action filter (the API filters by exact code). */
export const AUDIT_ACTIONS = [
  'DOCUMENT_UPLOADED', 'DOCUMENT_DRAFT_UPLOADED', 'DOCUMENT_VIEWED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_METADATA_CHANGED',
  'DOCUMENT_CHECKED_OUT', 'DOCUMENT_CHECKED_IN', 'DOCUMENT_CHECKOUT_CANCELLED', 'DOCUMENT_VERSION_RESTORED',
  'DOCUMENT_DELETED', 'DOCUMENT_RESTORED', 'DOCUMENT_DISPOSED', 'DOCUMENT_IMPORTED',
  'APPROVAL_STARTED', 'APPROVAL_DECIDED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_RETURNED',
  'APPROVAL_RESUBMITTED', 'TASK_CLAIMED',
  'CHANGE_PROPOSED', 'CHANGE_APPROVED', 'CHANGE_REJECTED',
  'LEGAL_HOLD_CREATED', 'LEGAL_HOLD_ITEMS_ADDED', 'LEGAL_HOLD_ITEM_REMOVED', 'LEGAL_HOLD_RELEASED',
  'DISPOSITION_PREPARED', 'DISPOSITION_EXECUTED', 'DISPOSITION_REJECTED', 'RECORD_DECLARED', 'RECORD_EVENT_RECEIVED',
  'SEARCH_PERFORMED', 'AUDIT_QUERIED', 'AUDIT_VERIFIED', 'ACCESS_DENIED', 'USER_PROFILE_CREATED', 'USER_ROLES_SYNCED',
] as const;

/** "DOCUMENT_CHECKED_OUT" → "Document checked out": the fallback for codes without a translation. */
export function sentenceCase(code: string): string {
  const words = code.toLowerCase().split('_').filter(Boolean).join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The display name of an audit action (UI handoff §6.10); unknown codes read as the code in sentence case. */
export function auditActionName(code: string, t: (key: string, options?: Record<string, unknown>) => string): string {
  return t(`auditActions.${code}`, { defaultValue: sentenceCase(code) });
}
