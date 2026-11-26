import { useEffect, useRef, useState } from 'react';
import { Button, Input, Space } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { useTranslation } from 'react-i18next';
import { ErrorAlert } from './ErrorAlert';

interface Props {
  id: string;
  label: string;
  /** The verb on the danger button, e.g. "Reject". */
  confirmLabel: string;
  onConfirm: (comment: string) => void;
  onCancel: () => void;
  loading?: boolean;
  error?: unknown;
}

/**
 * A required comment that opens on a table row, in place of a dialog (UI handoff §4.13): reject a batch or a pending
 * change. Focus moves to the comment when it opens; the button stays off until there is a comment.
 */
export function RowComment({ id, label, confirmLabel, onConfirm, onCancel, loading = false, error }: Props) {
  const { t } = useTranslation();
  const field = useRef<TextAreaRef>(null);
  const [comment, setComment] = useState('');
  useEffect(() => {
    field.current?.focus();
  }, []);
  return (
    <div className="jdms-row-form">
      <label htmlFor={id}>{label}</label>
      <Input.TextArea ref={field} id={id} value={comment} maxLength={1000} autoSize={{ minRows: 2, maxRows: 4 }}
        onChange={(event) => setComment(event.target.value)}
        onKeyDown={(event) => { if (event.key === 'Escape') onCancel(); }} />
      <ErrorAlert error={error} />
      <Space wrap>
        <Button danger disabled={comment.trim() === ''} loading={loading} onClick={() => onConfirm(comment.trim())}>{confirmLabel}</Button>
        <Button onClick={onCancel}>{t('common.cancel')}</Button>
      </Space>
    </div>
  );
}
