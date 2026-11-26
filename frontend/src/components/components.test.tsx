import { render, screen } from '@testing-library/react';
import { Form } from 'antd';
import i18n from '../i18n';
import { ApiError } from '../api/client';
import type { CatalogField } from '../api/types';
import { DynamicFields } from './DynamicFields';
import { customFieldValues } from './fieldValues';
import { ErrorAlert } from './ErrorAlert';
import { Snippet } from './Snippet';

const field = (overrides: Partial<CatalogField>): CatalogField => ({
  key: 'f', labelEn: 'Field', labelAr: 'حقل', dataType: 'TEXT', mandatory: false, maxLength: null, defaultValue: null,
  lovCode: null, sensitive: false, displayOrder: 1, displayRow: 1, options: [], ...overrides,
});

describe('Snippet', () => {
  it('renders server text as text and marks only the matches', () => {
    const { container } = render(<Snippet segments={[
      { text: 'before <img src=x onerror=alert(1)> ', match: false },
      { text: 'loan', match: true },
    ]} />);

    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('loan').tagName).toBe('MARK');
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
  });
});

describe('DynamicFields', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('renders each field type with its label, in row order', () => {
    render(
      <Form>
        <DynamicFields namePrefix="custom" fields={[
          field({ key: 'amount', labelEn: 'Amount', dataType: 'NUMBER', displayRow: 2 }),
          field({ key: 'passport_no', labelEn: 'Passport number', mandatory: true }),
          field({ key: 'nationality', labelEn: 'Nationality', dataType: 'LOV', displayOrder: 2,
            options: [{ code: 'OM', labelEn: 'Omani', labelAr: 'عماني' }] }),
          field({ key: 'joint', labelEn: 'Joint account', dataType: 'BOOLEAN', displayRow: 3 }),
        ]} />
      </Form>,
    );

    const labels = screen.getAllByText(/Passport number|Nationality|Amount|Joint account/).map((node) => node.textContent);
    expect(labels).toEqual(['Passport number', 'Nationality', 'Amount', 'Joint account']);
    // yes/no is a choice, not a switch: nothing happens until the form is submitted
    expect(screen.getByRole('radiogroup', { name: 'Joint account' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Yes' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Nationality' })).toBeInTheDocument();
  });

  it('shows Arabic labels in Arabic', async () => {
    await i18n.changeLanguage('ar');
    render(<Form><DynamicFields namePrefix="custom" fields={[field({ key: 'x', labelAr: 'رقم الجواز' })]} /></Form>);

    expect(screen.getByText('رقم الجواز')).toBeInTheDocument();
  });

  it('converts values to the API representation', () => {
    const fields = [field({ key: 'd', dataType: 'DATE' }), field({ key: 'n', dataType: 'NUMBER' }),
      field({ key: 'b', dataType: 'BOOLEAN' }), field({ key: 'empty' })];

    expect(customFieldValues(fields, { d: { format: () => '2026-10-25' }, n: 125.5, b: false, empty: '' }))
      .toEqual({ d: '2026-10-25', n: '125.5', b: false });
  });
});

describe('ErrorAlert', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('translates known problem codes and lists violations', () => {
    render(<ErrorAlert error={new ApiError(422, 'VALIDATION_FAILED', 'Invalid', [
      { field: 'cif', code: 'REQUIRED', message: 'cif is required' },
    ])} />);

    expect(screen.getByText('Some values are not valid.')).toBeInTheDocument();
    expect(screen.getByText('cif: is required')).toBeInTheDocument();
  });

  it('falls back to the server detail for unknown codes', () => {
    render(<ErrorAlert error={new ApiError(409, 'SOMETHING_NEW', 'Server says no')} />);

    expect(screen.getByText('Server says no')).toBeInTheDocument();
  });
});
