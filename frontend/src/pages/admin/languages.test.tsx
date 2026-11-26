import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router';
import i18n from '../../i18n';
import { TypeEditor } from './DocumentTypes';
import { Languages } from './Languages';

// ADR-0023: Arabic is a per-bank option, and Arabic names stay optional when it is on.

describe('Arabic as an option', () => {
  let languages: string[];
  const writes: { url: string; init?: RequestInit }[] = [];

  beforeEach(async () => {
    await i18n.changeLanguage('en');
    languages = ['en'];
    writes.length = 0;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method && init.method !== 'GET') writes.push({ url, init });
      const body = url === '/api/v1/me' ? { tenantCode: 'BANK1', userId: 'u1', username: 'tenantadmin1', languages }
        : url === '/api/v1/admin/languages' ? { languages, supported: ['en', 'ar'], defaultLanguage: 'en', version: 3 }
          : url.startsWith('/api/v1/admin/pending-changes') ? { items: [], page: 0, size: 100, total: 0 }
            : url.startsWith('/api/v1/admin/document-types') || url.startsWith('/api/v1/admin/lovs')
              ? { items: [], page: 0, size: 200, total: 0 }
              : url === '/api/v1/cabinets' ? [] : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function renderAt(path: string) {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AntApp>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path="admin/types/new" element={<TypeEditor />} />
              <Route path="admin/languages" element={<Languages />} />
            </Routes>
          </MemoryRouter>
        </AntApp>
      </QueryClientProvider>,
    );
  }

  it('asks an English-only bank for one name, without Arabic boxes', async () => {
    renderAt('/admin/types/new');
    expect(await screen.findByLabelText('Name')).toBeInTheDocument();
    expect(screen.queryByLabelText('Name (Arabic)')).toBeNull();
    expect(screen.queryByLabelText('Name (English)')).toBeNull();
  });

  it('offers optional Arabic names when the bank has Arabic', async () => {
    languages = ['en', 'ar'];
    renderAt('/admin/types/new');
    expect(await screen.findByLabelText('Name (Arabic)')).toBeInTheDocument();
    expect(screen.getByLabelText('Name (English)')).toBeInTheDocument();
    expect(screen.getByText('Optional. Arabic screens show the English name where this is empty.')).toBeInTheDocument();
  });

  it('proposes switching Arabic on, based on the version shown', async () => {
    renderAt('/admin/languages');
    const propose = await screen.findByRole('button', { name: 'Propose change' });
    expect(propose).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'English' })).toBeDisabled();

    await userEvent.click(screen.getByRole('switch', { name: 'Arabic' }));
    await userEvent.click(propose);

    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]?.url).toBe('/api/v1/admin/languages');
    expect(writes[0]?.init?.method).toBe('PUT');
    expect((writes[0]?.init?.headers as Record<string, string>)['If-Match']).toBe('"3"');
    expect(JSON.parse(String(writes[0]?.init?.body))).toEqual({ languages: ['en', 'ar'] });
  });
});
