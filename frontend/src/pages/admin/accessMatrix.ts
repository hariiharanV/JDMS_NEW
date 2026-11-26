import type { Action } from '../../api/types';

export type ScopeType = 'TENANT' | 'CABINET' | 'DOCUMENT_TYPE' | 'FOLDER' | 'DOCUMENT';

export interface Rule {
  id: string;
  scopeType: ScopeType;
  scopeId: string | null;
  scopeLabel: string | null;
  principalType: 'ROLE' | 'USER';
  principalId: string;
  actions: string[];
  effect: 'ALLOW' | 'DENY';
  branchConstraint: 'OWN' | 'ALL' | 'LIST';
  branchCodes: string[];
  description: string | null;
  version: number;
}

/** The matrix columns, grouped as the handoff draws them (§6.9). */
export const ACTION_GROUPS: { key: string; actions: (Action | 'IMPORT')[] }[] = [
  { key: 'documents', actions: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'UPLOAD', 'EDIT_METADATA', 'CHECK_OUT', 'DELETE'] },
  { key: 'workflow', actions: ['APPROVE'] },
  { key: 'records', actions: ['RESTORE', 'MANAGE_HOLDS', 'MANAGE_RETENTION', 'DISPOSE'] },
  { key: 'administration', actions: ['ADMIN_METADATA', 'ADMIN_ACCESS', 'VIEW_AUDIT', 'IMPORT'] },
];
export const KNOWN_ACTIONS: string[] = ACTION_GROUPS.flatMap((group) => group.actions);

/** The roles in the order of Testing Guide table 3.1; other roles found in the rules follow. */
export const ROLE_ORDER = ['MAKER', 'CHECKER', 'VIEWER', 'COMMITTEE_MEMBER', 'RECORDS_OFFICER', 'INTEGRATION_CLIENT',
  'TENANT_ADMIN', 'SECURITY_ADMIN', 'MIGRATION'];

export type Cell = 'ALLOW' | 'DENY' | null;

export interface Matrix {
  roles: string[];
  /** Actions in rules that the handoff's columns do not name: an "Other" group. */
  otherActions: string[];
  cell: (role: string, action: string) => Cell;
  /** The whole-bank rules behind a cell. */
  rulesFor: (role: string, action: string) => Rule[];
  /** Rules on a cabinet, type, folder or document, or for one user. */
  narrower: Rule[];
}

/**
 * The role × action matrix of the whole-bank (tenant-scope) role rules. A deny wins over an allow, as in the access
 * policy; without a rule the action is not granted.
 */
export function buildMatrix(rules: Rule[]): Matrix {
  const wide = rules.filter((rule) => rule.scopeType === 'TENANT' && rule.principalType === 'ROLE');
  const found = [...new Set(wide.map((rule) => rule.principalId))];
  const roles = [...ROLE_ORDER.filter((role) => found.includes(role)), ...found.filter((role) => !ROLE_ORDER.includes(role)).sort()];
  const otherActions = [...new Set(wide.flatMap((rule) => rule.actions))].filter((action) => !KNOWN_ACTIONS.includes(action)).sort();
  const rulesFor = (role: string, action: string) =>
    wide.filter((rule) => rule.principalId === role && rule.actions.includes(action));
  const cell = (role: string, action: string): Cell => {
    const matching = rulesFor(role, action);
    if (matching.some((rule) => rule.effect === 'DENY')) return 'DENY';
    return matching.length > 0 ? 'ALLOW' : null;
  };
  const narrower = rules.filter((rule) => !wide.includes(rule));
  return { roles, otherActions, cell, rulesFor, narrower };
}
