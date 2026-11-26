import { useId, useRef, useState } from 'react';
import { Button, Form, Input } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { useTranslation } from 'react-i18next';
import { needsComment } from '../api/tasks';
import type { Decision } from '../api/types';

const ORDER: Decision[] = ['RESUBMIT', 'APPROVE', 'RETURN', 'REJECT'];

interface Props {
  decisions: Decision[];
  onDecide: (decision: Decision, comment: string) => void;
  /** The decision being sent, if any. */
  pending?: Decision | null;
}

/**
 * Decides a task in place, under the evidence (UI handoff §4.12): a comment and the allowed decisions, no dialog.
 * Reject and return without a comment are stopped at the comment field.
 */
export function DecisionBar({ decisions, onDecide, pending = null }: Props) {
  const { t } = useTranslation();
  const id = useId();
  const field = useRef<TextAreaRef>(null);
  const [comment, setComment] = useState('');
  const [missing, setMissing] = useState<Decision | null>(null);
  const allowed = ORDER.filter((decision) => decisions.includes(decision));
  const commented = allowed.filter(needsComment);

  const decide = (decision: Decision) => {
    if (needsComment(decision) && comment.trim() === '') {
      setMissing(decision);
      field.current?.focus();
      return;
    }
    setMissing(null);
    onDecide(decision, comment.trim());
  };

  const help = missing ? t(`decisionBar.missing.${missing}`)
    : commented.length === 2 ? t('decisionBar.required.both')
      : commented.length === 1 ? t(`decisionBar.required.${commented[0]}`)
        : t('decisionBar.optional');

  return (
    <div className="jdms-decision-bar">
      <Form layout="vertical" component="div">
        <Form.Item label={t('history.comment')} htmlFor={`${id}-comment`} validateStatus={missing ? 'error' : undefined}
          help={<span id={`${id}-help`} role={missing ? 'alert' : undefined}>{help}</span>}>
          <Input.TextArea ref={field} id={`${id}-comment`} aria-describedby={`${id}-help`} aria-invalid={!!missing}
            value={comment} maxLength={1000} autoSize={{ minRows: 2, maxRows: 6 }}
            onChange={(event) => {
              setComment(event.target.value);
              if (event.target.value.trim()) setMissing(null);
            }} />
        </Form.Item>
      </Form>
      <div className="jdms-decision-actions">
        {allowed.map((decision) => (
          <Button key={decision} type={decision === 'APPROVE' || decision === 'RESUBMIT' ? 'primary' : 'default'}
            danger={decision === 'REJECT'} loading={pending === decision} disabled={!!pending && pending !== decision}
            onClick={() => decide(decision)}>
            {t(`tasks.decisions.${decision}`)}
          </Button>
        ))}
        <span className="jdms-decision-hint">{t('decisionBar.nextOpens')}</span>
      </div>
    </div>
  );
}
