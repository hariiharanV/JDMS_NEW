import { useState } from 'react';
import { Alert, App, Button, Card, Switch } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, ifMatch } from '../../api/client';
import type { Page, PendingChange } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';

interface LanguagesView {
  languages: string[];
  supported: string[];
  defaultLanguage: string;
  version: number;
}

/**
 * The languages the bank's screens offer (ADR-0023): English always; Arabic adds the language switch, right-to-left
 * layout and Arabic names. Switching is a proposal a second administrator approves.
 */
export function Languages() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const current = useQuery({ queryKey: ['admin', 'languages'], queryFn: () => api<LanguagesView>('/admin/languages') });
  const pending = useQuery({
    queryKey: ['pending-changes'],
    queryFn: () => api<Page<PendingChange>>('/admin/pending-changes?status=PENDING&size=100'),
  });
  const waiting = pending.data?.items.some((change) => change.subjectType === 'TENANT_LANGUAGES') ?? false;
  const arabicNow = current.data?.languages.includes('ar') ?? false;
  const [arabic, setArabic] = useState<boolean>();
  const chosen = arabic ?? arabicNow;

  const propose = useMutation({
    mutationFn: (view: LanguagesView) => api<PendingChange>('/admin/languages', {
      method: 'PUT', idempotent: true, headers: ifMatch(view.version), body: { languages: chosen ? ['en', 'ar'] : ['en'] },
    }),
    onSuccess: () => {
      void message.success(t('admin.proposed'));
      setArabic(undefined);
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
    },
  });

  return (
    <Card className="jdms-languages">
      <ErrorAlert error={current.error ?? propose.error} />
      {waiting ? <Alert type="info" showIcon className="jdms-languages-pending" title={t('admin.languagesPending')} /> : null}
      <ul className="jdms-language-list">
        <li>
          <span className="jdms-title-cell">
            <strong id="language-en">{t('admin.languageEnglish')}</strong>
            <span className="jdms-title-sub">{t('admin.languageEnglishNote')}</span>
          </span>
          <Switch checked disabled aria-labelledby="language-en" />
        </li>
        <li>
          <span className="jdms-title-cell">
            <strong id="language-ar">{t('admin.languageArabic')}</strong>
            <span className="jdms-title-sub" id="language-ar-note">{t('admin.languageArabicNote')}</span>
          </span>
          <Switch checked={chosen} disabled={!current.data || waiting} onChange={setArabic}
            aria-labelledby="language-ar" aria-describedby="language-ar-note" />
        </li>
      </ul>
      <div className="jdms-languages-actions">
        <Button type="primary" disabled={!current.data || waiting || chosen === arabicNow} loading={propose.isPending}
          onClick={() => current.data && propose.mutate(current.data)}>
          {t('admin.languagesSave')}
        </Button>
        {!waiting && chosen === arabicNow ? <span className="jdms-muted">{t('admin.languagesUnchanged')}</span> : null}
      </div>
    </Card>
  );
}
