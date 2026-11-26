import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/client';

/** Translates an API problem by its stable code; unknown codes fall back to the server's detail. */
export function useErrorMessage() {
  const { t } = useTranslation();
  return (error: unknown): string => {
    if (error instanceof ApiError) {
      return t(`errors.${error.code}`, { defaultValue: error.detail || t('errors.generic') });
    }
    return t('errors.generic');
  };
}
