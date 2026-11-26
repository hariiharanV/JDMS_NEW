import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import i18n from '../i18n';
import type { SearchHit, SearchResult } from '../api/types';
import { SearchPage } from './SearchPage';

vi.mock('../components/DocumentViewer', () => ({ DocumentViewer: () => <div>preview</div> }));

const hit: SearchHit = {
  id: 'd1', title: 'Passport Aisha', documentTypeCode: 'PASSPORT', cabinetCode: 'CUST', branchCode: '001',
  status: 'ACTIVE', classification: 'CONFIDENTIAL', cif: 'C123', accountNo: null, createdAt: '2026-10-25T18:31:00Z',
  snippets: [[{ text: 'JDMS ', match: false }, { text: 'Passport', match: true }]],
};

describe('SearchPage', () => {
  let result: SearchResult;
  const bodies: Record<string, unknown>[] = [];

  beforeEach(async () => {
    await i18n.changeLanguage('en');
    bodies.length = 0;
    result = { items: [hit], total: 1, page: 0, size: 20,
      facets: { status: [{ value: 'ACTIVE', count: 1 }], documentType: [{ value: 'PASSPORT', count: 1 }], cabinet: [], branch: [] } };
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/search') bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      const body = url === '/api/v1/search' ? result
        : url === '/api/v1/document-types' ? [{ code: 'PASSPORT', nameEn: 'Passport', nameAr: 'جواز السفر', defaultClassification: 'CONFIDENTIAL', fields: [] }]
          : url === '/api/v1/cabinets' ? []
            : url === '/api/v1/documents/d1' ? { ...hit, currentVersion: null, allowedActions: [] } : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function Where() {
    const location = useLocation();
    return <output data-testid="where">{location.pathname + location.search}</output>;
  }

  function renderAt(path: string) {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AntApp>
          <MemoryRouter initialEntries={[path]}>
            <Routes><Route path="search" element={<SearchPage />} /></Routes>
            <Where />
          </MemoryRouter>
        </AntApp>
      </QueryClientProvider>,
    );
  }

  it('reads the whole view from the URL: CIF scope and a status filter', async () => {
    renderAt('/search?q=C123&scope=cif&status=ACTIVE');
    expect(await screen.findByRole('heading', { level: 1, name: '1 result for CIF C123' })).toBeInTheDocument();
    expect(bodies[0]).toMatchObject({ filters: { cif: 'C123', status: 'ACTIVE' }, page: 0, size: 20 });
    expect(bodies[0]?.query).toBeUndefined();
    expect(screen.getByRole('radio', { name: 'CIF' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /^Active/ })).toBeChecked();
  });

  it('shows labelled facts, the classification only above the type minimum, and highlights as marks', async () => {
    renderAt('/search?q=passport');
    const title = await screen.findByRole('link', { name: 'Passport Aisha' });
    expect(title).toHaveAttribute('href', '/documents/d1');
    expect(screen.getByText('CIF', { selector: 'dt' }).nextSibling).toHaveTextContent('C123');
    expect(screen.queryByText('Confidential')).toBeNull();
    expect(screen.getByText('Passport', { selector: 'mark' })).toBeInTheDocument();
  });

  it('removes a filter from its pill and goes back to the first page', async () => {
    renderAt('/search?q=passport&status=ACTIVE&page=3');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove filter Status: Active' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/search?q=passport'));
  });

  it('offers a CIF search when a single word finds nothing', async () => {
    result = { items: [], total: 0, page: 0, size: 20, facets: {} };
    renderAt('/search?q=C999');
    await userEvent.click(await screen.findByRole('button', { name: 'Search C999 as a CIF instead' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('scope=cif'));
  });

  it('opens the preview in place under the result', async () => {
    renderAt('/search?q=passport');
    // the icon is hidden, so the button's name is just its text
    const toggle = await screen.findByRole('button', { name: 'Preview' });
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    // the document is loaded for the preview, and its allowed actions decide what it shows
    expect(await screen.findByText('You may not view the content of this document.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
