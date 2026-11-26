// One way to show dates, numbers and identifiers (UI handoff §3.4). Western digits in both languages (decision D2).
// Server values are UTC instants; they are shown in the browser's time zone unless one is given (tests).

type Language = 'en' | 'ar';

const LOCALE: Record<Language, string> = { en: 'en-GB', ar: 'ar' };

interface Options {
  timeZone?: string;
  now?: Date;
}

function formatter(language: Language, options: Intl.DateTimeFormatOptions, timeZone?: string) {
  return new Intl.DateTimeFormat(LOCALE[language], { ...options, numberingSystem: 'latn', timeZone });
}

const TIME: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hour12: false };

/** `25 Oct 2026, 22:31` */
export function formatDateTime(value: string | null | undefined, language: Language, options: Options = {}): string {
  if (!value) return '';
  return formatter(language, { day: 'numeric', month: 'short', year: 'numeric', ...TIME }, options.timeZone)
    .format(new Date(value));
}

/** In lists the current year is left out: `25 Oct, 22:31`. */
export function formatListDateTime(value: string | null | undefined, language: Language, options: Options = {}): string {
  if (!value) return '';
  const date = new Date(value);
  const year = (d: Date) => formatter(language, { year: 'numeric' }, options.timeZone).format(d);
  const sameYear = year(date) === year(options.now ?? new Date());
  return formatter(language, { day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }), ...TIME },
    options.timeZone).format(date);
}

/** `31 May 2031`; date-only values (`2031-05-31`) are calendar dates, not instants, so they are read as UTC. */
export function formatDate(value: string | null | undefined, language: Language, options: Options = {}): string {
  if (!value) return '';
  const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(value);
  return formatter(language, { day: 'numeric', month: 'short', year: 'numeric' },
    dateOnly ? 'UTC' : options.timeZone).format(new Date(value));
}

export function formatNumber(value: number, language: Language): string {
  return new Intl.NumberFormat(LOCALE[language], { numberingSystem: 'latn' }).format(value);
}

export function formatBytes(value: number, language: Language): string {
  const megabytes = value >= 1048576;
  return new Intl.NumberFormat(LOCALE[language], { style: 'unit', unit: megabytes ? 'megabyte' : 'kilobyte',
    maximumFractionDigits: 1, numberingSystem: 'latn' }).format(megabytes ? value / 1048576 : value / 1024);
}

/** First 8 characters and an ellipsis, for IDs in tables; the full value goes in details. */
export function shortId(value: string | null | undefined): string {
  if (!value) return '';
  return value.length > 8 ? `${value.slice(0, 8)}…` : value;
}

/** Initials for the avatar: first letters of the first and last words. */
export function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '';
  const first = words[0] ?? '';
  const last = words.length > 1 ? (words[words.length - 1] ?? '') : '';
  return (Array.from(first)[0] ?? '') + (Array.from(last)[0] ?? '');
}
