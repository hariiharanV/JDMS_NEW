import { render, screen } from '@testing-library/react';
import i18n from '../i18n';
import { DocumentViewer } from './DocumentViewer';

vi.mock('pdfjs-dist', () => ({ getDocument: vi.fn(), GlobalWorkerOptions: {} }));
vi.mock('pdfjs-dist/build/pdf.worker.min.mjs?url', () => ({ default: 'worker.js' }));

/** B-19: an image original (the preview of a photographed or scanned page) is shown as itself. */
describe('DocumentViewer', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }));
  });

  it('shows an image preview without PDF.js', async () => {
    const load = () => Promise.resolve(new Blob([new Uint8Array([137, 80, 78, 71])], { type: 'image/png' }));
    render(<DocumentViewer load={load} fileName="passport.png" watermark="maker1 2026-11-24" />);

    const image = await screen.findByTestId('image-preview');
    expect(image).toHaveAttribute('src', 'blob:preview');
    expect(image).toHaveAccessibleName('Preview of the document image');
    expect(screen.getByRole('button', { name: 'Zoom in' })).toBeEnabled();
    expect(screen.queryByTestId('pdf-canvas')).toBeNull();
  });
});
