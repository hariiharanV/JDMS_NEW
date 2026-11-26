import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter } from 'react-router';
import i18n from '../../i18n';
import { FoldersPage } from './AdminPage';

/** B-19: the folder tree of a cabinet, managed on screen as proposals a second administrator approves. */
describe('folders administration', { timeout: 30_000 }, () => {
  let requests: { url: string; method: string; body: unknown; headers: Record<string, string> }[];
  const folders = [
    { id: 'f1', parentId: null, name: 'Customer onboarding', path: '/Customer onboarding' },
    { id: 'f2', parentId: 'f1', name: 'KYC', path: '/Customer onboarding/KYC' },
  ];

  beforeEach(async () => {
    await i18n.changeLanguage('en');
    requests = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      requests.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) as unknown : undefined,
        headers: (init?.headers ?? {}) as Record<string, string> });
      const body = method !== 'GET' ? { id: 'pc1', status: 'PENDING' }
        : url === '/api/v1/cabinets' ? [{ id: 'cab1', code: 'CUSTOMERS', name: 'Customers' }]
          : url === '/api/v1/cabinets/cab1/folders' ? folders
            : url === '/api/v1/admin/folders/f2' ? { id: 'f2', parentId: 'f1', name: 'KYC', version: 7 } : {};
      return new Response(JSON.stringify(body), { status: method === 'GET' ? 200 : 202,
        headers: { 'Content-Type': 'application/json' } });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function renderPage() {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AntApp><MemoryRouter><FoldersPage /></MemoryRouter></AntApp>
      </QueryClientProvider>,
    );
  }

  it('shows the tree and proposes a sub-folder under the selected folder', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText('KYC'));
    expect(screen.getByText('/Customer onboarding/KYC', { selector: 'code' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'New sub-folder' }));
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Passports');
    await user.click(screen.getByRole('button', { name: 'Propose' }));

    await waitFor(() => expect(requests.some((request) => request.method === 'POST')).toBe(true));
    const post = requests.find((request) => request.method === 'POST');
    expect(post?.url).toBe('/api/v1/admin/cabinets/cab1/folders');
    expect(post?.body).toEqual({ name: 'Passports', parentId: 'f2' });
    expect(post?.headers['Idempotency-Key']).toBeTruthy();
  });

  it('renames with the folder\'s current version, and refuses a slash in a name', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText('KYC'));
    await user.click(screen.getByRole('button', { name: 'Rename or move' }));
    const name = screen.getByRole('textbox', { name: 'Name' });
    await user.clear(name);
    await user.type(name, 'KYC/2026');
    await user.click(screen.getByRole('button', { name: 'Propose' }));
    expect(await screen.findByText('A folder name cannot contain /')).toBeInTheDocument();

    await user.clear(name);
    await user.type(name, 'Know your customer');
    await user.click(screen.getByRole('button', { name: 'Propose' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'PUT')).toBe(true));
    const put = requests.find((request) => request.method === 'PUT');
    expect(put?.url).toBe('/api/v1/admin/folders/f2');
    expect(put?.headers['If-Match']).toBe('"7"');
    expect(put?.body).toEqual({ name: 'Know your customer', parentId: 'f1' });
  });

  it('only offers to delete an empty folder', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText('Customer onboarding'));
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled();
    await user.click(screen.getByText('KYC'));
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'DELETE')).toBe(true));
    expect(requests.find((request) => request.method === 'DELETE')?.headers['If-Match']).toBe('"7"');
  });
});
