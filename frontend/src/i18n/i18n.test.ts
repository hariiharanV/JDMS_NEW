import en from './locales/en.json';
import ar from './locales/ar.json';
import { directionOf } from '.';

function keys(value: unknown, prefix = ''): string[] {
  if (value === null || typeof value !== 'object') {
    return [prefix];
  }
  return Object.entries(value).flatMap(([key, child]) => keys(child, prefix ? `${prefix}.${key}` : key));
}

/** A plural key without its form (Arabic has six forms, English two): `count_other` → `count`. */
function base(key: string): string {
  return key.replace(/_(zero|one|two|few|many|other)$/, '');
}

describe('translations', () => {
  it('Arabic has exactly the same keys as English (plural forms aside)', () => {
    expect([...new Set(keys(ar).map(base))].sort()).toEqual([...new Set(keys(en).map(base))].sort());
  });

  it('every Arabic plural has all six forms', () => {
    const plurals = [...new Set(keys(ar).filter((key) => base(key) !== key).map(base))];
    for (const plural of plurals) {
      const forms = keys(ar).filter((key) => base(key) === plural).map((key) => key.slice(plural.length + 1)).sort();
      expect({ plural, forms }).toEqual({ plural, forms: ['few', 'many', 'one', 'other', 'two', 'zero'] });
    }
  });

  it('every translation is non-empty', () => {
    const values = [en, ar].flatMap((locale) =>
      keys(locale).map((path) => path.split('.').reduce<unknown>((node, key) => (node as Record<string, unknown>)[key], locale)),
    );
    expect(values.every((value) => typeof value === 'string' && value.trim().length > 0)).toBe(true);
  });

  it('every literal key used in the code exists in English', () => {
    const sources = import.meta.glob('../**/*.tsx', { query: '?raw', import: 'default', eager: true });
    const known = new Set(keys(en).map(base));
    const used = Object.entries(sources)
      .filter(([path]) => !path.endsWith('.test.tsx'))
      .flatMap(([, source]) => [...String(source).matchAll(/\bt\('([a-zA-Z][\w.-]*)'/g)].map((match) => match[1] ?? ''));
    expect(used.length).toBeGreaterThan(100);
    expect(used.filter((key) => !known.has(key))).toEqual([]);
  });

  it('Arabic is right to left and English left to right', () => {
    expect(directionOf('ar')).toBe('rtl');
    expect(directionOf('en')).toBe('ltr');
  });
});
