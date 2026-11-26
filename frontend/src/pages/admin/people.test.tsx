import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter } from 'react-router';
import i18n from '../../i18n';
import { CommitteesPage, UsersPage } from './AdminPage';

/** B-17: committees and users' access, managed on screen as proposals a second administrator approves. */
describe('committees and users administration', { timeout: 30_000 }, () => {
  let requests: { url: string; method: string; body: unknown; headers: Record<string, string> }[];
  let committees: unknown[];

  const candidates = [
    { id: 'u1', username: 'committee1', displayName: 'Cora One', roles: ['COMMITTEE_MEMBER'] },
    { id: 'u2', username: 'committee2', displayName: 'Cyrus Two', roles: ['COMMITTEE_MEMBER'] },
    { id: 'u3', username: 'viewer1', displayName: 'Vera Viewer', roles: ['VIEWER'] },
  ];
  const users = [
    { id: 'u3', username: 'viewer1', displayName: 'Vera Viewer', email: null, principalType: 'USER', homeBranchCode: '002',
      allBranches: false, branchCodes: ['002'], clearance: 'INTERNAL', status: 'ACTIVE', roles: ['VIEWER'], version: 4 },
    { id: 'me', username: 'secadmin1', displayName: 'Sami Security', email: null, principalType: 'USER', homeBranchCode: '001',
      allBranches: true, branchCodes: [], clearance: 'RESTRICTED', status: 'ACTIVE', roles: ['SECURITY_ADMIN'], version: 1 },
    { id: 'c1', username: 'service-account-crm', displayName: null, email: null, principalType: 'CLIENT', homeBranchCode: null,
      allBranches: true, branchCodes: [], clearance: 'CONFIDENTIAL', status: 'ACTIVE', roles: [], version: 0 },
  ];

  beforeEach(async () => {
    await i18n.changeLanguage('en');
    requests = [];
    committees = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      requests.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) as unknown : undefined,
        headers: (init?.headers ?? {}) as Record<string, string> });
      const body = method !== 'GET' ? { id: 'pc1', status: 'PENDING' }
        : url.startsWith('/api/v1/admin/committees/candidates') ? candidates
          : url.startsWith('/api/v1/admin/committees') ? { items: committees, page: 0, size: 200, total: committees.length }
            : url === '/api/v1/admin/users?size=200' ? { items: users, page: 0, size: 200, total: users.length }
              : url === '/api/v1/admin/users/u3' ? { ...users[0], version: 5 }
                : url === '/api/v1/branches' ? [{ code: '001', name: 'Head office' }, { code: '002', name: 'Ruwi' }]
                  : url === '/api/v1/me' ? { userId: 'me', username: 'secadmin1', roles: ['SECURITY_ADMIN'], branchCodes: [],
                    allBranches: true, clearance: 'RESTRICTED', languages: ['en'], tenantCode: 'BANK1', displayName: null,
                    principalType: 'USER' } : {};
      return new Response(JSON.stringify(body), { status: method === 'GET' ? 200 : 202,
        headers: { 'Content-Type': 'application/json' } });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function renderPage(page: React.ReactNode) {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AntApp><MemoryRouter>{page}</MemoryRouter></AntApp>
      </QueryClientProvider>,
    );
  }

  it('proposes the credit committee with its members and quorum', async () => {
    const user = userEvent.setup();
    renderPage(<CommitteesPage />);
    expect(await screen.findByText('No committees yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'New committee' }));

    expect(screen.getByRole('textbox', { name: 'Code' })).toHaveValue('CREDIT_COMMITTEE');
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Credit committee');
    const pick = async (index: number, name: string) => {
      const member = screen.getByRole('group', { name: `Member ${index}` });
      await user.click(within(member).getByRole('combobox', { name: 'User' }));
      // each member row opens its own list; the newest is the last in the page
      const options = await screen.findAllByTitle(name);
      await user.click(options[options.length - 1] as HTMLElement);
    };
    await pick(1, 'Cora One (committee1)');
    await pick(2, 'Cyrus Two (committee2)');
    await pick(3, 'Vera Viewer (viewer1)');
    // a member without a voting role is flagged before anything is proposed
    expect(screen.getByText('This user has neither the Committee member nor the Checker role, so they cannot vote.'))
      .toBeInTheDocument();
    await user.type(within(screen.getByRole('group', { name: 'Member 1' })).getByRole('textbox', { name: 'Designation' }), 'Chair');
    await user.click(screen.getByRole('button', { name: 'Propose' }));

    await waitFor(() => expect(requests.some((request) => request.method === 'POST')).toBe(true));
    const post = requests.find((request) => request.method === 'POST');
    expect(post?.url).toBe('/api/v1/admin/committees');
    expect(post?.headers['Idempotency-Key']).toBeTruthy();
    expect(post?.body).toEqual({ code: 'CREDIT_COMMITTEE', name: 'Credit committee', minQuorum: 2, members: [
      { userId: 'u1', designation: 'Chair' }, { userId: 'u2', designation: null }, { userId: 'u3', designation: null },
    ] });
  });

  it('shows a committee with its members by name and proposes a deactivation with its version', async () => {
    committees = [{ id: 'k1', code: 'CREDIT_COMMITTEE', name: 'Credit committee', minQuorum: 2, status: 'ACTIVE', version: 3,
      members: [{ userId: 'u1', designation: 'Chair' }, { userId: 'u3', designation: null }] }];
    const user = userEvent.setup();
    renderPage(<CommitteesPage />);
    expect(await screen.findByText('Cora One')).toBeInTheDocument();
    expect(screen.getByText('2 of 2 members must approve')).toBeInTheDocument();
    expect(screen.getByText('Document types with the Committee approval route')).toBeInTheDocument();
    expect(screen.getByText('Cannot vote')).toBeInTheDocument(); // Vera has no voting role

    await user.click(screen.getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'DELETE')).toBe(true));
    const remove = requests.find((request) => request.method === 'DELETE');
    expect(remove?.url).toBe('/api/v1/admin/committees/k1');
    expect(remove?.headers['If-Match']).toBe('"3"');
  });

  it('lists people (not system clients) with roles and branches, and proposes a branch and clearance change', async () => {
    const user = userEvent.setup();
    renderPage(<UsersPage />);
    expect(await screen.findByRole('link', { name: 'Vera Viewer' })).toBeInTheDocument();
    expect(screen.queryByText('service-account-crm')).toBeNull();
    expect(screen.getAllByText('Viewer').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Change branches or clearance' }));
    await user.click(screen.getByRole('combobox', { name: 'Branch list' }));
    await user.click(await screen.findByTitle('001 Head office'));
    await user.click(screen.getByRole('button', { name: 'Propose' }));

    await waitFor(() => expect(requests.some((request) => request.method === 'PUT')).toBe(true));
    const put = requests.find((request) => request.method === 'PUT');
    expect(put?.url).toBe('/api/v1/admin/users/u3/scope');
    expect(put?.headers['If-Match']).toBe('"5"'); // the version read just before, not the list's
    expect(put?.body).toEqual({ allBranches: false, branchCodes: ['002', '001'], clearance: 'INTERNAL' });
  });

  it('does not let security administrators change their own access', async () => {
    const user = userEvent.setup();
    renderPage(<UsersPage />);
    await user.click(await screen.findByRole('link', { name: 'Sami Security' }));
    expect(screen.getByRole('button', { name: 'Change branches or clearance' })).toBeDisabled();
    expect(screen.getByText('You cannot change your own branches or clearance.')).toBeInTheDocument();
  });
});
