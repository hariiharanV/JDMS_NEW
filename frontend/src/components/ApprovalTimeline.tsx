import type { ComponentType, HTMLAttributes } from 'react';
import { ArrowUpOutlined, CheckOutlined, CloseOutlined, RollbackOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ApprovalRecord } from '../api/types';
import { useFormatters } from '../i18n/labels';

type Icon = ComponentType<HTMLAttributes<HTMLSpanElement>>;

const SUBMITTED = { icon: ArrowUpOutlined as Icon, tone: 'submitted' };

const STEP: Record<string, { icon: Icon; tone: string }> = {
  SUBMIT: SUBMITTED,
  APPROVE: { icon: CheckOutlined, tone: 'approved' },
  VOTE_APPROVE: { icon: CheckOutlined, tone: 'approved' },
  REJECT: { icon: CloseOutlined, tone: 'rejected' },
  VOTE_REJECT: { icon: CloseOutlined, tone: 'rejected' },
  RETURN: { icon: RollbackOutlined, tone: 'returned' },
};

/** The approval history, newest first: verb, who, when and any comment (UI handoff §4.8). */
export function ApprovalTimeline({ records }: { records: ApprovalRecord[] }) {
  const { t } = useTranslation();
  const format = useFormatters();
  const newestFirst = [...records].sort((a, b) => b.decidedAt.localeCompare(a.decidedAt));
  return (
    <ol className="jdms-timeline">
      {newestFirst.map((record) => {
        const { icon: StepIcon, tone } = STEP[record.decision] ?? SUBMITTED;
        return (
          <li key={record.id} className="jdms-timeline-item">
            <span className={`jdms-timeline-dot jdms-timeline-${tone}`} aria-hidden="true"><StepIcon /></span>
            <div className="jdms-timeline-body">
              <span>
                <strong>{t(`decision.${record.decision}`, { defaultValue: record.decision })}</strong>
                {record.actorName ? <> {t('timeline.by', { name: record.actorName })}</> : null}
              </span>
              <span className="jdms-timeline-meta">
                <time dateTime={record.decidedAt}>{format.dateTime(record.decidedAt)}</time>
                {record.stageName ? <span>{record.stageName}</span> : null}
              </span>
              {record.comment ? <q className="jdms-timeline-comment">{record.comment}</q> : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
