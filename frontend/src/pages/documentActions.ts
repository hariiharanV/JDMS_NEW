import type { Action, DocumentSummary } from '../api/types';

export type DocumentAction =
  | 'download'
  | 'newVersion'
  | 'checkOut'
  | 'checkIn'
  | 'cancelCheckOut'
  | 'restore'
  | 'delete'
  | 'copyLink';

export interface DocumentActions {
  /** At most one; never a destructive action. */
  primary: DocumentAction | null;
  secondary: DocumentAction[];
  /** The "More" menu: copy link and delete. */
  more: DocumentAction[];
}

/**
 * The document page's actions by state and the caller's rights on this document (UI handoff §6.3). Only hides what
 * cannot be done; the server checks every request.
 */
export function documentActions(document: DocumentSummary, allowed: Set<Action>, userId: string | undefined): DocumentActions {
  const download = allowed.has('DOWNLOAD') && document.currentVersion !== null;
  const actions: DocumentActions = { primary: null, secondary: [], more: [] };
  if (document.status === 'DISPOSED') {
    // nothing left but the record
  } else if (document.status === 'DELETED') {
    if (allowed.has('RESTORE')) actions.primary = 'restore';
  } else if (document.checkedOutBy !== null && document.checkedOutBy === userId) {
    actions.primary = 'checkIn';
    if (download) actions.secondary.push('download');
    actions.more.push('cancelCheckOut');
  } else if (document.checkedOutBy !== null) {
    if (download) actions.primary = 'download';
  } else if (document.status === 'ACTIVE') {
    if (download) actions.primary = 'download';
    if (allowed.has('CHECK_OUT')) actions.secondary.push('newVersion', 'checkOut');
    if (allowed.has('DELETE') && !document.onLegalHold) actions.more.push('delete');
  } else if (download) {
    actions.primary = 'download';
  }
  actions.more.unshift('copyLink');
  return actions;
}
