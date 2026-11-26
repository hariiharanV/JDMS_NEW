import { useEffect, useRef, type ReactNode } from 'react';
import { Alert, Button, Space } from 'antd';
import { useTranslation } from 'react-i18next';

interface Props {
  /** The consequence, in one sentence. */
  message: ReactNode;
  /** The verb on the danger button, e.g. "Delete document". */
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
}

/**
 * Confirms a reversible destructive action in the page, where it was started (UI handoff §4.14): not a Popconfirm,
 * not a Modal. Focus moves to it; Escape cancels.
 */
export function InlineConfirm({ message, confirmLabel, onConfirm, onCancel, loading = false }: Props) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  const cancel = useRef(onCancel);
  useEffect(() => {
    cancel.current = onCancel;
  });
  // focus once when it opens; Escape anywhere inside cancels
  useEffect(() => {
    const element = ref.current;
    element?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') cancel.current();
    };
    element?.addEventListener('keydown', onKey);
    return () => element?.removeEventListener('keydown', onKey);
  }, []);
  return (
    <div ref={ref} tabIndex={-1} className="jdms-inline-confirm">
      <Alert
        type="warning"
        showIcon
        title={message}
        action={(
          <Space wrap>
            <Button danger onClick={onConfirm} loading={loading}>{confirmLabel}</Button>
            <Button onClick={onCancel}>{t('common.cancel')}</Button>
          </Space>
        )}
      />
    </div>
  );
}
