import { Alert } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/client';
import { useErrorMessage } from './errorMessage';

/** An API failure with its field violations listed by field. */
export function ErrorAlert({ error }: { error: unknown }) {
  const { t } = useTranslation();
  const message = useErrorMessage();
  if (!error) {
    return null;
  }
  const violations = error instanceof ApiError ? error.errors : [];
  return (
    <Alert
      type="error"
      showIcon
      role="alert"
      title={message(error)}
      description={violations.length > 0 ? (
        <ul className="violations">
          {violations.map((violation) => (
            <li key={`${violation.field}-${violation.code}`}>
              {violation.field}: {t(`violations.${violation.code}`, { defaultValue: violation.message })}
            </li>
          ))}
        </ul>
      ) : undefined}
    />
  );
}
