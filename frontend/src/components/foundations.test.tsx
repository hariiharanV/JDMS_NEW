import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import i18n from '../i18n';
import { AuthContext, type AuthState } from '../auth/AuthContext';
import type { Action, Classification, DocumentStatus } from '../api/types';
import { AppShell } from './AppShell';
import { ClassificationLabel } from './ClassificationLabel';
import { EmptyState } from './EmptyState';
import { InlineConfirm } from './InlineConfirm';
import { adminHome, buildNav } from './nav';
import { PageHeader } from './PageHeader';
import { StatusChip } from './StatusChip';

// The baseline rules each role starts with (BaselineRules.java, Testing Guide table 3.1)
const ROLE_ACTIONS: Record<string, Action[]> = {
  maker: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'UPLOAD', 'EDIT_METADATA', 'CHECK_OUT', 'DELETE'],
  checker: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'APPROVE'],
  viewer: ['VIEW_METADATA', 'VIEW_CONTENT'],
  recordsOfficer: ['VIEW_METADATA', 'VIEW_CONTENT', 'DOWNLOAD', 'RESTORE', 'MANAGE_HOLDS', 'MANAGE_RETENTION', 'DISPOSE'],
  tenantAdmin: ['ADMIN_METADATA'],
  securityAdmin: ['ADMIN_ACCESS', 'VIEW_AUDIT'],
};

const canFor = (role: string) => (action: Action) => (ROLE_ACTIONS[role] ?? []).includes(action);

function menu(role: string) {
  return Object.fromEntries(buildNav(canFor(role)).map((group) => [group.key, group.items.map((item) => item.key)]));
}

describe('buildNav', () => {
  it('gives each role the sections of the handoff role table', () => {
    expect(menu('maker')).toEqual({ work: ['home', 'tasks', 'upload'], find: ['browse', 'search'] });
    expect(menu('checker')).toEqual({ work: ['home', 'tasks'], find: ['browse', 'search'] });
    expect(menu('viewer')).toEqual({ work: ['home', 'tasks'], find: ['browse', 'search'] });
    expect(menu('recordsOfficer')).toEqual({
      work: ['home', 'tasks'], find: ['browse', 'search'], governance: ['records', 'recycleBin'],
    });
    expect(menu('tenantAdmin')).toEqual({
      work: ['home', 'tasks'], find: ['browse', 'search'], admin: ['documentTypes', 'folders', 'committees', 'languages', 'pendingChanges'],
    });
    expect(menu('securityAdmin')).toEqual({
      work: ['home', 'tasks'], find: ['browse', 'search'], security: ['pendingChanges', 'accessRules', 'users', 'audit'],
    });
  });

  it('shows pending changes once for a user who administers both types and access', () => {
    const both = (action: Action) => ['ADMIN_METADATA', 'ADMIN_ACCESS'].includes(action);
    const keys = buildNav(both).flatMap((group) => group.items.map((item) => item.key));
    expect(keys.filter((key) => key === 'pendingChanges')).toHaveLength(1);
  });

  it('puts the task count on Tasks', () => {
    const tasks = buildNav(canFor('checker'), { tasks: 9 })[0]?.items.find((item) => item.key === 'tasks');
    expect(tasks?.badge).toBe(9);
  });

  it('opens /admin on the first administration screen the caller may use', () => {
    expect(adminHome(canFor('tenantAdmin'))).toBe('/admin/types');
    expect(adminHome(canFor('securityAdmin'))).toBe('/admin/access');
    expect(adminHome(canFor('maker'))).toBe('/');
  });
});

