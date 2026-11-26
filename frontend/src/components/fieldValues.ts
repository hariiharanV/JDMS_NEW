import type { CatalogField } from '../api/types';

/** Dates read as "1 Jun 2021"; typed dates are also accepted as 2021-06-01 or 1/6/2021. */
export const DATE_FORMATS = ['D MMM YYYY', 'YYYY-MM-DD', 'D/M/YYYY'];

/** A date picker value; formatted to ISO dates (YYYY-MM-DD) before sending. */
export interface DateValue {
  format: (pattern: string) => string;
}

/** Converts form values of custom fields to the API's representation (dates as ISO strings, numbers as text). */
export function customFieldValues(fields: CatalogField[], values: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const field of fields) {
    const value = values[field.key];
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (field.dataType === 'DATE') {
      result[field.key] = (value as DateValue).format('YYYY-MM-DD');
    } else if (field.dataType === 'NUMBER') {
      result[field.key] = String(value);
    } else {
      result[field.key] = value;
    }
  }
  return result;
}
