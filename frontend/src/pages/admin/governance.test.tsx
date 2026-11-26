import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../i18n';
import { auditActionName, sentenceCase } from '../../components/auditActions';
import { RowComment } from '../../components/RowComment';
import { buildMatrix, KNOWN_ACTIONS, type Rule } from './accessMatrix';

// BaselineRules.java, Testing Guide table 3.1
const BASELINE: Record<string, string[]> = {
  MAKER: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'UPLOAD', 'EDIT_METADATA', 'CHECK_OUT', 'DELETE'],
  CHECKER: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'APPROVE'],
  VIEWER: ['VIEW_METADATA', 'VIEW_CONTENT'],
  COMMITTEE_MEMBER: ['VIEW_METADATA', 'VIEW_CONTENT', 'APPROVE'],
  RECORDS_OFFICER: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'RESTORE', 'MANAGE_HOLDS', 'MANAGE_RETENTION', 'DISPOSE'],
  INTEGRATION_CLIENT: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'UPLOAD', 'EDIT_METADATA', 'CHECK_OUT'],
  TENANT_ADMIN: ['ADMIN_METADATA'],
  SECURITY_ADMIN: ['ADMIN_ACCESS', 'VIEW_AUDIT'],
  MIGRATION: ['IMPORT', 'VIEW_METADATA'],
};

function rule(overrides: Partial<Rule>): Rule {
  return {
    id: crypto.randomUUID(), scopeType: 'TENANT', scopeId: 't', scopeLabel: null, principalType: 'ROLE',
    principalId: 'MAKER', actions: [], effect: 'ALLOW', branchConstraint: 'OWN', branchCodes: [], description: null,
    version: 1, ...overrides,
  };
}

const baseline = Object.entries(BASELINE).map(([role, actions]) => rule({ principalId: role, actions }));

describe('the access matrix', () => {
  it('reproduces the baseline role table exactly, roles in the table order', () => {
    const matrix = buildMatrix([...baseline].reverse());
    expect(matrix.roles).toEqual(Object.keys(BASELINE));
    for (const [role, actions] of Object.entries(BASELINE)) {
      expect(KNOWN_ACTIONS.filter((action) => matrix.cell(role, action) === 'ALLOW')).toEqual(
        KNOWN_ACTIONS.filter((action) => actions.includes(action)));
    }
    expect(matrix.otherActions).toEqual([]);
    expect(matrix.narrower).toEqual([]);
  });

  it('lets a deny win, and keeps narrower rules and user rules out of the matrix', () => {
    const deny = rule({ principalId: 'MAKER', actions: ['DELETE'], effect: 'DENY' });
    const folder = rule({ scopeType: 'FOLDER', scopeLabel: '/kyc/2019', principalId: 'VIEWER', actions: ['VIEW_CONTENT'] });
    const user = rule({ principalType: 'USER', principalId: 'u1', actions: ['DOWNLOAD'] });
    const matrix = buildMatrix([...baseline, deny, folder, user]);
    expect(matrix.cell('MAKER', 'DELETE')).toBe('DENY');
    expect(matrix.cell('MAKER', 'UPLOAD')).toBe('ALLOW');
    expect(matrix.cell('VIEWER', 'DOWNLOAD')).toBeNull();
    expect(matrix.rulesFor('MAKER', 'DELETE')).toHaveLength(2);
    expect(matrix.narrower).toEqual([folder, user]);
  });

  it('puts actions the columns do not name into an Other group', () => {
    expect(buildMatrix([rule({ actions: ['NEW_THING'] })]).otherActions).toEqual(['NEW_THING']);
  });
});

describe('audit action names', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('uses the display name, else the code in sentence case', () => {
    const t = i18n.t.bind(i18n) as unknown as Parameters<typeof auditActionName>[1];
    expect(auditActionName('AUDIT_VERIFIED', t)).toBe('Audit chain verified');
    expect(auditActionName('SOMETHING_NEW_HAPPENED', t)).toBe('Something new happened');
    expect(sentenceCase('DOCUMENT_CHECKED_OUT')).toBe('Document checked out');
  });
});

describe('RowComment', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('takes focus, needs a comment, and cancels with Escape', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<RowComment id="c" label="Why is this batch rejected?" confirmLabel="Reject" onConfirm={onConfirm} onCancel={onCancel} />);
    const field = screen.getByRole('textbox', { name: 'Why is this batch rejected?' });
    expect(field).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
    await userEvent.click(field);
    await userEvent.paste(' Not due yet ');
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    expect(onConfirm).toHaveBeenCalledWith('Not due yet');
    await userEvent.type(field, '{Escape}');
    expect(onCancel).toHaveBeenCalled();
  });
});
