import { useTranslation } from 'react-i18next';
import { formatBytes, formatDate, formatDateTime, formatListDateTime, formatNumber } from './format';

type Named = { labelEn?: string; labelAr?: string | null; nameEn?: string; nameAr?: string | null };

/**
 * Picks the English or Arabic label of a metadata item for the current language. Arabic names are optional
 * (ADR-0023): without one, Arabic screens show the English name.
 */
export function useLocalizedLabel() {
  const { i18n } = useTranslation();
  const arabic = i18n.resolvedLanguage === 'ar';
  const english = (item: Named) => item.labelEn ?? item.nameEn ?? '';
  return (item: Named): string => (arabic ? item.labelAr?.trim() || item.nameAr?.trim() || english(item) : english(item));
}

/** Dates, numbers and sizes in the user's language, always from UTC instants (one format: i18n/format.ts). */
export function useFormatters() {
  const { i18n } = useTranslation();
  const language = i18n.resolvedLanguage === 'ar' ? 'ar' : 'en';
  return {
    dateTime: (value: string | null | undefined) => formatDateTime(value, language),
    /** In lists: the current year left out. */
    listDateTime: (value: string | null | undefined) => formatListDateTime(value, language),
    date: (value: string | null | undefined) => formatDate(value, language),
    number: (value: number) => formatNumber(value, language),
    bytes: (value: number) => formatBytes(value, language),
  };
}
