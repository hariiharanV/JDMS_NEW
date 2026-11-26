import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router';
import i18n from '../i18n';
import type { Capture, DocumentType } from '../api/types';
import { UploadPage } from './UploadPage';

const receipt: DocumentType = {
  id: 't1', cabinetId: 'cab1', code: 'RECEIPT', nameEn: 'Receipt', nameAr: null, category: 'TEST', channels: ['UI'],
  defaultClassification: 'INTERNAL', requiredStandardFields: ['cif'],
  fields: [
    { key: 'amount', labelEn: 'Amount', labelAr: null, dataType: 'NUMBER', mandatory: true, maxLength: null,
      defaultValue: null, lovCode: null, sensitive: false, displayOrder: 1, displayRow: 1, options: [] },
    { key: 'currency', labelEn: 'Currency', labelAr: null, dataType: 'LOV', mandatory: false, maxLength: null,
      defaultValue: null, lovCode: 'CURRENCY', sensitive: false, displayOrder: 2, displayRow: 1,
      options: [{ code: 'OMR', labelEn: 'Omani Rial', labelAr: null }] },
  ],
};

/** B-15: the upload form is filled in from the attached file (ADR-0024). */
describe('UploadPage assisted indexing', { timeout: 30_000 }, () => {
  let capture: Capture;
  let started: { metadata: unknown }[];
  let deleted: string[];

  beforeEach(async () => {
    await i18n.changeLanguage('en');
    started = [];
    deleted = [];
    capture = { id: 'c1', status: 'DONE', documentTypeCode: 'RECEIPT', ocr: true, expiresAt: '2026-11-24T14:00:00Z',
      suggestions: [
        { field: 'cif', value: 'C25112594', confidence: 'HIGH', label: 'CIF No' },
        { field: 'customerName', value: 'Ahmed Al Balushi', confidence: 'HIGH', label: 'Customer Name' },
        { field: 'customFields.amount', value: '1250.500', confidence: 'MEDIUM', label: 'Amount' },
        { field: 'customFields.currency', value: 'EUR', confidence: 'HIGH', label: 'Currency' }, // not in the list
      ] };
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (url === '/api/v1/captures' && method === 'POST') {
        const metadata = (init?.body as FormData).get('metadata') as Blob;
        started.push({ metadata: JSON.parse(await metadata.text()) as unknown });
        return json({ ...capture, status: 'PENDING', suggestions: [] }, 202);
      }
      if (url === '/api/v1/captures/c1' && method === 'DELETE') {
        deleted.push('c1');
        return new Response(null, { status: 204 });
      }
      const body = url === '/api/v1/captures/c1' ? capture
        : url === '/api/v1/me' ? { tenantCode: 'BANK1', userId: 'u1', username: 'maker', displayName: null,
          principalType: 'USER', roles: ['MAKER'], allBranches: false, branchCodes: ['001'], clearance: 'CONFIDENTIAL',
          languages: ['en'] }
          : url === '/api/v1/cabinets' ? [{ id: 'cab1', code: 'DOCS', name: 'Documents' }]
            : url === '/api/v1/document-types' ? [receipt]
              : url.startsWith('/api/v1/cabinets/') ? [] : {};
      return json(body);
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function json(body: unknown, status = 200) {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  }

  function renderPage() {
    return render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AntApp>
          <MemoryRouter initialEntries={['/upload?cabinet=DOCS']}>
            <Routes><Route path="upload" element={<UploadPage />} /></Routes>
          </MemoryRouter>
        </AntApp>
      </QueryClientProvider>,
    );
  }

  async function attachAndChooseType(user: ReturnType<typeof userEvent.setup>) {
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(input, new File(['scan'], 'scan.png', { type: 'image/png' }));
    expect(await screen.findByText('Choose the document type and the form will be filled in from the file where possible.'))
      .toBeInTheDocument();
    await user.click(await screen.findByRole('combobox', { name: 'Document type' }));
    await user.click(await screen.findByTitle('Receipt'));
  }

  it('fills in the fields found in the document and says where they came from', async () => {
    const user = userEvent.setup();
    renderPage();
    await attachAndChooseType(user);

    await waitFor(() => expect(screen.getByRole('textbox', { name: 'CIF' })).toHaveValue('C25112594'),
      { timeout: 5000 });
    expect(started[0]?.metadata).toMatchObject({ documentTypeCode: 'RECEIPT', branchCode: '001',
      classification: 'INTERNAL' });
    expect(screen.getByRole('textbox', { name: 'Customer name' })).toHaveValue('Ahmed Al Balushi');
    expect(screen.getByRole('spinbutton', { name: 'Amount' })).toHaveValue('1250.5');
    // a list value that is not in the list is not filled in
    expect(screen.queryByTitle('Omani Rial')).toBeNull();
    expect(within(screen.getByRole('status')).getByText(
      '3 fields were filled in from the document. Check them before you upload.')).toBeInTheDocument();
    expect(screen.getByText('From the document (“CIF No”)')).toBeInTheDocument();
    expect(screen.getByText('From the document (“Amount”). Please check it.')).toBeInTheDocument();
    // the title follows the customer read from the document
    expect(screen.getByRole('textbox', { name: 'Title' })).toHaveValue('Receipt Ahmed Al Balushi');

    // once the user changes a filled-in field it is theirs: the note goes
    await user.type(screen.getByRole('textbox', { name: 'CIF' }), '0');
    expect(screen.queryByText('From the document (“CIF No”)')).toBeNull();
    expect(screen.getByText('From the document (“Customer Name”)')).toBeInTheDocument();
  });

  it('keeps what the user typed while the document was being read', async () => {
    capture = { ...capture, status: 'PENDING' };
    const user = userEvent.setup();
    renderPage();
    await attachAndChooseType(user);
    expect(await screen.findByText('Reading the document to fill in the form…')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'CIF' }), 'C1');

    capture = { ...capture, status: 'DONE' };
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Customer name' })).toHaveValue('Ahmed Al Balushi'),
      { timeout: 4000 });
    expect(screen.getByRole('textbox', { name: 'CIF' })).toHaveValue('C1');
  });

  it('says so when nothing is found or the file fails the virus check, and discards the capture on leaving', async () => {
    capture = { ...capture, status: 'INFECTED', suggestions: [] };
    const user = userEvent.setup();
    const view = renderPage();
    await attachAndChooseType(user);
    expect(await screen.findByText('This file did not pass the virus check. Choose another file.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'CIF' })).toHaveValue('');

    view.unmount();
    await waitFor(() => expect(deleted).toEqual(['c1']));
  });
});
