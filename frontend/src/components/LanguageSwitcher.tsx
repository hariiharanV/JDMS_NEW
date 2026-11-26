import { ConfigProvider, Segmented } from 'antd';
import { useTranslation } from 'react-i18next';
import { isLanguage, rememberLanguage, SUPPORTED_LANGUAGES } from '../i18n';
import { onNavy } from '../theme';

/** English or Arabic; {@code onDark} on the navy header, {@code compact} for the short labels (EN / عربي). */
export function LanguageSwitcher({ onDark = false, compact = false }: { onDark?: boolean; compact?: boolean }) {
  const { t, i18n } = useTranslation();
  const language = isLanguage(i18n.resolvedLanguage) ? i18n.resolvedLanguage : 'en';
  const switcher = (
    <Segmented
      aria-label={t('app.languageSwitcher')}
      value={language}
      options={SUPPORTED_LANGUAGES.map((code) => ({
        value: code,
        label: <span lang={code}>{t(compact ? `language.short.${code}` : `language.${code}`)}</span>,
      }))}
      onChange={(value) => {
        if (isLanguage(value)) {
          rememberLanguage(value);
          void i18n.changeLanguage(value);
        }
      }}
    />
  );
  return onDark ? <ConfigProvider theme={onNavy}>{switcher}</ConfigProvider> : switcher;
}
