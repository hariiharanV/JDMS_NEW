import { Button, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { LanguageSwitcher } from '../components/LanguageSwitcher';

/** Shown before sign-in: the bank's single sign-on (Keycloak) does the authentication. */
export function LoginPage() {
  const { t } = useTranslation();
  const { login } = useAuth();
  return (
    <main className="login">
      <section className="login-brand" aria-hidden="true">
        <span className="login-mark">{t('app.mark')}</span>
        <span className="login-mark-rest">{t('app.markSuffix')}</span>
        <p className="login-claim">{t('login.claim')}</p>
      </section>
      <section className="login-panel">
        <div className="login-language"><LanguageSwitcher /></div>
        <div className="login-form">
          <Typography.Title level={1}>{t('home.title')}</Typography.Title>
          <Typography.Paragraph type="secondary">{t('home.subtitle')}</Typography.Paragraph>
          <Button type="primary" size="large" onClick={() => void login(window.location.pathname)}>
            {t('auth.signIn')}
          </Button>
          <Typography.Paragraph type="secondary" className="login-note">{t('login.note')}</Typography.Paragraph>
        </div>
      </section>
    </main>
  );
}
