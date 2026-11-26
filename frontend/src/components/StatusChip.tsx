import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { DocumentStatus, Task } from '../api/types';

export type ChipTone = 'neutral' | 'success' | 'warning' | 'danger';
/** The marker shape tells the families apart without colour. */
export type ChipMarker = 'ring' | 'dot' | 'square' | 'hollow-square' | 'bar' | 'none';

const DOCUMENT_STATUS: Record<DocumentStatus, { tone: ChipTone; marker: ChipMarker }> = {
  DRAFT: { tone: 'neutral', marker: 'ring' },
  PENDING_APPROVAL: { tone: 'warning', marker: 'dot' },
  ACTIVE: { tone: 'success', marker: 'dot' },
  REJECTED: { tone: 'danger', marker: 'square' },
  DELETED: { tone: 'danger', marker: 'hollow-square' },
  DISPOSED: { tone: 'neutral', marker: 'bar' },
};

/** A pill with dark text on a tinted ground and a shape marker (UI handoff §4.3); also used for task and audit states. */
export function Chip({ tone, marker = 'dot', children }: { tone: ChipTone; marker?: ChipMarker; children: ReactNode }) {
  return (
    <span className={`jdms-chip jdms-chip-${tone}`}>
      {marker === 'none' ? null : <span className={`jdms-chip-marker jdms-marker-${marker}`} aria-hidden="true" />}
      {children}
    </span>
  );
}

export function StatusChip({ status }: { status: DocumentStatus }) {
  const { t } = useTranslation();
  const { tone, marker } = DOCUMENT_STATUS[status];
  return <Chip tone={tone} marker={marker}>{t(`status.${status}`)}</Chip>;
}

/** A task's deadline: due soon and overdue as chips; on time as quiet text; nothing without a deadline. */
export function SlaChip({ sla }: { sla: Task['slaStatus'] }) {
  const { t } = useTranslation();
  switch (sla) {
    case 'DUE_SOON': return <Chip tone="warning">{t('tasks.slaStatus.DUE_SOON')}</Chip>;
    case 'OVERDUE': return <Chip tone="danger" marker="square">{t('tasks.slaStatus.OVERDUE')}</Chip>;
    case 'ON_TIME': return <span className="jdms-sla-on-time">{t('tasks.slaStatus.ON_TIME')}</span>;
    default: return null;
  }
}
