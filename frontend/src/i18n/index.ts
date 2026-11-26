import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import ar from './locales/ar.json';

export const SUPPORTED_LANGUAGES = ['en', 'ar'] as const;
export type Language = (typeof SUPPORTED_LANGUAGES)[number];

const STORAGE_KEY = 'jdms.language';
const RTL_LANGUAGES: ReadonlySet<string> = new Set(['ar']);

export function isLanguage(value: string | null | undefined): value is Language {
  return SUPPORTED_LANGUAGES.some((language) => language === value);
}

export function directionOf(language: string): 'rtl' | 'ltr' {
  return RTL_LANGUAGES.has(language) ? 'rtl' : 'ltr';
}

/** Keeps <html lang dir> in step with the UI language so the browser and assistive tech render RTL correctly. */
export function applyDocumentLanguage(language: string): void {
  document.documentElement.lang = language;
  document.documentElement.dir = directionOf(language);
}

// Remembered language is a per-browser convenience; storage can be unavailable (private mode, blocked site data).
function storedLanguage(): Language | undefined {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return isLanguage(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

export function rememberLanguage(language: Language): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // Not persisted; the choice still applies for this session.
  }
}

void i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    ar: { translation: ar },
  },
  lng: storedLanguage() ?? 'en',
  fallbackLng: 'en',
  supportedLngs: SUPPORTED_LANGUAGES,
  interpolation: {
    // React already escapes rendered values
    escapeValue: false,
  },
});

export default i18n;
