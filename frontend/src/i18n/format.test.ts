import { formatBytes, formatDate, formatDateTime, formatListDateTime, formatNumber, initials, shortId } from './format';

const UTC = { timeZone: 'UTC' };

describe('formatters', () => {
  it('writes one date and time format, with Western digits in both languages', () => {
    expect(formatDateTime('2026-10-25T22:31:00Z', 'en', UTC)).toBe('25 Oct 2026, 22:31');
    const arabic = formatDateTime('2026-10-25T22:31:00Z', 'ar', UTC);
    expect(arabic).toMatch(/25/);
    expect(arabic).toMatch(/2026/);
    expect(arabic).toMatch(/22:31/);
    expect(arabic).not.toMatch(/[٠-٩]/);
  });

  it('drops the current year in lists only', () => {
    const now = new Date('2026-10-26T08:00:00Z');
    expect(formatListDateTime('2026-10-25T22:31:00Z', 'en', { ...UTC, now })).toBe('25 Oct, 22:31');
    expect(formatListDateTime('2025-10-25T22:31:00Z', 'en', { ...UTC, now })).toBe('25 Oct 2025, 22:31');
  });

  it('shows calendar dates without shifting the day', () => {
    expect(formatDate('2031-05-31', 'en', { timeZone: 'America/Los_Angeles' })).toBe('31 May 2031');
    expect(formatDate(null, 'en')).toBe('');
  });

  it('formats numbers and sizes without Arabic-Indic digits', () => {
    expect(formatNumber(1234567, 'en')).toBe('1,234,567');
    expect(formatNumber(1234567, 'ar')).not.toMatch(/[٠-٩]/);
    expect(formatBytes(2 * 1048576, 'en')).toBe('2 MB');
  });

  it('shortens identifiers and makes initials', () => {
    expect(shortId('3f2a9c1e-77aa-4c11-9d0e-0123456789ab')).toBe('3f2a9c1e…');
    expect(shortId('C7750')).toBe('C7750');
    expect(initials('Mona Maker')).toBe('MM');
    expect(initials('records1')).toBe('r');
    expect(initials('منى أحمد الحارثي')).toBe('ما');
  });
});