describe('StatusChip', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it.each<[DocumentStatus, string, string, string]>([
    ['DRAFT', 'Draft', 'neutral', 'ring'],
    ['PENDING_APPROVAL', 'Pending approval', 'warning', 'dot'],
    ['ACTIVE', 'Active', 'success', 'dot'],
    ['REJECTED', 'Rejected', 'danger', 'square'],
    ['DELETED', 'Deleted', 'danger', 'hollow-square'],
    ['DISPOSED', 'Disposed', 'neutral', 'bar'],
  ])('shows %s as text with a %s marker', (status, text, tone, marker) => {
    const { container } = render(<StatusChip status={status} />);
    const chip = screen.getByText(text);
    expect(chip).toHaveClass('jdms-chip', `jdms-chip-${tone}`);
    expect(container.querySelector(`.jdms-marker-${marker}`)).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('ClassificationLabel', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it.each<[Classification, string, string]>([
    ['PUBLIC', 'Public', 'unlock'],
    ['INTERNAL', 'Internal', 'lock'],
    ['CONFIDENTIAL', 'Confidential', 'lock'],
    ['RESTRICTED', 'Restricted', 'lock'],
  ])('shows %s with its text and an icon', (classification, text, icon) => {
    const { container } = render(<ClassificationLabel classification={classification} />);
    expect(screen.getByText(text)).toHaveClass(`jdms-classification-${classification}`);
    expect(container.querySelector(`.anticon-${icon}`)).not.toBeNull();
  });

  it('in lists, appears only above the type minimum and not while it is unknown', () => {
    const { rerender } = render(<ClassificationLabel classification="CONFIDENTIAL" minimum="CONFIDENTIAL" inList />);
    expect(screen.queryByText('Confidential')).toBeNull();
    rerender(<ClassificationLabel classification="RESTRICTED" minimum="CONFIDENTIAL" inList />);
    expect(screen.getByText('Restricted')).toBeInTheDocument();
    rerender(<ClassificationLabel classification="RESTRICTED" inList />);
    expect(screen.queryByText('Restricted')).toBeNull();
  });
});

describe('PageHeader and EmptyState', () => {
  it('makes the title the page heading and keeps the actions beside it', () => {
    render(<PageHeader title="Tasks" description="Decide what is waiting for you" actions={<button>Upload</button>} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Tasks' })).toBeInTheDocument();
    expect(screen.getByText('Decide what is waiting for you')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeInTheDocument();
  });

  it('says what empty means and offers the next action', () => {
    render(<EmptyState title="No tasks waiting" description="New uploads appear here." action={<button>Browse</button>} />);
    expect(screen.getByText('No tasks waiting')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Browse' })).toBeInTheDocument();
  });
});

describe('InlineConfirm', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('takes focus, confirms with the verb and cancels with Escape', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<InlineConfirm message="Delete this document?" confirmLabel="Delete document" onConfirm={onConfirm}
      onCancel={onCancel} />);

    expect(screen.getByText('Delete this document?').closest('.jdms-inline-confirm')).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: 'Delete document' }));
    expect(onConfirm).toHaveBeenCalledOnce();
    await userEvent.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledOnce();
  });
});

describe('AppShell', () => {
  const responses: Record<string, unknown> = {
    '/api/v1/me': { tenantCode: 'BANK1', userId: 'u1', username: 'records1', displayName: 'Rana Records',
      principalType: 'USER', roles: ['RECORDS_OFFICER'], allBranches: true, branchCodes: [], clearance: 'RESTRICTED',
      languages: ['en', 'ar'] },
    '/api/v1/me/permissions': { actions: ROLE_ACTIONS.recordsOfficer },
    '/api/v1/tasks?size=1': { items: [], page: 0, size: 1, total: 3 },
    '/api/v1/me/notifications?size=20': { items: [], page: 0, size: 20, total: 0 },
  };

  beforeEach(async () => {
    await i18n.changeLanguage('en');
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(responses[url] ?? {}),
      { status: 200, headers: { 'Content-Type': 'application/json' } })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function renderShell() {
    const auth: AuthState = { user: null, loading: false, login: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined) };
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/records']}>
          <AuthContext.Provider value={auth}>
            <Routes>
              <Route element={<AppShell />}>
                <Route path="records" element={<p>Records page</p>} />
              </Route>
            </Routes>
          </AuthContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it('starts with the skip link and lands it on main', () => {
    renderShell();
    const skip = screen.getByRole('link', { name: 'Skip to main content' });
    expect(skip).toHaveAttribute('href', '#main');
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
    expect(screen.getByText('Records page')).toBeInTheDocument();
  });

  it('shows the grouped menu for the role, the current section and the task count', async () => {
    renderShell();
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(await within(nav).findByRole('link', { name: /Recycle bin/ })).toBeInTheDocument();
    expect(within(nav).getByRole('group', { name: 'Governance' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: /Upload/ })).toBeNull();
    expect(within(nav).getByRole('link', { name: 'Records' })).toHaveClass('jdms-nav-active');
    expect(await within(nav).findByLabelText('3 waiting')).toBeInTheDocument();
  });

  it('names the user with their role and branches, and the bank', async () => {
    renderShell();
    expect(await screen.findByText('Rana Records')).toBeInTheDocument();
    expect(screen.getByText('Records officer · All branches')).toBeInTheDocument();
    expect(screen.getByText('BANK1')).toBeInTheDocument();
    expect(screen.getByText('RR')).toBeInTheDocument();
  });

  it('offers the language switch only when the bank has Arabic', async () => {
    renderShell();
    expect(await screen.findByRole('radiogroup', { name: 'Language' })).toBeInTheDocument();
  });

  it('keeps an English-only bank in English, without the switch', async () => {
    await i18n.changeLanguage('ar');
    responses['/api/v1/me'] = { ...(responses['/api/v1/me'] as object), languages: ['en'] };
    try {
      renderShell();
      expect(await screen.findByText('Rana Records')).toBeInTheDocument();
      await waitFor(() => expect(i18n.resolvedLanguage).toBe('en'));
      expect(screen.queryByRole('radiogroup', { name: 'Language' })).toBeNull();
    } finally {
      responses['/api/v1/me'] = { ...(responses['/api/v1/me'] as object), languages: ['en', 'ar'] };
    }
  });
});
